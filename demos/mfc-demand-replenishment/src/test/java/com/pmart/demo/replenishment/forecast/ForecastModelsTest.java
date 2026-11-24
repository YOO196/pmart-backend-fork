package com.pmart.demo.replenishment.forecast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pmart.demo.replenishment.forecast.ForecastModel.Forecast;
import com.pmart.demo.replenishment.forecast.ForecastModel.InsufficientHistoryException;
import com.pmart.demo.replenishment.replenishment.CalcStatus;
import com.pmart.demo.replenishment.sales.AvailabilityStatus;
import com.pmart.demo.replenishment.sales.DailySale;
import com.pmart.demo.replenishment.sales.UnknownReason;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ForecastModelsTest {

  static final LocalDate D0 = LocalDate.of(2010, 1, 4); // 월요일

  /** 값 배열로 이력. 음수 = 미확인(UNKNOWN). */
  static List<DailySale> history(long... values) {
    List<DailySale> out = new ArrayList<>();
    for (int i = 0; i < values.length; i++) {
      LocalDate d = D0.plusDays(i);
      if (values[i] < 0) {
        out.add(new DailySale("M", "A", d, 0, AvailabilityStatus.UNKNOWN, UnknownReason.NO_CENTER_SALES, "B", "Z"));
      } else {
        AvailabilityStatus s = values[i] > 0 ? AvailabilityStatus.NORMAL : AvailabilityStatus.ZERO_ASSUMED;
        out.add(new DailySale("M", "A", d, values[i], s, null, "B", "Z"));
      }
    }
    return out;
  }

  @Test
  void movingAverageSkipsUnknownDays() {
    List<DailySale> h = history(10, 10, -1, 10, 10, 10, 10, 10, 0, 0);
    Forecast f = new MovingAverage(7).forecast(h, D0.plusDays(10), 3);
    // 최근 유효 7일: 0,0,10,10,10,10,10 → 50/7. 미확인일은 0 으로 세지 않는다.
    assertThat(f.daily()).containsExactly(50.0 / 7, 50.0 / 7, 50.0 / 7);
  }

  @Test
  void movingAverageNeedsHistory() {
    assertThatThrownBy(() -> new MovingAverage(28).forecast(history(1, 2, 3), D0.plusDays(3), 7))
        .isInstanceOf(InsufficientHistoryException.class);
  }

  @Test
  void seasonalNaiveUsesSameWeekdayAndMarksNoBasis() {
    // 2주: 월~일. 토요일(인덱스 5, 12)은 미확인.
    List<DailySale> h = history(1, 2, 3, 4, 5, -1, 7, 11, 12, 13, 14, 15, -1, 17);
    Forecast f = new SeasonalNaiveWeekly().forecast(h, D0.plusDays(14), 7);
    assertThat(f.daily()[0]).isEqualTo(11.0);
    assertThat(f.daily()[4]).isEqualTo(15.0);
    assertThat(f.daily()[5]).isNaN(); // 토요일 근거 없음
    assertThat(f.complete()).isFalse();
  }

  @Test
  void crostonDoesNotBridgeUnknownGaps() {
    // 수요가 3일마다. 두 번째 간격 사이에 미확인일이 있어 그 간격은 쓰지 않는다.
    List<DailySale> h = history(5, 0, 0, 5, 0, -1, 5, 0, 0, 5);
    CrostonSba.State st = new CrostonSba(0.1).fit(h, D0.plusDays(10));
    assertThat(st.demands()).isEqualTo(4);
    assertThat(st.usedIntervals()).isEqualTo(2);
    assertThat(st.skippedIntervals()).isEqualTo(1);
    assertThat(st.p()).isEqualTo(3.0);
    Forecast f = new CrostonSba(0.1).forecast(h, D0.plusDays(10), 2);
    assertThat(f.daily()[0]).isEqualTo((1 - 0.05) * 5 / 3.0);
  }

  @Test
  void crostonWithoutValidIntervalIsInsufficient() {
    List<DailySale> h = history(5, -1, 5, -1, 5);
    assertThatThrownBy(() -> new CrostonSba().forecast(h, D0.plusDays(5), 1))
        .isInstanceOf(InsufficientHistoryException.class);
  }

  @Test
  void selectorFallsBackAndNeverInventsZero() {
    ForecastSelector sel = ForecastSelector.defaultChain(28);
    // 유효 관측 5일뿐 → MA28 부족 → Croston.
    ForecastSelector.Selection s = sel.select(history(3, 0, 0, 3, 0), D0.plusDays(5), 7);
    assertThat(s.status()).isEqualTo(CalcStatus.REFERENCE_ONLY);
    assertThat(s.forecast().modelId()).isEqualTo("CROSTON_SBA");
    assertThat(s.fallbackReason()).contains("MA28");

    ForecastSelector.Selection none = sel.select(history(-1, -1, 4), D0.plusDays(3), 7);
    assertThat(none.status()).isEqualTo(CalcStatus.INPUT_PENDING);
    assertThat(none.forecast()).isNull();
  }

  @Test
  void outlierThresholdFromTrainingOnly() {
    long[] v = new long[30];
    for (int i = 0; i < 30; i++) {
      v[i] = 10;
    }
    v[29] = 5000; // 도매 이상치
    List<DailySale> h = history(v);
    List<DailySale> clipped = OutlierClipper.clip(h, D0.plusDays(30));
    assertThat(clipped.get(29).unitsSold()).isEqualTo(5000); // 99백분위 = 최댓값(30개) → 안 잘림
    long[] w = new long[200];
    for (int i = 0; i < 200; i++) {
      w[i] = i == 150 ? 5000 : 10;
    }
    List<DailySale> h2 = history(w);
    List<DailySale> c2 = OutlierClipper.clip(h2, D0.plusDays(200));
    assertThat(c2.get(150).unitsSold()).isEqualTo(10);
    // 검토일 뒤(평가 구간)는 자르지 않는다.
    List<DailySale> c3 = OutlierClipper.clip(h2, D0.plusDays(150));
    assertThat(c3.get(150).unitsSold()).isEqualTo(5000);
  }
}
