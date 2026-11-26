package com.pmart.mfc.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.mfc.inventory.domain.LotAllocator.Allocation;
import com.pmart.mfc.inventory.domain.LotAllocator.LineRequest;
import com.pmart.mfc.inventory.domain.LotAllocator.LotCandidate;
import com.pmart.mfc.inventory.domain.LotAllocator.ShelfRule;
import com.pmart.mfc.inventory.domain.StockUnit.Change;
import com.pmart.mfc.inventory.domain.StockUnit.Key;
import com.pmart.mfc.inventory.domain.StockUnit.Quality;
import com.pmart.mfc.inventory.domain.StockUnit.Work;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** LOT 배정(ADR-0040 결정 4, ADR-0041): FEFO, 줄당 LOT 수 제한 없음, 최소 잔여일·제조 후 경과일(배송 약속일 기준). */
class LotAllocatorTest {

  static final String MILK = "E-00000011";
  static final String SNACK = "E-00000012";
  static final LocalDate D = LocalDate.of(2026, 11, 8);
  static final Instant T0 = Instant.parse("2026-11-01T00:00:00Z");

  static LotCandidate lot(String sku, String id, LocalDate exp, int avail) {
    return new LotCandidate(sku, id, exp, null, T0, avail);
  }

  /** 보관 유형 기본값만: 냉장 우유 2일, 과자 30일. */
  static final LotAllocator.Rules MIN = sku -> ShelfRule.storageDefault(MILK.equals(sku) ? 2 : 30);

  static final LotAllocator.Rules NONE = sku -> ShelfRule.storageDefault(null);

  @Test
  void fefoMixesLotsWithoutLimitOldestFirst() {
    var lots =
        List.of(
            lot(MILK, "LOT-C", D.plusDays(7), 10),
            lot(MILK, "LOT-A", D.plusDays(2), 2),
            lot(MILK, "LOT-B", D.plusDays(4), 1));
    var r = LotAllocator.allocate(List.of(new LineRequest("L1", MILK, 4)), lots, D, MIN);
    assertThat(r.shortages()).isEmpty();
    assertThat(r.allocations())
        .containsExactly(
            new Allocation("L1", MILK, "LOT-A", 2),
            new Allocation("L1", MILK, "LOT-B", 1),
            new Allocation("L1", MILK, "LOT-C", 1));
  }

  /** IV-9 마감세일: 지정 줄은 그 LOT 에서만, 먼저 배정 — 같은 SKU 일반 줄은 남은 것에서 FEFO(마감 LOT 을 피하지 않음). */
  @Test
  void pinnedLineTakesOnlyItsLotFirstAndPlainLineContinuesFefo() {
    var lots =
        List.of(lot(MILK, "LOT-CLR", D.plusDays(3), 3), lot(MILK, "LOT-NEW", D.plusDays(9), 10));
    var r =
        LotAllocator.allocate(
            List.of(new LineRequest("L1", MILK, 2), new LineRequest("L2", MILK, 2, "LOT-CLR")),
            lots,
            D,
            MIN);
    assertThat(r.shortages()).isEmpty();
    assertThat(r.allocations())
        .containsExactly(
            new Allocation("L2", MILK, "LOT-CLR", 2),
            new Allocation("L1", MILK, "LOT-CLR", 1),
            new Allocation("L1", MILK, "LOT-NEW", 1));
  }

  @Test
  void pinnedLineDoesNotSpillIntoOtherLots() {
    var lots =
        List.of(lot(MILK, "LOT-CLR", D.plusDays(3), 1), lot(MILK, "LOT-NEW", D.plusDays(9), 10));
    var r = LotAllocator.allocate(List.of(new LineRequest("L1", MILK, 2, "LOT-CLR")), lots, D, MIN);
    assertThat(r.allocations()).isEmpty();
    assertThat(r.shortages())
        .singleElement()
        .satisfies(
            s -> {
              assertThat(s.eligibleQty()).isEqualTo(1);
              assertThat(s.lotId()).isEqualTo("LOT-CLR");
            });
    // 할인으로 품질 제한을 풀지 않는다 — 잔여일 미달 LOT 은 지정해도 0
    var expired =
        LotAllocator.allocate(
            List.of(new LineRequest("L1", MILK, 1, "LOT-OLD")),
            List.of(lot(MILK, "LOT-OLD", D.plusDays(1), 5)),
            D,
            MIN);
    assertThat(expired.shortages())
        .singleElement()
        .satisfies(s -> assertThat(s.eligibleQty()).isZero());
  }

  @Test
  void lotsBelowMinimumRemainingDaysAreNotAllocated() {
    var lots =
        List.of(lot(MILK, "LOT-OLD", D.plusDays(1), 5), lot(MILK, "LOT-OK", D.plusDays(2), 1));
    var r = LotAllocator.allocate(List.of(new LineRequest("L1", MILK, 2)), lots, D, MIN);
    assertThat(r.allocations()).isEmpty();
    assertThat(r.shortages())
        .singleElement()
        .satisfies(
            s -> {
              assertThat(s.requestedQty()).isEqualTo(2);
              assertThat(s.eligibleQty()).isEqualTo(1);
            });
    // 예약 배송 날짜가 앞당겨지면(약속일 기준) 적격이 달라진다
    var earlier =
        LotAllocator.allocate(List.of(new LineRequest("L1", MILK, 2)), lots, D.minusDays(1), MIN);
    assertThat(earlier.shortages()).isEmpty();
  }

  @Test
  void laterLinesSeeWhatEarlierLinesTookAndTiesUseFirstNormalReceipt() {
    var a = new LotCandidate(SNACK, "LOT-Z", D.plusDays(60), null, T0, 3);
    var b = new LotCandidate(SNACK, "LOT-Y", D.plusDays(60), null, T0.plusSeconds(60), 3);
    var r =
        LotAllocator.allocate(
            List.of(new LineRequest("L1", SNACK, 2), new LineRequest("L2", SNACK, 2)),
            List.of(b, a),
            D,
            MIN);
    assertThat(r.allocations())
        .containsExactly(
            new Allocation("L1", SNACK, "LOT-Z", 2),
            new Allocation("L2", SNACK, "LOT-Z", 1),
            new Allocation("L2", SNACK, "LOT-Y", 1));
  }

  @Test
  void lotWithoutExpiryIsNotCheckedAndGoesLast() {
    var noExp = new LotCandidate(SNACK, "LOT-NONE", null, null, T0, 5);
    var r =
        LotAllocator.allocate(
            List.of(new LineRequest(null, SNACK, 3)),
            List.of(noExp, lot(SNACK, "LOT-E", D.plusDays(40), 2)),
            D,
            NONE);
    // 최소 잔여일 미설정이면 기한 있는 LOT 은 배정 못 한다 — 호출자가 missingPolicy 로 먼저 CONFIG_REQUIRED
    assertThat(LotAllocator.missingPolicy(List.of(lot(SNACK, "LOT-E", D, 2)), NONE))
        .containsExactly(SNACK);
    assertThat(r.allocations()).containsExactly(new Allocation(null, SNACK, "LOT-NONE", 3));
  }

  @Test
  void productRemainingDaysGuaranteeReplacesStorageDefaultAndNeedsExpiry() {
    // 상온 과자지만 고시가 "주문일 기준 91일 이상 남은 상품" → 기본 30일 대신 91일
    LotAllocator.Rules rules = sku -> new ShelfRule(91, true, null);
    var lots =
        List.of(
            lot(SNACK, "LOT-60", D.plusDays(60), 5),
            lot(SNACK, "LOT-91", D.plusDays(91), 1),
            new LotCandidate(SNACK, "LOT-UNTRACKED", null, null, T0, 9));
    var r = LotAllocator.allocate(List.of(new LineRequest("L1", SNACK, 1)), lots, D, rules);
    assertThat(r.allocations()).containsExactly(new Allocation("L1", SNACK, "LOT-91", 1));
    // 기한을 모르는 LOT(이관분 등)은 보장을 확인할 수 없어 적격 수량에도 들지 않는다
    var two = LotAllocator.allocate(List.of(new LineRequest("L1", SNACK, 2)), lots, D, rules);
    assertThat(two.shortages()).singleElement().extracting("eligibleQty").isEqualTo(1);
    // 보장 값이 기본보다 짧아도 상품 보장값을 쓴다(냉동 기본 30 → 고시 7)
    LotAllocator.Rules shortGuarantee = sku -> new ShelfRule(7, true, null);
    assertThat(LotAllocator.eligible(lot(SNACK, "LOT-8", D.plusDays(8), 1), D, shortGuarantee))
        .isTrue();
  }

  @Test
  void manufactureGuaranteeNeedsKnownRecentManufactureDate() {
    // 고시 "주문일 기준 3일 이내 제조된 상품"(빵) + 냉장 기본 잔여 2일
    LotAllocator.Rules rules = sku -> new ShelfRule(2, false, 3);
    var fresh = new LotCandidate(MILK, "LOT-FRESH", D.plusDays(4), D.minusDays(3), T0, 2);
    var stale = new LotCandidate(MILK, "LOT-STALE", D.plusDays(5), D.minusDays(4), T0, 9);
    var unknown = new LotCandidate(MILK, "LOT-UNKNOWN", D.plusDays(6), null, T0, 9);
    assertThat(LotAllocator.eligible(fresh, D, rules)).isTrue();
    assertThat(LotAllocator.eligible(stale, D, rules)).isFalse();
    assertThat(LotAllocator.eligible(unknown, D, rules)).isFalse();
    // 제조일 보장만 있고 기한 없는 LOT 은 잔여일 보장이 없으니 제조일만 본다
    var noExp = new LotCandidate(MILK, "LOT-NOEXP", null, D.minusDays(1), T0, 1);
    assertThat(LotAllocator.eligible(noExp, D, rules)).isTrue();
  }

  @Test
  void unitKeyContributions() {
    var k = new Key("RCV-1", MILK, "LOT-A", Quality.PENDING_INSPECTION, Work.UNSTOWED);
    assertThat(new Change(k, 5).de()).isZero();
    assertThat(new Change(k.with(Quality.QUARANTINED, Work.UNSTOWED), 5).dq()).isEqualTo(5);
    assertThat(new Change(k.with(Quality.NORMAL, Work.UNSTOWED), 5).de()).isZero();
    assertThat(new Change(k.with(Quality.NORMAL, Work.STORED), 5).de()).isEqualTo(5);
    assertThat(new Change(k.with(Quality.NORMAL, Work.CANCEL_RETURN_PENDING), 5).de()).isZero();
  }
}
