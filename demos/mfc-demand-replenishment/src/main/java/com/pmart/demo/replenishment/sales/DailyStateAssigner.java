package com.pmart.demo.replenishment.sales;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.Set;

/**
 * UCI 판매에서 SKU·날짜 상태를 붙인다(명세 6장 구현 지시 1 "일별 상태 부여 순서"). 위에서부터 처음 맞는 것:
 *
 * <ol>
 *   <li>센터 판매 없음 → UNKNOWN(NO_CENTER_SALES)
 *   <li>그 SKU 판매 있음 → NORMAL
 *   <li>첫 판매 전 → UNKNOWN(BEFORE_FIRST_OBSERVED)
 *   <li>직전 판매일로부터 staleGapDays 초과 → UNKNOWN(AFTER_LAST_OBSERVED)
 *   <li>그 밖 → ZERO_ASSUMED(0)
 * </ol>
 *
 * 각 날의 상태는 그날까지의 데이터와 staleGapDays 만으로 정해진다(미래 정보 없음).
 */
public final class DailyStateAssigner {

  private DailyStateAssigner() {}

  public static List<DailySale> assign(
      SalesSource source,
      String skuId,
      NavigableMap<LocalDate, Long> series,
      Set<LocalDate> centerSaleDates,
      LocalDate from,
      LocalDate toInclusive,
      int staleGapDays) {
    List<DailySale> out = new ArrayList<>();
    if (source.explicitDays() != null) {
      // 원천이 상태를 직접 줌(명세 4장 형식). 파일에 없는 날은 수집 누락(MISSING)으로 둔다 — 0 수요로 채우지 않는다.
      NavigableMap<LocalDate, DailySale> given = source.explicitDays().get(skuId);
      for (LocalDate d = from; !d.isAfter(toInclusive); d = d.plusDays(1)) {
        DailySale s = given == null ? null : given.get(d);
        out.add(
            s != null
                ? s
                : new DailySale(
                    source.mfcId(),
                    skuId,
                    d,
                    0,
                    AvailabilityStatus.MISSING,
                    null,
                    source.salesBasis(),
                    source.sourceTimezone()));
      }
      return out;
    }
    LocalDate lastSale = series.lowerKey(from);
    for (LocalDate d = from; !d.isAfter(toInclusive); d = d.plusDays(1)) {
      Long units = series.get(d);
      AvailabilityStatus status;
      UnknownReason reason = null;
      long qty = 0;
      if (!centerSaleDates.contains(d)) {
        status = AvailabilityStatus.UNKNOWN;
        reason = UnknownReason.NO_CENTER_SALES;
      } else if (units != null && units > 0) {
        status = AvailabilityStatus.NORMAL;
        qty = units;
        lastSale = d;
      } else if (lastSale == null) {
        status = AvailabilityStatus.UNKNOWN;
        reason = UnknownReason.BEFORE_FIRST_OBSERVED;
      } else if (ChronoUnit.DAYS.between(lastSale, d) > staleGapDays) {
        status = AvailabilityStatus.UNKNOWN;
        reason = UnknownReason.AFTER_LAST_OBSERVED;
      } else {
        status = AvailabilityStatus.ZERO_ASSUMED;
      }
      out.add(
          new DailySale(
              source.mfcId(),
              skuId,
              d,
              qty,
              status,
              reason,
              source.salesBasis(),
              source.sourceTimezone()));
    }
    return out;
  }
}
