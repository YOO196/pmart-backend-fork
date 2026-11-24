package com.pmart.demo.replenishment.sales;

import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 주·월·계절·연간 집계와 상태별 일수(명세 F01). 일별 합계 = 각 기간 합계. */
public final class SalesAggregator {

  public enum PeriodType {
    WEEK,
    MONTH,
    SEASON,
    YEAR
  }

  /** 한 기간 집계. 관측 일수 = NORMAL + ZERO_ASSUMED. */
  public record Row(
      String mfcId,
      String skuId,
      PeriodType periodType,
      String periodKey,
      long units,
      Map<AvailabilityStatus, Integer> daysByStatus) {

    public int observedDays() {
      return days(AvailabilityStatus.NORMAL) + days(AvailabilityStatus.ZERO_ASSUMED);
    }

    public int days(AvailabilityStatus s) {
      return daysByStatus.getOrDefault(s, 0);
    }
  }

  private SalesAggregator() {}

  public static String periodKey(PeriodType type, LocalDate d) {
    return switch (type) {
      case WEEK ->
          String.format(
              "%d-W%02d", d.get(IsoFields.WEEK_BASED_YEAR), d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
      case MONTH -> String.format("%d-%02d", d.getYear(), d.getMonthValue());
      case SEASON -> Season.key(d);
      case YEAR -> Integer.toString(d.getYear());
    };
  }

  /** 한 SKU 의 일별 줄(날짜 순)을 기간 종류별로 묶는다. */
  public static List<Row> aggregate(List<DailySale> days) {
    List<Row> out = new ArrayList<>();
    if (days.isEmpty()) {
      return out;
    }
    DailySale first = days.getFirst();
    for (PeriodType type : PeriodType.values()) {
      Map<String, long[]> units = new LinkedHashMap<>();
      Map<String, Map<AvailabilityStatus, Integer>> counts = new LinkedHashMap<>();
      for (DailySale s : days) {
        String key = periodKey(type, s.businessDate());
        units.computeIfAbsent(key, k -> new long[1])[0] += s.unitsSold();
        counts
            .computeIfAbsent(key, k -> new EnumMap<>(AvailabilityStatus.class))
            .merge(s.status(), 1, Integer::sum);
      }
      for (var e : units.entrySet()) {
        out.add(
            new Row(
                first.mfcId(),
                first.skuId(),
                type,
                e.getKey(),
                e.getValue()[0],
                counts.get(e.getKey())));
      }
    }
    return out;
  }
}
