package com.pmart.mfc.fulfillment.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 센터 출고 목록(F-9 — 포장 인계 현황) — 출고·작업(PICK·PACK)·살아 있는 포장을 세 번의 조회로. */
@Repository
public class CenterShipmentStore {

  public record ShipmentRow(
      String shipmentId,
      String fulfillmentId,
      String orderId,
      String mfcId,
      String workOrderKind,
      String state,
      Instant createdAt,
      @Nullable Instant readyAt,
      @Nullable Instant dispatchRequestedAt) {}

  public record TaskRow(
      String shipmentId,
      String taskType,
      String state,
      @Nullable String blockReason,
      @Nullable Instant startedAt) {}

  public record PackageRow(
      String packageId,
      String shipmentId,
      String state,
      String fenceState,
      @Nullable Instant handoverAuthorizedAt,
      @Nullable Instant handedOverAt,
      @Nullable String riderId) {}

  private final NamedParameterJdbcTemplate jdbc;

  public CenterShipmentStore(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 센터의 출고 — 상태로 거름, 만든 순(같으면 ID), 커서 = 마지막 shipmentId. */
  public List<ShipmentRow> shipments(
      String mfcId, Collection<String> states, @Nullable String after, int limit) {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("mfc", mfcId);
    args.put("states", states);
    args.put("limit", limit);
    StringBuilder sql =
        new StringBuilder(
            "SELECT s.shipment_id, s.fulfillment_id, w.order_id, w.mfc_id, w.kind, s.state,"
                + " s.created_at, s.ready_at, s.dispatch_requested_at FROM shipment s"
                + " JOIN work_order w ON w.fulfillment_id = s.fulfillment_id"
                + " WHERE w.mfc_id = :mfc AND s.state IN (:states)");
    if (after != null) {
      sql.append(
          " AND (s.created_at, s.shipment_id) > (SELECT created_at, shipment_id FROM shipment"
              + " WHERE shipment_id = :after)");
      args.put("after", after);
    }
    sql.append(" ORDER BY s.created_at, s.shipment_id LIMIT :limit");
    return jdbc.query(
        sql.toString(),
        args,
        (rs, i) ->
            new ShipmentRow(
                rs.getString("shipment_id"),
                rs.getString("fulfillment_id"),
                rs.getString("order_id"),
                rs.getString("mfc_id"),
                rs.getString("kind"),
                rs.getString("state"),
                rs.getTimestamp("created_at").toInstant(),
                instant(rs, "ready_at"),
                instant(rs, "dispatch_requested_at")));
  }

  public List<TaskRow> tasks(Collection<String> shipmentIds) {
    if (shipmentIds.isEmpty()) {
      return List.of();
    }
    return jdbc.query(
        "SELECT shipment_id, task_type, state, block_reason, started_at FROM task"
            + " WHERE shipment_id IN (:ids) AND task_type IN ('PICK', 'PACK')",
        Map.of("ids", shipmentIds),
        (rs, i) ->
            new TaskRow(
                rs.getString("shipment_id"),
                rs.getString("task_type"),
                rs.getString("state"),
                rs.getString("block_reason"),
                instant(rs, "started_at")));
  }

  /** 살아 있는 포장(무효·거절 제외 — 재포장 전 포장은 새 포장으로 대신). */
  public List<PackageRow> packages(Collection<String> shipmentIds) {
    if (shipmentIds.isEmpty()) {
      return List.of();
    }
    List<PackageRow> out = new ArrayList<>();
    jdbc.query(
        "SELECT p.package_id, p.shipment_id, p.state, p.fence_state, p.fence_authorized_at,"
            + " p.handed_over_at, h.rider_id FROM package p LEFT JOIN package_handover h"
            + " ON h.package_id = p.package_id AND h.state = 'APPLIED'"
            + " WHERE p.shipment_id IN (:ids) AND p.state NOT IN ('INVALIDATED', 'VOIDED')"
            + " ORDER BY p.created_at, p.package_id",
        Map.of("ids", shipmentIds),
        rs -> {
          out.add(
              new PackageRow(
                  rs.getString("package_id"),
                  rs.getString("shipment_id"),
                  rs.getString("state"),
                  rs.getString("fence_state"),
                  instant(rs, "fence_authorized_at"),
                  instant(rs, "handed_over_at"),
                  rs.getString("rider_id")));
        });
    return out;
  }

  private static @Nullable Instant instant(ResultSet rs, String col) throws SQLException {
    Timestamp t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }
}
