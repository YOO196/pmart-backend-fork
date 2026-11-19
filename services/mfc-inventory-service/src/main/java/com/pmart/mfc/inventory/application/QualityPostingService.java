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
 *   <li>(0.35.0, IV-6) RELEASE·DISPOSE 는 옮겨진 격리품을 따라간다: 줄의 위치·업무 상태에 QUARANTINED 가 모자라면 같은 LOT 의 격리
 *       구역 칸(QUARANTINE)에서 나머지를 뺀다 — mfc-quality 는 처음 기록한 위치를 보내도 된다. 선반·피킹 밖(격리·수령·반품 구역)에서의
 *       RELEASE 결과는 NORMAL·UNSTOWED(mfc-inbound 적치 작업 뒤 판매).
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
  private final Locations locations;

  public QualityPostingService(
      StockStore stock,
      ReservationBlocker blocker,
      Ledger ledger,
      RequestIdempotency idempotency,
      Clock clock,
      Locations locations) {
    this.locations = locations;
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
              Map<Key, Integer> used = new java.util.HashMap<>();
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
                  default -> {
                    boolean release = "RELEASE".equals(in.postingType());
                    for (Take t : follow(in.mfcId(), l, w, used)) {
                      Key q =
                          new Key(t.location(), l.sku(), l.lotId(), Quality.QUARANTINED, t.work());
                      changes.add(new Change(q, -t.qty()));
                      if (release) {
                        changes.add(
                            new Change(
                                q.with(Quality.NORMAL, releasedWork(in.mfcId(), t)), t.qty()));
                      }
                    }
                  }
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

  /** 격리품을 뺄 칸 한 조각. */
  private record Take(String location, Work work, int qty) {}

  /**
   * RELEASE·DISPOSE 줄을 뺄 칸(0.35.0, IV-6): 줄의 위치·업무 상태의 QUARANTINED 먼저, 모자라면 같은 LOT 의 격리 구역 칸(위치 코드
   * 순)에서. 그래도 모자라면 나머지를 줄의 위치에 둬 Ledger 가 422 INSUFFICIENT_STOCK 을 낸다(전과 같은 오류).
   */
  private List<Take> follow(String mfcId, Line l, Work w, Map<Key, Integer> used) {
    Map<Key, Integer> have = new java.util.LinkedHashMap<>();
    for (var u : stock.units(mfcId, l.sku())) {
      if (u.key().lotId().equals(l.lotId()) && u.key().quality() == Quality.QUARANTINED) {
        have.merge(u.key(), u.qty(), Integer::sum);
      }
    }
    Key own = new Key(l.locationCode(), l.sku(), l.lotId(), Quality.QUARANTINED, w);
    List<Take> out = new ArrayList<>();
    int left = l.qty();
    left -= take(own, have, used, left, out);
    if (left > 0) {
      List<Key> zone =
          have.keySet().stream()
              .filter(k -> !k.locationCode().equals(l.locationCode()))
              .filter(
                  k ->
                      locations
                          .projection(mfcId, k.locationCode())
                          .map(r -> Locations.QUARANTINE_ZONE.equals(r.zone()))
                          .orElse(false))
              .sorted(
                  java.util.Comparator.comparing(Key::locationCode)
                      .thenComparing(k -> k.work().name()))
              .toList();
      for (Key k : zone) {
        if (left == 0) {
          break;
        }
        left -= take(k, have, used, left, out);
      }
    }
    if (left > 0) {
      out.add(new Take(l.locationCode(), w, left)); // 모자람 — Ledger 가 거절
      used.merge(own, left, Integer::sum);
    }
    return out;
  }

  private static int take(
      Key k, Map<Key, Integer> have, Map<Key, Integer> used, int want, List<Take> out) {
    int free = have.getOrDefault(k, 0) - used.getOrDefault(k, 0);
    int n = Math.max(0, Math.min(free, want));
    if (n > 0) {
      out.add(new Take(k.locationCode(), k.work(), n));
      used.merge(k, n, Integer::sum);
    }
    return n;
  }

  /** 해제 뒤 업무 상태: 선반·피킹 칸은 그대로, 위치 사본에 있는 그 밖의 구역(격리·수령·반품)은 UNSTOWED(적치 뒤 판매). */
  private Work releasedWork(String mfcId, Take t) {
    return locations
        .projection(mfcId, t.location())
        .map(r -> Locations.STORE_ZONES.contains(r.zone()) ? t.work() : Work.UNSTOWED)
        .orElse(t.work());
  }

  private static Work work(Line l) {
    return l.workState() == null ? Work.STORED : Work.valueOf(l.workState());
  }
}
