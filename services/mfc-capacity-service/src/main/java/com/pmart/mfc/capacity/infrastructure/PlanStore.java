package com.pmart.mfc.capacity.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 배정 추천(V6): 머리·항목·미배정. */
@Repository
public class PlanStore {

  public record PlanRow(
      String planId,
      String mfcId,
      String state,
      String solverStatus,
      Instant horizonStart,
      int horizonMinutes,
      int unassignedCount,
      int lateMinutes,
      int maxLoadMinutes,
      long objectiveValue,
      int wallTimeMs,
      List<String> staleReasons,
      String createdBy,
      Instant createdAt,
      @Nullable String committedBy,
      @Nullable Instant committedAt,
      @Nullable String settings,
      List<String> unverifiedWorkers) {}

  public record ItemRow(
      String taskId,
      String taskType,
      int taskVersion,
      String workerId,
      int dutyVersion,
      Instant startAt,
      Instant endAt,
      int lateMinutes) {}

  public record UnassignedRow(String taskId, String taskType, String reason) {}

  private final JdbcTemplate jdbc;

  public PlanStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 계산 입력(0.12.0). */
  public record InputRow(String planId, String json, String hash, int bytes, Instant createdAt) {}

  public void insertInput(String planId, String json, String hash, int bytes, Instant at) {
    jdbc.update(
        "INSERT INTO assignment_plan_input (plan_id, input_json, input_hash, bytes, created_at)"
            + " VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING",
        planId,
        json,
        hash,
        bytes,
        Timestamp.from(at));
  }

  public java.util.Optional<InputRow> input(String planId) {
    return jdbc
        .query(
            "SELECT plan_id, input_json, input_hash, bytes, created_at FROM assignment_plan_input"
                + " WHERE plan_id = ?",
            (rs, i) ->
                new InputRow(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getInt(4),
                    rs.getTimestamp(5).toInstant()),
            planId)
        .stream()
        .findFirst();
  }

  public @org.jspecify.annotations.Nullable String inputHash(String planId) {
    return jdbc
        .queryForList(
            "SELECT input_hash FROM assignment_plan_input WHERE plan_id = ?", String.class, planId)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public long totalInputBytes() {
    Long n =
        jdbc.queryForObject(
            "SELECT COALESCE(SUM(bytes), 0) FROM assignment_plan_input", Long.class);
    return n == null ? 0 : n;
  }

  /**
   * 오래된 입력부터 지운다 — 확정 안 된 계획 먼저, 그다음 확정된 계획. 지운 합이 excess 이상이 될 때까지만(한 건씩, 넘치게 지우지 않음).
   *
   * @return 지운 바이트
   */
  public long deleteOldestInputs(long excess) {
    long freed = 0;
    while (freed < excess) {
      List<java.util.Map<String, Object>> rows =
          jdbc.queryForList(
              "SELECT i.plan_id, i.bytes FROM assignment_plan_input i JOIN assignment_plan p"
                  + " ON p.plan_id = i.plan_id"
                  + " ORDER BY (p.state = 'COMMITTED'), i.created_at, i.plan_id LIMIT 1");
      if (rows.isEmpty()) {
        break;
      }
      var r = rows.get(0);
      jdbc.update("DELETE FROM assignment_plan_input WHERE plan_id = ?", r.get("plan_id"));
      freed += ((Number) r.get("bytes")).longValue();
    }
    return freed;
  }

  public void insert(PlanRow p, List<ItemRow> items, List<UnassignedRow> left) {
    jdbc.update(
        "INSERT INTO assignment_plan (plan_id, mfc_id, state, solver_status, horizon_start,"
            + " horizon_minutes, unassigned_count, late_minutes, max_load_minutes, objective_value,"
            + " wall_time_ms, created_by, created_at, settings, unverified_workers)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        p.planId(),
        p.mfcId(),
        p.state(),
        p.solverStatus(),
        Timestamp.from(p.horizonStart()),
        p.horizonMinutes(),
        p.unassignedCount(),
        p.lateMinutes(),
        p.maxLoadMinutes(),
        p.objectiveValue(),
        p.wallTimeMs(),
        p.createdBy(),
        Timestamp.from(p.createdAt()),
        p.settings(),
        String.join("\n", p.unverifiedWorkers()));
    for (ItemRow i : items) {
      jdbc.update(
          "INSERT INTO assignment_plan_item (plan_id, task_id, task_type, task_version, worker_id,"
              + " duty_version, start_at, end_at, late_minutes) VALUES (?,?,?,?,?,?,?,?,?)",
          p.planId(),
          i.taskId(),
          i.taskType(),
          i.taskVersion(),
          i.workerId(),
          i.dutyVersion(),
          Timestamp.from(i.startAt()),
          Timestamp.from(i.endAt()),
          i.lateMinutes());
    }
    for (UnassignedRow u : left) {
      jdbc.update(
          "INSERT INTO assignment_plan_unassigned (plan_id, task_id, task_type, reason)"
              + " VALUES (?,?,?,?)",
          p.planId(),
          u.taskId(),
          u.taskType(),
          u.reason());
    }
  }

  /** 센터 추천 ID — 만든 날(서울)·상태로 거름, 최근 순, 커서 = 마지막 planId(MB-1). */
  public List<String> planIds(
      String mfcId,
      java.time.@Nullable LocalDate day,
      @Nullable List<String> states,
      @Nullable String after,
      int limit) {
    StringBuilder sql = new StringBuilder("SELECT plan_id FROM assignment_plan WHERE mfc_id = ?");
    List<Object> args = new java.util.ArrayList<>(List.of(mfcId));
    if (day != null) {
      java.time.ZoneId seoul = java.time.ZoneId.of("Asia/Seoul");
      sql.append(" AND created_at >= ? AND created_at < ?");
      args.add(java.sql.Timestamp.from(day.atStartOfDay(seoul).toInstant()));
      args.add(java.sql.Timestamp.from(day.plusDays(1).atStartOfDay(seoul).toInstant()));
    }
    if (states != null && !states.isEmpty()) {
      sql.append(" AND state IN (")
          .append(String.join(",", states.stream().map(x -> "?").toList()))
          .append(")");
      args.addAll(states);
    }
    if (after != null) {
      sql.append(
          " AND (created_at, plan_id) < (SELECT created_at, plan_id FROM assignment_plan"
              + " WHERE plan_id = ?)");
      args.add(after);
    }
    sql.append(" ORDER BY created_at DESC, plan_id DESC LIMIT ?");
    args.add(limit);
    return jdbc.queryForList(sql.toString(), String.class, args.toArray());
  }

  public Optional<PlanRow> find(String planId) {
    return jdbc
        .query("SELECT * FROM assignment_plan WHERE plan_id = ?", (rs, i) -> plan(rs), planId)
        .stream()
        .findFirst();
  }

  public Optional<PlanRow> lock(String planId) {
    return jdbc
        .query(
            "SELECT * FROM assignment_plan WHERE plan_id = ? FOR UPDATE",
            (rs, i) -> plan(rs),
            planId)
        .stream()
        .findFirst();
  }

  public List<ItemRow> items(String planId) {
    return jdbc.query(
        "SELECT * FROM assignment_plan_item WHERE plan_id = ? ORDER BY start_at, task_id",
        (rs, i) ->
            new ItemRow(
                rs.getString("task_id"),
                rs.getString("task_type"),
                rs.getInt("task_version"),
                rs.getString("worker_id"),
                rs.getInt("duty_version"),
                rs.getTimestamp("start_at").toInstant(),
                rs.getTimestamp("end_at").toInstant(),
                rs.getInt("late_minutes")),
        planId);
  }

  public List<UnassignedRow> unassigned(String planId) {
    return jdbc.query(
        "SELECT * FROM assignment_plan_unassigned WHERE plan_id = ? ORDER BY task_id",
        (rs, i) ->
            new UnassignedRow(
                rs.getString("task_id"), rs.getString("task_type"), rs.getString("reason")),
        planId);
  }

  public void markStale(String planId, List<String> reasons) {
    jdbc.update(
        "UPDATE assignment_plan SET state = 'STALE', stale_reasons = ? WHERE plan_id = ?",
        String.join("\n", reasons),
        planId);
  }

  public void markCommitted(String planId, String by, Instant at) {
    jdbc.update(
        "UPDATE assignment_plan SET state = 'COMMITTED', committed_by = ?, committed_at = ?"
            + " WHERE plan_id = ?",
        by,
        Timestamp.from(at),
        planId);
  }

  private static PlanRow plan(ResultSet rs) throws SQLException {
    String stale = rs.getString("stale_reasons");
    String uw = rs.getString("unverified_workers");
    Timestamp ca = rs.getTimestamp("committed_at");
    return new PlanRow(
        rs.getString("plan_id"),
        rs.getString("mfc_id"),
        rs.getString("state"),
        rs.getString("solver_status"),
        rs.getTimestamp("horizon_start").toInstant(),
        rs.getInt("horizon_minutes"),
        rs.getInt("unassigned_count"),
        rs.getInt("late_minutes"),
        rs.getInt("max_load_minutes"),
        rs.getLong("objective_value"),
        rs.getInt("wall_time_ms"),
        stale == null || stale.isEmpty() ? List.of() : Arrays.asList(stale.split("\n")),
        rs.getString("created_by"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getString("committed_by"),
        ca == null ? null : ca.toInstant(),
        rs.getString("settings"),
        uw == null || uw.isEmpty() ? List.of() : Arrays.asList(uw.split("\n")));
  }
}
