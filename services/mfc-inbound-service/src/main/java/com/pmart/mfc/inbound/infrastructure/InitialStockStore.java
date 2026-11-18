package com.pmart.mfc.inbound.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 초기 보유 반입 기록 저장(JDBC). 전기 대기(PENDING) 선점은 {@code FOR UPDATE SKIP LOCKED} + lease(next_attempt_at
 * 미룸)라서 여러 인스턴스가 같은 기록을 동시에 보내지 않는다(보내더라도 inventory 가 receiptId 로 막는다).
 */
@Repository
public class InitialStockStore {

  /** 반입 기록. lines 는 SKU 오름차순. */
  public record LoadRow(
      String receiptId,
      String approvalId,
      @Nullable String mfcId,
      String state,
      @Nullable String rejectReason,
      @Nullable String commitId,
      int attempts,
      @Nullable String lastError,
      Instant createdAt,
      @Nullable Instant appliedAt,
      Map<String, Integer> lines,
      @Nullable String receivingLocation,
      @Nullable Instant receivedAt,
      Map<String, Integer> received) {}

  private static final String COLUMNS =
      "receipt_id, approval_id, mfc_id, state, reject_reason, inventory_commit_id, attempts,"
          + " last_error, created_at, applied_at, receiving_location, received_at";

  private final JdbcTemplate jdbc;

  public InitialStockStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public boolean existsForApproval(String approvalId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM initial_stock_load WHERE approval_id = ?",
            Integer.class,
            approvalId);
    return n != null && n > 0;
  }

  /** EXPECTED(입고 예정) 또는 REJECTED(승인 내용 오류) 기록. */
  public void insert(
      String receiptId,
      String approvalId,
      @Nullable String mfcId,
      String state,
      @Nullable String rejectReason,
      @Nullable String approverId,
      Instant at,
      Map<String, Integer> lines) {
    jdbc.update(
        "INSERT INTO initial_stock_load (receipt_id, approval_id, mfc_id, state, reject_reason,"
            + " next_attempt_at, approver_id, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?)",
        receiptId,
        approvalId,
        mfcId,
        state,
        rejectReason,
        "PENDING".equals(state) ? Timestamp.from(at) : null,
        approverId,
        Timestamp.from(at),
        Timestamp.from(at));
    lines.forEach(
        (sku, qty) ->
            jdbc.update(
                "INSERT INTO initial_stock_load_line (receipt_id, sku, qty) VALUES (?,?,?)",
                receiptId,
                sku,
                qty));
  }

  /**
   * 전기할 PENDING 기록을 선점한다: 잠근 뒤 시도 횟수 +1, 다음 시도 시각을 lease 만큼 미룬다. 호출자 트랜잭션 안에서.
   *
   * @return 선점한 receiptId
   */
  public List<String> claimDue(Instant now, Instant leaseUntil, int limit) {
    List<String> ids =
        jdbc.query(
            "SELECT receipt_id FROM initial_stock_load WHERE state = 'PENDING' AND next_attempt_at <= ?"
                + " ORDER BY next_attempt_at LIMIT ? FOR UPDATE SKIP LOCKED",
            (rs, i) -> rs.getString(1),
            Timestamp.from(now),
            limit);
    for (String id : ids) {
      jdbc.update(
          "UPDATE initial_stock_load SET attempts = attempts + 1, next_attempt_at = ?, updated_at = ?"
              + " WHERE receipt_id = ?",
          Timestamp.from(leaseUntil),
          Timestamp.from(now),
          id);
    }
    return ids;
  }

  public void markApplied(String receiptId, String commitId, Instant at) {
    jdbc.update(
        "UPDATE initial_stock_load SET state = 'APPLIED', inventory_commit_id = ?, applied_at = ?,"
            + " next_attempt_at = NULL, last_error = NULL, updated_at = ?"
            + " WHERE receipt_id = ? AND state = 'PENDING'",
        commitId,
        Timestamp.from(at),
        Timestamp.from(at),
        receiptId);
  }

  public void markRejected(String receiptId, String reason, Instant at) {
    jdbc.update(
        "UPDATE initial_stock_load SET state = 'REJECTED', reject_reason = ?, next_attempt_at = NULL,"
            + " updated_at = ? WHERE receipt_id = ? AND state = 'PENDING'",
        truncate(reason, 1000),
        Timestamp.from(at),
        receiptId);
  }

  public void markRetry(String receiptId, String error, Instant nextAttemptAt, Instant at) {
    jdbc.update(
        "UPDATE initial_stock_load SET last_error = ?, next_attempt_at = ?, updated_at = ?"
            + " WHERE receipt_id = ? AND state = 'PENDING'",
        truncate(error, 500),
        Timestamp.from(nextAttemptAt),
        Timestamp.from(at),
        receiptId);
  }

  public Optional<LoadRow> find(String receiptId) {
    return jdbc
        .query(
            "SELECT " + COLUMNS + " FROM initial_stock_load WHERE receipt_id = ?",
            (rs, i) -> row(rs),
            receiptId)
        .stream()
        .findFirst();
  }

  /** 받은 순서대로(MB-1 목록 — ID 는 InboundListStore 가 고른다). */
  public List<LoadRow> byReceiptIds(List<String> receiptIds) {
    if (receiptIds.isEmpty()) {
      return List.of();
    }
    java.util.Map<String, LoadRow> by = new java.util.HashMap<>();
    for (String id : receiptIds) {
      jdbc.query(
              "SELECT " + COLUMNS + " FROM initial_stock_load WHERE receipt_id = ?",
              (rs, i) -> row(rs),
              id)
          .forEach(r -> by.put(id, r));
    }
    return receiptIds.stream().map(by::get).filter(java.util.Objects::nonNull).toList();
  }

  public List<LoadRow> byApproval(String approvalId) {
    return jdbc.query(
        "SELECT " + COLUMNS + " FROM initial_stock_load WHERE approval_id = ? ORDER BY mfc_id",
        (rs, i) -> row(rs),
        approvalId);
  }

  private LoadRow row(ResultSet rs) throws SQLException {
    String receiptId = rs.getString("receipt_id");
    Timestamp applied = rs.getTimestamp("applied_at");
    return new LoadRow(
        receiptId,
        rs.getString("approval_id"),
        rs.getString("mfc_id"),
        rs.getString("state"),
        rs.getString("reject_reason"),
        rs.getString("inventory_commit_id"),
        rs.getInt("attempts"),
        rs.getString("last_error"),
        rs.getTimestamp("created_at").toInstant(),
        applied == null ? null : applied.toInstant(),
        lines(receiptId, "qty"),
        rs.getString("receiving_location"),
        rs.getTimestamp("received_at") == null ? null : rs.getTimestamp("received_at").toInstant(),
        lines(receiptId, "received_qty"));
  }

  /** SKU → 수량 열(승인 qty 또는 수령 received_qty). received_qty 가 없는 줄은 빠진다. */
  private Map<String, Integer> lines(String receiptId, String column) {
    Map<String, Integer> out = new TreeMap<>();
    jdbc.query(
        "SELECT sku, "
            + column
            + " AS q FROM initial_stock_load_line WHERE receipt_id = ?"
            + " AND "
            + column
            + " IS NOT NULL ORDER BY sku",
        rs -> {
          out.put(rs.getString("sku"), rs.getInt("q"));
        },
        receiptId);
    return out;
  }

  /** 입고 예정 기록을 잠근다. */
  public Optional<String> lockState(String receiptId) {
    return jdbc
        .query(
            "SELECT state FROM initial_stock_load WHERE receipt_id = ? FOR UPDATE",
            (rs, i) -> rs.getString(1),
            receiptId)
        .stream()
        .findFirst();
  }

  /** 현장 수령: EXPECTED → PENDING(곧 전기). 승인 줄 중 빠진 SKU 는 0. */
  public void markReceived(
      String receiptId,
      String location,
      String receivedBy,
      Map<String, Integer> received,
      Instant at) {
    jdbc.update(
        "UPDATE initial_stock_load_line SET received_qty = 0 WHERE receipt_id = ?", receiptId);
    received.forEach(
        (sku, q) ->
            jdbc.update(
                "UPDATE initial_stock_load_line SET received_qty = ? WHERE receipt_id = ? AND sku = ?",
                q,
                receiptId,
                sku));
    jdbc.update(
        "UPDATE initial_stock_load SET state = 'PENDING', receiving_location = ?, received_by = ?,"
            + " received_at = ?, next_attempt_at = ?, updated_at = ? WHERE receipt_id = ?"
            + " AND state = 'EXPECTED'",
        location,
        receivedBy,
        Timestamp.from(at),
        Timestamp.from(at),
        Timestamp.from(at),
        receiptId);
  }

  private static String truncate(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max);
  }
}
