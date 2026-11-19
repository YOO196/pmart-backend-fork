package com.pmart.mfc.inbound.application;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.inbound.infrastructure.InventoryMoveClient;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 재고 위치 이동 지시(IV-03, 2026-11-08 사용자 결정: MFC 선반 간 이동은 센터 관리자 지시, 작업자가 카트를 거쳐 두 단계 실행 — CDC 는 시스템
 * 지시). 수량·칸 규칙은 mfc-inventory 이동 배정이 판정하고, 여기는 지시·실행 사본을 inventory 응답으로 맞춘다. moveId 는 지시 요청 키에서 정해져
 * 같은 키로 다시 해도 같은 이동이다. 실행(집어 올리기·내려놓기)의 inventory 실행 ID = moveId + 요청 키 — 같은 키는 한 번.
 */
@Service
public class MoveOrderService {

  public static final String MOVE_ORDER_NOT_FOUND = "MOVE_ORDER_NOT_FOUND";
  static final String SUPERVISOR = "MFC_SUPERVISOR";
  static final Set<String> FIELD_ROLES = Set.of("MFC_WORKER", "MFC_SUPERVISOR");

  private static final Logger log = LoggerFactory.getLogger(MoveOrderService.class);

  public record Row(
      String moveId,
      String mfcId,
      String sku,
      String lotId,
      String fromLocation,
      String toLocation,
      @Nullable String cartLocation,
      int qty,
      int pickedQty,
      int droppedQty,
      String state,
      @Nullable String reason,
      String createdBy,
      Instant createdAt,
      Instant updatedAt,
      String kind,
      @Nullable String assigneeId,
      boolean urgent,
      @Nullable String pickedBy) {}

  public record CreateCommand(
      String mfcId,
      String sku,
      String lotId,
      String fromLocation,
      String toLocation,
      int qty,
      @Nullable String reason,
      boolean urgent) {}

  private final JdbcTemplate jdbc;
  private final InventoryMoveClient inventory;
  private final Clock clock;

  public MoveOrderService(JdbcTemplate jdbc, InventoryMoveClient inventory, Clock clock) {
    this.jdbc = jdbc;
    this.inventory = inventory;
    this.clock = clock;
  }

  public Row create(String requestId, String sourceSystem, CreateCommand c) {
    String me = require(c.mfcId(), Set.of(SUPERVISOR), "재고 이동은 이 센터의 MFC_SUPERVISOR 가 지시합니다.");
    String moveId =
        "MV-"
            + UUID.nameUUIDFromBytes(
                (sourceSystem + "|" + requestId).getBytes(StandardCharsets.UTF_8));
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("moveId", moveId);
    body.put("mfcId", c.mfcId());
    body.put("sku", c.sku());
    body.put("lotId", c.lotId());
    body.put("fromLocation", c.fromLocation());
    body.put("toLocation", c.toLocation());
    body.put("qty", c.qty());
    JsonNode m = inventory.create(moveId, body); // 거절은 inventory 의 코드 그대로
    Instant now = clock.instant();
    jdbc.update(
        "INSERT INTO move_order (move_id, mfc_id, sku, lot_id, from_location, to_location, qty,"
            + " state, reason, created_by, created_at, updated_at, urgent) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)"
            + " ON CONFLICT (move_id) DO NOTHING",
        moveId,
        c.mfcId(),
        c.sku(),
        c.lotId(),
        c.fromLocation(),
        c.toLocation(),
        c.qty(),
        m.path("state").asString("ACTIVE"),
        c.reason(),
        me,
        Timestamp.from(now),
        Timestamp.from(now),
        c.urgent());
    mirror(moveId, m);
    log.info(
        "재고 이동 지시 {} {} {}×{} {} → {} by {}",
        moveId,
        c.mfcId(),
        c.sku(),
        c.qty(),
        c.fromLocation(),
        c.toLocation(),
        me);
    return get(moveId);
  }

  public Row pickUp(String requestId, String moveId, String cart, int qty) {
    Row r = get(moveId);
    String me = require(r.mfcId(), FIELD_ROLES, "이 센터의 작업자만 실행합니다.");
    if (r.assigneeId() != null && !r.assigneeId().equals(me)) {
      throw new PmartException(
          "NOT_ASSIGNEE",
          org.springframework.http.HttpStatus.CONFLICT,
          "담당(" + r.assigneeId() + ")만 집어 올립니다 — 배정은 다음 작업 받기·센터 관리자.",
          false,
          List.of());
    }
    if (r.pickedBy() != null && !r.pickedBy().equals(me)) {
      throw new PmartException(
          "NOT_PICKER",
          org.springframework.http.HttpStatus.CONFLICT,
          "이미 " + r.pickedBy() + " 이(가) 카트에 실은 이동입니다.",
          false,
          List.of());
    }
    JsonNode m =
        inventory.pickUp(
            moveId,
            requestId,
            Map.of("executionId", moveId + ":" + requestId, "cartLocation", cart, "qty", qty));
    mirror(moveId, m);
    jdbc.update(
        "UPDATE move_order SET picked_by = COALESCE(picked_by, ?) WHERE move_id = ?", me, moveId);
    log.info("재고 이동 {} 집어 올림 {}개 → {} by {}", moveId, qty, cart, me);
    return get(moveId);
  }

  public Row drop(String requestId, String moveId, String toLocation, int qty) {
    Row r = get(moveId);
    String me = require(r.mfcId(), FIELD_ROLES, "이 센터의 작업자만 실행합니다.");
    if (r.pickedBy() != null && !r.pickedBy().equals(me)) {
      throw new PmartException(
          "NOT_PICKER",
          org.springframework.http.HttpStatus.CONFLICT,
          "카트에 실은 사람(" + r.pickedBy() + ")만 내려놓습니다.",
          false,
          List.of());
    }
    JsonNode m =
        inventory.drop(
            moveId,
            requestId,
            Map.of("executionId", moveId + ":" + requestId, "toLocation", toLocation, "qty", qty));
    mirror(moveId, m);
    log.info("재고 이동 {} 내려놓음 {}개 → {} by {}", moveId, qty, toLocation, me);
    return get(moveId);
  }

  public Row cancel(String requestId, String moveId, String reason) {
    Row r = get(moveId);
    String me = require(r.mfcId(), Set.of(SUPERVISOR), "이동 취소는 이 센터의 MFC_SUPERVISOR 가 합니다.");
    JsonNode m = inventory.cancel(moveId, requestId);
    mirror(moveId, m);
    jdbc.update(
        "UPDATE move_order SET reason = COALESCE(reason || ' / ', '') || ? WHERE move_id = ?",
        "취소: " + reason,
        moveId);
    log.info("재고 이동 {} 취소 by {} ({})", moveId, me, reason);
    return get(moveId);
  }

  public Row find(String moveId) {
    Row r = get(moveId);
    reader(r.mfcId());
    return r;
  }

  /** capacity AssignmentPublished(mfc.capacity.v1, inbox 트랜잭션 안) — 이동 지시 담당 투영(더 새 배정 버전만). */
  public void onCapacityEvent(com.pmart.common.messaging.EventEnvelope e) {
    if (!"AssignmentPublished".equals(e.eventType())) {
      return;
    }
    for (JsonNode a : e.payload().path("assignments")) {
      int n =
          jdbc.update(
              "UPDATE move_order SET worker_id = ?, assignment_version = ?, updated_at = ?"
                  + " WHERE move_id = ? AND assignment_version < ?",
              a.path("workerId").asString(null),
              a.path("assignmentVersion").asInt(0),
              Timestamp.from(clock.instant()),
              a.path("taskId").asString(""),
              a.path("assignmentVersion").asInt(0));
      if (n > 0) {
        log.info("이동 {} 담당 {}", a.path("taskId").asString(""), a.path("workerId").asString(""));
      }
    }
  }

  public List<Row> list(String mfcId, @Nullable String state) {
    reader(mfcId);
    return state == null
        ? jdbc.query(
            "SELECT * FROM move_order WHERE mfc_id = ? AND state IN ('ACTIVE', 'CANCEL_PENDING')"
                + " ORDER BY created_at LIMIT 500",
            (rs, i) -> row(rs),
            mfcId)
        : jdbc.query(
            "SELECT * FROM move_order WHERE mfc_id = ? AND state = ? ORDER BY created_at DESC"
                + " LIMIT 500",
            (rs, i) -> row(rs),
            mfcId,
            state);
  }

  /**
   * 자동 선반 보충 점검(IV-04, 사용자 결정: 시스템이 만든다) — inventory 가 만든 진행 중 보충 이동을 이동 지시(kind REPLENISH, 지시자
   * SYSTEM)로 남긴다. 다시 해도 같은 이동은 하나(moveId). 작업자는 일반 이동과 같이 카트로 실행한다.
   *
   * @return 새로 남긴 지시 수
   */
  public int recordReplenishments() {
    JsonNode r = inventory.evaluateReplenishment("RPL-EVAL-" + UUID.randomUUID());
    int n = 0;
    Instant now = clock.instant();
    for (JsonNode m : r.path("moves")) {
      String moveId = m.path("moveId").asString();
      n +=
          jdbc.update(
              "INSERT INTO move_order (move_id, mfc_id, sku, lot_id, from_location, to_location, qty,"
                  + " state, reason, created_by, created_at, updated_at, kind)"
                  + " VALUES (?,?,?,?,?,?,?,?,'자동 보충','SYSTEM',?,?,'REPLENISH')"
                  + " ON CONFLICT (move_id) DO NOTHING",
              moveId,
              m.path("mfcId").asString(),
              m.path("sku").asString(),
              m.path("lotId").asString(),
              m.path("fromLocation").asString(),
              m.path("toLocation").asString(),
              m.path("qty").asInt(),
              m.path("state").asString("ACTIVE"),
              Timestamp.from(now),
              Timestamp.from(now));
      mirror(moveId, m);
    }
    if (n > 0) {
      log.info("자동 보충 이동 {}건을 이동 지시로 남김", n);
    }
    return n;
  }

  /** (0.17.0) 현장 예외로 줄어든 이동 — inventory 응답 사본. */
  void mirrorFromInventory(String moveId, JsonNode m) {
    mirror(moveId, m);
  }

  /** inventory 응답 → 사본. */
  private void mirror(String moveId, JsonNode m) {
    jdbc.update(
        "UPDATE move_order SET qty = ?, picked_qty = ?, dropped_qty = ?, cart_location = ?,"
            + " state = ?, updated_at = ? WHERE move_id = ?",
        m.path("qty").asInt(),
        m.path("pickedQty").asInt(),
        m.path("droppedQty").asInt(),
        m.path("cartLocation").asString(null),
        m.path("state").asString("ACTIVE"),
        Timestamp.from(clock.instant()),
        moveId);
  }

  private Row get(String moveId) {
    return jdbc
        .query("SELECT * FROM move_order WHERE move_id = ?", (rs, i) -> row(rs), moveId)
        .stream()
        .findFirst()
        .orElseThrow(
            () -> PmartException.notFound(MOVE_ORDER_NOT_FOUND, "재고 이동 지시가 없습니다: " + moveId));
  }

  private static String require(String mfcId, Set<String> roles, String message) {
    String me = InboundTaskService.requireOperator(mfcId);
    PmartPrincipal p = PmartPrincipal.current().orElseThrow();
    if (roles.stream().noneMatch(p::hasRole)) {
      throw new PmartException(ErrorCode.FORBIDDEN, message);
    }
    return me;
  }

  private static void reader(String mfcId) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "토큰이 필요합니다."));
    if (!p.isService() && !p.canAccessCenter(mfcId)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "이 센터에 대한 권한이 없습니다.");
    }
  }

  private static Row row(ResultSet rs) throws SQLException {
    return new Row(
        rs.getString("move_id"),
        rs.getString("mfc_id"),
        rs.getString("sku"),
        rs.getString("lot_id"),
        rs.getString("from_location"),
        rs.getString("to_location"),
        rs.getString("cart_location"),
        rs.getInt("qty"),
        rs.getInt("picked_qty"),
        rs.getInt("dropped_qty"),
        rs.getString("state"),
        rs.getString("reason"),
        rs.getString("created_by"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant(),
        rs.getString("kind"),
        rs.getString("worker_id"),
        rs.getBoolean("urgent"),
        rs.getString("picked_by"));
  }
}
