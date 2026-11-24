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
 * @param skuDailyUnits SKU → 날짜 → 판매 합계(1 이상만)
 * @param centerSaleDates 센터 전체에 유효 판매가 있는 날
 */
public record SalesSource(
    String mfcId,
    String salesBasis,
    String sourceTimezone,
    LocalDate firstDate,
    LocalDate lastDate,
    Map<String, NavigableMap<LocalDate, Long>> skuDailyUnits,
    NavigableSet<LocalDate> centerSaleDates) {}
