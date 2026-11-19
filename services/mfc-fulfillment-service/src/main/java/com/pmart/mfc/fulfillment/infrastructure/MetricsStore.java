package com.pmart.mfc.fulfillment.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 결품률 지표(F-10, 2026-11-19) — 날짜(Asia/Seoul, 주문 피킹 시작)·센터별 PICK 줄·결품 줄. */
@Repository
public class MetricsStore {

  public record PickingLines(
      LocalDate date,
      String mfcId,
      int pickedLines,
      int shortLines,
      int shortShort,
      int shortDamaged,
      int shortBlocked,
      int shortSpoiled) {}

  private final NamedParameterJdbcTemplate named;

  public MetricsStore(NamedParameterJdbcTemplate named) {
    this.named = named;
  }

  /** [from, to) 시각, centers 가 null 이면 모든 센터. 원래 주문 작업만(재발송 제외). */
  public List<PickingLines> pickingLines(
      Instant from, Instant to, @Nullable Collection<String> centers) {
    Map<String, Object> a = new LinkedHashMap<>();
    a.put("from", Timestamp.from(from));
    a.put("to", Timestamp.from(to));
    String centerFilter = "";
    if (centers != null) {
      if (centers.isEmpty()) {
        return List.of();
      }
      a.put("centers", centers);
      centerFilter = " AND w.mfc_id IN (:centers)";
    }
    String sql =
        "WITH lines AS (SELECT DISTINCT (w.preparation_started_at AT TIME ZONE 'Asia/Seoul')::date AS d,"
            + " w.mfc_id, t.task_id, l.order_line_id FROM work_order w"
            + " JOIN shipment s ON s.fulfillment_id = w.fulfillment_id"
            + " JOIN task t ON t.shipment_id = s.shipment_id AND t.task_type = 'PICK'"
            + " JOIN task_line l ON l.task_id = t.task_id"
            + " WHERE w.kind = 'ORIGINAL' AND w.preparation_started_at >= :from"
            + " AND w.preparation_started_at < :to"
            + centerFilter
            + "), marked AS (SELECT x.d, x.mfc_id,"
            + " EXISTS (SELECT 1 FROM line_shortage z WHERE z.task_id = x.task_id AND z.order_line_id = x.order_line_id) AS any_short,"
            + " EXISTS (SELECT 1 FROM line_shortage z WHERE z.task_id = x.task_id AND z.order_line_id = x.order_line_id AND z.reason = 'SHORT') AS s_short,"
            + " EXISTS (SELECT 1 FROM line_shortage z WHERE z.task_id = x.task_id AND z.order_line_id = x.order_line_id AND z.reason = 'DAMAGED') AS s_damaged,"
            + " EXISTS (SELECT 1 FROM line_shortage z WHERE z.task_id = x.task_id AND z.order_line_id = x.order_line_id AND z.reason = 'BLOCKED') AS s_blocked,"
            + " EXISTS (SELECT 1 FROM line_shortage z WHERE z.task_id = x.task_id AND z.order_line_id = x.order_line_id AND z.reason = 'SPOILED') AS s_spoiled"
            + " FROM lines x)"
            + " SELECT d, mfc_id, COUNT(*) AS picked, COUNT(*) FILTER (WHERE any_short) AS short_lines,"
            + " COUNT(*) FILTER (WHERE s_short) AS r_short, COUNT(*) FILTER (WHERE s_damaged) AS r_damaged,"
            + " COUNT(*) FILTER (WHERE s_blocked) AS r_blocked, COUNT(*) FILTER (WHERE s_spoiled) AS r_spoiled FROM marked GROUP BY d, mfc_id ORDER BY d, mfc_id";
    return named.query(
        sql,
        a,
        (rs, i) ->
            new PickingLines(
                rs.getObject("d", LocalDate.class),
                rs.getString("mfc_id"),
                rs.getInt("picked"),
                rs.getInt("short_lines"),
                rs.getInt("r_short"),
                rs.getInt("r_damaged"),
                rs.getInt("r_blocked"),
                rs.getInt("r_spoiled")));
  }
}
