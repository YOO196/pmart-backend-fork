package com.pmart.demo.replenishment.sales;

import java.time.LocalDate;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;

/**
 * 정제가 끝난 판매 원천.
 *
 * @param mfcId 센터(UCI 는 DEMO_UK_01)
 * @param firstDate 데이터 첫날
 * @param lastDate 데이터 마지막 날
 * @param skuDailyUnits SKU → 날짜 → 판매 합계(1 이상만). stale_gap·판매 이력 판정용
 * @param centerSaleDates 센터 전체에 유효 판매가 있는 날
 * @param explicitDays 원천이 일별 상태를 직접 줄 때(명세 4장 일별 판매 형식) SKU → 날짜 → 그날 줄. null 이면 UCI 처럼
 *     상태를 {@link DailyStateAssigner} 규칙으로 붙인다
 */
public record SalesSource(
    String mfcId,
    String salesBasis,
    String sourceTimezone,
    LocalDate firstDate,
    LocalDate lastDate,
    Map<String, NavigableMap<LocalDate, Long>> skuDailyUnits,
    NavigableSet<LocalDate> centerSaleDates,
    Map<String, NavigableMap<LocalDate, DailySale>> explicitDays) {

  /** 상태를 규칙으로 붙이는 원천(UCI). */
  public SalesSource(
      String mfcId,
      String salesBasis,
      String sourceTimezone,
      LocalDate firstDate,
      LocalDate lastDate,
      Map<String, NavigableMap<LocalDate, Long>> skuDailyUnits,
      NavigableSet<LocalDate> centerSaleDates) {
    this(mfcId, salesBasis, sourceTimezone, firstDate, lastDate, skuDailyUnits, centerSaleDates, null);
  }
}
