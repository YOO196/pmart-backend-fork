package com.pmart.demo.replenishment.scenario;

import com.pmart.demo.replenishment.csv.Csv;
import com.pmart.demo.replenishment.replenishment.Model.Caps;
import com.pmart.demo.replenishment.replenishment.Model.Commitment;
import com.pmart.demo.replenishment.replenishment.Model.CommitmentStatus;
import com.pmart.demo.replenishment.replenishment.Model.ForecastBucket;
import com.pmart.demo.replenishment.replenishment.Model.Granularity;
import com.pmart.demo.replenishment.replenishment.Model.Inventory;
import com.pmart.demo.replenishment.replenishment.Model.Policy;
import com.pmart.demo.replenishment.replenishment.Model.ProtectionMethod;
import com.pmart.demo.replenishment.replenishment.Model.Supply;
import com.pmart.demo.replenishment.replenishment.Model.SupplyStage;
import com.pmart.demo.replenishment.replenishment.Model.SupplyStatus;
import com.pmart.demo.replenishment.replenishment.Model.TargetMethod;
import com.pmart.demo.replenishment.replenishment.Model.Timed;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 고정 데모 시나리오(명세 6장 구현 지시 3, data_mode=simulated_inventory) 읽기. 폴더 안 UTF-8 CSV:
 *
 * <ul>
 *   <li>policy.csv — 시나리오 SKU 목록이자 공급 정책 + 계산 스냅샷(필수)
 *   <li>caps.csv — 실행 상한. 없는 SKU 는 상한 미설정(INPUT_PENDING)
 *   <li>shared_caps.csv — 여러 SKU 가 나눠 쓰는 상한(선택)
 *   <li>inventory.csv — kind H·P·EXPIRY
 *   <li>commitments.csv, supplies.csv — 고객 약속, 기존 공급
 *   <li>forecast.csv — 예측 구간을 직접 줄 때(선택). 없는 SKU 는 배치의 모델 예측을 쓴다
 * </ul>
 *
 * 한 SKU 의 줄 형식이 틀리면 그 SKU 만 오류로 남긴다(배치가 실패 건으로 기록).
 */
public final class ScenarioLoader {

  /** 시나리오 한 SKU. error 가 있으면 계산하지 않고 실패로 남긴다. */
  public record ScenarioSku(
      String mfcId,
      String skuId,
      Policy policy,
      Caps caps,
      Inventory inventory,
      List<Commitment> commitments,
      List<Supply> supplies,
      List<ForecastBucket> forecast,
      String forecastModel,
      boolean acceptSecuredSupply,
      String error) {}

  public record Scenario(List<ScenarioSku> skus, Map<String, Long> sharedCaps) {}

  private ScenarioLoader() {}

  public static Scenario load(Path dir) throws IOException {
    Map<String, Builder> builders = new LinkedHashMap<>();
    for (Csv.Row row : Csv.readAll(dir.resolve("policy.csv"))) {
      String k = key(row);
      Builder b = new Builder(row.get("mfc_id"), row.get("sku_id"));
      builders.put(k, b);
      b.guard(
          () -> {
            b.policy =
                new Policy(
                    Granularity.valueOf(row.get("granularity")),
                    ProtectionMethod.valueOf(row.get("protection_method")),
                    integer(row.opt("review_interval_days")),
                    integer(row.opt("transfer_multiple_m")),
                    TargetMethod.valueOf(row.get("target_method")),
                    dbl(row.opt("target_quantile")),
                    lng(row.opt("ss_qty")),
                    lng(row.opt("calibrated_cumulative_quantile")),
                    time(row.get("proposed_ready_at")),
                    time(row.get("protection_end")),
                    row.get("policy_version"),
                    row.opt("shared_cap_group"));
            String accept = row.opt("accept_secured_supply");
            b.acceptSecured = accept == null || Boolean.parseBoolean(accept);
          });
    }
    each(
        dir.resolve("caps.csv"),
        builders,
        (b, row) ->
            b.caps =
                new Caps(
                    lng(row.opt("cdc_available_qty")),
                    lng(row.opt("mfc_receivable_qty")),
                    lng(row.opt("transport_cap_qty"))));
    each(
        dir.resolve("inventory.csv"),
        builders,
        (b, row) -> {
          long qty = Long.parseLong(row.get("qty"));
          switch (row.get("kind")) {
            case "H" -> b.onHand += qty;
            case "P" -> b.pending.add(new Timed(qty, time(row.get("at"))));
            case "EXPIRY" -> b.expiries.add(new Timed(qty, time(row.get("at"))));
            default -> throw new IllegalArgumentException("inventory kind 는 H·P·EXPIRY: " + row.get("kind"));
          }
        });
    each(
        dir.resolve("commitments.csv"),
        builders,
        (b, row) ->
            b.commitments.add(
                new Commitment(
                    row.get("order_line_id"),
                    Long.parseLong(row.get("open_qty")),
                    time(row.get("due_at")),
                    CommitmentStatus.valueOf(row.get("status")))));
    each(
        dir.resolve("supplies.csv"),
        builders,
        (b, row) ->
            b.supplies.add(
                new Supply(
                    row.get("supply_id"),
                    SupplyStage.valueOf(row.get("stage")),
                    Long.parseLong(row.get("remaining_qty")),
                    row.opt("ready_eta") == null ? null : time(row.get("ready_eta")),
                    SupplyStatus.valueOf(row.get("status")))));
    each(
        dir.resolve("forecast.csv"),
        builders,
        (b, row) -> {
          b.forecast.add(
              new ForecastBucket(
                  time(row.get("bucket_start")),
                  time(row.get("bucket_end")),
                  Double.parseDouble(row.get("qty")),
                  row.get("forecast_scope")));
          b.forecastModel = row.opt("model_id") == null ? "SCENARIO" : row.get("model_id");
        });

    Map<String, Long> shared = new LinkedHashMap<>();
    Path sharedFile = dir.resolve("shared_caps.csv");
    if (Files.exists(sharedFile)) {
      for (Csv.Row row : Csv.readAll(sharedFile)) {
        shared.put(row.get("group_id"), Long.parseLong(row.get("cap_qty")));
      }
    }
    List<ScenarioSku> out = new ArrayList<>();
    for (Builder b : builders.values()) {
      out.add(b.build());
    }
    return new Scenario(out, shared);
  }

  private interface RowConsumer {
    void accept(Builder b, Csv.Row row);
  }

  private static void each(Path file, Map<String, Builder> builders, RowConsumer c) throws IOException {
    if (!Files.exists(file)) {
      return;
    }
    for (Csv.Row row : Csv.readAll(file)) {
      Builder b = builders.get(key(row));
      if (b == null) {
        continue; // policy.csv 에 없는 SKU 는 시나리오 대상 아님
      }
      b.guard(() -> c.accept(b, row));
    }
  }

  private static String key(Csv.Row row) {
    return row.get("mfc_id") + "|" + row.get("sku_id");
  }

  private static LocalDateTime time(String s) {
    return LocalDateTime.parse(s.trim());
  }

  private static Long lng(String s) {
    return s == null ? null : Long.valueOf(s);
  }

  private static Integer integer(String s) {
    return s == null ? null : Integer.valueOf(s);
  }

  private static Double dbl(String s) {
    return s == null ? null : Double.valueOf(s);
  }

  private static final class Builder {
    final String mfcId;
    final String skuId;
    Policy policy;
    Caps caps = new Caps(null, null, null);
    long onHand;
    final List<Timed> pending = new ArrayList<>();
    final List<Timed> expiries = new ArrayList<>();
    final List<Commitment> commitments = new ArrayList<>();
    final List<Supply> supplies = new ArrayList<>();
    final List<ForecastBucket> forecast = new ArrayList<>();
    String forecastModel;
    boolean acceptSecured = true;
    String error;

    Builder(String mfcId, String skuId) {
      this.mfcId = mfcId;
      this.skuId = skuId;
    }

    void guard(Runnable r) {
      if (error != null) {
        return;
      }
      try {
        r.run();
      } catch (RuntimeException e) {
        error = e.getClass().getSimpleName() + ": " + e.getMessage();
      }
    }

    ScenarioSku build() {
      return new ScenarioSku(
          mfcId,
          skuId,
          policy,
          caps,
          new Inventory(onHand, pending, expiries),
          commitments,
          supplies,
          forecast,
          forecastModel,
          acceptSecured,
          error);
    }
  }
}
