package com.pmart.mfc.inventory.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 순환 실사 표(count_session, V10). JSON 열은 문자열로. */
@Repository
public class CountStore {

  public record SessionRow(
      String sessionId,
      String mfcId,
      String state,
      String locationCodes,
      @Nullable String reason,
      String createdBy,
      @Nullable String firstCounter,
      @Nullable String recounter,
      @Nullable String approverId,
      @Nullable String approvalRef,
      @Nullable String decisionNote,
      String book,
      @Nullable String firstCount,
      @Nullable String recount,
      @Nullable String commitId,
      int version,
      Instant baselineAt,
      Instant updatedAt,
      @Nullable String planId,
      @Nullable String firstUnresolved,
      @Nullable String recountUnresolved,
      String exceptions,
      @Nullable String cancelReason) {}

  /** 실사 계획(V24, BR-52 R14). */
  public record PlanRow(
      String planId,
      String mfcId,
      @Nullable String reason,
      String createdBy,
      Instant createdAt,
      @Nullable String cancelledBy,
      @Nullable Instant cancelledAt,
      @Nullable String assigneeId) {}

  /** 계획의 실사 한 줄(계획 조회용). */
  public record PlanSession(
      String sessionId, String locationCodes, String state, int exceptions, int openExceptions) {}

  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate named;

  public CountStore(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
    this.jdbc = jdbc;
    this.named = named;
  }

  public void insert(SessionRow r) {
    jdbc.update(
        "INSERT INTO count_session (session_id, mfc_id, state, location_codes, reason, created_by,"
            + " book, version, baseline_at, updated_at, plan_id)"
            + " VALUES (?,?,?,?::jsonb,?,?,?::jsonb,1,?,?,?)",
        r.sessionId(),
        r.mfcId(),
        r.state(),
        r.locationCodes(),
        r.reason(),
        r.createdBy(),
        r.book(),
        Timestamp.from(r.baselineAt()),
        Timestamp.from(r.updatedAt()),
        r.planId());
  }

  public void insertPlan(PlanRow p) {
    jdbc.update(
        "INSERT INTO count_plan (plan_id, mfc_id, reason, created_by, created_at, assignee_id)"
            + " VALUES (?,?,?,?,?,?)",
        p.planId(),
        p.mfcId(),
        p.reason(),
        p.createdBy(),
        Timestamp.from(p.createdAt()),
        p.assigneeId());
  }

  public Optional<PlanRow> findPlan(String planId) {
    return jdbc
        .query(
            "SELECT * FROM count_plan WHERE plan_id = ?",
            (rs, i) ->
                new PlanRow(
                    rs.getString("plan_id"),
                    rs.getString("mfc_id"),
                    rs.getString("reason"),
                    rs.getString("created_by"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getString("cancelled_by"),
                    rs.getTimestamp("cancelled_at") == null
                        ? null
                        : rs.getTimestamp("cancelled_at").toInstant(),
                    rs.getString("assignee_id")),
            planId)
        .stream()
        .findFirst();
  }

  /** 계획 행 잠금(취소 직렬화). */
  public void lockPlan(String planId) {
    jdbc.query("SELECT plan_id FROM count_plan WHERE plan_id = ? FOR UPDATE", rs -> {}, planId);
  }

  public void cancelPlan(String planId, String by, @Nullable String reason, Instant at) {
    jdbc.update(
        "UPDATE count_plan SET cancelled_by = ?, cancel_reason = ?, cancelled_at = ?"
            + " WHERE plan_id = ? AND cancelled_at IS NULL",
        by,
        reason,
        Timestamp.from(at),
        planId);
  }

  public List<PlanSession> planSessions(String planId) {
    return jdbc.query(
        "SELECT session_id, location_codes, state, jsonb_array_length(exceptions) AS n,"
            + " (SELECT count(*) FROM jsonb_array_elements(exceptions) e WHERE e->>'resolvedAt' IS NULL)"
            + " AS open_n FROM count_session WHERE plan_id = ? ORDER BY baseline_at, session_id",
        (rs, i) ->
            new PlanSession(
                rs.getString("session_id"),
                rs.getString("location_codes"),
                rs.getString("state"),
                rs.getInt("n"),
                rs.getInt("open_n")),
        planId);
  }

  /** 센터 단위 직렬화(겹치는 계획을 한 줄로). 트랜잭션 끝에 풀린다. */
  public void lockCenter(String mfcId) {
    jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?))", rs -> {}, "count-plan:" + mfcId);
  }

  /** 계획 잠금 — 끝나지 않은 계획 실사에 든 칸인가(BR-52 R14, 이 서비스가 잠근다). */
  public boolean planLocked(String mfcId, String locationCode) {
    Boolean b =
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM count_session WHERE mfc_id = ? AND plan_id IS NOT NULL"
                + " AND state IN ('COUNTING', 'RECOUNT_REQUIRED', 'PENDING_APPROVAL')"
                + " AND location_codes @> jsonb_build_array(?::text))",
            Boolean.class,
            mfcId,
            locationCode);
    return Boolean.TRUE.equals(b);
  }

  /** 작업자에게 줄 계획 실사(계수 단계·열린 예외 없음 — 0.35.0 해결된 예외는 막지 않는다), 기준 시각 오래된 순. */
  public List<SessionRow> countWork(String mfcId, int limit) {
    return jdbc.query(
        "SELECT * FROM count_session WHERE mfc_id = ? AND plan_id IS NOT NULL"
            + " AND state IN ('COUNTING', 'RECOUNT_REQUIRED') AND"
            + " NOT EXISTS (SELECT 1 FROM jsonb_array_elements(exceptions) e"
            + " WHERE e->>'resolvedAt' IS NULL)"
            + " ORDER BY baseline_at, session_id LIMIT ?",
        (rs, i) -> row(rs),
        mfcId,
        limit);
  }

  public void addException(String sessionId, String exceptionJson, Instant at) {
    jdbc.update(
        "UPDATE count_session SET exceptions = exceptions || jsonb_build_array(?::jsonb),"
            + " version = version + 1, updated_at = ? WHERE session_id = ?",
        exceptionJson,
        Timestamp.from(at),
        sessionId);
  }

  /** 예외 목록 통째로 바꾸기(0.35.0 다시 시작 — 행 잠금 아래에서만). */
  public void replaceExceptions(String sessionId, String exceptionsJson, Instant at) {
    jdbc.update(
        "UPDATE count_session SET exceptions = ?::jsonb, version = version + 1, updated_at = ?"
            + " WHERE session_id = ?",
        exceptionsJson,
        Timestamp.from(at),
        sessionId);
  }

  public void cancel(String sessionId, String by, @Nullable String reason, Instant at) {
    jdbc.update(
        "UPDATE count_session SET state = 'CANCELLED', cancelled_by = ?, cancel_reason = ?,"
            + " version = version + 1, updated_at = ? WHERE session_id = ?",
        by,
        reason,
        Timestamp.from(at),
        sessionId);
  }

  /** 바코드 → SKU(사본 — 줄바꿈으로 이어 저장, 선행 0 그대로 비교). 하나뿐인지만 보려고 최대 2. */
  public List<String> skusByBarcode(String barcode) {
    return jdbc.queryForList(
        "SELECT sku FROM sku_projection WHERE ? = ANY (string_to_array(barcodes, chr(10)))"
            + " ORDER BY sku LIMIT 2",
        String.class,
        barcode);
  }

  /** 그 SKU 의 소비기한이 같은 LOT(하나뿐인지만 보려고 최대 2). */
  public List<String> lotsByExpiry(String sku, java.time.LocalDate expiry) {
    return jdbc.queryForList(
        "SELECT lot_id FROM stock_lot WHERE sku = ? AND expiry_date = ? ORDER BY lot_id LIMIT 2",
        String.class,
        sku,
        java.sql.Date.valueOf(expiry));
  }

  public Optional<SessionRow> find(String sessionId) {
    return jdbc
        .query("SELECT * FROM count_session WHERE session_id = ?", (rs, i) -> row(rs), sessionId)
        .stream()
        .findFirst();
  }

  public Optional<SessionRow> lock(String sessionId) {
    return jdbc
        .query(
            "SELECT * FROM count_session WHERE session_id = ? FOR UPDATE",
            (rs, i) -> row(rs),
            sessionId)
        .stream()
        .findFirst();
  }

  /** 열린 실사에 이미 들어 있는 위치(겹치는 실사 막기). */
  public List<String> openLocations(String mfcId, Collection<String> locations) {
    return named.queryForList(
        "SELECT DISTINCT l FROM count_session s, jsonb_array_elements_text(s.location_codes) l"
            + " WHERE s.mfc_id = :mfc AND s.state IN ('COUNTING', 'RECOUNT_REQUIRED', 'PENDING_APPROVAL')"
            + " AND l IN (:locs)",
        Map.of("mfc", mfcId, "locs", locations),
        String.class);
  }

  /** 센터 실사 ID — 상태로 거름, 시작(baseline_at) 오래된 순, 커서 = 마지막 sessionId(MB-1). */
  public List<String> sessionIds(
      String mfcId, java.util.Collection<String> states, @Nullable String after, int limit) {
    Map<String, Object> args = new java.util.LinkedHashMap<>();
    args.put("mfc", mfcId);
    args.put("states", states);
    args.put("limit", limit);
    String cursor = "";
    if (after != null) {
      cursor =
          " AND (baseline_at, session_id) > (SELECT baseline_at, session_id FROM count_session"
              + " WHERE session_id = :after)";
      args.put("after", after);
    }
    return named.queryForList(
        "SELECT session_id FROM count_session WHERE mfc_id = :mfc AND state IN (:states)"
            + cursor
            + " ORDER BY baseline_at, session_id LIMIT :limit",
        args,
        String.class);
  }

  public void firstCount(
      String sessionId, String counter, String lines, String unresolved, String state, Instant at) {
    jdbc.update(
        "UPDATE count_session SET first_counter = ?, first_count = ?::jsonb, first_unresolved ="
            + " ?::jsonb, state = ?, version = version + 1, first_counted_at = ?, updated_at = ?"
            + " WHERE session_id = ?",
        counter,
        lines,
        unresolved,
        state,
        Timestamp.from(at),
        Timestamp.from(at),
        sessionId);
  }

  public void recount(
      String sessionId, String counter, String lines, String unresolved, String state, Instant at) {
    jdbc.update(
        "UPDATE count_session SET recounter = ?, recount = ?::jsonb, recount_unresolved = ?::jsonb,"
            + " state = ?, version = version + 1, recounted_at = ?, updated_at = ?"
            + " WHERE session_id = ?",
        counter,
        lines,
        unresolved,
        state,
        Timestamp.from(at),
        Timestamp.from(at),
        sessionId);
  }

  public void decide(
      String sessionId,
      String state,
      String approver,
      @Nullable String approvalRef,
      @Nullable String note,
      @Nullable String commitId,
      Instant at) {
    jdbc.update(
        "UPDATE count_session SET state = ?, approver_id = ?, approval_ref = ?, decision_note = ?,"
            + " commit_id = ?, version = version + 1, updated_at = ? WHERE session_id = ?",
        state,
        approver,
        approvalRef,
        note,
        commitId,
        Timestamp.from(at),
        sessionId);
  }

  private static SessionRow row(ResultSet rs) throws SQLException {
    return new SessionRow(
        rs.getString("session_id"),
        rs.getString("mfc_id"),
        rs.getString("state"),
        rs.getString("location_codes"),
        rs.getString("reason"),
        rs.getString("created_by"),
        rs.getString("first_counter"),
        rs.getString("recounter"),
        rs.getString("approver_id"),
        rs.getString("approval_ref"),
        rs.getString("decision_note"),
        rs.getString("book"),
        rs.getString("first_count"),
        rs.getString("recount"),
        rs.getString("commit_id"),
        rs.getInt("version"),
        rs.getTimestamp("baseline_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant(),
        rs.getString("plan_id"),
        rs.getString("first_unresolved"),
        rs.getString("recount_unresolved"),
        rs.getString("exceptions"),
        rs.getString("cancel_reason"));
  }
}
