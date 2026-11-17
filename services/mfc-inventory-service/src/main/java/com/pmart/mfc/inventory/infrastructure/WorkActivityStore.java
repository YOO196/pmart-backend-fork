package com.pmart.mfc.inventory.infrastructure;

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
      "SELECT 'COUNT' AS activity_type, session_id AS ref_id, NULL AS task_id, NULL AS sku, (SELECT COALESCE(SUM((e->>'countedQty')::int), 0) FROM jsonb_array_elements(first_count) e) AS qty, state AS result, first_counted_at AS at FROM count_session WHERE mfc_id = :mfc AND first_counter = :op AND first_counted_at >= :from AND first_counted_at < :to UNION ALL SELECT 'RECOUNT', session_id, NULL, NULL, (SELECT COALESCE(SUM((e->>'countedQty')::int), 0) FROM jsonb_array_elements(recount) e), state, recounted_at FROM count_session WHERE mfc_id = :mfc AND recounter = :op AND recounted_at >= :from AND recounted_at < :to";

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
