package com.pmart.demo.replenishment.sales;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 명세 F01·F02, 6장 구현 지시 1·2, 7장 상태 부여 회귀 사례. */
class SalesRulesTest {

  static final LocalDate D0 = LocalDate.of(2009, 12, 1);

  @Test
  void seasonBoundaries() {
    assertThat(Season.key(LocalDate.of(2009, 12, 31))).isEqualTo("2010-WINTER");
    assertThat(Season.key(LocalDate.of(2010, 1, 1))).isEqualTo("2010-WINTER");
    assertThat(Season.key(LocalDate.of(2010, 2, 28))).isEqualTo("2010-WINTER");
    assertThat(Season.key(LocalDate.of(2010, 3, 1))).isEqualTo("2010-SPRING");
    assertThat(Season.key(LocalDate.of(2010, 5, 31))).isEqualTo("2010-SPRING");
    assertThat(Season.key(LocalDate.of(2010, 6, 1))).isEqualTo("2010-SUMMER");
    assertThat(Season.key(LocalDate.of(2010, 8, 31))).isEqualTo("2010-SUMMER");
    assertThat(Season.key(LocalDate.of(2010, 9, 1))).isEqualTo("2010-AUTUMN");
    assertThat(Season.key(LocalDate.of(2010, 11, 30))).isEqualTo("2010-AUTUMN");
    assertThat(Season.key(LocalDate.of(2010, 12, 1))).isEqualTo("2011-WINTER");
  }

  /** 7장 상태 부여 회귀 사례. */
  @Test
  void stateAssignmentOrder() {
    // 센터는 매일 판매(단 D0+20 은 센터 전체 무판매). SKU 는 D0+3, +5, +8 에 판매 후 끊겼다가 D0+40 에 다시 판매.
    TreeSet<LocalDate> center = new TreeSet<>();
    for (int i = 0; i <= 45; i++) {
      if (i != 20) {
        center.add(D0.plusDays(i));
      }
    }
    TreeMap<LocalDate, Long> sku = new TreeMap<>();
    sku.put(D0.plusDays(3), 2L);
    sku.put(D0.plusDays(5), 1L);
    sku.put(D0.plusDays(8), 4L);
    sku.put(D0.plusDays(40), 3L);
    SalesSource src = source(Map.of("A", sku), center);

    List<DailySale> days = DailyStateAssigner.assign(src, "A", sku, center, D0, D0.plusDays(45), 14);

    assertUnknown(at(days, 0), UnknownReason.BEFORE_FIRST_OBSERVED);
    assertThat(at(days, 3).status()).isEqualTo(AvailabilityStatus.NORMAL);
    assertThat(at(days, 4).status()).isEqualTo(AvailabilityStatus.ZERO_ASSUMED);
    assertThat(at(days, 4).unitsSold()).isZero();
    assertThat(at(days, 22).status()).isEqualTo(AvailabilityStatus.ZERO_ASSUMED); // 8 + 14
    assertUnknown(at(days, 23), UnknownReason.AFTER_LAST_OBSERVED); // 15일 지남
    assertUnknown(at(days, 20), UnknownReason.NO_CENTER_SALES);
    assertThat(at(days, 40).status()).isEqualTo(AvailabilityStatus.NORMAL);
    assertThat(at(days, 41).status()).isEqualTo(AvailabilityStatus.ZERO_ASSUMED);
    assertThat(days).noneMatch(d -> d.status() == AvailabilityStatus.PRE_LAUNCH);
  }

  @Test
  void staleGapUsesOnlyDataBeforeReview() {
    TreeMap<LocalDate, Long> sku = new TreeMap<>();
    for (int i = 0; i < 60; i += 10) {
      sku.put(D0.plusDays(i), 1L); // 간격 10 × 5
    }
    Map<String, NavigableMap<LocalDate, Long>> all = new TreeMap<>(Map.of("A", sku));
    LocalDate review = D0.plusDays(55);
    StaleGap.Value v = StaleGap.before(all, review, 2.0).forSku(sku, review);
    assertThat(v.days()).isEqualTo(20);
    assertThat(v.pooled()).isFalse();

    // 검토일 뒤 데이터를 바꿔도 같은 값.
    TreeMap<LocalDate, Long> changed = new TreeMap<>(sku);
    changed.put(D0.plusDays(56), 9L);
    changed.put(D0.plusDays(200), 9L);
    Map<String, NavigableMap<LocalDate, Long>> all2 = new TreeMap<>(Map.of("A", changed));
    assertThat(StaleGap.before(all2, review, 2.0).forSku(changed, review)).isEqualTo(v);
  }

  @Test
  void staleGapClampsAndPoolsWhenFewGaps() {
    TreeMap<LocalDate, Long> dense = new TreeMap<>();
    for (int i = 0; i < 30; i++) {
      dense.put(D0.plusDays(i), 1L); // 간격 1 → 2 → 최소 14
    }
    TreeMap<LocalDate, Long> sparse = new TreeMap<>(Map.of(D0, 1L, D0.plusDays(5), 1L)); // 간격 1개
    Map<String, NavigableMap<LocalDate, Long>> all = new TreeMap<>(Map.of("D", dense, "S", sparse));
    LocalDate review = D0.plusDays(40);
    StaleGap gap = StaleGap.before(all, review, 2.0);
    assertThat(gap.forSku(dense, review).days()).isEqualTo(StaleGap.MIN_DAYS);
    StaleGap.Value pooled = gap.forSku(sparse, review);
    assertThat(pooled.pooled()).isTrue();
    assertThat(pooled.days()).isEqualTo(StaleGap.MIN_DAYS);
  }

  @Test
  void aggregatesMatchDailyTotals() {
    TreeSet<LocalDate> center = new TreeSet<>();
    TreeMap<LocalDate, Long> sku = new TreeMap<>();
    long total = 0;
    for (int i = 0; i < 400; i++) {
      LocalDate d = D0.plusDays(i);
      center.add(d);
      if (i % 3 == 0) {
        sku.put(d, (long) (i % 7 + 1));
        total += i % 7 + 1;
      }
    }
    SalesSource src = source(Map.of("A", sku), center);
    List<DailySale> days = DailyStateAssigner.assign(src, "A", sku, center, D0, D0.plusDays(399), 30);
    List<SalesAggregator.Row> rows = SalesAggregator.aggregate(days);
    for (SalesAggregator.PeriodType t : SalesAggregator.PeriodType.values()) {
      long sum = rows.stream().filter(r -> r.periodType() == t).mapToLong(SalesAggregator.Row::units).sum();
      int dayCount =
          rows.stream()
              .filter(r -> r.periodType() == t)
              .mapToInt(r -> r.daysByStatus().values().stream().mapToInt(Integer::intValue).sum())
              .sum();
      assertThat(sum).as(t.name()).isEqualTo(total);
      assertThat(dayCount).as(t.name()).isEqualTo(400);
    }
    assertThat(rows)
        .filteredOn(r -> r.periodType() == SalesAggregator.PeriodType.SEASON)
        .extracting(SalesAggregator.Row::periodKey)
        .startsWith("2010-WINTER", "2010-SPRING");
  }

  @Test
  void uciCleanerExcludesAndCounts(@TempDir Path dir) throws IOException {
    Path csv = dir.resolve("uci.csv");
    Files.writeString(
        csv,
        String.join(
            "\n",
            "Invoice,StockCode,Description,Quantity,InvoiceDate,Price,Customer ID,Country",
            "489434,85048,\"LIGHT, PINK\",12,2009-12-01 07:45:00,6.95,13085,United Kingdom",
            "489434,85048,X,3,2009-12-01 09:00:00,6.95,13085,United Kingdom",
            "C489449,85048,X,-12,2009-12-01 10:33:00,6.95,16321,United Kingdom",
            "489435,22041,X,-5,2009-12-01 11:00:00,2.0,,United Kingdom",
            "489436,22041,X,5,2009-12-02 11:00:00,0,,United Kingdom",
            "489437,POST,POSTAGE,1,2009-12-02 11:00:00,18,,United Kingdom",
            "489438,C2,CARRIAGE,1,2009-12-02 11:00:00,50,,United Kingdom",
            "489439,TEST001,X,1,2009-12-02 11:00:00,4.5,,United Kingdom",
            "489440,BANK CHARGES,X,1,2009-12-02 11:00:00,15,,United Kingdom",
            "489441,22041,X,7,2009-12-03 11:00:00,2.0,,France",
            "489442,22041,X,abc,2009-12-03 11:00:00,2.0,,United Kingdom",
            "489443,22041a,X,4,2009-12-03 12:00:00,2.0,,United Kingdom",
            "489444,DCGSSBOY,BOYS PARTY BAG,2,2009-12-03 12:00:00,3.29,,United Kingdom",
            "489445,m,Manual,1,2009-12-03 12:00:00,2.55,,United Kingdom",
            "489446,gift_0001_20,Gift Voucher,1,2009-12-03 12:00:00,17.02,,United Kingdom")
            + "\n",
        StandardCharsets.UTF_8);
    UciCleaner.Result r = UciCleaner.clean(csv);
    assertThat(r.readLines()).isEqualTo(15);
    assertThat(r.keptLines()).isEqualTo(4);
    assertThat(r.keptCodesWithoutDigit()).containsEntry("DCGSSBOY", 1L); // 실물 상품 — 남기고 보고서에 띄움
    assertThat(r.excludedNonProductCodes()).containsKeys("m", "gift_0001_20");
    assertThat(r.excluded())
        .containsEntry(UciCleaner.Exclusion.CANCELLED_INVOICE, 1L)
        .containsEntry(UciCleaner.Exclusion.NON_POSITIVE_QTY, 1L)
        .containsEntry(UciCleaner.Exclusion.NON_POSITIVE_PRICE, 1L)
        .containsEntry(UciCleaner.Exclusion.NON_PRODUCT_CODE, 6L)
        .containsEntry(UciCleaner.Exclusion.NON_UK, 1L)
        .containsEntry(UciCleaner.Exclusion.INVALID_ROW, 1L);
    assertThat(r.excludedNonProductCodes()).containsKeys("POST", "C2", "TEST001", "BANK CHARGES");
    assertThat(r.source().skuDailyUnits().get("85048")).containsEntry(D0, 15L);
    assertThat(r.source().skuDailyUnits().get("22041A")).containsEntry(D0.plusDays(2), 4L);
    assertThat(r.source().centerSaleDates()).containsExactly(D0, D0.plusDays(2));
    assertThat(r.source().salesBasis()).isEqualTo("INVOICE_ORDER_CONFIRMED_PROXY");
    assertThat(r.source().sourceTimezone()).isEqualTo("Europe/London");
  }

  private static SalesSource source(Map<String, TreeMap<LocalDate, Long>> skus, TreeSet<LocalDate> center) {
    Map<String, NavigableMap<LocalDate, Long>> m = new TreeMap<>(skus);
    return new SalesSource(
        UciCleaner.MFC_ID,
        UciCleaner.SALES_BASIS,
        UciCleaner.SOURCE_TIMEZONE,
        center.first(),
        center.last(),
        m,
        center);
  }

  private static DailySale at(List<DailySale> days, int offset) {
    return days.get(offset);
  }

  private static void assertUnknown(DailySale sale, UnknownReason reason) {
    assertThat(sale.status()).as(sale.businessDate().toString()).isEqualTo(AvailabilityStatus.UNKNOWN);
    assertThat(sale.unknownReason()).as(sale.businessDate().toString()).isEqualTo(reason);
  }
}
