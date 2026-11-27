package com.pmart.mfc.inbound.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** CDC 이관 입고 종결(MI-5, V15 transit_closure) — TRF 하나에 한 줄, CDC 알림 재시도 상태. */
@Repository
public class TransitClosureStore {

  /** 종결 한 줄. */
  public record Closure(
      String trfId,
      String mfcId,
      @Nullable String fromCdc,
      @Nullable String departureId,
      String kind,
      Instant closedAt,
      String closedBy,
      @Nullable String reason,
      @Nullable Instant notifiedAt,
      @Nullable Integer unreconciledQty,
      int attempts,
      @Nullable String lastError,
      @Nullable String cdcCancelRequestId) {}

  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate named;

  public TransitClosureStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
    this.named = new NamedParameterJdbcTemplate(jdbc);
  }

  /**
   * 처음이면 넣고 true — 이미 종결이면 false(그대로). (0.26.0) departureId 가 없으면(출발 없는 예정) CDC 에 알리지 않으므로
   * next_try_at 을 비운다.
   */
  public boolean insert(
      String trfId,
      String mfcId,
      @Nullable String fromCdc,
      @Nullable String departureId,
      String kind,
      Instant closedAt,
      String closedBy,
      @Nullable String reason) {
    return jdbc.update(
            "INSERT INTO transit_closure (trf_id, mfc_id, from_cdc, departure_id, kind, closed_at,"
                + " closed_by, reason, next_try_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT"
                + " (trf_id) DO NOTHING",
            trfId,
            mfcId,
            fromCdc,
            departureId,
            kind,
            Timestamp.from(closedAt),
            closedBy,
            reason,
            departureId == null ? null : Timestamp.from(closedAt))
        == 1;
  }

  /** (0.27.0, CO-3) DC 미출발 취소 요청 키를 종결에 남긴다. */
  public void setCdcCancelRequest(String trfId, String requestId) {
    jdbc.update(
        "UPDATE transit_closure SET cdc_cancel_request_id = ? WHERE trf_id = ?", requestId, trfId);
  }

  /** (0.27.0, CO-3) 이 TRF 가 공급 요청 이관이면 그 공급 요청 ID(ADR-0091 supply_request_transfer). */
  public Optional<String> supplyRequestOf(String trfId) {
    return jdbc
        .queryForList(
            "SELECT supply_request_id FROM supply_request_transfer WHERE trf_id = ?",
            String.class,
            trfId)
        .stream()
        .findFirst();
  }

  /** (0.27.0, CO-3) 아직 DC 에 못 보낸 이관을 멈춘다(종결된 예정 — 보내지 않음). */
  public void stopUnsentTransfer(String trfId, String why) {
    jdbc.update(
        "UPDATE supply_request_transfer SET next_try_at = NULL, error = ? WHERE trf_id = ? AND"
            + " sent_at IS NULL",
        why,
        trfId);
  }

  public Optional<Closure> find(String trfId) {
    return jdbc.query("SELECT * FROM transit_closure WHERE trf_id = ?", this::row, trfId).stream()
        .findFirst();
  }

  public List<Closure> findAll(Collection<String> trfIds) {
    if (trfIds.isEmpty()) {
      return List.of();
    }
    return named.query(
        "SELECT * FROM transit_closure WHERE trf_id IN (:ids)",
        new MapSqlParameterSource("ids", trfIds),
        this::row);
  }

  /** CDC 에 아직 알리지 못했고 다시 보낼 때가 된 것. */
  public List<String> due(Instant now, int limit) {
    return jdbc.queryForList(
        "SELECT trf_id FROM transit_closure WHERE notified_at IS NULL AND next_try_at <= ? ORDER BY"
            + " next_try_at LIMIT ?",
        String.class,
        Timestamp.from(now),
        limit);
  }

  public void notified(String trfId, @Nullable Integer unreconciled, Instant now) {
    jdbc.update(
        "UPDATE transit_closure SET notified_at = ?, unreconciled_qty = ?, attempts = attempts + 1,"
            + " last_error = NULL, next_try_at = NULL WHERE trf_id = ? AND notified_at IS NULL",
        Timestamp.from(now),
        unreconciled,
        trfId);
  }

  /** 실패 — nextTry 가 null 이면 다시 보내지 않는다(CDC 가 내용으로 거절). */
  public void failed(String trfId, String error, @Nullable Instant nextTry) {
    jdbc.update(
        "UPDATE transit_closure SET attempts = attempts + 1, last_error = ?, next_try_at = ? WHERE"
            + " trf_id = ? AND notified_at IS NULL",
        error.length() > 1000 ? error.substring(0, 1000) : error,
        nextTry == null ? null : Timestamp.from(nextTry),
        trfId);
  }

  private Closure row(ResultSet rs, int i) throws SQLException {
    Timestamp n = rs.getTimestamp("notified_at");
    int u = rs.getInt("unreconciled_qty");
    Integer unreconciled = rs.wasNull() ? null : u;
    return new Closure(
        rs.getString("trf_id"),
        rs.getString("mfc_id"),
        rs.getString("from_cdc"),
        rs.getString("departure_id"),
        rs.getString("kind"),
        rs.getTimestamp("closed_at").toInstant(),
        rs.getString("closed_by"),
        rs.getString("reason"),
        n == null ? null : n.toInstant(),
        unreconciled,
        rs.getInt("attempts"),
        rs.getString("last_error"),
        rs.getString("cdc_cancel_request_id"));
  }
}
