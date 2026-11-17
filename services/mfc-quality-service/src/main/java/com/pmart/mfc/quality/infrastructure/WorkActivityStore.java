package com.pmart.mfc.quality.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 작업자 처리 내역 조회(F-7 R3) — 이 서비스 원장의 행위자·시각 칸만 읽는다. */
@Repository
public class WorkActivityStore {

  public record Activity(
      String activityType,
      String refId,
      @Nullable String taskId,
      @Nullable String sku,
      @Nullable Integer qty,
      @Nullable String result,
      Instant at) {}

  private static final String SQL =
      "SELECT d.decision_type AS activity_type, d.decision_id AS ref_id, NULL AS task_id, NULL AS sku, (SELECT COALESCE(SUM((e->>'qty')::int), 0) FROM jsonb_array_elements(d.lines) e) AS qty, d.state AS result, d.created_at AS at FROM quality_decision d WHERE d.mfc_id = :mfc AND d.decided_by = :op AND d.created_at >= :from AND d.created_at < :to UNION ALL SELECT 'DISPOSE', x.execution_id, x.disposal_approval_id, NULL, x.qty, x.state, x.created_at FROM disposal_execution x JOIN disposal_approval a ON a.disposal_approval_id = x.disposal_approval_id WHERE a.mfc_id = :mfc AND x.executed_by = :op AND x.created_at >= :from AND x.created_at < :to UNION ALL SELECT 'RETURN_INSPECTION', i.return_inspection_id, i.return_receipt_id, NULL, i.resale_qty + i.dispose_qty, i.apply_status, i.inspected_at FROM return_inspection i WHERE i.mfc_id = :mfc AND i.inspected_by = :op AND i.inspected_at >= :from AND i.inspected_at < :to";

  private final NamedParameterJdbcTemplate jdbc;

  public WorkActivityStore(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<Activity> list(String mfcId, String operatorId, Instant from, Instant to, int limit) {
    return jdbc.query(
        "SELECT * FROM (" + SQL + ") a ORDER BY at, ref_id LIMIT :limit",
        Map.of(
            "mfc", mfcId,
            "op", operatorId,
            "from", Timestamp.from(from),
            "to", Timestamp.from(to),
            "limit", limit),
        (rs, i) -> {
          int q = rs.getInt("qty");
          Integer qty = rs.wasNull() ? null : q;
          return new Activity(
              rs.getString("activity_type"),
              rs.getString("ref_id"),
              rs.getString("task_id"),
              rs.getString("sku"),
              qty,
              rs.getString("result"),
              rs.getTimestamp("at").toInstant());
        });
  }
}
