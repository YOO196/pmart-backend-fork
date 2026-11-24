package com.pmart.demo.replenishment;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.demo.replenishment.batch.DemoJobConfig;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 합성 샘플 + 고정 시나리오로 작업 전체를 돌린다(실제 PostgreSQL). 명세 7장 검수 항목 중 배치 쪽: 추천 값, 실패 건 분리, 재실행 시
 * 활성 추천 하나, 실패 단계부터 다시 시작, 집계 합계, 보고서.
 */
@Testcontainers
@SpringBootTest(
    properties = {
      "spring.batch.job.enabled=false",
      "demo.output-dir=build/it-out",
      "demo.backtest-step-days=28"
    })
class DemoJobIT {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  static final String AS_OF = "2011-06-01T00:00";
  static final Timestamp AS_OF_TS = Timestamp.valueOf(LocalDateTime.parse(AS_OF));

  @Autowired JobOperator operator;
  @Autowired Job job;
  @Autowired JdbcTemplate jdbc;

  JobExecution run(String label, String scenarioDir) throws Exception {
    JobParameters params =
        new JobParametersBuilder()
            .addString("asOf", AS_OF)
            .addString("salesCsv", "sample/uci-synthetic.csv")
            .addString("scenarioDir", scenarioDir)
            .addString("runLabel", label)
            .toJobParameters();
    return operator.start(job, params);
  }

  Map<String, Object> rec(String runId, String sku) {
    return jdbc.queryForMap(
        "SELECT * FROM demo_recommendation WHERE run_id = ? AND sku_id = ?", runId, sku);
  }

  static double d(Object v) {
    return ((BigDecimal) v).doubleValue();
  }

  @Test
  void fullRunRecommendsAndRerunKeepsOneActive() throws Exception {
    JobExecution first = run("it-1", "sample/scenario");
    assertThat(first.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    String runId = "run-" + first.getJobInstance().getInstanceId();
    assertThat(DemoJobConfig.JOB_NAME).isEqualTo(first.getJobInstance().getJobName());

    // 명세 3장 예시 표
    Map<String, Object> ex1 = rec(runId, "EX-01");
    assertThat(d(ex1.get("s_qty"))).isEqualTo(90.0);
    assertThat(d(ex1.get("ip_qty"))).isEqualTo(36.0);
    assertThat(d(ex1.get("n_qty"))).isEqualTo(54.0);
    assertThat(ex1.get("w_qty")).isEqualTo(60L);
    assertThat(ex1.get("k_qty")).isEqualTo(48L);
    assertThat(ex1.get("q_qty")).isEqualTo(48L);
    assertThat(d(ex1.get("unmet_qty"))).isEqualTo(6.0);
    assertThat(ex1.get("status")).isEqualTo("CALCULABLE");
    assertThat(rec(runId, "EX-02").get("q_qty")).isEqualTo(0L);
    assertThat(d(rec(runId, "EX-02").get("unmet_qty"))).isEqualTo(54.0);
    assertThat(rec(runId, "EX-03").get("q_qty")).isEqualTo(6L);
    assertThat(d(rec(runId, "EX-03").get("excess_qty"))).isEqualTo(1.0);
    assertThat(d(rec(runId, "EX-04").get("ip_qty"))).isEqualTo(-3.0);
    assertThat(d(rec(runId, "EX-05").get("f_qty"))).isEqualTo(10.0);
    assertThat(rec(runId, "EX-05").get("c_qty")).isEqualTo(8L);

    // 7장 조기 부족: 일 단위 / 시각 단위
    Map<String, Object> ex6 = rec(runId, "EX-06");
    assertThat(d(ex6.get("ip_qty"))).isEqualTo(9.0);
    assertThat(ex6.get("q_qty")).isEqualTo(0L);
    assertThat(ex6.get("first_shortage_at")).isEqualTo(AS_OF_TS);
    assertThat(ex6.get("recovery_at")).isEqualTo(AS_OF_TS);
    assertThat(d(ex6.get("max_shortage"))).isEqualTo(1.0);
    Map<String, Object> ex7 = rec(runId, "EX-07");
    assertThat(ex7.get("first_shortage_at")).isEqualTo(Timestamp.valueOf("2011-06-01 11:00:00"));
    assertThat(ex7.get("recovery_at")).isEqualTo(Timestamp.valueOf("2011-06-01 15:00:00"));

    // 모델 예측을 쓰는 SKU
    Map<String, Object> s1 = rec(runId, "10001");
    assertThat(s1.get("forecast_model")).isEqualTo("MA28");
    assertThat(s1.get("i_qty")).isEqualTo(40L); // 같은 계보 승인·확보는 한 번, 보호기간 밖 출발분 제외
    assertThat(s1.get("c_qty")).isEqualTo(17L); // OPEN 10 + BLOCKED 4 + 기한 지난 3, 취소 제외
    assertThat(s1.get("h_expiring_qty")).isEqualTo(8L);
    assertThat(s1.get("p_qty")).isEqualTo(6L);
    assertThat((String) s1.get("reason")).contains("SUPPLY_INTENT_OPEN", "OVERDUE_COMMITMENTS", "SHARED_CAP_");
    Map<String, Object> s5 = rec(runId, "10005");
    assertThat(s5.get("status")).isEqualTo("REFERENCE_ONLY");
    assertThat(s5.get("target_method")).isEqualTo("POINT_SUM_FALLBACK");
    assertThat(s5.get("i_qty")).isEqualTo(0L); // ETA 불명
    assertThat(rec(runId, "99999").get("status")).isEqualTo("INPUT_PENDING");
    assertThat(rec(runId, "99999").get("q_qty")).isNull();
    assertThat(rec(runId, "10003").get("status")).isEqualTo("INPUT_PENDING"); // 상한 없음

    // 한 SKU 실패는 기록되고 나머지는 처리됨
    List<String> failed =
        jdbc.queryForList(
            "SELECT sku_id FROM demo_failure WHERE run_id = ? AND step = 'recommend' ORDER BY sku_id",
            String.class,
            runId);
    assertThat(failed).containsExactly("EX-BAD-M", "EX-BAD-ROW");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM demo_recommendation WHERE run_id = ?", Long.class, runId))
        .isEqualTo(12L);

    // 집계: 주 합계 = 연 합계(10001)
    Long week =
        jdbc.queryForObject(
            "SELECT sum(units) FROM demo_sales_aggregate WHERE run_id = ? AND sku_id = '10001' AND period_type = 'WEEK'",
            Long.class,
            runId);
    Long year =
        jdbc.queryForObject(
            "SELECT sum(units) FROM demo_sales_aggregate WHERE run_id = ? AND sku_id = '10001' AND period_type = 'YEAR'",
            Long.class,
            runId);
    assertThat(week).isEqualTo(year).isPositive();

    // 백테스트: 토요일 무판매(UNKNOWN) 때문에 9일 창은 STRICT 로 평가할 수 없고 DAY_MATCHED 로만 평가된다.
    Long strict =
        jdbc.queryForObject(
            "SELECT sum(windows) FROM demo_backtest WHERE run_id = ? AND metric_kind = 'STRICT'", Long.class, runId);
    Long matched =
        jdbc.queryForObject(
            "SELECT sum(windows) FROM demo_backtest WHERE run_id = ? AND metric_kind = 'DAY_MATCHED'",
            Long.class,
            runId);
    assertThat(strict).isZero();
    assertThat(matched).isPositive();

    Path report = Path.of("build/it-out", runId, "report.md");
    assertThat(Files.readString(report, StandardCharsets.UTF_8)).contains("보충 추천", "EX-01", "DAY_MATCHED");

    // 재실행(새 runLabel): 활성 추천은 기준시각·센터·SKU 마다 하나.
    JobExecution second = run("it-2", "sample/scenario");
    assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    Long dup =
        jdbc.queryForObject(
            "SELECT count(*) FROM (SELECT 1 FROM demo_recommendation WHERE active AND as_of = ? "
                + "GROUP BY mfc_id, sku_id HAVING count(*) > 1) x",
            Long.class,
            AS_OF_TS);
    assertThat(dup).isZero();
    String runId2 = "run-" + second.getJobInstance().getInstanceId();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM demo_recommendation WHERE run_id = ? AND active", Long.class, runId2))
        .isEqualTo(12L);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM demo_recommendation WHERE run_id = ? AND active", Long.class, runId))
        .isZero();
  }

  @Test
  void failedStepRestartsWithSameParameters(@TempDir Path tmp) throws Exception {
    Path scenario = tmp.resolve("scenario"); // 아직 없음 → recommend 단계 실패
    JobExecution failed = run("it-restart", scenario.toString());
    assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
    String runId = "run-" + failed.getJobInstance().getInstanceId();

    copyDir(Path.of("sample/scenario"), scenario);
    JobExecution restarted = operator.restart(failed);
    assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    assertThat(restarted.getJobInstance().getInstanceId()).isEqualTo(failed.getJobInstance().getInstanceId());
    assertThat(jdbc.queryForObject("SELECT count(*) FROM demo_recommendation WHERE run_id = ?", Long.class, runId))
        .isEqualTo(12L);
    assertThat(jdbc.queryForObject("SELECT status FROM demo_run WHERE run_id = ?", String.class, runId))
        .isEqualTo("COMPLETED");
  }

  private static void copyDir(Path from, Path to) throws IOException {
    Files.createDirectories(to);
    try (Stream<Path> s = Files.list(from)) {
      for (Path f : s.toList()) {
        Files.copy(f, to.resolve(f.getFileName()));
      }
    }
  }
}
