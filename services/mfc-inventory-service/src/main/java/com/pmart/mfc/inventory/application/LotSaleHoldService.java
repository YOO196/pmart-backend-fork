package com.pmart.mfc.inventory.application;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.inventory.application.Ledger.HoldChange;
import com.pmart.mfc.inventory.domain.StockUnit.Key;
import com.pmart.mfc.inventory.domain.StockUnit.Quality;
import com.pmart.mfc.inventory.domain.StockUnit.Work;
import com.pmart.mfc.inventory.infrastructure.LotSaleHoldStore;
import com.pmart.mfc.inventory.infrastructure.LotSaleHoldStore.HoldRow;
import com.pmart.mfc.inventory.infrastructure.StockStore;
import com.pmart.mfc.inventory.infrastructure.StockStore.LotRow;
import com.pmart.mfc.inventory.infrastructure.StockStore.UnitRow;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 품질 사고 LOT 판매 보류(IV-09 "범위 불명 보류" — 출하·일반 피킹·보충 차단, 사용자 결정 2026-11-20, 계약 0.39.0).
 *
 * <p>mfc-quality 가 사고를 열면 그 센터의 그 LOT 정상 재고(선반 STORED·적치 전 UNSTOWED)를 모두 {@code stock_hold}(reason
 * {@code QUALITY})로 막는다 — 격리(Q)가 아니다(이상품이 확인된 실물만 격리 — IV-09 판단 규칙). 보류는 E 에서만 빼고(STORED) 집품·이동·적치를
 * 막는다({@link Ledger}). LOT 의 예약(R)이 남은 E 를 넘으면 넘는 양을 {@link ReservationBlocker} 로
 * BLOCKED(QUALITY_HOLD) → 같은 SKU 다른 적격 LOT 으로 재확보 → 남은 것만 {@code ReservationBlocked}. 이미 집품·포장한
 * 것(PICKED·PACKED)은 건드리지 않는다.
 *
 * <p>보류가 걸린 동안 새로 생긴 같은 LOT 정상 재고(반품 반입·격리 해제 등)는 주기 작업이 막는다. 실물을 격리하면({@link
 * QualityPostingService} QUARANTINE) 그만큼 보류가 줄고, 실사 손실로 실물이 보류보다 적어지면 없는 만큼 줄어든다. 같은 LOT 에 여러 사고가
 * 걸리면 마지막 사고가 풀 때 남은 판매 보류를 모두 푼다(E 로 돌아감 — 기다리던 예약은 {@link BlockedResecurer}).
 */
@Service
public class LotSaleHoldService {

  public static final String REASON = "QUALITY";
  static final String SOURCE = "mfc-quality";
  private static final Logger log = LoggerFactory.getLogger(LotSaleHoldService.class);

  public record Result(
      String mfcId, String lotId, String sku, String state, int heldQty, int activeSources) {}

  private final LotSaleHoldStore holds;
  private final StockStore stock;
  private final Ledger ledger;
  private final ReservationBlocker blocker;
  private final TransactionTemplate tx;
  private final Clock clock;

  public LotSaleHoldService(
      LotSaleHoldStore holds,
      StockStore stock,
      Ledger ledger,
      ReservationBlocker blocker,
      PlatformTransactionManager txm,
      Clock clock) {
    this.holds = holds;
    this.stock = stock;
    this.ledger = ledger;
    this.blocker = blocker;
    this.tx = new TransactionTemplate(txm);
    this.clock = clock;
  }

  /** 걸기(같은 사고·LOT 은 한 번 — 다시 불러도 같은 결과, 이미 푼 사고는 되살리지 않는다). */
  public Result place(String mfcId, String lotId, String sourceRef) {
    LotRow lot = requireLot(lotId, sourceRef);
    return tx.execute(
        s -> {
          Instant at = clock.instant();
          holds.insertIfAbsent(mfcId, lotId, sourceRef, lot.sku(), at);
          List<HoldRow> rows = holds.lockLot(mfcId, lotId);
          int added = applyLocked(mfcId, lotId, rows, at);
          if (added > 0) {
            log.info("LOT 판매 보류 {} {} — 사고 {} 정상 재고 {}개 막음", mfcId, lotId, sourceRef, added);
          }
          return result(mfcId, lotId, lot.sku(), sourceRef, rows);
        });
  }

  /** 풀기 — 이 사고의 보류만 끝내고, 그 LOT 에 걸린 사고가 더 없으면 남은 판매 보류를 모두 푼다. */
  public Result release(String mfcId, String lotId, String sourceRef) {
    LotRow lot = requireLot(lotId, sourceRef);
    return tx.execute(
        s -> {
          Instant at = clock.instant();
          holds.lockLot(mfcId, lotId);
          holds.release(mfcId, lotId, sourceRef, at);
          List<HoldRow> rows = holds.lockLot(mfcId, lotId);
          boolean stillHeld = rows.stream().anyMatch(r -> "ACTIVE".equals(r.state()));
          if (!stillHeld) {
            Map<Key, Integer> open = stock.openQualityHolds(mfcId, lotId);
            if (!open.isEmpty()) {
              List<HoldChange> changes = new ArrayList<>();
              open.forEach((k, q) -> changes.add(new HoldChange(k, -q)));
              ledger.postWithHolds(
                  Ledger.newCommitId(),
                  "QUALITY_SALE_RELEASE",
                  mfcId,
                  null,
                  SOURCE,
                  sourceRef,
                  open.values().stream().mapToInt(Integer::intValue).sum(),
                  List.of(),
                  List.of(),
                  changes,
                  at);
              open.forEach((k, q) -> stock.consumeQualityHold(mfcId, k, q, sourceRef, at));
              log.info(
                  "LOT 판매 보류 {} {} 풀림 — 사고 {} (정상 재고 {}줄)", mfcId, lotId, sourceRef, open.size());
            }
          }
          return result(mfcId, lotId, lot.sku(), sourceRef, rows);
        });
  }

  /**
   * 보류가 걸린 LOT 의 새 정상 재고를 막는다({@code pmart.inventory.lot-hold-sweep-interval}, 기본 1분). LOT 마다 별도
   * 트랜잭션 — 경합(그새 집품·이동)으로 실패하면 다음 주기.
   */
  @Scheduled(
      fixedDelayString = "${pmart.inventory.lot-hold-sweep-interval:PT1M}",
      initialDelayString = "PT30S")
  public void sweep() {
    for (LotSaleHoldStore.LotRef l : holds.activeLots(200)) {
      try {
        Integer added =
            tx.execute(
                s ->
                    applyLocked(
                        l.mfcId(),
                        l.lotId(),
                        holds.lockLot(l.mfcId(), l.lotId()),
                        clock.instant()));
        if (added != null && added > 0) {
          log.info("LOT 판매 보류 {} {} — 새 정상 재고 {}개 막음", l.mfcId(), l.lotId(), added);
        }
      } catch (RuntimeException ex) {
        log.warn("LOT 판매 보류 {} {} 반영 실패 — 다음 주기에: {}", l.mfcId(), l.lotId(), ex.getMessage());
      }
    }
  }

  /** 잠근 보류 행 기준으로, ACTIVE 가 있으면 아직 막히지 않은 정상 재고를 막는다. 반환 = 새로 막은 양. */
  private int applyLocked(String mfcId, String lotId, List<HoldRow> rows, Instant at) {
    HoldRow active = rows.stream().filter(r -> "ACTIVE".equals(r.state())).findFirst().orElse(null);
    if (active == null) {
      return 0;
    }
    List<HoldChange> changes = new ArrayList<>();
    Map<String, Integer> sellableDrop = new TreeMap<>();
    Map<String, Set<String>> dropLocations = new TreeMap<>();
    int total = 0;
    for (UnitRow u : stock.lotUnits(mfcId, lotId)) {
      Key k = u.key();
      if (k.quality() != Quality.NORMAL || (k.work() != Work.STORED && k.work() != Work.UNSTOWED)) {
        continue;
      }
      int free = u.qty() - stock.openHold(mfcId, k);
      if (free <= 0) {
        continue;
      }
      changes.add(new HoldChange(k, free));
      total += free;
      if (k.sellable()) {
        String key = k.sku() + "|" + k.lotId();
        sellableDrop.merge(key, free, Integer::sum);
        dropLocations.computeIfAbsent(key, x -> new TreeSet<>()).add(k.locationCode());
      }
    }
    if (changes.isEmpty()) {
      return 0;
    }
    String ref = active.sourceRef();
    ReservationBlocker.Result blocked =
        blocker.block(mfcId, sellableDrop, dropLocations, "QUALITY_HOLD", ref, at);
    ledger.postWithHolds(
        Ledger.newCommitId(),
        "QUALITY_SALE_HOLD",
        mfcId,
        null,
        SOURCE,
        ref,
        total,
        List.of(),
        blocked.reserved(),
        changes,
        at);
    for (HoldChange h : changes) {
      stock.insertHold("QSH-" + UUID.randomUUID(), mfcId, h.key(), h.delta(), REASON, ref, at);
    }
    blocked = blocker.resecure(blocked, "LOT-SALE-HOLD:" + ref + ":" + lotId, SOURCE, at);
    blocker.publish(blocked, "QUALITY_HOLD", at);
    return total;
  }

  private Result result(
      String mfcId, String lotId, String sku, String sourceRef, List<HoldRow> rows) {
    String state =
        rows.stream()
            .filter(r -> r.sourceRef().equals(sourceRef))
            .map(HoldRow::state)
            .findFirst()
            .orElse("RELEASED");
    int active = (int) rows.stream().filter(r -> "ACTIVE".equals(r.state())).count();
    int held =
        stock.openQualityHolds(mfcId, lotId).values().stream().mapToInt(Integer::intValue).sum();
    return new Result(mfcId, lotId, sku, state, held, active);
  }

  private LotRow requireLot(String lotId, String sourceRef) {
    if (sourceRef == null || sourceRef.isBlank() || sourceRef.length() > 128) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "sourceRef(품질 사고 ID)는 1~128자입니다.");
    }
    LotRow lot = stock.lots(List.of(lotId)).get(lotId);
    if (lot == null) {
      throw PmartException.notFound("LOT_NOT_FOUND", "LOT 이 없습니다: " + lotId);
    }
    return lot;
  }
}
