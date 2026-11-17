package com.pmart.mfc.inventory.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.web.CommandResponse;
import com.pmart.common.web.CommandStatus;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.inventory.domain.StockUnit.Change;
import com.pmart.mfc.inventory.domain.StockUnit.Key;
import com.pmart.mfc.inventory.domain.StockUnit.Quality;
import com.pmart.mfc.inventory.domain.StockUnit.Work;
import com.pmart.mfc.inventory.infrastructure.StockStore;
import com.pmart.mfc.inventory.infrastructure.StockStore.LotRow;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 품질 반영(mfc-quality → inventory, IV-09·IV-10). 승인 근거(approvalRef) 필수, 같은 요청 키는 한 번.
 *
 * <ul>
 *   <li>QUARANTINE: 위치의 NORMAL 단위(기본 STORED, UNSTOWED 가능) → QUARANTINED(같은 업무 상태). STORED 면 E−q.
 *       LOT 의 R 이 남은 E 를 넘으면 그 LOT 의 아직 집지 않은 예약 조각을 **나중에 잡은 예약부터** 줄여 BLOCKED(이행부족 D) +
 *       ReservationBlocked — 자동 재배분은 하지 않는다(MFC Q-13 "다주문 부족 자동 배분 차단, 영향 약속 보류·수동 해결").
 *   <li>RELEASE: QUARANTINED → NORMAL(같은 업무 상태). STORED 면 E+q. UNSTOWED 는 적치 뒤에야 판매 가능.
 *   <li>DISPOSE: QUARANTINED 단위 −q(H−q, Q−q). 승인 잔량은 mfc-quality 가 지킨다(approvalRef = 폐기 승인 ID).
 * </ul>
 */
@Service
public class QualityPostingService {

  static final String OP = "postQualityDecision";

  private static final Logger log = LoggerFactory.getLogger(QualityPostingService.class);

  public record Line(
      String sku, String lotId, String locationCode, int qty, @Nullable String workState) {}

  public record Posting(
      String postingType,
      String mfcId,
      @Nullable String qualityDecisionId,
      String approvalRef,
      @Nullable String reason,
      List<Line> lines) {}

  private final StockStore stock;
  private final ReservationBlocker blocker;
  private final Ledger ledger;
  private final RequestIdempotency idempotency;
  private final Clock clock;

  public QualityPostingService(
      StockStore stock,
      ReservationBlocker blocker,
      Ledger ledger,
      RequestIdempotency idempotency,
      Clock clock) {
    this.stock = stock;
    this.blocker = blocker;
    this.ledger = ledger;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  public CommandResponse post(String requestId, String sourceSystem, Posting in) {
    if (in.approvalRef() == null || in.approvalRef().isBlank()) {
      throw new PmartException(ErrorCode.APPROVAL_REQUIRED, "승인 근거(approvalRef)가 필요합니다.");
    }
    // RELEASE·DISPOSE 의 approvalRef 는 mfc-quality 내부 승인(해제 승인자:판정 ID·폐기 승인 ID) — 본사 승인 번호 확인은
    // mfc-quality 가
    // 한다(AP-1). 여기서는 기록만.
    if (!List.of("QUARANTINE", "RELEASE", "DISPOSE").contains(in.postingType())) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED, "postingType 은 QUARANTINE/RELEASE/DISPOSE 입니다.");
    }
    if (in.lines().isEmpty() || in.lines().size() > 500) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "줄은 1~500개입니다.");
    }
    for (Line l : in.lines()) {
      if (l.qty() < 1) {
        throw new PmartException(ErrorCode.VALIDATION_FAILED, "수량은 1 이상입니다: " + l.sku());
      }
      Work w = work(l);
      if (w != Work.STORED && w != Work.UNSTOWED) {
        throw new PmartException(
            ErrorCode.VALIDATION_FAILED,
            "workState 는 STORED/UNSTOWED 입니다(집품·포장분은 결품·파손 보고로): " + l.sku());
      }
    }
    int total = in.lines().stream().mapToInt(Line::qty).sum();
    return idempotency
        .execute(
            Callers.key(sourceSystem, requestId, OP),
            in,
            CommandResponse.class,
            () -> {
              Instant at = clock.instant();
              Map<String, LotRow> lots =
                  stock.lots(in.lines().stream().map(Line::lotId).collect(Collectors.toSet()));
              List<Change> changes = new ArrayList<>();
              // LOT 별 E 감소(격리) — 예약 영향 계산
              Map<String, Integer> sellableDrop = new TreeMap<>();
              Map<String, java.util.Set<String>> dropLocations = new TreeMap<>();
              for (Line l : in.lines()) {
                LotRow lot = lots.get(l.lotId());
                if (lot == null || !lot.sku().equals(l.sku())) {
                  throw new PmartException(
                      ErrorCode.VALIDATION_FAILED,
                      "lotId " + l.lotId() + " 는 SKU " + l.sku() + " 의 LOT 이 아닙니다.");
                }
                Work w = work(l);
                Key normal = new Key(l.locationCode(), l.sku(), l.lotId(), Quality.NORMAL, w);
                Key quarantined = normal.with(Quality.QUARANTINED, w);
                switch (in.postingType()) {
                  case "QUARANTINE" -> {
                    changes.add(new Change(normal, -l.qty()));
                    changes.add(new Change(quarantined, l.qty()));
                    if (normal.sellable()) {
                      sellableDrop.merge(l.sku() + "|" + l.lotId(), l.qty(), Integer::sum);
                      dropLocations
                          .computeIfAbsent(
                              l.sku() + "|" + l.lotId(), k -> new java.util.TreeSet<>())
                          .add(l.locationCode());
                    }
                  }
                  case "RELEASE" -> {
                    changes.add(new Change(quarantined, -l.qty()));
                    changes.add(new Change(normal, l.qty()));
                  }
                  default -> changes.add(new Change(quarantined, -l.qty()));
                }
              }
              ReservationBlocker.Result held =
                  blocker.block(
                      in.mfcId(),
                      sellableDrop,
                      dropLocations,
                      "QUALITY_HOLD",
                      in.approvalRef(),
                      at);
              String commitId =
                  ledger.post(
                      "QUALITY_" + in.postingType(),
                      in.mfcId(),
                      requestId,
                      sourceSystem,
                      in.qualityDecisionId() != null ? in.qualityDecisionId() : in.approvalRef(),
                      total,
                      changes,
                      held.reserved(),
                      at);
              // 같은 SKU 적격 LOT 으로 다시 잡고(ADR-0042 결정 1) 남은 것만 알린다
              held = blocker.resecure(held, requestId, sourceSystem, at);
              blocker.publish(held, "QUALITY_HOLD", at);
              log.info(
                  "품질 반영 {} {} {}개 (근거 {}, 결정 {}) — 예약 보류 {}건",
                  commitId,
                  in.postingType(),
                  total,
                  in.approvalRef(),
                  in.qualityDecisionId(),
                  held.blocked().size());
              return new CommandResponse(
                  CommandStatus.APPLIED, requestId, commitId, total, null, false, null, at);
            })
        .value();
  }

  private static Work work(Line l) {
    return l.workState() == null ? Work.STORED : Work.valueOf(l.workState());
  }
}
