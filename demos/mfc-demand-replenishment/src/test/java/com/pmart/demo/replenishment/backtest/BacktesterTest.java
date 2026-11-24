package com.pmart.demo.replenishment.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.demo.replenishment.forecast.MovingAverage;
import com.pmart.demo.replenishment.sales.SalesSource;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class BacktesterTest {

  static final LocalDate D0 = LocalDate.of(2010, 1, 1);

  static SalesSource constant(boolean closeSaturdays) {
    TreeSet<LocalDate> center = new TreeSet<>();
    TreeMap<LocalDate, Long> sku = new TreeMap<>();
    for (int i = 0; i < 500; i++) {
      LocalDate d = D0.plusDays(i);
      if (closeSaturdays && d.getDayOfWeek() == DayOfWeek.SATURDAY) {
        continue;
      }
      center.add(d);
      sku.put(d, 10L);
    }
    Map<String, NavigableMap<LocalDate, Long>> skus = new TreeMap<>(Map.of("A", sku));
    return new SalesSource("M", "B", "Z", D0, D0.plusDays(499), skus, center);
  }

  static Backtester.Config cfg() {
    return new Backtester.Config(D0.plusDays(365), D0.plusDays(480), 7, 2, 2.0, 365);
  }

  /** 일정한 판매면 오차·편향 0, 재고 시뮬레이션 품절 0. */
  @Test
  void constantDemandHasNoError() {
    Backtester.Result r = Backtester.run(constant(false), List.of(new MovingAverage(7)), cfg());
    Backtester.Metric strict =
        r.metrics().stream().filter(m -> !m.clipped && m.kind == Backtester.Kind.STRICT).findFirst().orElseThrow();
    assertThat(strict.windows).isPositive();
    assertThat(strict.mae()).isEqualTo(0.0);
    assertThat(strict.bias()).isEqualTo(0.0);
    assertThat(r.simulations().getFirst().stockoutRate()).isEqualTo(0.0);
  }

  /** 토요일마다 센터 무판매(UNKNOWN)면 9일 창은 STRICT 로 평가할 수 없고 DAY_MATCHED 는 오차 0 으로 평가된다. */
  @Test
  void weeklyUnknownDaysExcludeStrictWindows() {
    Backtester.Result r = Backtester.run(constant(true), List.of(new MovingAverage(7)), cfg());
    Backtester.Metric strict =
        r.metrics().stream().filter(m -> !m.clipped && m.kind == Backtester.Kind.STRICT).findFirst().orElseThrow();
    Backtester.Metric matched =
        r.metrics().stream().filter(m -> !m.clipped && m.kind == Backtester.Kind.DAY_MATCHED).findFirst().orElseThrow();
    assertThat(strict.windows).isZero();
    assertThat(strict.excludedWindows).isPositive();
    assertThat(matched.windows).isPositive();
    assertThat(matched.mae()).isEqualTo(0.0);
    assertThat(matched.coverage()).isLessThan(1.0);
  }
}
