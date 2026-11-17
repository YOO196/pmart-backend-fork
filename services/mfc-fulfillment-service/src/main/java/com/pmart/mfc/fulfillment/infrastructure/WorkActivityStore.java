package com.pmart.mfc.fulfillment.infrastructure;

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
      "SELECT x.execution_type AS activity_type, x.execution_id AS ref_id, x.task_id AS task_id, NULL AS sku, CASE WHEN x.state = 'APPLIED' THEN x.applied_qty ELSE (SELECT COALESCE(SUM((e->>'pickedQty')::int), 0) FROM jsonb_array_elements(x.lines) e) END AS qty, x.state AS result, x.recorded_at AS at FROM task_execution x JOIN task t ON t.task_id = x.task_id JOIN shipment s ON s.shipment_id = t.shipment_id JOIN work_order w ON w.fulfillment_id = s.fulfillment_id WHERE w.mfc_id = :mfc AND x.worker_id = :op AND x.recorded_at >= :from AND x.recorded_at < :to UNION ALL SELECT 'PACK', p.package_id, s.shipment_id || '-PACK', NULL, (SELECT COALESCE(SUM((e->>'qty')::int), 0) FROM jsonb_array_elements(p.contents) e), p.state, COALESCE(p.sealed_at, p.created_at) FROM package p JOIN shipment s ON s.shipment_id = p.shipment_id JOIN work_order w ON w.fulfillment_id = s.fulfillment_id WHERE w.mfc_id = :mfc AND p.packed_by = :op AND COALESCE(p.sealed_at, p.created_at) >= :from AND COALESCE(p.sealed_at, p.created_at) < :to";

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
