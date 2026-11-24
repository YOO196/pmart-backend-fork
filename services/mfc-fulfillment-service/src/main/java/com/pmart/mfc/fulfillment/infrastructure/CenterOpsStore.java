package com.pmart.mfc.fulfillment.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 센터 운영판 조회(2026-11-19, MFC 백오피스 OE-1·OE-2) — 결품 보고 목록·출하 약속 요약. 조회만. */
@Repository
public class CenterOpsStore {

  public record ShortageRow(
      long shortageId,
      String reason,
      String taskId,
      @Nullable String taskType,
      @Nullable String taskState,
      @Nullable String shipmentId,
      @Nullable String orderId,
      @Nullable String orderNumber,
      String orderLineId,
      @Nullable String sku,
      @Nullable String lotId,
      @Nullable String locationCode,
      int qty,
      @Nullable String reportedBy,
      Instant reportedAt) {}

  public record PromiseSummary(int overdue, int dueSoon, int onTime, int noPromise) {}

  private final JdbcTemplate jdbc;

  public CenterOpsStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static String where(
      List<Object> args,
      String mfcId,
      @Nullable List<String> reasons,
      @Nullable Instant from,
      @Nullable Instant to) {
    StringBuilder w = new StringBuilder(" WHERE z.mfc_id = ?");
    args.add(mfcId);
    if (reasons != null && !reasons.isEmpty()) {
      w.append(" AND z.reason IN (")
          .append(String.join(",", java.util.Collections.nCopies(reasons.size(), "?")))
          .append(")");
      args.addAll(reasons);
    }
    if (from != null) {
      w.append(" AND z.reported_at >= ?");
      args.add(Timestamp.from(from));
    }
    if (to != null) {
      w.append(" AND z.reported_at < ?");
      args.add(Timestamp.from(to));
    }
    return w.toString();
  }

  /** 최근 순(보고 번호 큰 것부터), cursor = 앞 쪽 마지막 shortageId. */
  public List<ShortageRow> shortages(
      String mfcId,
      @Nullable List<String> reasons,
      @Nullable Instant from,
      @Nullable Instant to,
      @Nullable Long cursor,
      int limit) {
    List<Object> args = new ArrayList<>();
    String w = where(args, mfcId, reasons, from, to);
    if (cursor != null) {
      w += " AND z.shortage_id < ?";
      args.add(cursor);
    }
    args.add(limit);
    return jdbc.query(
        "SELECT z.shortage_id, z.reason, z.task_id, t.task_type, t.state AS task_state, t.shipment_id,"
            + " wo.order_id, n.order_number, z.order_line_id, z.sku, z.lot_id, z.location_code, z.qty,"
            + " z.reported_by, z.reported_at FROM line_shortage z"
            + " LEFT JOIN task t ON t.task_id = z.task_id"
            + " LEFT JOIN shipment s ON s.shipment_id = t.shipment_id"
            + " LEFT JOIN work_order wo ON wo.fulfillment_id = s.fulfillment_id"
            + " LEFT JOIN order_display_number n ON n.order_id = wo.order_id"
            + w
            + " ORDER BY z.shortage_id DESC LIMIT ?",
        (rs, i) ->
            new ShortageRow(
                rs.getLong("shortage_id"),
                rs.getString("reason"),
                rs.getString("task_id"),
                rs.getString("task_type"),
                rs.getString("task_state"),
                rs.getString("shipment_id"),
                rs.getString("order_id"),
                rs.getString("order_number"),
                rs.getString("order_line_id"),
                rs.getString("sku"),
                rs.getString("lot_id"),
                rs.getString("location_code"),
                rs.getInt("qty"),
                rs.getString("reported_by"),
                rs.getTimestamp("reported_at").toInstant()),
        args.toArray());
  }

  public int countShortages(
      String mfcId, @Nullable List<String> reasons, @Nullable Instant from, @Nullable Instant to) {
    List<Object> args = new ArrayList<>();
    String w = where(args, mfcId, reasons, from, to);
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM line_shortage z" + w, Integer.class, args.toArray());
    return n == null ? 0 : n;
  }

  /**
   * 출하 약속 요약(OE-2) — 그 센터의 취소 안 된 출고 중 아직 READY 전(PREPARING)인 것만. 약속 = 배차 요청 예상 준비 시각(0.35.0, 사용자
   * 결정 2026-11-24 — 전에는 PICK 시작 가능 시각 task.ready_at 이라 즉시 주문이 늘 초과), 배차 요청 전은 noPromise.
   */
  public PromiseSummary promiseSummary(String mfcId, Instant now, Instant dueBefore) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FILTER (WHERE p < ?) AS overdue,"
            + " COUNT(*) FILTER (WHERE p >= ? AND p < ?) AS due_soon,"
            + " COUNT(*) FILTER (WHERE p >= ?) AS on_time,"
            + " COUNT(*) FILTER (WHERE p IS NULL) AS no_promise"
            + " FROM (SELECT s.dispatch_estimated_ready_at AS p FROM shipment s"
            + " JOIN work_order wo ON wo.fulfillment_id = s.fulfillment_id"
            + " WHERE wo.mfc_id = ? AND s.state = 'PREPARING') x",
        (rs, i) ->
            new PromiseSummary(
                rs.getInt("overdue"),
                rs.getInt("due_soon"),
                rs.getInt("on_time"),
                rs.getInt("no_promise")),
        Timestamp.from(now),
        Timestamp.from(now),
        Timestamp.from(dueBefore),
        Timestamp.from(dueBefore),
        mfcId);
  }

  /** 포장 화면 표기(DL-1) — 라벨 6자리·주문번호. */
  public record PackageLabel(@Nullable String handoverCode, @Nullable String orderNumber) {}

  public PackageLabel packageLabel(String packageId, @Nullable String orderId) {
    List<String> code =
        jdbc.queryForList(
            "SELECT handover_code FROM package WHERE package_id = ?", String.class, packageId);
    List<String> num =
        orderId == null
            ? List.of()
            : jdbc.queryForList(
                "SELECT order_number FROM order_display_number WHERE order_id = ?",
                String.class,
                orderId);
    return new PackageLabel(code.isEmpty() ? null : code.get(0), num.isEmpty() ? null : num.get(0));
  }
}
