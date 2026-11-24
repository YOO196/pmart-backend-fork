package com.pmart.demo.replenishment.batch;

import com.pmart.demo.replenishment.csv.Csv;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 결과 예시 CSV 와 검증 보고서(report.md). 모두 DB 에서 읽어 만든다(다시 시작한 실행도 같은 보고서).
 */
@Component
public class ReportWriter {

  private final JdbcTemplate jdbc;

  public ReportWriter(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void write(String runId, Path dir, DemoSettings settings) {
    try {
      Files.createDirectories(dir);
      exportCsv(
          dir.resolve("recommendations.csv"),
          "SELECT * FROM demo_recommendation WHERE run_id = ? ORDER BY sku_id",
          runId);
      exportCsv(
          dir.resolve("forecasts.csv"),
          "SELECT * FROM demo_forecast WHERE run_id = ? ORDER BY sku_id, forecast_date",
          runId);
      exportCsv(
          dir.resolve("aggregates.csv"),
          "SELECT * FROM demo_sales_aggregate WHERE run_id = ? ORDER BY sku_id, period_type, period_key",
          runId);
      exportCsv(
          dir.resolve("backtest.csv"),
          "SELECT * FROM demo_backtest WHERE run_id = ? ORDER BY metric_kind, clipped, model_id",
          runId);
      exportCsv(dir.resolve("failures.csv"), "SELECT * FROM demo_failure WHERE run_id = ? ORDER BY id", runId);
      Files.writeString(dir.resolve("report.md"), markdown(runId, settings), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void exportCsv(Path file, String sql, String runId) throws IOException {
    List<Map<String, Object>> rows = jdbc.queryForList(sql, runId);
    if (rows.isEmpty()) {
      Files.deleteIfExists(file);
      return;
    }
    String[] header = rows.getFirst().keySet().toArray(String[]::new);
    try (Csv.Writer w = new Csv.Writer(file, header)) {
      for (Map<String, Object> r : rows) {
        w.row(r.values().toArray());
      }
    }
  }

  String markdown(String runId, DemoSettings s) {
    StringBuilder md = new StringBuilder();
    Map<String, Object> run = jdbc.queryForMap("SELECT * FROM demo_run WHERE run_id = ?", runId);
    md.append("# MFC 수요예측·보충추천 데모 보고서\n\n");
    md.append("> 데모 결과다. 재고·약속·공급·상한은 고정 시나리오(`data_mode = simulated_inventory`)이고, ")
        .append("시뮬레이션 품절률·재고는 실제 품절률·공급 효과가 아니다.\n\n");
    md.append("| 항목 | 값 |\n|---|---|\n");
    for (String k :
        List.of(
            "run_id", "run_label", "as_of", "mfc_id", "app_version", "input_snapshot", "sales_basis",
            "source_timezone", "data_mode")) {
      md.append("| ").append(k).append(" | ").append(num(run.get(k))).append(" |\n");
    }
    md.append("| 설정 | ma-window ")
        .append(s.maWindow)
        .append(", 예측 ")
        .append(s.forecastHorizonDays)
        .append("일, stale_gap 배수 ")
        .append(s.staleGapMultiplier)
        .append(", 백테스트 주기 ")
        .append(s.backtestStepDays)
        .append("일·리드 ")
        .append(s.backtestLeadDays)
        .append("일 |\n\n");

    md.append("## 1. 정제(F01·F02)\n\n| 항목 | 줄 수 |\n|---|---:|\n");
    for (Map<String, Object> r :
        jdbc.queryForList(
            "SELECT item, line_count FROM demo_clean_report WHERE run_id = ? AND item NOT LIKE 'state.%' "
                + "AND item NOT LIKE 'group.%' ORDER BY item",
            runId)) {
      md.append("| ").append(r.get("item")).append(" | ").append(r.get("line_count")).append(" |\n");
    }

    md.append("\n## 2. 일별 상태 비율(기준일 전 전체 기간, SKU×일)\n\n");
    md.append("UCI 형식이면 stale_gap_days 배수 민감도(x1.5·x2.0·x3.0), 원천이 상태를 주면 `given`. `UNKNOWN.*` 는 사유별.\n\n");
    md.append(shareTable(runId, "state.", "배수"));
    md.append("\nSKU 군별(판매일 비율: HIGH ≥ 50%, MID ≥ 10%, LOW 그 밖 — 배수 ")
        .append(s.staleGapMultiplier)
        .append(")\n\n");
    md.append(shareTable(runId, "group.", "군"));

    md.append("\n## 3. 예측(F03·F04)\n\n| 모델 | 상태 | SKU 수 |\n|---|---|---:|\n");
    for (Map<String, Object> r :
        jdbc.queryForList(
            "SELECT model_id, status, count(DISTINCT sku_id) n FROM demo_forecast WHERE run_id = ? "
                + "GROUP BY model_id, status ORDER BY model_id",
            runId)) {
      md.append("| ").append(r.get("model_id")).append(" | ").append(r.get("status")).append(" | ")
          .append(r.get("n")).append(" |\n");
    }
    Long pending =
        jdbc.queryForObject(
            "SELECT count(*) FROM demo_failure WHERE run_id = ? AND step = 'forecast' AND error LIKE 'INPUT_PENDING%'",
            Long.class,
            runId);
    md.append("| (예측 없음 — 0 으로 채우지 않음) | INPUT_PENDING | ").append(pending).append(" |\n");

    md.append("\n## 4. 보충 추천(F05)\n\n");
    md.append("| SKU | 상태 | H | C | I | F | S | IP | N | m | W | B | K | **Q** | 미충족 | 초과 | 최초 부족 | 최대 부족 | 회복 |\n");
    md.append("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---:|---|\n");
    List<Map<String, Object>> recs =
        jdbc.queryForList("SELECT * FROM demo_recommendation WHERE run_id = ? ORDER BY sku_id", runId);
    for (Map<String, Object> r : recs) {
      md.append("| ").append(r.get("sku_id")).append(" | ").append(r.get("status"));
      for (String c :
          List.of(
              "h_qty", "c_qty", "i_qty", "f_qty", "s_qty", "ip_qty", "n_qty", "m_qty", "w_qty", "b_qty",
              "k_qty", "q_qty", "unmet_qty", "excess_qty", "first_shortage_at", "max_shortage",
              "recovery_at")) {
        md.append(" | ").append(num(r.get(c)));
      }
      md.append(" |\n");
    }
    md.append("\n사유:\n\n");
    for (Map<String, Object> r : recs) {
      md.append("- **").append(r.get("sku_id")).append("**: ").append(r.get("reason")).append('\n');
    }

    List<Map<String, Object>> fails =
        jdbc.queryForList(
            "SELECT step, sku_id, error FROM demo_failure WHERE run_id = ? AND error NOT LIKE 'INPUT_PENDING%' "
                + "ORDER BY id",
            runId);
    md.append("\n## 5. 실패 건(F07 — 다른 SKU 는 처리됨)\n\n");
    if (fails.isEmpty()) {
      md.append("없음\n");
    } else {
      md.append("| 단계 | SKU | 오류 |\n|---|---|---|\n");
      for (Map<String, Object> f : fails) {
        md.append("| ").append(f.get("step")).append(" | ").append(f.get("sku_id")).append(" | ")
            .append(f.get("error")).append(" |\n");
      }
    }

    md.append("\n## 6. 순차 백테스트(F06)\n\n");
    md.append("STRICT = 보호기간 모든 날이 평가 가능한 창만(명세 규칙). DAY_MATCHED = 평가 가능한 날만 같은 날끼리 누적 비교")
        .append("(coverage = 평가 가능일 비율).\n\n");
    md.append("| 지표 | 이상치 처리 | 모델 | 창 | 제외 창 | 예측 불가 | MAE | 편향 | WAPE | coverage | ZERO_ASSUMED 비중 |\n");
    md.append("|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|\n");
    for (Map<String, Object> r :
        jdbc.queryForList(
            "SELECT * FROM demo_backtest WHERE run_id = ? ORDER BY metric_kind DESC, clipped, model_id", runId)) {
      md.append("| ").append(r.get("metric_kind")).append(" | ").append(r.get("clipped")).append(" | ")
          .append(r.get("model_id")).append(" | ").append(r.get("windows")).append(" | ")
          .append(r.get("excluded_windows")).append(" | ").append(r.get("unavailable"));
      for (String c : List.of("mae", "bias", "wape", "coverage", "zero_assumed_share")) {
        md.append(" | ").append(num(r.get(c)));
      }
      md.append(" |\n");
    }
    md.append("\n재고 시뮬레이션(data_mode = simulated_inventory, 주기 검토·목표 = 보호기간 예측 합, 이상치 처리 없음)\n\n");
    md.append("| 모델 | 검토 | 주문 | 예측 없는 검토 | 품절일 비율 | 평균 재고 |\n|---|---:|---:|---:|---:|---:|\n");
    for (Map<String, Object> r :
        jdbc.queryForList("SELECT * FROM demo_simulation WHERE run_id = ? ORDER BY model_id", runId)) {
      md.append("| ").append(r.get("model_id")).append(" | ").append(r.get("reviews")).append(" | ")
          .append(r.get("orders")).append(" | ").append(r.get("no_forecast_reviews")).append(" | ")
          .append(num(r.get("stockout_rate"))).append(" | ").append(num(r.get("average_on_hand")))
          .append(" |\n");
    }
    md.append("\n## 7. 한계\n\n")
        .append("- UCI 형식 데이터에는 품절 정보가 없다. 판매 관측 구간 안의 무판매는 ZERO_ASSUMED(데모 가정)로 두며, ")
        .append("재고 보유를 입증하지 않는다. 품절일은 알 수 없어 STOCKOUT 으로 표시하지 않는다.\n")
        .append("- 요일마다 센터 무판매일(UNKNOWN.NO_CENTER_SALES, 예: 토요일)이 있으면 보호기간이 7일을 넘는 창은 STRICT 로 ")
        .append("평가할 수 없다. 모델 비교는 DAY_MATCHED 와 coverage 를 함께 본다.\n")
        .append("- 백테스트 검토일 수가 적으면(주기를 길게 잡은 실행) 편향·MAE 는 계절 추세에 크게 흔들린다. 검토일 수는 위 표의 창 수로 본다.\n")
        .append("- 분위수 목표(pinball loss·커버리지)는 이 판에서 계산하지 않는다(점예측만). 분위수 추천은 보정값이 없으면 점예측 합계로 떨어진다.\n")
        .append("- 다품목 적재(중량·부피·온도 구획)는 검증하지 않는다.\n")
        .append("- 1년 이력은 연간 계절을 한 번 본 데이터다. 연간 반복 효과는 추가 연도 데이터가 생기면 비교한다.\n");
    return md.toString();
  }

  /** prefix 로 시작하는 항목을 (구분, 상태) 표로. 비율은 구분 안 합계 대비. */
  private String shareTable(String runId, String prefix, String label) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT item, line_count FROM demo_clean_report WHERE run_id = ? AND item LIKE ? ORDER BY item",
            runId,
            prefix + "%");
    Map<String, Long> totals = new java.util.TreeMap<>();
    for (Map<String, Object> r : rows) {
      String rest = ((String) r.get("item")).substring(prefix.length());
      String g = groupOf(prefix, rest);
      totals.merge(g, ((Number) r.get("line_count")).longValue(), Long::sum);
    }
    StringBuilder sb = new StringBuilder("| " + label + " | 상태 | 일수 | 비율 |\n|---|---|---:|---:|\n");
    for (Map<String, Object> r : rows) {
      String rest = ((String) r.get("item")).substring(prefix.length());
      String g = groupOf(prefix, rest);
      long n = ((Number) r.get("line_count")).longValue();
      sb.append("| ").append(g).append(" | ").append(rest.substring(g.length() + 1)).append(" | ").append(n)
          .append(" | ").append(String.format("%.1f%%", 100.0 * n / totals.get(g))).append(" |\n");
    }
    return sb.toString();
  }

  /** "x2.0.NORMAL" → "x2.0", "HIGH.NORMAL" → "HIGH". */
  private static String groupOf(String prefix, String rest) {
    if (prefix.equals("state.") && rest.startsWith("x")) {
      int second = rest.indexOf('.', rest.indexOf('.') + 1);
      return rest.substring(0, second); // x2.0
    }
    return rest.substring(0, rest.indexOf('.'));
  }

  private static String num(Object v) {
    if (v == null) {
      return "—";
    }
    if (v instanceof java.math.BigDecimal b) {
      return b.stripTrailingZeros().toPlainString();
    }
    if (v instanceof java.sql.Timestamp t) {
      return t.toLocalDateTime().toString();
    }
    return v.toString();
  }
}
