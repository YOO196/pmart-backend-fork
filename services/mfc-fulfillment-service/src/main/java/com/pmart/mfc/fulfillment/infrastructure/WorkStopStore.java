package com.pmart.mfc.fulfillment.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 주문 단위 작업 중지 기록(JDBC). */
@Repository
public class WorkStopStore {

  /**
   * @param cancelReturn 집품 뒤 중지(V5)의 inventory cancel-returns 요청 본문. null 이면 준비 전(진행 중 실적 대기) 또는 집품
   *     전 중지
   * @param packageVerdicts 포장·출고별 판정 JSON 배열
   */
  public record StopRow(
      String stopRequestId,
      String orderId,
      @Nullable String claimId,
      String scope,
      String requestedLines,
      String state,
      String notStartedLines,
      Instant decidedAt,
      @Nullable String cancelReturn,
      String packageVerdicts,
      @Nullable String lastError,
      @Nullable Instant resolvedAt) {}

  private final JdbcTemplate jdbc;

  public WorkStopStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<StopRow> find(String stopRequestId) {
    return jdbc
        .query(
            "SELECT * FROM order_work_stop WHERE stop_request_id = ?",
            (rs, i) -> row(rs),
            stopRequestId)
        .stream()
        .findFirst();
  }

  public void insert(StopRow r) {
    jdbc.update(
        "INSERT INTO order_work_stop (stop_request_id, order_id, claim_id, scope, requested_lines,"
            + " state, not_started_lines, decided_at, cancel_return, package_verdicts, resolved_at)"
            + " VALUES (?,?,?,?,?::jsonb,?,?::jsonb,?,?::jsonb,?::jsonb,?)",
        r.stopRequestId(),
        r.orderId(),
        r.claimId(),
        r.scope(),
        r.requestedLines(),
        r.state(),
        r.notStartedLines(),
        Timestamp.from(r.decidedAt()),
        r.cancelReturn(),
        r.packageVerdicts(),
        r.resolvedAt() == null ? null : Timestamp.from(r.resolvedAt()));
  }

  /** 준비 전 PENDING 에 복귀 본문·판정을 넣는다(조건부). 반환 = 바뀐 행 수. */
  public int prepared(String stopRequestId, String cancelReturn, String packageVerdicts) {
    return jdbc.update(
        "UPDATE order_work_stop SET cancel_return = ?::jsonb, package_verdicts = ?::jsonb"
            + " WHERE stop_request_id = ? AND state = 'PENDING' AND cancel_return IS NULL",
        cancelReturn,
        packageVerdicts,
        stopRequestId);
  }

  /** PENDING → RESOLVED(조건부). 반환 = 바뀐 행 수. */
  public int resolve(String stopRequestId, Instant at) {
    return jdbc.update(
        "UPDATE order_work_stop SET state = 'RESOLVED', resolved_at = ?, last_error = NULL"
            + " WHERE stop_request_id = ? AND state = 'PENDING'",
        Timestamp.from(at),
        stopRequestId);
  }

  /** 실패로 멈춤 — 사유와 실패 시각(V14, 처리 대기 목록 정렬). */
  public void recordError(String stopRequestId, String error, Instant at) {
    jdbc.update(
        "UPDATE order_work_stop SET last_error = ?, failed_at = ? WHERE stop_request_id = ?"
            + " AND state = 'PENDING'",
        error.length() > 300 ? error.substring(0, 300) : error,
        Timestamp.from(at),
        stopRequestId);
  }

  /**
   * 주문에 살아 있는 중지가 있는가 — PENDING, 또는 RESOLVED 중 인계된 포장 때문에 거절한 것(packages 에 HANDED_OVER)이 아닌 것. 있으면
   * 인계 허가를 막는다.
   */
  public boolean orderStopped(String orderId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM order_work_stop WHERE order_id = ? AND (state = 'PENDING'"
                + " OR NOT package_verdicts @> '[{\"verdict\": \"HANDED_OVER\"}]'::jsonb)",
            Integer.class,
            orderId);
    return n != null && n > 0;
  }

  /** 대기 중 판정만 갱신(인계 진행으로 기다리는 포장 표시). */
  public void pendingVerdicts(String stopRequestId, String packageVerdicts) {
    jdbc.update(
        "UPDATE order_work_stop SET package_verdicts = ?::jsonb WHERE stop_request_id = ?"
            + " AND state = 'PENDING' AND cancel_return IS NULL",
        packageVerdicts,
        stopRequestId);
  }

  /** 인계된 포장이 있어 전체 중지를 거절(작업 그대로): RESOLVED + 판정. 반환 = 바뀐 행 수. */
  public int refuse(String stopRequestId, String packageVerdicts, Instant at) {
    return jdbc.update(
        "UPDATE order_work_stop SET state = 'RESOLVED', package_verdicts = ?::jsonb, resolved_at = ?,"
            + " last_error = NULL WHERE stop_request_id = ? AND state = 'PENDING'",
        packageVerdicts,
        Timestamp.from(at),
        stopRequestId);
  }

  /** 사유를 지운다(운영자 재시도). 반환 = 바뀐 행 수. */
  public int clearError(String stopRequestId) {
    return jdbc.update(
        "UPDATE order_work_stop SET last_error = NULL WHERE stop_request_id = ? AND state = 'PENDING'",
        stopRequestId);
  }

  /** 오래된 순 PENDING 중지 ID(sweep). 실패로 멈춘 것(last_error)은 빼다. */
  public List<String> pending(int limit) {
    return jdbc.queryForList(
        "SELECT stop_request_id FROM order_work_stop WHERE state = 'PENDING' AND last_error IS NULL"
            + " ORDER BY decided_at LIMIT ?",
        String.class,
        limit);
  }

  /** 주문의 작업 전 중지 수량 합(orderLineId → 수량). 작업 접수가 이 수량을 빼고 받는다. */
  public Map<String, Integer> stoppedByLine(String orderId) {
    Map<String, Integer> out = new LinkedHashMap<>();
    jdbc.query(
        "SELECT l->>'orderLineId' AS line, SUM((l->>'qty')::int) AS qty FROM order_work_stop w,"
            + " jsonb_array_elements(w.not_started_lines) l WHERE w.order_id = ? GROUP BY 1",
        rs -> {
          out.put(rs.getString("line"), rs.getInt("qty"));
        },
        orderId);
    return out;
  }

  private static StopRow row(ResultSet rs) throws SQLException {
    Timestamp resolved = rs.getTimestamp("resolved_at");
    return new StopRow(
        rs.getString("stop_request_id"),
        rs.getString("order_id"),
        rs.getString("claim_id"),
        rs.getString("scope"),
        rs.getString("requested_lines"),
        rs.getString("state"),
        rs.getString("not_started_lines"),
        rs.getTimestamp("decided_at").toInstant(),
        rs.getString("cancel_return"),
        rs.getString("package_verdicts"),
        rs.getString("last_error"),
        resolved == null ? null : resolved.toInstant());
  }
}
