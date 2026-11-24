package com.pmart.demo.replenishment.backtest;

import com.pmart.demo.replenishment.forecast.ForecastModel;
import com.pmart.demo.replenishment.forecast.ForecastModel.Forecast;
import com.pmart.demo.replenishment.forecast.ForecastModel.InsufficientHistoryException;
import com.pmart.demo.replenishment.forecast.OutlierClipper;
import com.pmart.demo.replenishment.sales.AvailabilityStatus;
import com.pmart.demo.replenishment.sales.DailySale;
import com.pmart.demo.replenishment.sales.DailyStateAssigner;
import com.pmart.demo.replenishment.sales.SalesSource;
import com.pmart.demo.replenishment.sales.StaleGap;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

/**
 * 순차 백테스트(명세 F06, 6장 구현 지시 2). 검토일마다 그 전 데이터만으로 상태(stale_gap_days 포함)·이상치 임계값·예측을 만들고, 보호기간
 * 누적 예측을 실제와 비교한다.
 *
 * <ul>
 *   <li>STRICT: 보호기간의 모든 날이 평가 가능(NORMAL·ZERO_ASSUMED)한 창만 비교(명세 원문 규칙).
 *   <li>DAY_MATCHED: 평가 가능한 날만 골라 같은 날의 예측과 실제를 누적 비교. 평가 가능일 비율을 함께 낸다. UCI 처럼 요일마다
 *       센터 무판매일(UNKNOWN)이 있으면 STRICT 창이 거의 없어 이 지표를 함께 본다.
 * </ul>
 *
 * 시뮬레이션(재고)은 data_mode=simulated_inventory: 주기 검토·목표 재고(order-up-to = 보호기간 예측 합)·리드타임 뒤 입고. 실제
 * 품절률이 아니다.
 */
public final class Backtester {

  public enum Kind {
    STRICT,
    DAY_MATCHED
  }

  /** 설정. horizonDays = leadDays + stepDays(보호기간 근사). */
  public record Config(
      LocalDate firstReview,
      LocalDate lastReview,
      int stepDays,
      int leadDays,
      double staleGapMultiplier,
      int lookbackDays) {

    public int horizonDays() {
      return leadDays + stepDays;
    }
  }

  /** 모델·이상치 처리·지표 종류별 합계. */
  public static final class Metric {
    public final String modelId;
    public final boolean clipped;
    public final Kind kind;
    public long windows;
    public long excludedWindows;
    public long unavailable;
    double absErr;
    double err;
    double actual;
    public long evaluatedDays;
    public long zeroAssumedDays;
    public long horizonDays;

    Metric(String modelId, boolean clipped, Kind kind) {
      this.modelId = modelId;
      this.clipped = clipped;
      this.kind = kind;
    }

    public Double mae() {
      return windows == 0 ? null : absErr / windows;
    }

    public Double bias() {
      return windows == 0 ? null : err / windows;
    }

    /** Σ|오차| / Σ실제. */
    public Double wape() {
      return actual == 0 ? null : absErr / actual;
    }

    public Double zeroAssumedShare() {
      return evaluatedDays == 0 ? null : (double) zeroAssumedDays / evaluatedDays;
    }

    /** 평가 가능일 / 보호기간 날 수(DAY_MATCHED). */
    public Double coverage() {
      return horizonDays == 0 ? null : (double) evaluatedDays / horizonDays;
    }
  }

  /** 재고 시뮬레이션 결과(모델별, 이상치 처리 안 함). */
  public static final class Simulation {
    public final String modelId;
    public long reviews;
    public long orders;
    public long noForecastReviews;
    public long demandDays;
    public long stockoutDays;
    double onHandSum;
    public long simulatedDays;

    Simulation(String modelId) {
      this.modelId = modelId;
    }

    public Double stockoutRate() {
      return demandDays == 0 ? null : (double) stockoutDays / demandDays;
    }

    public Double averageOnHand() {
      return simulatedDays == 0 ? null : onHandSum / simulatedDays;
    }
  }

  public record Result(Config config, List<Metric> metrics, List<Simulation> simulations, long skuReviews) {}

  private Backtester() {}

  public static Result run(SalesSource source, List<ForecastModel> models, Config cfg) {
    Map<String, Metric> metrics = new LinkedHashMap<>();
    Map<String, Simulation> sims = new LinkedHashMap<>();
    for (ForecastModel m : models) {
      for (boolean clip : new boolean[] {false, true}) {
        for (Kind k : Kind.values()) {
          metrics.put(key(m.id(), clip, k), new Metric(m.id(), clip, k));
        }
      }
      sims.put(m.id(), new Simulation(m.id()));
    }
    int h = cfg.horizonDays();
    long skuReviews = 0;
    // 시뮬레이션 상태: 모델 → SKU → [재고, 입고 예정(날짜→수량)]
    Map<String, Map<String, SimState>> simStates = new LinkedHashMap<>();
    for (ForecastModel m : models) {
      simStates.put(m.id(), new LinkedHashMap<>());
    }

    for (LocalDate r = cfg.firstReview(); !r.isAfter(cfg.lastReview()); r = r.plusDays(cfg.stepDays())) {
      LocalDate evalEnd = r.plusDays(h - 1);
      if (evalEnd.isAfter(source.lastDate())) {
        break;
      }
      StaleGap gapCalc = StaleGap.before(source.skuDailyUnits(), r, cfg.staleGapMultiplier());
      LocalDate from = r.minusDays(cfg.lookbackDays());
      if (from.isBefore(source.firstDate())) {
        from = source.firstDate();
      }
      for (var e : source.skuDailyUnits().entrySet()) {
        NavigableMap<LocalDate, Long> series = e.getValue();
        if (series.lowerKey(r) == null) {
          continue; // 검토일 전 판매가 없는 SKU
        }
        skuReviews++;
        int gap = gapCalc.forSku(series, r).days();
        List<DailySale> days =
            DailyStateAssigner.assign(source, e.getKey(), series, source.centerSaleDates(), from, evalEnd, gap);
        int split = days.size() - h;
        if (split <= 0) {
          continue;
        }
        List<DailySale> history = days.subList(0, split);
        List<DailySale> actual = days.subList(split, days.size());
        boolean strictOk = actual.stream().allMatch(s -> s.status().usableAsDemand());

        for (ForecastModel m : models) {
          for (boolean clip : new boolean[] {false, true}) {
            List<DailySale> train = clip ? OutlierClipper.clip(history, r) : history;
            Forecast f;
            try {
              f = m.forecast(train, r, h);
            } catch (InsufficientHistoryException ex) {
              f = null;
            }
            score(metrics.get(key(m.id(), clip, Kind.STRICT)), f, actual, strictOk, true);
            score(metrics.get(key(m.id(), clip, Kind.DAY_MATCHED)), f, actual, true, false);
            if (!clip) {
              simulate(sims.get(m.id()), simStates.get(m.id()), e.getKey(), f, actual, r, cfg);
            }
          }
        }
      }
    }
    return new Result(cfg, new ArrayList<>(metrics.values()), new ArrayList<>(sims.values()), skuReviews);
  }

  private static void score(Metric mt, Forecast f, List<DailySale> actual, boolean eligible, boolean strict) {
    if (f == null) {
      mt.unavailable++;
      return;
    }
    if (!eligible) {
      mt.excludedWindows++;
      return;
    }
    double fs = 0;
    double as = 0;
    int n = 0;
    int zero = 0;
    for (int i = 0; i < actual.size(); i++) {
      DailySale a = actual.get(i);
      double fv = f.daily()[i];
      if (!a.status().usableAsDemand() || Double.isNaN(fv)) {
        if (strict) {
          mt.excludedWindows++; // 근거 없는 예측일 포함
          return;
        }
        continue;
      }
      fs += fv;
      as += a.unitsSold();
      n++;
      if (a.status() == AvailabilityStatus.ZERO_ASSUMED) {
        zero++;
      }
    }
    if (n == 0) {
      mt.excludedWindows++;
      return;
    }
    mt.windows++;
    mt.absErr += Math.abs(fs - as);
    mt.err += fs - as;
    mt.actual += as;
    mt.evaluatedDays += n;
    mt.zeroAssumedDays += zero;
    mt.horizonDays += actual.size();
  }

  /** SKU 별 시뮬레이션 상태. */
  private static final class SimState {
    double onHand;
    final Map<LocalDate, Double> arrivals = new LinkedHashMap<>();
    boolean started;
  }

  /**
   * 검토일 r 에 주문(목표 = 보호기간 예측 합, 근거 없는 날 제외)을 내고 r+lead 에 입고, r ~ r+step−1 동안 실제 수요를 뺀다. 미확인일은
   * 수요 없음으로 둔다(평가 불가일). 첫 검토일의 시작 재고 = 목표.
   */
  private static void simulate(
      Simulation sim, Map<String, SimState> states, String sku, Forecast f, List<DailySale> actual, LocalDate r, Config cfg) {
    SimState st = states.computeIfAbsent(sku, k -> new SimState());
    sim.reviews++;
    double target = Double.NaN;
    if (f != null) {
      double s = 0;
      for (double v : f.daily()) {
        if (!Double.isNaN(v)) {
          s += v;
        }
      }
      target = s;
    }
    if (!st.started) {
      if (Double.isNaN(target)) {
        sim.noForecastReviews++;
        return; // 시작 재고를 정할 수 없음
      }
      st.onHand = Math.ceil(target);
      st.started = true;
    }
    if (Double.isNaN(target)) {
      sim.noForecastReviews++;
    } else {
      double onOrder = st.arrivals.values().stream().mapToDouble(Double::doubleValue).sum();
      double q = Math.max(0, Math.ceil(target - (st.onHand + onOrder)));
      if (q > 0) {
        st.arrivals.merge(r.plusDays(cfg.leadDays()), q, Double::sum);
        sim.orders++;
      }
    }
    for (int i = 0; i < cfg.stepDays() && i < actual.size(); i++) {
      LocalDate d = r.plusDays(i);
      Double in = st.arrivals.remove(d);
      if (in != null) {
        st.onHand += in;
      }
      DailySale a = actual.get(i);
      if (a.status().usableAsDemand()) {
        if (a.unitsSold() > 0) {
          sim.demandDays++;
          if (a.unitsSold() > st.onHand) {
            sim.stockoutDays++;
          }
        }
        st.onHand = Math.max(0, st.onHand - a.unitsSold());
      }
      sim.onHandSum += st.onHand;
      sim.simulatedDays++;
    }
  }

  private static String key(String model, boolean clip, Kind k) {
    return model + "|" + clip + "|" + k;
  }
}
