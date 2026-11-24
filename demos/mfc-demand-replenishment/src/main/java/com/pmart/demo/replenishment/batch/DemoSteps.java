package com.pmart.demo.replenishment.batch;

import com.pmart.demo.replenishment.backtest.Backtester;
import com.pmart.demo.replenishment.forecast.CrostonSba;
import com.pmart.demo.replenishment.forecast.ForecastModel;
import com.pmart.demo.replenishment.forecast.ForecastSelector;
import com.pmart.demo.replenishment.forecast.MovingAverage;
import com.pmart.demo.replenishment.forecast.SeasonalNaiveWeekly;
import com.pmart.demo.replenishment.replenishment.CalcStatus;
import com.pmart.demo.replenishment.replenishment.ForecastConsumption;
import com.pmart.demo.replenishment.replenishment.Model;
import com.pmart.demo.replenishment.replenishment.Model.ForecastBucket;
import com.pmart.demo.replenishment.replenishment.Recommendation;
import com.pmart.demo.replenishment.replenishment.ReplenishmentCalculator;
import com.pmart.demo.replenishment.sales.AvailabilityStatus;
import com.pmart.demo.replenishment.sales.DailySale;
import com.pmart.demo.replenishment.sales.DailySalesCsv;
import com.pmart.demo.replenishment.sales.DailyStateAssigner;
import com.pmart.demo.replenishment.sales.SalesAggregator;
import com.pmart.demo.replenishment.sales.SalesSource;
import com.pmart.demo.replenishment.sales.StaleGap;
import com.pmart.demo.replenishment.sales.UciCleaner;
import com.pmart.demo.replenishment.scenario.ScenarioLoader;
import com.pmart.demo.replenishment.scenario.ScenarioLoader.ScenarioSku;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.stereotype.Component;

/**
 * 데모 작업의 단계별 처리. 단계는 메모리 상태가 없어도(다시 시작) 입력 파일과 DB 에서 다시 만들 수 있다.
 *
 * <p>작업 매개변수(식별): asOf(기준시각 ISO), salesCsv(UCI 형식 CSV), scenarioDir(없으면 빈 값), runLabel.
 */
@Component
public class DemoSteps {

  private static final Logger log = LoggerFactory.getLogger(DemoSteps.class);
  static final String DATA_MODE = "simulated_inventory";

  private final ResultStore store;
  private final DemoSettings settings;
  private final ReportWriter reports;
  private final Map<String, LoadedSales> salesCache = new ConcurrentHashMap<>();

  public DemoSteps(ResultStore store, DemoSettings settings, ReportWriter reports) {
    this.store = store;
    this.settings = settings;
    this.reports = reports;
  }

  /** 작업 매개변수 묶음. */
  record Params(String runId, LocalDateTime asOf, Path salesCsv, Path scenarioDir, String runLabel) {

    static Params of(StepExecution se) {
      var p = se.getJobParameters();
      String scenario = p.getString("scenarioDir", "");
      return new Params(
          "run-" + se.getJobExecution().getJobInstance().getInstanceId(),
          LocalDateTime.parse(p.getString("asOf")),
          Path.of(p.getString("salesCsv")),
          scenario.isBlank() ? null : Path.of(scenario),
          p.getString("runLabel", "default"));
    }
  }

  void prepare(StepExecution se) {
    Params p = Params.of(se);
    store.startRun(p.runId(), p.asOf(), p.runLabel(), DemoSettings.APP_VERSION, snapshot(p), DATA_MODE);
    log.info("데모 실행 {} 기준시각 {}", p.runId(), p.asOf());
  }

  /** 읽은 판매와 정제 보고 항목. */
  record LoadedSales(SalesSource source, Map<String, Long> report) {}

  /** 머리줄로 형식을 고른다: availability_status 가 있으면 명세 4장 일별 판매, 아니면 UCI 거래 줄. */
  LoadedSales sales(Params p) {
    return salesCache.computeIfAbsent(
        p.salesCsv().toAbsolutePath().toString(),
        k -> {
          try {
            Map<String, Long> items = new LinkedHashMap<>();
            if (DailySalesCsv.matches(p.salesCsv())) {
              DailySalesCsv.Result r = DailySalesCsv.read(p.salesCsv());
              items.put("format.DAILY_SALES", 1L);
              items.put("read_lines", r.readLines());
              items.put("kept_lines", r.keptLines());
              r.errors().forEach((e, v) -> items.put("error." + e, v));
              DailySalesCsv.statusCounts(r.source()).forEach((s, v) -> items.put("given_status." + s, v));
              return new LoadedSales(r.source(), items);
            }
            UciCleaner.Result r = UciCleaner.clean(p.salesCsv());
            items.put("format.UCI_TRANSACTIONS", 1L);
            items.put("read_lines", r.readLines());
            items.put("kept_lines", r.keptLines());
            r.excluded().forEach((e, v) -> items.put("excluded." + e, v));
            r.excludedNonProductCodes().forEach((c, v) -> items.put("nonproduct." + c, v));
            r.keptCodesWithoutDigit().forEach((c, v) -> items.put("kept_nodigit_review." + c, v));
            return new LoadedSales(r.source(), items);
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        });
  }

  /**
   * F01·F02: 정제·상태·집계. 상태 비율은 SKU 판매 빈도 군별로, UCI 형식이면 stale_gap 배수 1.5·2·3 민감도도 남긴다(원천이 상태를
   * 주는 형식은 배수가 상태를 바꾸지 않는다).
   */
  void salesStep(StepExecution se) {
    Params p = Params.of(se);
    LoadedSales loaded = sales(p);
    SalesSource src = loaded.source();
    store.runSource(p.runId(), src.mfcId(), src.salesBasis(), src.sourceTimezone());
    Map<String, Long> items = new LinkedHashMap<>(loaded.report());
    items.put("skus", (long) src.skuDailyUnits().size());

    LocalDate asOfDate = p.asOf().toLocalDate();
    LocalDate last = asOfDate.minusDays(1);
    if (last.isAfter(src.lastDate())) {
      last = src.lastDate();
    }
    store.clearAggregates(p.runId());
    java.util.Set<Double> mults = new java.util.LinkedHashSet<>();
    if (src.explicitDays() == null) {
      mults.add(1.5);
    }
    mults.add(settings.staleGapMultiplier);
    if (src.explicitDays() == null) {
      mults.add(3.0);
    }
    for (double mult : mults) {
      StaleGap gap = StaleGap.before(src.skuDailyUnits(), asOfDate, mult);
      for (var e : src.skuDailyUnits().entrySet()) {
        List<DailySale> days =
            DailyStateAssigner.assign(
                src,
                e.getKey(),
                e.getValue(),
                src.centerSaleDates(),
                src.firstDate(),
                last,
                gap.forSku(e.getValue(), asOfDate).days());
        String prefix = src.explicitDays() == null ? "state.x" + mult + "." : "state.given.";
        String group = "group." + frequencyGroup(days) + ".";
        for (DailySale d : days) {
          String s = d.status() == AvailabilityStatus.UNKNOWN ? "UNKNOWN." + d.unknownReason() : d.status().name();
          items.merge(prefix + s, 1L, Long::sum);
          if (mult == settings.staleGapMultiplier) {
            items.merge(group + s, 1L, Long::sum);
          }
        }
        if (mult == settings.staleGapMultiplier) {
          store.aggregates(p.runId(), SalesAggregator.aggregate(days));
        }
      }
    }
    store.cleanReport(p.runId(), items);
  }

  /** 판매일 비율로 SKU 군: HIGH ≥ 50%, MID ≥ 10%, LOW 그 밖. */
  static String frequencyGroup(List<DailySale> days) {
    // 분모 = 판매를 관측한 날(NORMAL·ZERO_ASSUMED·STOCKOUT), 분자 = 그중 판매량 1 이상인 날.
    long center =
        days.stream()
            .filter(d -> d.status().usableAsDemand() || d.status() == AvailabilityStatus.STOCKOUT)
            .count();
    long sold = days.stream().filter(d -> d.unitsSold() > 0).count();
    double share = center == 0 ? 0 : (double) sold / center;
    return share >= 0.5 ? "HIGH" : share >= 0.1 ? "MID" : "LOW";
  }

  /** F03·F04: SKU 별 예측. 한 SKU 실패는 기록하고 다음 SKU 로. */
  void forecastStep(StepExecution se) {
    Params p = Params.of(se);
    store.clearStep(p.runId(), "forecast");
    SalesSource src = sales(p).source();
    LocalDate asOfDate = p.asOf().toLocalDate();
    StaleGap gap = StaleGap.before(src.skuDailyUnits(), asOfDate, settings.staleGapMultiplier);
    ForecastSelector selector = ForecastSelector.defaultChain(settings.maWindow);
    LocalDate from = asOfDate.minusDays(settings.lookbackDays);
    if (from.isBefore(src.firstDate())) {
      from = src.firstDate();
    }
    int ok = 0;
    int pending = 0;
    for (var e : src.skuDailyUnits().entrySet()) {
      NavigableMap<LocalDate, Long> series = e.getValue();
      if (series.lowerKey(asOfDate) == null || !from.isBefore(asOfDate)) {
        continue;
      }
      try {
        List<DailySale> history =
            DailyStateAssigner.assign(
                src, e.getKey(), series, src.centerSaleDates(), from, asOfDate.minusDays(1),
                gap.forSku(series, asOfDate).days());
        ForecastSelector.Selection sel = selector.select(history, asOfDate, settings.forecastHorizonDays);
        if (sel.forecast() == null) {
          pending++;
          store.failure(p.runId(), "forecast", src.mfcId(), e.getKey(), "INPUT_PENDING: " + sel.fallbackReason());
          continue;
        }
        store.forecast(p.runId(), p.asOf(), src.mfcId(), e.getKey(), sel.forecast(), sel.status(), sel.fallbackReason());
        ok++;
      } catch (RuntimeException ex) {
        store.failure(p.runId(), "forecast", src.mfcId(), e.getKey(), ex.toString());
      }
    }
    log.info("예측 저장 {}건, 예측 없음 {}건", ok, pending);
  }

  /** F05: 고정 시나리오로 추천. 공유 상한을 넘는 군은 사유로 표시한다. */
  void recommendStep(StepExecution se) {
    Params p = Params.of(se);
    store.clearStep(p.runId(), "recommend");
    if (p.scenarioDir() == null) {
      log.info("시나리오 없음 — 추천 단계 건너뜀");
      return;
    }
    ScenarioLoader.Scenario scenario;
    try {
      scenario = ScenarioLoader.load(p.scenarioDir());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    Map<String, StoredForecast> forecasts = storedForecasts(p.runId());
    List<Recommendation> recs = new ArrayList<>();
    for (ScenarioSku s : scenario.skus()) {
      if (s.error() != null) {
        store.failure(p.runId(), "recommend", s.mfcId(), s.skuId(), s.error());
        continue;
      }
      try {
        List<ForecastBucket> buckets;
        CalcStatus fStatus;
        String model;
        if (!s.forecast().isEmpty()) {
          buckets = s.forecast();
          fStatus = CalcStatus.CALCULABLE;
          model = s.forecastModel();
        } else {
          StoredForecast f = forecasts.get(s.skuId());
          buckets = f == null ? List.of() : f.buckets(p.asOf(), s.policy().protectionEnd());
          fStatus = f == null ? CalcStatus.INPUT_PENDING : f.status();
          model = f == null ? null : f.modelId();
        }
        Model.Input in =
            new Model.Input(
                s.mfcId(), s.skuId(), p.asOf(), s.policy(), s.caps(), s.inventory(), s.commitments(),
                s.supplies(), buckets, fStatus, model, s.acceptSecuredSupply(), DATA_MODE);
        recs.add(ReplenishmentCalculator.calculate(in));
      } catch (RuntimeException ex) {
        store.failure(p.runId(), "recommend", s.mfcId(), s.skuId(), ex.toString());
      }
    }
    recs = checkSharedCaps(recs, scenario.sharedCaps());
    for (Recommendation r : recs) {
      store.recommendation(p.runId(), r);
    }
    log.info("추천 저장 {}건", recs.size());
  }

  /** 같은 상한을 나눠 쓰는 SKU 들의 Q 합이 상한을 넘으면 각 SKU 에 사유를 붙인다(명세 7장 3번). */
  static List<Recommendation> checkSharedCaps(List<Recommendation> recs, Map<String, Long> sharedCaps) {
    Map<String, Long> sums = new TreeMap<>();
    for (Recommendation r : recs) {
      if (r.sharedCapGroup() != null && r.quantity() != null) {
        sums.merge(r.sharedCapGroup(), r.quantity(), Long::sum);
      }
    }
    List<Recommendation> out = new ArrayList<>();
    for (Recommendation r : recs) {
      String g = r.sharedCapGroup();
      if (g == null) {
        out.add(r);
      } else if (!sharedCaps.containsKey(g)) {
        out.add(r.withReason("SHARED_CAP_UNKNOWN: 공유 상한 " + g + " 값 없음 — 합계 실행 가능 여부 미확인"));
      } else if (sums.getOrDefault(g, 0L) > sharedCaps.get(g)) {
        out.add(
            r.withReason(
                "SHARED_CAP_EXCEEDED: " + g + " 합계 " + sums.get(g) + " > 상한 " + sharedCaps.get(g)
                    + " — 개별 추천의 합은 실행 불가"));
      } else {
        out.add(r.withReason("SHARED_CAP_OK: " + g + " 합계 " + sums.getOrDefault(g, 0L) + " ≤ " + sharedCaps.get(g)));
      }
    }
    return out;
  }

  /** F06. */
  void backtestStep(StepExecution se) {
    Params p = Params.of(se);
    store.clearStep(p.runId(), "backtest");
    if (!settings.backtest) {
      return;
    }
    SalesSource src = sales(p).source();
    LocalDate firstReview = src.firstDate().plusYears(1);
    LocalDate lastReview = p.asOf().toLocalDate().minusDays(1);
    List<ForecastModel> models =
        List.of(
            new MovingAverage(7),
            new MovingAverage(14),
            new MovingAverage(28),
            new SeasonalNaiveWeekly(),
            new CrostonSba());
    Backtester.Result result =
        Backtester.run(
            src,
            models,
            new Backtester.Config(
                firstReview,
                lastReview,
                settings.backtestStepDays,
                settings.backtestLeadDays,
                settings.staleGapMultiplier,
                settings.lookbackDays));
    store.backtest(p.runId(), result);
    log.info("백테스트 SKU·검토일 {}건", result.skuReviews());
  }

  void reportStep(StepExecution se) {
    Params p = Params.of(se);
    Path dir = Path.of(settings.outputDir, p.runId());
    reports.write(p.runId(), dir, settings);
    store.finishRun(p.runId());
    log.info("보고서: {}", dir.resolve("report.md").toAbsolutePath());
  }

  /** 앞 단계 예측(DB). 다시 시작해도 같다. */
  Map<String, StoredForecast> storedForecasts(String runId) {
    Map<String, StoredForecast> out = new LinkedHashMap<>();
    for (Map<String, Object> row : store.forecastsOf(runId)) {
      String sku = (String) row.get("sku_id");
      LocalDate d = ((java.sql.Date) row.get("forecast_date")).toLocalDate();
      double v = ((BigDecimal) row.get("point_forecast")).doubleValue();
      out.computeIfAbsent(
              sku,
              k -> new StoredForecast((String) row.get("model_id"), CalcStatus.valueOf((String) row.get("status"))))
          .daily
          .put(d, v);
    }
    return out;
  }

  /** 저장된 일별 예측. */
  static final class StoredForecast {
    final String modelId;
    final CalcStatus status;
    final TreeMap<LocalDate, Double> daily = new TreeMap<>();

    StoredForecast(String modelId, CalcStatus status) {
      this.modelId = modelId;
      this.status = status;
    }

    String modelId() {
      return modelId;
    }

    CalcStatus status() {
      return status;
    }

    /** [asOf, end) 를 하루 구간으로. 예측이 없는 날에서 멈춘다(계산기가 INPUT_PENDING 으로 본다). */
    List<ForecastBucket> buckets(LocalDateTime asOf, LocalDateTime end) {
      List<ForecastBucket> out = new ArrayList<>();
      LocalDateTime cursor = asOf;
      while (cursor.isBefore(end)) {
        LocalDateTime next = cursor.toLocalDate().plusDays(1).atStartOfDay();
        Double v = daily.get(cursor.toLocalDate());
        if (v == null) {
          break;
        }
        out.add(new ForecastBucket(cursor, next.isAfter(end) ? end : next, v, ForecastConsumption.FULL_DEMAND));
        cursor = next;
      }
      return out;
    }
  }

  /** 입력 파일들의 SHA-256(입력 스냅샷 버전). */
  static String snapshot(Params p) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      List<Path> files = new ArrayList<>();
      files.add(p.salesCsv());
      if (p.scenarioDir() != null) {
        try (Stream<Path> s = Files.list(p.scenarioDir())) {
          s.filter(f -> f.toString().endsWith(".csv")).sorted().forEach(files::add);
        }
      }
      for (Path f : files) {
        md.update(f.getFileName().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try (InputStream in = new DigestInputStream(Files.newInputStream(f), md)) {
          in.transferTo(java.io.OutputStream.nullOutputStream());
        }
      }
      return HexFormat.of().formatHex(md.digest());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
