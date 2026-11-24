package com.pmart.demo.replenishment.batch;

import com.pmart.demo.replenishment.backtest.Backtester;
import com.pmart.demo.replenishment.forecast.ForecastModel.Forecast;
import com.pmart.demo.replenishment.replenishment.CalcStatus;
import com.pmart.demo.replenishment.replenishment.Recommendation;
import com.pmart.demo.replenishment.sales.AvailabilityStatus;
import com.pmart.demo.replenishment.sales.SalesAggregator;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 데모 결과 저장(JdbcTemplate). 단계를 다시 돌리면 그 run_id 의 그 단계 결과를 지우고 다시 쓴다. */
@Component
public class ResultStore {

  private final JdbcTemplate jdbc;

  public ResultStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void startRun(
      String runId,
      LocalDateTime asOf,
      String label,
      String version,
      String snapshot,
      String dataMode) {
    jdbc.update(
        """
        INSERT INTO demo_run (run_id, as_of, run_label, app_version, input_snapshot, data_mode, started_at, status)
        VALUES (?, ?, ?, ?, ?, ?, now(), 'STARTED')
        ON CONFLICT (run_id) DO UPDATE SET status = 'RESTARTED', input_snapshot = EXCLUDED.input_snapshot
        """,
        runId,
        Timestamp.valueOf(asOf),
        label,
        version,
        snapshot,
        dataMode);
  }

  public void runSource(String runId, String mfcId, String salesBasis, String timezone) {
    jdbc.update(
        "UPDATE demo_run SET mfc_id = ?, sales_basis = ?, source_timezone = ? WHERE run_id = ?",
        mfcId,
        salesBasis,
        timezone,
        runId);
  }

  public void finishRun(String runId) {
    jdbc.update("UPDATE demo_run SET status = 'COMPLETED', finished_at = now() WHERE run_id = ?", runId);
  }

  public void cleanReport(String runId, Map<String, Long> items) {
    jdbc.update("DELETE FROM demo_clean_report WHERE run_id = ?", runId);
    List<Object[]> rows = new ArrayList<>();
    items.forEach((k, v) -> rows.add(new Object[] {runId, k, v}));
    jdbc.batchUpdate("INSERT INTO demo_clean_report (run_id, item, line_count) VALUES (?, ?, ?)", rows);
  }

  public void clearAggregates(String runId) {
    jdbc.update("DELETE FROM demo_sales_aggregate WHERE run_id = ?", runId);
  }

  public void aggregates(String runId, List<SalesAggregator.Row> rows) {
    List<Object[]> args = new ArrayList<>(rows.size());
    for (SalesAggregator.Row r : rows) {
      args.add(
          new Object[] {
            runId,
            r.mfcId(),
            r.skuId(),
            r.periodType().name(),
            r.periodKey(),
            r.units(),
            r.days(AvailabilityStatus.NORMAL),
            r.days(AvailabilityStatus.ZERO_ASSUMED),
            r.days(AvailabilityStatus.STOCKOUT),
            r.days(AvailabilityStatus.MISSING),
            r.days(AvailabilityStatus.CLOSED_DAY),
            r.days(AvailabilityStatus.PRE_LAUNCH),
            r.days(AvailabilityStatus.UNKNOWN)
          });
    }
    jdbc.batchUpdate(
        """
        INSERT INTO demo_sales_aggregate (run_id, mfc_id, sku_id, period_type, period_key, units, normal_days,
          zero_assumed_days, stockout_days, missing_days, closed_days, pre_launch_days, unknown_days)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        args);
  }

  public void clearStep(String runId, String step) {
    switch (step) {
      case "forecast" -> jdbc.update("DELETE FROM demo_forecast WHERE run_id = ?", runId);
      case "recommend" -> jdbc.update("DELETE FROM demo_recommendation WHERE run_id = ?", runId);
      case "backtest" -> {
        jdbc.update("DELETE FROM demo_backtest WHERE run_id = ?", runId);
        jdbc.update("DELETE FROM demo_simulation WHERE run_id = ?", runId);
      }
      default -> throw new IllegalArgumentException(step);
    }
    jdbc.update("DELETE FROM demo_failure WHERE run_id = ? AND step = ?", runId, step);
  }

  public void forecast(
      String runId, LocalDateTime asOf, String mfcId, String skuId, Forecast f, CalcStatus status, String fallback) {
    List<Object[]> args = new ArrayList<>();
    for (int i = 0; i < f.daily().length; i++) {
      args.add(
          new Object[] {
            runId,
            Timestamp.valueOf(asOf),
            mfcId,
            skuId,
            java.sql.Date.valueOf(f.firstDate().plusDays(i)),
            f.daily()[i],
            "FULL_DEMAND",
            f.modelId(),
            f.modelVersion(),
            status.name(),
            fallback
          });
    }
    jdbc.batchUpdate(
        """
        INSERT INTO demo_forecast (run_id, as_of, mfc_id, sku_id, forecast_date, point_forecast, forecast_scope,
          model_id, model_version, status, fallback_reason)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        args);
  }

  /** 같은 기준시각·센터·SKU 의 이전 활성 추천을 내리고 새 추천을 활성으로 넣는다. */
  public void recommendation(String runId, Recommendation r) {
    jdbc.update(
        "UPDATE demo_recommendation SET active = false WHERE as_of = ? AND mfc_id = ? AND sku_id = ? AND active",
        Timestamp.valueOf(r.asOf()),
        r.mfcId(),
        r.skuId());
    jdbc.update(
        """
        INSERT INTO demo_recommendation (run_id, active, as_of, mfc_id, sku_id, proposed_ready_at, protection_end,
          protection_method, granularity, policy_version, target_method, forecast_model, h_qty, h_expiring_qty, c_qty,
          i_qty, p_qty, f_qty, s_qty, ip_qty, n_qty, m_qty, w_qty, b_qty, k_qty, q_qty, unmet_qty, excess_qty,
          first_shortage_at, max_shortage, recovery_at, shortage_before_proposed_ready,
          residual_shortage_after_replenishment, shared_cap_group, status, reason, data_mode)
        VALUES (?, true, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
          ?, ?, ?)
        """,
        runId,
        Timestamp.valueOf(r.asOf()),
        r.mfcId(),
        r.skuId(),
        Timestamp.valueOf(r.proposedReadyAt()),
        Timestamp.valueOf(r.protectionEnd()),
        r.protectionMethod(),
        r.granularity(),
        r.policyVersion(),
        r.targetMethod(),
        r.forecastModel(),
        r.onHand(),
        r.expiringQty(),
        r.commitments(),
        r.incoming(),
        r.pendingReady(),
        r.newDemand(),
        r.target(),
        r.inventoryPosition(),
        r.shortage(),
        r.multiple(),
        r.wanted(),
        r.bottleneck(),
        r.capByMultiple(),
        r.quantity(),
        r.unmetQty(),
        r.excessQty(),
        ts(r.firstShortageAt()),
        r.maxShortage(),
        ts(r.recoveryAt()),
        r.shortageBeforeProposedReady(),
        r.residualShortageAfterReplenishment(),
        r.sharedCapGroup(),
        r.status().name(),
        r.reason(),
        r.dataMode());
  }

  public void failure(String runId, String step, String mfcId, String skuId, String error) {
    jdbc.update(
        "INSERT INTO demo_failure (run_id, step, mfc_id, sku_id, error) VALUES (?, ?, ?, ?, ?)",
        runId,
        step,
        mfcId,
        skuId,
        error);
  }

  public void backtest(String runId, Backtester.Result result) {
    for (Backtester.Metric m : result.metrics()) {
      jdbc.update(
          """
          INSERT INTO demo_backtest (run_id, model_id, clipped, metric_kind, windows, excluded_windows, unavailable,
            mae, bias, wape, coverage, zero_assumed_share)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """,
          runId,
          m.modelId,
          m.clipped,
          m.kind.name(),
          m.windows,
          m.excludedWindows,
          m.unavailable,
          m.mae(),
          m.bias(),
          m.wape(),
          m.coverage(),
          m.zeroAssumedShare());
    }
    for (Backtester.Simulation s : result.simulations()) {
      jdbc.update(
          """
          INSERT INTO demo_simulation (run_id, model_id, reviews, orders, no_forecast_reviews, stockout_rate,
            average_on_hand, data_mode)
          VALUES (?, ?, ?, ?, ?, ?, ?, 'simulated_inventory')
          """,
          runId,
          s.modelId,
          s.reviews,
          s.orders,
          s.noForecastReviews,
          s.stockoutRate(),
          s.averageOnHand());
    }
  }

  /** 다시 시작할 때 앞 단계 예측을 DB 에서 읽는다. SKU → (첫날, 일별 값, 모델, 상태). */
  public List<Map<String, Object>> forecastsOf(String runId) {
    return jdbc.queryForList(
        """
        SELECT sku_id, forecast_date, point_forecast, model_id, status
        FROM demo_forecast WHERE run_id = ? ORDER BY sku_id, forecast_date
        """,
        runId);
  }

  private static Timestamp ts(LocalDateTime t) {
    return t == null ? null : Timestamp.valueOf(t);
  }
}
