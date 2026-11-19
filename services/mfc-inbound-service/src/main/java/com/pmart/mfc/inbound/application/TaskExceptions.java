package com.pmart.mfc.inbound.application;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.inbound.infrastructure.InboundTaskStore;
import com.pmart.mfc.inbound.infrastructure.InboundTaskStore.TaskRow;
import com.pmart.mfc.inbound.infrastructure.InventoryMoveClient;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * 현장 예외 보고(PDA BR-52 R19-7, 2026-11-20): 이동·보충·적치 중 "출발 칸에 없음·모자람(SHORT)·파손(DAMAGED)·변질(SPOILED)·칸
 * 못 씀(BLOCKED)". 사유 값은 PICK 결품 보고와 같다. 같은 Idempotency-Key 는 같은 기록.
 *
 * <p>(0.17.0, 사용자 결정 2026-11-20 "PICK 결품처럼") 기록 뒤 mfc-inventory 에 반영한다 — SHORT·BLOCKED = 분실 조사
 * 보류(실사가 정리), DAMAGED·SPOILED = 격리 + 보고한 사람에게 격리 이동 작업. 이동·보충은 아직 집지 않은 몫(지시 수량이 줄어든다 — inventory
 * 이동 예외), 적치는 작업의 출발 칸 적치 전 재고(작업 잔량에서 뺀다 — inventory 재고 예외). PICK 결품
 * 보고(/internal/shortage-reports)는 확정 예약이 있어야 해서 쓰지 않는다. 기록(짧은 트랜잭션) → inventory(트랜잭션 밖, reportId =
 * 예외 ID 로 한 번) → 결과 기록. 거절은 그 코드로, 결과 모름은 UNKNOWN_RESULT 로 두고 같은 키로 다시.
 */
@Service
public class TaskExceptions {

  static final Set<String> REASONS = Set.of("SHORT", "DAMAGED", "SPOILED", "BLOCKED");

  private static final Logger log = LoggerFactory.getLogger(TaskExceptions.class);

  /** 보고 본문. qty 가 없으면 남은 양 전부(0.17.0). */
  public record Report(
      String reason,
      @Nullable Integer qty,
      @Nullable String locationCode,
      @Nullable String note,
      @Nullable String evidenceRef) {}

  /** 기록. */
  public record Row(
      String exceptionId,
      String mfcId,
      String targetType,
      String targetId,
      @Nullable String sku,
      @Nullable String lotId,
      String reason,
      @Nullable Integer qty,
      @Nullable String locationCode,
      @Nullable String note,
      @Nullable String evidenceRef,
      String reportedBy,
      Instant reportedAt,
      String applyStatus,
      @Nullable String inventoryCommitId,
      @Nullable String rejectReason,
      @Nullable String quarantineTaskId) {}

  private final JdbcTemplate jdbc;
  private final MoveOrderService moves;
  private final InboundTaskService tasks;
  private final InboundTaskStore taskStore;
  private final InventoryMoveClient inventory;
  private final TransactionTemplate tx;
  private final Clock clock;

  public TaskExceptions(
      JdbcTemplate jdbc,
      MoveOrderService moves,
      InboundTaskService tasks,
      InboundTaskStore taskStore,
      InventoryMoveClient inventory,
      TransactionTemplate tx,
      Clock clock) {
    this.jdbc = jdbc;
    this.moves = moves;
    this.tasks = tasks;
    this.taskStore = taskStore;
    this.inventory = inventory;
    this.tx = tx;
    this.clock = clock;
  }

  /** 이동·보충 — 그 센터 MFC_WORKER·MFC_SUPERVISOR, 진행 중(ACTIVE) 지시만, 담당이 있으면 담당 또는 카트에 실은 사람. */
  public Row reportMove(String requestId, String sourceSystem, String moveId, Report r) {
    validate(r);
    MoveOrderService.Row m = moves.find(moveId);
    String me = InboundTaskService.requireOperator(m.mfcId());
    PmartPrincipal p = PmartPrincipal.current().orElseThrow();
    if (MoveOrderService.FIELD_ROLES.stream().noneMatch(p::hasRole)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "이 센터의 작업자만 보고합니다.");
    }
    if (m.assigneeId() != null && !m.assigneeId().equals(me) && !me.equals(m.pickedBy())) {
      throw notAssignee(m.assigneeId());
    }
    Row saved = existing(sourceSystem, requestId, moveId);
    if (saved == null) {
      if (!"ACTIVE".equals(m.state())) {
        throw PmartException.invalidState(moveId + " 는 진행 중인 이동이 아닙니다(" + m.state() + ").");
      }
      int unpicked = m.qty() - m.pickedQty();
      int qty = r.qty() != null ? r.qty() : unpicked;
      if (qty < 1 || qty > unpicked) {
        throw new PmartException(
            "MOVE_QTY_EXCEEDED",
            HttpStatus.CONFLICT,
            "아직 집지 않은 양 " + unpicked + "개 안에서만 보고합니다(카트 위 파손은 내려놓은 뒤 품질 격리로).",
            false,
            List.of());
      }
      saved =
          record(
              requestId,
              sourceSystem,
              m.mfcId(),
              "REPLENISH".equals(m.kind()) ? "REPLENISH" : "MOVE",
              moveId,
              m.sku(),
              m.lotId(),
              withQty(r, qty),
              me);
    } else {
      same(saved, r, me);
    }
    if ("APPLIED".equals(saved.applyStatus()) || "RECORDED".equals(saved.applyStatus())) {
      return saved;
    }
    Row s = saved;
    int qty = Objects.requireNonNull(s.qty());
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("reportId", s.exceptionId());
    body.put("reason", s.reason());
    body.put("qty", qty);
    JsonNode res = call(s, () -> inventory.moveException(moveId, s.exceptionId(), body));
    tx.executeWithoutResult(
        st -> {
          moves.mirrorFromInventory(moveId, res.path("move"));
          String qrt =
              damaged(s.reason())
                  ? tasks.createQuarantineMove(
                      s.mfcId(),
                      s.exceptionId(),
                      null,
                      Objects.requireNonNull(s.sku()),
                      Objects.requireNonNull(s.lotId()),
                      m.fromLocation(),
                      qty,
                      "EXCEPTION",
                      "EXCEPTION:" + s.exceptionId(),
                      s.reportedBy(),
                      clock.instant())
                  : null;
          applied(s.exceptionId(), res.path("commitId").asString(null), qrt, qty);
        });
    log.info("이동 예외 {} {} {}×{} 반영 ({})", s.exceptionId(), moveId, s.reason(), qty, me);
    return load(s.exceptionId());
  }

  /** 적치 — 그 센터 운영자, READY 적치 작업만, 담당이 있으면 담당. */
  public Row reportPutaway(String requestId, String sourceSystem, String taskId, Report r) {
    validate(r);
    TaskRow t = tasks.get(taskId);
    String me = InboundTaskService.requireOperator(t.mfcId());
    if (!"PUTAWAY".equals(t.taskType())) {
      throw PmartException.invalidState(
          taskId + " 는 " + t.taskType() + " 작업입니다 — 예외 보고는 적치만(품질검사는 격리 판정으로).");
    }
    if (t.assigneeId() != null && !t.assigneeId().equals(me)) {
      throw notAssignee(t.assigneeId());
    }
    Row saved = existing(sourceSystem, requestId, taskId);
    if (saved == null) {
      if (!"READY".equals(t.state())) {
        throw PmartException.invalidState(taskId + " 는 이미 끝난 작업입니다(" + t.state() + ").");
      }
      int room = t.remaining() - tasks.openQty(t);
      int qty = r.qty() != null ? r.qty() : room;
      if (qty < 1 || qty > room) {
        throw new PmartException(
            "TASK_QTY_EXCEEDED",
            HttpStatus.CONFLICT,
            "작업 잔량(" + room + ") 안에서만 보고합니다: " + qty,
            false,
            List.of());
      }
      saved =
          record(
              requestId,
              sourceSystem,
              t.mfcId(),
              "PUTAWAY",
              taskId,
              t.sku(),
              t.lotId(),
              withQty(r, qty),
              me);
    } else {
      same(saved, r, me);
    }
    if ("APPLIED".equals(saved.applyStatus()) || "RECORDED".equals(saved.applyStatus())) {
      return saved;
    }
    Row s = saved;
    int qty = Objects.requireNonNull(s.qty());
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("reportId", s.exceptionId());
    body.put("mfcId", t.mfcId());
    body.put("sku", t.sku());
    body.put("lotId", t.lotId());
    body.put("locationCode", t.locationCode());
    body.put("workState", "UNSTOWED");
    body.put("reason", s.reason());
    body.put("qty", qty);
    body.put("sourceRef", taskId);
    JsonNode res = call(s, () -> inventory.stockException(s.exceptionId(), body));
    tx.executeWithoutResult(
        st -> {
          Instant at = clock.instant();
          taskStore.addExcepted(taskId, qty, at);
          String qrt =
              damaged(s.reason())
                  ? tasks.createQuarantineMove(
                      t.mfcId(),
                      s.exceptionId(),
                      taskId,
                      t.sku(),
                      t.lotId(),
                      t.locationCode(),
                      qty,
                      "EXCEPTION",
                      "EXCEPTION:" + s.exceptionId(),
                      s.reportedBy(),
                      at)
                  : null;
          applied(s.exceptionId(), res.path("commitId").asString(null), qrt, qty);
        });
    log.info("적치 예외 {} {} {}×{} 반영 ({})", s.exceptionId(), taskId, s.reason(), qty, me);
    return load(s.exceptionId());
  }

  /** 목록 — 그 센터 운영자(서비스도), 최근 순, 커서 = 마지막 exceptionId. */
  public List<Row> list(
      String mfcId,
      @Nullable Instant from,
      @Nullable Instant to,
      @Nullable String cursor,
      int limit) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "토큰이 필요합니다."));
    if (!p.isService()
        && (p.type() != PmartPrincipal.PrincipalType.OPERATOR || !p.canAccessCenter(mfcId))) {
      throw new PmartException(ErrorCode.FORBIDDEN, "이 센터에 대한 권한이 없습니다.");
    }
    if (from != null && to != null && !from.isBefore(to)) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "from 은 to 보다 앞이어야 합니다.");
    }
    StringBuilder sql = new StringBuilder("SELECT * FROM task_exception WHERE mfc_id = ?");
    List<Object> args = new ArrayList<>();
    args.add(mfcId);
    if (from != null) {
      sql.append(" AND reported_at >= ?");
      args.add(Timestamp.from(from));
    }
    if (to != null) {
      sql.append(" AND reported_at < ?");
      args.add(Timestamp.from(to));
    }
    if (cursor != null) {
      sql.append(
          " AND (reported_at, exception_id) < (SELECT reported_at, exception_id FROM task_exception"
              + " WHERE exception_id = ?)");
      args.add(cursor);
    }
    sql.append(" ORDER BY reported_at DESC, exception_id DESC LIMIT ?");
    args.add(Math.max(1, Math.min(limit, 201)));
    return jdbc.query(sql.toString(), (rs, i) -> row(rs), args.toArray());
  }

  /** inventory 호출 — 4xx 는 REJECTED 로 남기고 그 코드로, 미전달·불명은 UNKNOWN_RESULT(같은 키로 다시). */
  private JsonNode call(Row s, java.util.function.Supplier<JsonNode> c) {
    try {
      return c.get();
    } catch (PmartException e) {
      String status = e.status().is4xxClientError() ? "REJECTED" : "UNKNOWN_RESULT";
      String reason = e.code() + ": " + e.getMessage();
      jdbc.update(
          "UPDATE task_exception SET apply_status = ?, reject_reason = ? WHERE exception_id = ?",
          status,
          reason.length() > 500 ? reason.substring(0, 500) : reason,
          s.exceptionId());
      log.warn("현장 예외 {} 재고 반영 {}: {}", s.exceptionId(), status, reason);
      throw e;
    }
  }

  private void applied(String id, @Nullable String commitId, @Nullable String qrt, int qty) {
    jdbc.update(
        "UPDATE task_exception SET apply_status = 'APPLIED', inventory_commit_id = ?,"
            + " quarantine_task_id = ?, applied_qty = ?, reject_reason = NULL WHERE exception_id = ?",
        commitId,
        qrt,
        qty,
        id);
  }

  private @Nullable Row existing(String sourceSystem, String requestId, String targetId) {
    String key = sourceSystem + "|" + requestId + "|" + targetId;
    return jdbc
        .query("SELECT * FROM task_exception WHERE request_key = ?", (rs, i) -> row(rs), key)
        .stream()
        .findFirst()
        .orElse(null);
  }

  private Row load(String id) {
    return jdbc.query("SELECT * FROM task_exception WHERE exception_id = ?", (rs, i) -> row(rs), id)
        .getFirst();
  }

  /** 같은 키로 다시 — 같은 내용이어야 같은 기록(qty 생략은 처음 정한 양). */
  private static void same(Row saved, Report r, String me) {
    if (!saved.reason().equals(r.reason())
        || (r.qty() != null && !Objects.equals(saved.qty(), r.qty()))
        || !Objects.equals(saved.locationCode(), r.locationCode())
        || !Objects.equals(saved.note(), r.note())
        || !Objects.equals(saved.evidenceRef(), r.evidenceRef())
        || !saved.reportedBy().equals(me)) {
      throw PmartException.idempotencyConflict("같은 Idempotency-Key 에 다른 내용입니다.");
    }
  }

  private Row record(
      String requestId,
      String sourceSystem,
      String mfcId,
      String targetType,
      String targetId,
      @Nullable String sku,
      @Nullable String lotId,
      Report r,
      String me) {
    String key = sourceSystem + "|" + requestId + "|" + targetId;
    String id = "EXC-" + UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    int n =
        jdbc.update(
            "INSERT INTO task_exception (exception_id, request_key, mfc_id, target_type, target_id,"
                + " sku, lot_id, reason, qty, location_code, note, evidence_ref, reported_by,"
                + " reported_at, apply_status) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,'PENDING')"
                + " ON CONFLICT (request_key) DO NOTHING",
            id,
            key,
            mfcId,
            targetType,
            targetId,
            sku,
            lotId,
            r.reason(),
            r.qty(),
            r.locationCode(),
            r.note(),
            r.evidenceRef(),
            me,
            Timestamp.from(clock.instant()));
    Row saved = load(id);
    if (n == 0) {
      same(saved, r, me); // 경합한 같은 키
      return saved;
    }
    log.info("현장 예외 {} {} {} {} qty={} by {}", id, targetType, targetId, r.reason(), r.qty(), me);
    return saved;
  }

  private static Report withQty(Report r, int qty) {
    return new Report(r.reason(), qty, r.locationCode(), r.note(), r.evidenceRef());
  }

  private static boolean damaged(String reason) {
    return "DAMAGED".equals(reason) || "SPOILED".equals(reason);
  }

  private static void validate(Report r) {
    if (r.reason() == null || !REASONS.contains(r.reason())) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED, "reason 은 SHORT·DAMAGED·SPOILED·BLOCKED.");
    }
    if (r.qty() != null && r.qty() < 1) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "수량은 1 이상이어야 합니다.");
    }
    if (r.note() != null && r.note().length() > 200) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "note 는 200자 이하입니다.");
    }
    if (r.locationCode() != null && r.locationCode().length() > 64) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "locationCode 는 64자 이하입니다.");
    }
    if (r.evidenceRef() != null && (r.evidenceRef().isBlank() || r.evidenceRef().length() > 64)) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "evidenceRef 는 1~64자입니다.");
    }
  }

  private static PmartException notAssignee(String assignee) {
    return new PmartException(
        "NOT_ASSIGNEE",
        HttpStatus.CONFLICT,
        "담당(" + assignee + ")만 보고합니다 — 배정은 다음 작업 받기·센터 관리자.",
        false,
        List.of());
  }

  private static Row row(ResultSet rs) throws SQLException {
    int q = rs.getInt("qty");
    Integer qty = rs.wasNull() ? null : q;
    return new Row(
        rs.getString("exception_id"),
        rs.getString("mfc_id"),
        rs.getString("target_type"),
        rs.getString("target_id"),
        rs.getString("sku"),
        rs.getString("lot_id"),
        rs.getString("reason"),
        qty,
        rs.getString("location_code"),
        rs.getString("note"),
        rs.getString("evidence_ref"),
        rs.getString("reported_by"),
        rs.getTimestamp("reported_at").toInstant(),
        rs.getString("apply_status"),
        rs.getString("inventory_commit_id"),
        rs.getString("reject_reason"),
        rs.getString("quarantine_task_id"));
  }
}
