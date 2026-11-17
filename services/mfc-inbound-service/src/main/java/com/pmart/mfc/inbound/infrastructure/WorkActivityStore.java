package com.pmart.mfc.inbound.infrastructure;

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
      "SELECT t.task_type AS activity_type, x.execution_id AS ref_id, x.task_id AS task_id, t.sku AS sku, CASE WHEN jsonb_typeof(x.body) = 'array' THEN (SELECT COALESCE(SUM((e->>'qty')::int), 0) FROM jsonb_array_elements(x.body) e) ELSE (x.body->>'qty')::int END AS qty, x.apply_status AS result, x.created_at AS at FROM inbound_task_execution x JOIN inbound_task t ON t.task_id = x.task_id WHERE t.mfc_id = :mfc AND x.executed_by = :op AND x.created_at >= :from AND x.created_at < :to UNION ALL SELECT 'RECEIVE', l.receipt_id, NULL, NULL, (SELECT COALESCE(SUM(ln.received_qty), 0) FROM initial_stock_load_line ln WHERE ln.receipt_id = l.receipt_id), l.state, l.received_at FROM initial_stock_load l WHERE l.mfc_id = :mfc AND l.received_by = :op AND l.received_at >= :from AND l.received_at < :to UNION ALL SELECT 'MOVE', m.move_id, m.move_id, m.sku, GREATEST(m.dropped_qty, m.picked_qty), m.state, m.updated_at FROM move_order m WHERE m.mfc_id = :mfc AND m.picked_by = :op AND m.updated_at >= :from AND m.updated_at < :to UNION ALL SELECT 'RETURN_RECEIPT', r.return_receipt_id, NULL, NULL, (SELECT COALESCE(SUM((e->>'qty')::int), 0) FROM jsonb_array_elements(r.lines) e), r.state, r.received_at FROM return_receipt r WHERE r.mfc_id = :mfc AND r.received_by = :op AND r.received_at >= :from AND r.received_at < :to";

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
