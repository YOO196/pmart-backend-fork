package com.pmart.demo.replenishment.forecast;

import com.pmart.demo.replenishment.sales.DailySale;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/**
 * 단순 이동평균(운영 1차 후보, 명세 5장): 기준일 전 최근 window 개의 유효 관측일 평균을 매일 같은 값으로 낸다. 최근 365일 안에서만
 * 찾고, 유효 관측이 min(window, 7)개 미만이면 이력 부족.
 */
public final class MovingAverage implements ForecastModel {

  static final int LOOKBACK_DAYS = 365;
  private final int window;

  public MovingAverage(int window) {
    if (window < 1) {
      throw new IllegalArgumentException("window >= 1");
    }
    this.window = window;
  }

  @Override
  public String id() {
    return "MA" + window;
  }

  @Override
  public String version() {
    return "1";
  }

  @Override
  public Forecast forecast(List<DailySale> history, LocalDate firstTarget, int horizonDays) {
    LocalDate earliest = firstTarget.minusDays(LOOKBACK_DAYS);
    double sum = 0;
    int n = 0;
    for (int i = history.size() - 1; i >= 0 && n < window; i--) {
      DailySale s = history.get(i);
      if (!s.businessDate().isBefore(firstTarget)) {
        continue;
      }
      if (s.businessDate().isBefore(earliest)) {
        break;
      }
      if (s.status().usableAsDemand()) {
        sum += s.unitsSold();
        n++;
      }
    }
    int minObs = Math.min(window, 7);
    if (n < minObs) {
      throw new InsufficientHistoryException(id() + ": 유효 관측 " + n + "일 < " + minObs + "일");
    }
    double[] daily = new double[horizonDays];
    Arrays.fill(daily, sum / n);
    return new Forecast(id(), version(), firstTarget, daily);
  }
}
