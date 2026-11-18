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
      Instant updatedAt) {}

  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate named;

  public CountStore(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
    this.jdbc = jdbc;
    this.named = named;
  }

  public void insert(SessionRow r) {
    jdbc.update(
        "INSERT INTO count_session (session_id, mfc_id, state, location_codes, reason, created_by,"
            + " book, version, baseline_at, updated_at) VALUES (?,?,?,?::jsonb,?,?,?::jsonb,1,?,?)",
        r.sessionId(),
        r.mfcId(),
        r.state(),
        r.locationCodes(),
        r.reason(),
        r.createdBy(),
        r.book(),
        Timestamp.from(r.baselineAt()),
        Timestamp.from(r.updatedAt()));
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

  public void firstCount(String sessionId, String counter, String lines, String state, Instant at) {
    jdbc.update(
        "UPDATE count_session SET first_counter = ?, first_count = ?::jsonb, state = ?,"
            + " version = version + 1, first_counted_at = ?, updated_at = ? WHERE session_id = ?",
        counter,
        lines,
        state,
        Timestamp.from(at),
        Timestamp.from(at),
        sessionId);
  }

  public void recount(String sessionId, String counter, String lines, String state, Instant at) {
    jdbc.update(
        "UPDATE count_session SET recounter = ?, recount = ?::jsonb, state = ?,"
            + " version = version + 1, recounted_at = ?, updated_at = ? WHERE session_id = ?",
        counter,
        lines,
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
        rs.getTimestamp("updated_at").toInstant());
  }
}
