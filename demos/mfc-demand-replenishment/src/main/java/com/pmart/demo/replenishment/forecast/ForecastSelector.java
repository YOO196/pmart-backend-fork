package com.pmart.demo.replenishment.forecast;

import com.pmart.demo.replenishment.forecast.ForecastModel.Forecast;
import com.pmart.demo.replenishment.forecast.ForecastModel.InsufficientHistoryException;
import com.pmart.demo.replenishment.replenishment.CalcStatus;
import com.pmart.demo.replenishment.sales.DailySale;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 배치 예측의 모델 선택과 대체 경로(명세 F03·F04). 1순위 이동평균, 부족하면 Croston-SBA(간헐 수요). 둘 다 안 되면 예측 없음 —
 * 0 으로 줄이지 않는다.
 *
 * <p>상태: 1순위 성공 = CALCULABLE, 대체 모델 사용 = REFERENCE_ONLY, 예측 없음 = INPUT_PENDING.
 */
public final class ForecastSelector {

  /** 선택 결과. forecast 는 INPUT_PENDING 이면 null. */
  public record Selection(Forecast forecast, CalcStatus status, String fallbackReason) {}

  private final List<ForecastModel> chain;

  public ForecastSelector(List<ForecastModel> chain) {
    if (chain.isEmpty()) {
      throw new IllegalArgumentException("모델이 하나는 있어야 함");
    }
    this.chain = List.copyOf(chain);
  }

  public static ForecastSelector defaultChain(int movingAverageWindow) {
    return new ForecastSelector(List.of(new MovingAverage(movingAverageWindow), new CrostonSba()));
  }

  public Selection select(List<DailySale> history, LocalDate firstTarget, int horizonDays) {
    List<String> reasons = new ArrayList<>();
    for (int i = 0; i < chain.size(); i++) {
      ForecastModel m = chain.get(i);
      try {
        Forecast f = m.forecast(history, firstTarget, horizonDays);
        if (!f.complete()) {
          reasons.add(m.id() + ": 근거 없는 예측일 있음");
          continue;
        }
        CalcStatus status = i == 0 ? CalcStatus.CALCULABLE : CalcStatus.REFERENCE_ONLY;
        return new Selection(f, status, reasons.isEmpty() ? null : String.join("; ", reasons));
      } catch (InsufficientHistoryException e) {
        reasons.add(e.getMessage());
      }
    }
    return new Selection(null, CalcStatus.INPUT_PENDING, String.join("; ", reasons));
  }
}
