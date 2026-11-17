package com.pmart.mfc.fulfillment.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 백오피스 처리 대기(BO-12, 요청 F-4, 0.9.0) — 실패로 멈춘 중지·재고가 거절한 인계 목록과 운영자 재시도 기록(V14). 읽기 위주, 원장 상태는 바꾸지
 * 않는다.
 */
@Repository
public class PendingListStore {

  public static final String STOP_REQUEST = "STOP_REQUEST";
  public static final String HANDOVER = "HANDOVER";

  public record FailedStop(
      String stopRequestId,
      String orderId,
      @Nullable String claimId,
      @Nullable String fulfillmentId,
      @Nullable String mfcId,
      String scope,
      String lastError,
      @Nullable Instant failedAt,
      Instant requestedAt) {}

  public record RejectedHandover(
      String handoverId,
      String packageId,
      String shipmentId,
      @Nullable String fulfillmentId,
      @Nullable String orderId,
      String deliveryId,
      String riderId,
      @Nullable String mfcId,
      @Nullable String rejectReason,
      Instant rejectedAt,
      Instant requestedAt) {}

  public record Retry(@Nullable String operatorId, @Nullable String reason, Instant retriedAt) {}

  private final JdbcTemplate jdbc;

  public PendingListStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * PENDING + 실패 사유 — 오래된 순(실패 시각, 없으면 요청 시각). 작업 지시는 그 주문의 원래(ORIGINAL) 것. after = 앞 쪽 마지막
   * stopRequestId(없는 ID 면 처음부터가 아니라 빈 결과 — 목록에서 빠진 뒤의 커서).
   */
  public List<FailedStop> failedStops(@Nullable String mfcId, @Nullable String after, int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT s.stop_request_id, s.order_id, s.claim_id, s.scope, s.last_error, s.failed_at,"
                + " s.decided_at, w.fulfillment_id, w.mfc_id FROM order_work_stop s"
                + " LEFT JOIN work_order w ON w.order_id = s.order_id AND w.kind = 'ORIGINAL'"
                + " WHERE s.state = 'PENDING' AND s.last_error IS NOT NULL");
    List<Object> args = new ArrayList<>();
    if (mfcId != null) {
      sql.append(" AND w.mfc_id = ?");
      args.add(mfcId);
    }
    if (after != null) {
      sql.append(
          " AND (COALESCE(s.failed_at, s.decided_at), s.stop_request_id) > (SELECT"
              + " COALESCE(failed_at, decided_at), stop_request_id FROM order_work_stop"
              + " WHERE stop_request_id = ?)");
      args.add(after);
    }
    sql.append(" ORDER BY COALESCE(s.failed_at, s.decided_at), s.stop_request_id LIMIT ?");
    args.add(limit);
    return jdbc.query(sql.toString(), (rs, i) -> failedStop(rs), args.toArray());
  }

  /** REJECTED 인계 — 오래된 순(거절 시각 = updated_at). after = 앞 쪽 마지막 handoverId. */
  public List<RejectedHandover> rejectedHandovers(
      @Nullable String mfcId, @Nullable String after, int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT h.handover_id, h.package_id, h.shipment_id, h.delivery_id, h.rider_id,"
                + " h.reject_reason, h.updated_at, h.requested_at, s.fulfillment_id, w.order_id,"
                + " w.mfc_id FROM package_handover h"
                + " LEFT JOIN shipment s ON s.shipment_id = h.shipment_id"
                + " LEFT JOIN work_order w ON w.fulfillment_id = s.fulfillment_id"
                + " WHERE h.state = 'REJECTED'");
    List<Object> args = new ArrayList<>();
    if (mfcId != null) {
      sql.append(" AND w.mfc_id = ?");
      args.add(mfcId);
    }
    if (after != null) {
      sql.append(
          " AND (h.updated_at, h.handover_id) > (SELECT updated_at, handover_id FROM"
              + " package_handover WHERE handover_id = ?)");
      args.add(after);
    }
    sql.append(" ORDER BY h.updated_at, h.handover_id LIMIT ?");
    args.add(limit);
    return jdbc.query(sql.toString(), (rs, i) -> rejected(rs), args.toArray());
  }

  public void recordRetry(
      String targetType,
      String targetId,
      @Nullable String operatorId,
      @Nullable String reason,
      String sourceSystem,
      Instant at) {
    jdbc.update(
        "INSERT INTO manual_retry (target_type, target_id, operator_id, reason, source_system,"
            + " retried_at) VALUES (?, ?, ?, ?, ?, ?)",
        targetType,
        targetId,
        operatorId,
        reason,
        sourceSystem,
        Timestamp.from(at));
  }

  /** 대상마다 가장 최근 재시도(없으면 맵에 없음). */
  public Map<String, Retry> latestRetries(String targetType, List<String> targetIds) {
    Map<String, Retry> out = new HashMap<>();
    if (targetIds.isEmpty()) {
      return out;
    }
    jdbc.query(
        "SELECT DISTINCT ON (target_id) target_id, operator_id, reason, retried_at FROM manual_retry"
            + " WHERE target_type = ? AND target_id = ANY (?) ORDER BY target_id, retried_at DESC,"
            + " retry_id DESC",
        rs -> {
          out.put(
              rs.getString("target_id"),
              new Retry(
                  rs.getString("operator_id"),
                  rs.getString("reason"),
                  rs.getTimestamp("retried_at").toInstant()));
        },
        targetType,
        targetIds.toArray(String[]::new));
    return out;
  }

  private static FailedStop failedStop(ResultSet rs) throws SQLException {
    Timestamp failed = rs.getTimestamp("failed_at");
    return new FailedStop(
        rs.getString("stop_request_id"),
        rs.getString("order_id"),
        rs.getString("claim_id"),
        rs.getString("fulfillment_id"),
        rs.getString("mfc_id"),
        rs.getString("scope"),
        rs.getString("last_error"),
        failed == null ? null : failed.toInstant(),
        rs.getTimestamp("decided_at").toInstant());
  }

  private static RejectedHandover rejected(ResultSet rs) throws SQLException {
    return new RejectedHandover(
        rs.getString("handover_id"),
        rs.getString("package_id"),
        rs.getString("shipment_id"),
        rs.getString("fulfillment_id"),
        rs.getString("order_id"),
        rs.getString("delivery_id"),
        rs.getString("rider_id"),
        rs.getString("mfc_id"),
        rs.getString("reject_reason"),
        rs.getTimestamp("updated_at").toInstant(),
        rs.getTimestamp("requested_at").toInstant());
  }
}
