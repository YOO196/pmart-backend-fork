package com.pmart.mfc.capacity.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 작업자 휴게(작업자 쪽 3단계, V10) + 센터 휴게 번호·자동 휴게(V17, 2026-11-18). */
@Repository
public class BreakStore {

  public static final String MANUAL = "MANUAL";
  public static final String AUTO = "AUTO";
  public static final String SYSTEM = "SYSTEM";

  public record Break(
      String breakId,
      String workerId,
      Instant startedAt,
      String startedBy,
      @Nullable Instant endedAt,
      String source,
      @Nullable String shiftId,
      @Nullable Instant plannedEndAt) {}

  public record Pin(String mfcId, String pinHash, Instant updatedAt, String updatedBy) {}

  public record Failure(int failedCount, @Nullable Instant lockedUntil) {}

  private final JdbcTemplate jdbc;

  public BreakStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Break> open(String mfcId, String workerId) {
    return jdbc
        .query(
            "SELECT * FROM mfc_worker_break WHERE mfc_id = ? AND worker_id = ? AND ended_at IS NULL",
            (rs, i) -> row(rs),
            mfcId,
            workerId)
        .stream()
        .findFirst();
  }

  public Optional<Break> last(String mfcId, String workerId) {
    return jdbc
        .query(
            "SELECT * FROM mfc_worker_break WHERE mfc_id = ? AND worker_id = ? ORDER BY started_at DESC LIMIT 1",
            (rs, i) -> row(rs),
            mfcId,
            workerId)
        .stream()
        .findFirst();
  }

  /** 없으면 시작(이미 휴게 중이면 그대로). */
  public Break start(String mfcId, String workerId, String by, Instant at) {
    jdbc.update(
        "INSERT INTO mfc_worker_break (break_id, mfc_id, worker_id, started_at, started_by)"
            + " VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING",
        "BRK-" + UUID.randomUUID(),
        mfcId,
        workerId,
        Timestamp.from(at),
        by);
    return open(mfcId, workerId).orElseThrow();
  }

  public void end(String breakId, String by, Instant at) {
    jdbc.update(
        "UPDATE mfc_worker_break SET ended_at = ?, ended_by = ? WHERE break_id = ? AND ended_at IS NULL",
        Timestamp.from(at),
        by,
        breakId);
  }

  // ---------- 자동 휴게(근무 휴게 구간) ----------

  /**
   * 휴게 구간에 들어선 근무마다 자동 휴게를 연다 — 그 근무의 자동 휴게가 이미 있었거나(관리자가 일찍 끝낸 것 포함) 작업자가 이미 휴게 중이면 건너뛴다(부분 유일 색인
   * 두 개가 겹침도 막는다). 반환 = 연 수.
   */
  public int startDueAutoBreaks(Instant now) {
    return jdbc.update(
        "INSERT INTO mfc_worker_break (break_id, mfc_id, worker_id, started_at, started_by, source,"
            + " shift_id, planned_end_at)"
            + " SELECT 'BRK-' || gen_random_uuid(), s.mfc_id, s.worker_id, ?, ?, 'AUTO', s.shift_id,"
            + " s.break_end_at FROM mfc_worker_shift s"
            + " WHERE s.state = 'PLANNED' AND s.break_start_at <= ? AND s.break_end_at > ?"
            + " AND NOT EXISTS (SELECT 1 FROM mfc_worker_break b WHERE b.source = 'AUTO'"
            + " AND b.shift_id = s.shift_id)"
            + " AND NOT EXISTS (SELECT 1 FROM mfc_worker_break b WHERE b.mfc_id = s.mfc_id"
            + " AND b.worker_id = s.worker_id AND b.ended_at IS NULL)"
            + " ON CONFLICT DO NOTHING",
        Timestamp.from(now),
        SYSTEM,
        Timestamp.from(now),
        Timestamp.from(now));
  }

  /** 끝 시각이 지난 자동 휴게를 닫는다(근무가 취소됐으면 바로). 반환 = 닫은 수. */
  public int endDueAutoBreaks(Instant now) {
    return jdbc.update(
        "UPDATE mfc_worker_break b SET ended_at = ?, ended_by = ? WHERE b.source = 'AUTO'"
            + " AND b.ended_at IS NULL AND (b.planned_end_at <= ? OR EXISTS (SELECT 1 FROM"
            + " mfc_worker_shift s WHERE s.shift_id = b.shift_id AND s.state = 'CANCELLED'))",
        Timestamp.from(now),
        SYSTEM,
        Timestamp.from(now));
  }

  // ---------- 센터 휴게 번호 ----------

  public Optional<Pin> pin(String mfcId) {
    return jdbc
        .query(
            "SELECT * FROM mfc_break_pin WHERE mfc_id = ?",
            (rs, i) ->
                new Pin(
                    rs.getString("mfc_id"),
                    rs.getString("pin_hash"),
                    rs.getTimestamp("updated_at").toInstant(),
                    rs.getString("updated_by")),
            mfcId)
        .stream()
        .findFirst();
  }

  /** 번호를 정한다(바꾸면 그 센터 틀린 횟수·잠금을 모두 지운다). */
  public void setPin(String mfcId, String pinHash, String by, Instant at) {
    jdbc.update(
        "INSERT INTO mfc_break_pin (mfc_id, pin_hash, updated_at, updated_by) VALUES (?,?,?,?)"
            + " ON CONFLICT (mfc_id) DO UPDATE SET pin_hash = EXCLUDED.pin_hash,"
            + " updated_at = EXCLUDED.updated_at, updated_by = EXCLUDED.updated_by",
        mfcId,
        pinHash,
        Timestamp.from(at),
        by);
    jdbc.update("DELETE FROM mfc_break_pin_failure WHERE mfc_id = ?", mfcId);
  }

  /** 그 작업자의 틀린 기록(잠금) — 행 잠금. 없으면 0. */
  public Failure lockFailure(String mfcId, String workerId) {
    jdbc.update(
        "INSERT INTO mfc_break_pin_failure (mfc_id, worker_id, failed_count, updated_at)"
            + " VALUES (?,?,0,now()) ON CONFLICT DO NOTHING",
        mfcId,
        workerId);
    return jdbc.queryForObject(
        "SELECT failed_count, locked_until FROM mfc_break_pin_failure"
            + " WHERE mfc_id = ? AND worker_id = ? FOR UPDATE",
        (rs, i) -> {
          Timestamp t = rs.getTimestamp("locked_until");
          return new Failure(rs.getInt("failed_count"), t == null ? null : t.toInstant());
        },
        mfcId,
        workerId);
  }

  public void saveFailure(
      String mfcId, String workerId, int failedCount, @Nullable Instant lockedUntil, Instant at) {
    jdbc.update(
        "UPDATE mfc_break_pin_failure SET failed_count = ?, locked_until = ?, updated_at = ?"
            + " WHERE mfc_id = ? AND worker_id = ?",
        failedCount,
        lockedUntil == null ? null : Timestamp.from(lockedUntil),
        Timestamp.from(at),
        mfcId,
        workerId);
  }

  public void clearFailure(String mfcId, String workerId) {
    jdbc.update(
        "DELETE FROM mfc_break_pin_failure WHERE mfc_id = ? AND worker_id = ?", mfcId, workerId);
  }

  private static Break row(java.sql.ResultSet rs) throws java.sql.SQLException {
    Timestamp e = rs.getTimestamp("ended_at");
    Timestamp p = rs.getTimestamp("planned_end_at");
    return new Break(
        rs.getString("break_id"),
        rs.getString("worker_id"),
        rs.getTimestamp("started_at").toInstant(),
        rs.getString("started_by"),
        e == null ? null : e.toInstant(),
        rs.getString("source"),
        rs.getString("shift_id"),
        p == null ? null : p.toInstant());
  }
}
