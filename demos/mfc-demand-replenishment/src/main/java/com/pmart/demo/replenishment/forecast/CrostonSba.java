package com.pmart.demo.replenishment.forecast;

import com.pmart.demo.replenishment.sales.DailySale;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/**
 * 간헐 수요용 Croston-SBA(명세 F04): 수요 크기 z 와 수요 간격 p 를 지수평활하고 일 수요율 (1 − α/2)·z/p 를 낸다.
 *
 * <p>미확인일(유효 관측이 아닌 날)을 건너뛴 구간은 연속 관측으로 잇지 않는다: 그런 구간을 끼운 간격은 p 추정에 쓰지 않는다(명세 6장
 * 구현 지시 2). 유효 간격이 하나도 없으면 이력 부족.
 */
public final class CrostonSba implements ForecastModel {

  private final double alpha;

  public CrostonSba(double alpha) {
    if (alpha <= 0 || alpha >= 1) {
      throw new IllegalArgumentException("0 < alpha < 1");
    }
    this.alpha = alpha;
  }

  public CrostonSba() {
    this(0.1);
  }

  @Override
  public String id() {
    return "CROSTON_SBA";
  }

  @Override
  public String version() {
    return "1-a" + alpha;
  }

  /** 학습 결과(시험용). */
  record State(double z, double p, int demands, int usedIntervals, int skippedIntervals) {}

  State fit(List<DailySale> history, LocalDate firstTarget) {
    double z = Double.NaN;
    double p = Double.NaN;
    int demands = 0;
    int used = 0;
    int skipped = 0;
    int periodsSinceDemand = 0;
    boolean broken = false;
    boolean seenDemand = false;
    LocalDate prev = null;
    for (DailySale s : history) {
      LocalDate d = s.businessDate();
      if (!d.isBefore(firstTarget)) {
        break;
      }
      if (prev != null && d.isAfter(prev.plusDays(1))) {
        broken = true; // 이력에 빠진 날짜
      }
      prev = d;
      if (!s.status().usableAsDemand()) {
        broken = true;
        continue;
      }
      periodsSinceDemand++;
      if (s.unitsSold() > 0) {
        demands++;
        if (!seenDemand) {
          z = s.unitsSold();
          seenDemand = true;
        } else {
          z = z + alpha * (s.unitsSold() - z);
          if (broken) {
            skipped++;
          } else {
            p = Double.isNaN(p) ? periodsSinceDemand : p + alpha * (periodsSinceDemand - p);
            used++;
          }
        }
        periodsSinceDemand = 0;
        broken = false;
      }
    }
    return new State(z, p, demands, used, skipped);
  }

  @Override
  public Forecast forecast(List<DailySale> history, LocalDate firstTarget, int horizonDays) {
    State st = fit(history, firstTarget);
    if (Double.isNaN(st.z()) || Double.isNaN(st.p())) {
      throw new InsufficientHistoryException(
          id() + ": 유효 수요 간격 없음(수요 " + st.demands() + "회, 건너뛴 간격 " + st.skippedIntervals() + ")");
    }
    double[] daily = new double[horizonDays];
    Arrays.fill(daily, (1 - alpha / 2) * st.z() / st.p());
    return new Forecast(id(), version(), firstTarget, daily);
  }
}
