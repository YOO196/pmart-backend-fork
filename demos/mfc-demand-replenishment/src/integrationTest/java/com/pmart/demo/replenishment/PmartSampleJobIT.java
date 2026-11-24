package com.pmart.demo.replenishment;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.demo.replenishment.sample.PmartSample;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** PMart 취급 상품(503 SKU) 합성 입력으로 작업 전체(명세 4장 일별 판매 형식 — 원천이 상태를 줌). */
@Testcontainers
@SpringBootTest(
    properties = {
      "spring.batch.job.enabled=false",
      "demo.output-dir=build/it-out",
      "demo.backtest-step-days=28"
    })
class PmartSampleJobIT {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired JobOperator operator;
  @Autowired Job job;
  @Autowired JdbcTemplate jdbc;

  @Test
  void pmartCatalogRunsEndToEnd(@TempDir Path tmp) throws Exception {
    PmartSample.Summary sum = PmartSample.write(Path.of("sample/pmart"), tmp);
    assertThat(sum.skus()).isEqualTo(503);
    assertThat(sum.onHand()).isEqualTo(9671 - sum.expiredExcluded() - sum.notStoredExcluded());

    JobExecution ex =
        operator.start(
            job,
            new JobParametersBuilder()
                .addString("asOf", "2026-11-24T00:00")
                .addString("salesCsv", tmp.resolve("sales.csv").toString())
                .addString("scenarioDir", tmp.resolve("scenario").toString())
                .addString("runLabel", "pmart-it")
                .toJobParameters());
    assertThat(ex.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    String runId = "run-" + ex.getJobInstance().getInstanceId();

    Map<String, Object> run = jdbc.queryForMap("SELECT * FROM demo_run WHERE run_id = ?", runId);
    assertThat(run.get("mfc_id")).isEqualTo("MFC-SEED-01");
    assertThat(run.get("sales_basis")).isEqualTo("ORDER_CONFIRMED");
    assertThat(run.get("source_timezone")).isEqualTo("Asia/Seoul");

    // 원천이 준 상태가 그대로(품절·누락·출시 전이 0 수요로 바뀌지 않음)
    for (String s : new String[] {"STOCKOUT", "MISSING", "PRE_LAUNCH", "NORMAL"}) {
      assertThat(
              jdbc.queryForObject(
                  "SELECT line_count FROM demo_clean_report WHERE run_id = ? AND item = ?",
                  Long.class,
                  runId,
                  "given_status." + s))
          .as(s)
          .isPositive();
    }
    assertThat(jdbc.queryForObject("SELECT count(*) FROM demo_recommendation WHERE run_id = ? AND active", Long.class, runId))
        .isEqualTo(503L);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM demo_failure WHERE run_id = ?", Long.class, runId)).isZero();

    // 매일 영업하는 센터라 STRICT 창이 평가된다(UCI 와 다름)
    assertThat(
            jdbc.queryForObject(
                "SELECT sum(windows) FROM demo_backtest WHERE run_id = ? AND metric_kind = 'STRICT'", Long.class, runId))
        .isPositive();

    // 추천 수량 규칙: 양수 추천은 m 의 배수, K(상한) 이하
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM demo_recommendation WHERE run_id = ? AND q_qty > 0 AND (q_qty % m_qty <> 0 OR q_qty > k_qty)",
                Long.class,
                runId))
        .isZero();
    // IP = H − 적격상실 − C + I + P
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM demo_recommendation WHERE run_id = ? AND ip_qty <> h_qty - h_expiring_qty - c_qty + i_qty + p_qty",
                Long.class,
                runId))
        .isZero();
    BigDecimal h = jdbc.queryForObject("SELECT sum(h_qty) FROM demo_recommendation WHERE run_id = ?", BigDecimal.class, runId);
    assertThat(h.longValue()).isEqualTo(sum.onHand());
  }
}
