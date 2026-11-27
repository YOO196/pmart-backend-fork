package com.pmart.mfc.capacity.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 작업 배정 저장(JDBC). 작업마다 활성 1건(부분 유일 인덱스). */
@Repository
public class AssignmentStore {

  private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

  public record AssignmentRow(
      String assignmentId,
      String mfcId,
      String taskId,
      String taskType,
      String workerId,
      int assignmentVersion,
      String state,
      @Nullable String planId,
      @Nullable String assignedBy,
      @Nullable String reason,
      Instant assignedAt,
      @Nullable String temperatureZone) {}

  private final JdbcTemplate jdbc;

  public AssignmentStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** (0.36.0) 작업의 활성 배정(잠그지 않음 — 트랜잭션 밖 사전 확인용). */
  public Optional<AssignmentRow> active(String taskId) {
    return jdbc
        .query(
            "SELECT * FROM task_assignment WHERE task_id = ? AND state = 'ACTIVE'",
            (rs, i) -> row(rs),
            taskId)
        .stream()
        .findFirst();
  }

  /** 작업의 활성 배정을 잠근다(없으면 empty). 같은 작업의 동시 배정은 이 잠금·유일 인덱스로 하나만. */
  public Optional<AssignmentRow> lockActive(String taskId) {
    return jdbc
        .query(
            "SELECT * FROM task_assignment WHERE task_id = ? AND state = 'ACTIVE' FOR UPDATE",
            (rs, i) -> row(rs),
            taskId)
        .stream()
        .findFirst();
  }

  public int maxVersion(String taskId) {
    Integer v =
        jdbc.queryForObject(
            "SELECT COALESCE(MAX(GREATEST(assignment_version, COALESCE(release_version, 0))), 0)"
                + " FROM task_assignment WHERE task_id = ?",
            Integer.class,
            taskId);
    return v == null ? 0 : v;
  }

  /** 재배정 — ACTIVE → SUPERSEDED, 끝(누가·왜 — 0.31.0 배정 이력). */
  public void supersede(String assignmentId, Instant at, String endedBy, String endReason) {
    jdbc.update(
        "UPDATE task_assignment SET state = 'SUPERSEDED', ended_at = ?, ended_by = ?, end_reason = ?"
            + " WHERE assignment_id = ?",
        Timestamp.from(at),
        endedBy,
        endReason,
        assignmentId);
  }

  /** 작업 내놓기·막힘 해제·다음 사람에게 넘김 — ACTIVE → RELEASED, 끝(누가·왜 — 0.31.0). */
  public void release(String assignmentId, Instant at, String endedBy, @Nullable String endReason) {
    jdbc.update(
        "UPDATE task_assignment SET state = 'RELEASED', ended_at = ?, ended_by = ?, end_reason = ?"
            + " WHERE assignment_id = ? AND state = 'ACTIVE'",
        Timestamp.from(at),
        endedBy,
        endReason,
        assignmentId);
  }

  /** (0.36.0) 센터 관리자 배정 풀기 — RELEASED + 해제 버전(AssignmentReleased). 바뀐 행 수. */
  public int releaseWithVersion(
      String assignmentId, Instant at, String endedBy, String endReason, int releaseVersion) {
    return jdbc.update(
        "UPDATE task_assignment SET state = 'RELEASED', ended_at = ?, ended_by = ?, end_reason = ?,"
            + " release_version = ? WHERE assignment_id = ? AND state = 'ACTIVE'",
        Timestamp.from(at),
        endedBy,
        endReason,
        releaseVersion,
        assignmentId);
  }

  /** 배정 이력 한 행(0.31.0). */
  public record HistoryRow(
      AssignmentRow assignment,
      @Nullable Instant endedAt,
      @Nullable String endedBy,
      @Nullable String endReason) {}

  /** 한 작업의 배정 행 전부(버전 순). */
  public List<HistoryRow> history(String mfcId, String taskId) {
    return jdbc.query(
        "SELECT * FROM task_assignment WHERE mfc_id = ? AND task_id = ? ORDER BY assignment_version",
        (rs, i) -> {
          Timestamp ended = rs.getTimestamp("ended_at");
          return new HistoryRow(
              row(rs),
              ended == null ? null : ended.toInstant(),
              rs.getString("ended_by"),
              rs.getString("end_reason"));
        },
        mfcId,
        taskId);
  }

  public void insert(AssignmentRow r) {
    jdbc.update(
        "INSERT INTO task_assignment (assignment_id, mfc_id, task_id, task_type, worker_id,"
            + " assignment_version, state, plan_id, assigned_by, reason, assigned_at, temperature_zone)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
        r.assignmentId(),
        r.mfcId(),
        r.taskId(),
        r.taskType(),
        r.workerId(),
        r.assignmentVersion(),
        r.state(),
        r.planId(),
        r.assignedBy(),
        r.reason(),
        Timestamp.from(r.assignedAt()),
        r.temperatureZone());
  }

  public List<AssignmentRow> list(
      String mfcId,
      @Nullable String taskId,
      @Nullable String workerId,
      String state,
      @Nullable LocalDate date) {
    StringBuilder sql =
        new StringBuilder("SELECT * FROM task_assignment WHERE mfc_id = ? AND state = ?");
    List<Object> args = new ArrayList<>(List.of(mfcId, state));
    if (taskId != null) {
      sql.append(" AND task_id = ?");
      args.add(taskId);
    }
    if (workerId != null) {
      sql.append(" AND worker_id = ?");
      args.add(workerId);
    }
    if (date != null) {
      sql.append(" AND assigned_at >= ? AND assigned_at < ?");
      args.add(Timestamp.from(date.atStartOfDay(SEOUL).toInstant()));
      args.add(Timestamp.from(date.plusDays(1).atStartOfDay(SEOUL).toInstant()));
    }
    sql.append(" ORDER BY assigned_at, task_id LIMIT 500");
    return jdbc.query(sql.toString(), (rs, i) -> row(rs), args.toArray());
  }

  private static AssignmentRow row(ResultSet rs) throws SQLException {
    return new AssignmentRow(
        rs.getString("assignment_id"),
        rs.getString("mfc_id"),
        rs.getString("task_id"),
        rs.getString("task_type"),
        rs.getString("worker_id"),
        rs.getInt("assignment_version"),
        rs.getString("state"),
        rs.getString("plan_id"),
        rs.getString("assigned_by"),
        rs.getString("reason"),
        rs.getTimestamp("assigned_at").toInstant(),
        rs.getString("temperature_zone"));
  }
}
