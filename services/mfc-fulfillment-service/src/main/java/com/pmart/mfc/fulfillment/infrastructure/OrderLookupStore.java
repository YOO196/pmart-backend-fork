package com.pmart.mfc.fulfillment.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 주문 번호로 현장 상태(0.10.0, 백오피스 통합 조회 BO-11 — 요청 F-3). 읽기만. */
@Repository
public class OrderLookupStore {

  public record WorkOrder(
      String fulfillmentId,
      String kind,
      String mfcId,
      int workVersion,
      String acceptance,
      @Nullable String rejectReason,
      @Nullable String rejectMessage,
      @Nullable Instant decidedAt,
      @Nullable Instant preparationStartedAt,
      Instant createdAt) {}

  public record Shipment(
      String shipmentId,
      String fulfillmentId,
      String state,
      @Nullable Instant readyAt,
      @Nullable Instant dispatchRequestedAt) {}

  public record Task(
      String taskId,
      String shipmentId,
      String taskType,
      String state,
      @Nullable String workerId,
      @Nullable String startedBy,
      @Nullable Instant startedAt,
      Instant updatedAt,
      @Nullable String blockReason,
      int plannedQty,
      int completedQty,
      int cancelledQty) {}

  public record Shortage(
      String taskId,
      String shipmentId,
      String orderLineId,
      String sku,
      int qty,
      @Nullable String reason,
      Instant reportedAt,
      String executionState) {}

  public record Package(
      String packageId,
      String shipmentId,
      String state,
      @Nullable Instant sealedAt,
      String fenceState,
      @Nullable Instant authorizedAt,
      @Nullable String handoverStatus,
      @Nullable String handoverRejectReason,
      @Nullable Instant handedOverAt) {}

  public record Stop(
      String stopRequestId,
      String scope,
      String state,
      @Nullable String lastError,
      Instant requestedAt,
      @Nullable Instant failedAt,
      @Nullable Instant resolvedAt) {}

  private final JdbcTemplate jdbc;

  public OrderLookupStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 원래·재발송 작업 지시, 만든 순. 결정은 마지막 작업 버전의 것. */
  public List<WorkOrder> workOrders(String orderId) {
    return jdbc.query(
        "SELECT w.fulfillment_id, w.kind, w.mfc_id, w.last_work_version, w.acceptance,"
            + " w.preparation_started_at, w.created_at, v.reject_reason, v.message, v.decided_at"
            + " FROM work_order w LEFT JOIN work_order_version v ON v.fulfillment_id ="
            + " w.fulfillment_id AND v.work_version = w.last_work_version WHERE w.order_id = ?"
            + " ORDER BY w.created_at, w.fulfillment_id",
        (rs, i) ->
            new WorkOrder(
                rs.getString("fulfillment_id"),
                rs.getString("kind"),
                rs.getString("mfc_id"),
                rs.getInt("last_work_version"),
                rs.getString("acceptance"),
                rs.getString("reject_reason"),
                rs.getString("message"),
                time(rs, "decided_at"),
                time(rs, "preparation_started_at"),
                rs.getTimestamp("created_at").toInstant()),
        orderId);
  }

  public List<Shipment> shipments(List<String> fulfillmentIds) {
    return jdbc.query(
        "SELECT shipment_id, fulfillment_id, state, ready_at, dispatch_requested_at FROM shipment"
            + " WHERE fulfillment_id = ANY (?) ORDER BY created_at, shipment_id",
        (rs, i) ->
            new Shipment(
                rs.getString("shipment_id"),
                rs.getString("fulfillment_id"),
                rs.getString("state"),
                time(rs, "ready_at"),
                time(rs, "dispatch_requested_at")),
        (Object) fulfillmentIds.toArray(String[]::new));
  }

  /** 작업과 줄 합계(계획·완료·취소). 같은 시각에 만든 작업은 PICK → PACK → (DISPATCH) → RETURN. */
  public List<Task> tasks(List<String> shipmentIds) {
    return jdbc.query(
        "SELECT t.task_id, t.shipment_id, t.task_type, t.state, t.worker_id, t.started_by,"
            + " t.started_at, t.updated_at, t.block_reason, COALESCE(SUM(l.planned_qty), 0) AS"
            + " planned, COALESCE(SUM(l.completed_qty), 0) AS completed,"
            + " COALESCE(SUM(l.cancelled_qty), 0) AS cancelled FROM task t LEFT JOIN task_line l"
            + " ON l.task_id = t.task_id WHERE t.shipment_id = ANY (?) GROUP BY t.task_id"
            + " ORDER BY t.created_at, CASE t.task_type WHEN 'PICK' THEN 0 WHEN 'PACK' THEN 1"
            + " WHEN 'DISPATCH' THEN 2 ELSE 3 END, t.task_id",
        (rs, i) ->
            new Task(
                rs.getString("task_id"),
                rs.getString("shipment_id"),
                rs.getString("task_type"),
                rs.getString("state"),
                rs.getString("worker_id"),
                rs.getString("started_by"),
                time(rs, "started_at"),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getString("block_reason"),
                rs.getInt("planned"),
                rs.getInt("completed"),
                rs.getInt("cancelled")),
        (Object) shipmentIds.toArray(String[]::new));
  }

  /** 점원 결품 보고 — 실적 줄의 shortage(메모는 읽지 않는다). */
  public List<Shortage> shortages(List<String> shipmentIds) {
    return jdbc.query(
        "SELECT e.task_id, t.shipment_id, e.state, e.recorded_at, l ->> 'orderLineId' AS line,"
            + " l ->> 'sku' AS sku, (l -> 'shortage' ->> 'qty')::int AS qty,"
            + " l -> 'shortage' ->> 'reason' AS reason FROM task_execution e JOIN task t ON"
            + " t.task_id = e.task_id CROSS JOIN LATERAL jsonb_array_elements(e.lines) l WHERE"
            + " t.shipment_id = ANY (?) AND jsonb_typeof(l -> 'shortage') = 'object' ORDER BY"
            + " e.recorded_at, e.execution_id",
        (rs, i) ->
            new Shortage(
                rs.getString("task_id"),
                rs.getString("shipment_id"),
                rs.getString("line"),
                rs.getString("sku"),
                rs.getInt("qty"),
                rs.getString("reason"),
                rs.getTimestamp("recorded_at").toInstant(),
                rs.getString("state")),
        (Object) shipmentIds.toArray(String[]::new));
  }

  public List<Package> packages(List<String> shipmentIds) {
    return jdbc.query(
        "SELECT p.package_id, p.shipment_id, p.state, p.sealed_at, p.fence_state,"
            + " p.fence_authorized_at, h.state AS handover_state, h.reject_reason AS"
            + " handover_reject, COALESCE(h.handed_over_at, p.handed_over_at) AS handed_over FROM"
            + " package p LEFT JOIN package_handover h ON h.package_id = p.package_id WHERE"
            + " p.shipment_id = ANY (?) ORDER BY p.created_at, p.package_id",
        (rs, i) ->
            new Package(
                rs.getString("package_id"),
                rs.getString("shipment_id"),
                rs.getString("state"),
                time(rs, "sealed_at"),
                rs.getString("fence_state"),
                time(rs, "fence_authorized_at"),
                rs.getString("handover_state"),
                rs.getString("handover_reject"),
                time(rs, "handed_over")),
        (Object) shipmentIds.toArray(String[]::new));
  }

  public List<Stop> stops(String orderId) {
    return jdbc.query(
        "SELECT stop_request_id, scope, state, last_error, decided_at, failed_at, resolved_at"
            + " FROM order_work_stop WHERE order_id = ? ORDER BY decided_at, stop_request_id",
        (rs, i) ->
            new Stop(
                rs.getString("stop_request_id"),
                rs.getString("scope"),
                rs.getString("state"),
                rs.getString("last_error"),
                rs.getTimestamp("decided_at").toInstant(),
                time(rs, "failed_at"),
                time(rs, "resolved_at")),
        orderId);
  }

  private static @Nullable Instant time(ResultSet rs, String column) throws SQLException {
    Timestamp t = rs.getTimestamp(column);
    return t == null ? null : t.toInstant();
  }
}
