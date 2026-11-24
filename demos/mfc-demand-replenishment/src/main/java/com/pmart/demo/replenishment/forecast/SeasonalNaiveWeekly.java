package com.pmart.demo.replenishment.forecast;

import com.pmart.demo.replenishment.sales.DailySale;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 요일 Seasonal Naive(명세 F04): 각 예측일은 기준일 전 같은 요일 중 가장 최근 유효 관측값. 최근 4주 안에 같은 요일 유효 관측이 없으면
 * 그날은 NaN(근거 없음). 원래 날짜로 요일을 정한다. 모든 요일에 근거가 없으면 이력 부족.
 */
public final class SeasonalNaiveWeekly implements ForecastModel {

  static final int MAX_WEEKS_BACK = 4;

  @Override
  public String id() {
    return "SNAIVE_WEEK";
  }

  @Override
  public String version() {
    return "1";
  }

  @Override
  public Forecast forecast(List<DailySale> history, LocalDate firstTarget, int horizonDays) {
    Map<LocalDate, DailySale> byDate = new HashMap<>();
    for (DailySale s : history) {
      if (s.businessDate().isBefore(firstTarget)) {
        byDate.put(s.businessDate(), s);
      }
    }
    double[] daily = new double[horizonDays];
    int found = 0;
    for (int h = 0; h < horizonDays; h++) {
      LocalDate target = firstTarget.plusDays(h);
      daily[h] = Double.NaN;
      // target 에서 7일씩 뒤로 가며 기준일 전 첫 유효 관측.
      LocalDate d = target.minusDays(7);
      while (!d.isBefore(firstTarget)) {
        d = d.minusDays(7);
      }
      for (int k = 0; k < MAX_WEEKS_BACK; k++, d = d.minusDays(7)) {
        DailySale s = byDate.get(d);
        if (s != null && s.status().usableAsDemand()) {
          daily[h] = s.unitsSold();
          found++;
          break;
        }
      }
    }
    if (found == 0) {
      throw new InsufficientHistoryException(id() + ": 최근 " + MAX_WEEKS_BACK + "주에 같은 요일 유효 관측 없음");
    }
    return new Forecast(id(), version(), firstTarget, daily);
  }
}
