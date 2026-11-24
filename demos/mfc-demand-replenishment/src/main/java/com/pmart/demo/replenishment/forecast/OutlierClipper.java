package com.pmart.demo.replenishment.forecast;

import com.pmart.demo.replenishment.sales.DailySale;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalDouble;

/**
 * 도매 이상치 처리(명세 6장 구현 지시 1): 임계값은 검토일 전 학습 데이터로만 정한다. 학습 이력의 일 판매만 임계값으로 자르고, 평가에
 * 쓰는 실제 판매량은 바꾸지 않는다.
 *
 * <p>임계값 = 검토일 전 양수 유효 관측의 99번째 백분위. 양수 관측이 20개 미만이면 자르지 않는다.
 */
public final class OutlierClipper {

  static final double PERCENTILE = 0.99;
  static final int MIN_POSITIVE_OBS = 20;

  private OutlierClipper() {}

  public static OptionalDouble threshold(List<DailySale> history, LocalDate reviewDate) {
    List<Long> positives = new ArrayList<>();
    for (DailySale s : history) {
      if (s.businessDate().isBefore(reviewDate) && s.status().usableAsDemand() && s.unitsSold() > 0) {
        positives.add(s.unitsSold());
      }
    }
    if (positives.size() < MIN_POSITIVE_OBS) {
      return OptionalDouble.empty();
    }
    Collections.sort(positives);
    int rank = (int) Math.ceil(PERCENTILE * positives.size());
    return OptionalDouble.of(positives.get(Math.max(0, rank - 1)));
  }

  /** 학습 이력만 자른 사본. */
  public static List<DailySale> clip(List<DailySale> history, LocalDate reviewDate) {
    OptionalDouble t = threshold(history, reviewDate);
    if (t.isEmpty()) {
      return history;
    }
    long cap = (long) Math.floor(t.getAsDouble());
    List<DailySale> out = new ArrayList<>(history.size());
    for (DailySale s : history) {
      if (s.businessDate().isBefore(reviewDate) && s.unitsSold() > cap) {
        out.add(
            new DailySale(
                s.mfcId(),
                s.skuId(),
                s.businessDate(),
                cap,
                s.status(),
                s.unknownReason(),
                s.salesBasis(),
                s.sourceTimezone()));
      } else {
        out.add(s);
      }
    }
    return out;
  }
}
