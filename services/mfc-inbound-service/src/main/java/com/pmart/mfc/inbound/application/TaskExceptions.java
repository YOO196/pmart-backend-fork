package com.pmart.mfc.inbound.application;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.inbound.infrastructure.InboundTaskStore.TaskRow;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 현장 예외 보고(PDA BR-52 R19-7, 2026-11-20): 이동·보충·적치 중 "출발 칸에 없음·모자람(SHORT)·파손(DAMAGED)·변질(SPOILED)·칸
 * 못 씀(BLOCKED)"을 **기록만** 한다. 사유 값은 PICK 결품 보고와 같다. 재고·이동 배정·작업 잔량은 바꾸지 않는다 — 남은 양 처리(다른 칸 재지시·관리자
 * 이관·격리·품질 사고)는 아직 정하지 않았다(회신 남은 것). 같은 Idempotency-Key 는 같은 기록.
 */
@Service
public class TaskExceptions {

  static final Set<String> REASONS = Set.of("SHORT", "DAMAGED", "SPOILED", "BLOCKED");

  private static final Logger log = LoggerFactory.getLogger(TaskExceptions.class);

  /** 보고 본문. */
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
      Instant reportedAt) {}

  private final JdbcTemplate jdbc;
  private final MoveOrderService moves;
  private final InboundTaskService tasks;
  private final Clock clock;

  public TaskExceptions(
      JdbcTemplate jdbc, MoveOrderService moves, InboundTaskService tasks, Clock clock) {
    this.jdbc = jdbc;
    this.moves = moves;
    this.tasks = tasks;
    this.clock = clock;
  }

  /** 이동·보충 — 그 센터 MFC_WORKER·MFC_SUPERVISOR, 진행 중 지시만, 담당이 있으면 담당 또는 카트에 실은 사람. */
  public Row reportMove(String requestId, String sourceSystem, String moveId, Report r) {
    validate(r);
    MoveOrderService.Row m = moves.find(moveId);
    String me = InboundTaskService.requireOperator(m.mfcId());
    PmartPrincipal p = PmartPrincipal.current().orElseThrow();
    if (MoveOrderService.FIELD_ROLES.stream().noneMatch(p::hasRole)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "이 센터의 작업자만 보고합니다.");
    }
    if (!Set.of("ACTIVE", "CANCEL_PENDING").contains(m.state())) {
      throw PmartException.invalidState(moveId + " 는 끝난 이동입니다(" + m.state() + ").");
    }
    if (m.assigneeId() != null && !m.assigneeId().equals(me) && !me.equals(m.pickedBy())) {
      throw notAssignee(m.assigneeId());
    }
    return record(
        requestId,
        sourceSystem,
        m.mfcId(),
        "REPLENISH".equals(m.kind()) ? "REPLENISH" : "MOVE",
        moveId,
        m.sku(),
        m.lotId(),
        r,
        me);
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
    if (!"READY".equals(t.state())) {
      throw PmartException.invalidState(taskId + " 는 이미 끝난 작업입니다(" + t.state() + ").");
    }
    if (t.assigneeId() != null && !t.assigneeId().equals(me)) {
      throw notAssignee(t.assigneeId());
    }
    return record(requestId, sourceSystem, t.mfcId(), "PUTAWAY", taskId, t.sku(), t.lotId(), r, me);
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
                + " reported_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
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
    Row saved =
        jdbc.query("SELECT * FROM task_exception WHERE exception_id = ?", (rs, i) -> row(rs), id)
            .getFirst();
    if (n == 0) {
      // 같은 키로 다시 — 같은 내용이면 같은 기록
      if (!saved.reason().equals(r.reason())
          || !Objects.equals(saved.qty(), r.qty())
          || !Objects.equals(saved.locationCode(), r.locationCode())
          || !Objects.equals(saved.note(), r.note())
          || !Objects.equals(saved.evidenceRef(), r.evidenceRef())
          || !saved.reportedBy().equals(me)) {
        throw PmartException.idempotencyConflict("같은 Idempotency-Key 에 다른 내용입니다.");
      }
      return saved;
    }
    log.info("현장 예외 {} {} {} {} qty={} by {}", id, targetType, targetId, r.reason(), r.qty(), me);
    return saved;
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
        rs.getTimestamp("reported_at").toInstant());
  }
}
