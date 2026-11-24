package com.pmart.demo.replenishment.sales;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

/**
 * stale_gap_days 산정(명세 6장 구현 지시 1): 검토일 전 판매 간격의 95번째 백분위 × 배수, 14~90일. 간격이 3개 미만이면 전체
 * SKU 간격의 같은 백분위. 검토일과 그 뒤 데이터는 쓰지 않는다.
 */
public final class StaleGap {

  public static final double PERCENTILE = 0.95;
  public static final double DEFAULT_MULTIPLIER = 2.0;
  public static final int MIN_DAYS = 14;
  public static final int MAX_DAYS = 90;
  static final int MIN_OWN_GAPS = 3;

  /** 사용한 값과 근거. */
  public record Value(int days, boolean pooled, int gapCount) {}

  private final double multiplier;
  private final List<Long> pooledGaps;

  private StaleGap(double multiplier, List<Long> pooledGaps) {
    this.multiplier = multiplier;
    this.pooledGaps = pooledGaps;
  }

  public double multiplier() {
    return multiplier;
  }

  /** 검토일 전(이날 제외) 데이터로 준비한다. */
  public static StaleGap before(
      Map<String, NavigableMap<LocalDate, Long>> skuDailyUnits, LocalDate reviewDate, double multiplier) {
    List<Long> pooled = new ArrayList<>();
    for (NavigableMap<LocalDate, Long> series : skuDailyUnits.values()) {
      pooled.addAll(gaps(new ArrayList<>(series.headMap(reviewDate, false).navigableKeySet())));
    }
    Collections.sort(pooled);
    return new StaleGap(multiplier, pooled);
  }

  public Value forSku(NavigableMap<LocalDate, Long> series, LocalDate reviewDate) {
    List<Long> own = gaps(new ArrayList<>(series.headMap(reviewDate, false).navigableKeySet()));
    boolean pooled = own.size() < MIN_OWN_GAPS;
    List<Long> basis = pooled ? pooledGaps : sorted(own);
    if (basis.isEmpty()) {
      return new Value(MAX_DAYS, true, 0);
    }
    double p = percentile(basis, PERCENTILE);
    int days = (int) Math.ceil(p * multiplier);
    return new Value(Math.max(MIN_DAYS, Math.min(MAX_DAYS, days)), pooled, basis.size());
  }

  static List<Long> gaps(List<LocalDate> saleDates) {
    List<Long> out = new ArrayList<>();
    for (int i = 1; i < saleDates.size(); i++) {
      out.add(ChronoUnit.DAYS.between(saleDates.get(i - 1), saleDates.get(i)));
    }
    return out;
  }

  private static List<Long> sorted(List<Long> values) {
    List<Long> copy = new ArrayList<>(values);
    Collections.sort(copy);
    return copy;
  }

  /** 가장 가까운 순위 방식(nearest-rank). values 는 정렬돼 있어야 한다. */
  static double percentile(List<Long> sortedValues, double p) {
    int rank = (int) Math.ceil(p * sortedValues.size());
    return sortedValues.get(Math.max(0, Math.min(sortedValues.size() - 1, rank - 1)));
  }
}
