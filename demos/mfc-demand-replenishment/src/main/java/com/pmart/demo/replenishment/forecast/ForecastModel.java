package com.pmart.demo.replenishment.forecast;

import com.pmart.demo.replenishment.sales.DailySale;
import java.time.LocalDate;
import java.util.List;

/**
 * 일별 점예측 모델. 학습에는 {@code status.usableAsDemand()} 인 날만 쓴다(품절·누락·미확인을 0 수요로 쓰지 않는다).
 * 예측의 뜻은 "기준시각 이후 전체 수요"(판매 이력으로 배웠으므로 확정 주문을 포함).
 */
public interface ForecastModel {

  String FORECAST_SCOPE = "FULL_DEMAND";

  String id();

  String version();

  /**
   * @param history 기준일 전 일별 판매(날짜 순)
   * @param firstTarget 예측 첫날(기준일)
   * @param horizonDays 예측 일수
   * @return 일별 예측. 값이 NaN 인 날은 근거가 없는 날
   * @throws InsufficientHistoryException 이력이 부족할 때
   */
  Forecast forecast(List<DailySale> history, LocalDate firstTarget, int horizonDays);

  /** 일별 예측 결과. */
  record Forecast(String modelId, String modelVersion, LocalDate firstDate, double[] daily) {

    public Forecast {
      for (double v : daily) {
        if (v < 0) {
          throw new IllegalStateException("예측은 음수일 수 없음: " + modelId);
        }
      }
    }

    public boolean complete() {
      for (double v : daily) {
        if (Double.isNaN(v)) {
          return false;
        }
      }
      return true;
    }

    public double sum() {
      double s = 0;
      for (double v : daily) {
        s += v;
      }
      return s;
    }
  }

  /** 이력 부족. 대체 경로로 넘어간다(명세 F04). */
  final class InsufficientHistoryException extends RuntimeException {
    public InsufficientHistoryException(String message) {
      super(message);
    }
  }
}
