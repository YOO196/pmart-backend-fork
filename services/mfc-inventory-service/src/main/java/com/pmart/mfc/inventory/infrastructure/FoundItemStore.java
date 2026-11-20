package com.pmart.mfc.inventory.infrastructure;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 실사 발견품 조사(V28, 0.43.0) — 장부 밖 기록. */
@Repository
public class FoundItemStore {

  public record FoundRow(
      String foundId,
      String mfcId,
      String sessionId,
      String lastSessionId,
      int seenCount,
      String locationCode,
      @Nullable String sku,
      @Nullable String barcode,
      @Nullable LocalDate expiryDate,
      int qty,
      String cause,
      String state,
      @Nullable String outcome,
      @Nullable String outcomeRef,
      @Nullable String lotId,
      @Nullable String note,
      @Nullable String resolvedBy,
      @Nullable Instant resolvedAt,
      Instant createdAt,
      Instant updatedAt,
      int version) {}

  private static final String SELECT =
      "SELECT found_id, mfc_id, session_id, last_session_id, seen_count, location_code, sku, barcode,"
          + " expiry_date, qty, cause, state, outcome, outcome_ref, lot_id, note, resolved_by,"
          + " resolved_at, created_at, updated_at, version FROM count_found_item";

  private final JdbcTemplate jdbc;

  public FoundItemStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 이 실사 줄로 이미 만들었는가(승인 재시도). */
  public boolean recorded(String sessionId, int lineNo) {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM count_found_item WHERE session_id = ? AND line_no = ?",
            Integer.class,
            sessionId,
            lineNo);
    return n != null && n > 0;
  }

  /** 같은 센터·칸·상품 표시(SKU·바코드)·소비기한의 열린 조사 건 — 같은 물건을 다시 본 것으로 묶는다. */
  public Optional<String> lockOpenSame(
      String mfcId,
      String locationCode,
      @Nullable String sku,
      @Nullable String barcode,
      @Nullable LocalDate expiryDate) {
    return jdbc
        .query(
            "SELECT found_id FROM count_found_item WHERE mfc_id = ? AND state = 'OPEN'"
                + " AND location_code = ? AND sku IS NOT DISTINCT FROM ? AND barcode IS NOT DISTINCT FROM ?"
                + " AND expiry_date IS NOT DISTINCT FROM ? ORDER BY found_id LIMIT 1 FOR UPDATE",
            (rs, i) -> rs.getString(1),
            mfcId,
            locationCode,
            sku,
            barcode,
            expiryDate == null ? null : Date.valueOf(expiryDate))
        .stream()
        .findFirst();
  }

  public void insert(FoundRow r, int lineNo) {
    jdbc.update(
        "INSERT INTO count_found_item (found_id, mfc_id, session_id, line_no, last_session_id,"
            + " location_code, sku, barcode, expiry_date, qty, cause, created_at, updated_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
        r.foundId(),
        r.mfcId(),
        r.sessionId(),
        lineNo,
        r.lastSessionId(),
        r.locationCode(),
        r.sku(),
        r.barcode(),
        r.expiryDate() == null ? null : Date.valueOf(r.expiryDate()),
        r.qty(),
        r.cause(),
        Timestamp.from(r.createdAt()),
        Timestamp.from(r.updatedAt()));
  }

  /** 다시 봄 — 마지막 실사·수량(마지막 계수 값)·횟수. 줄 번호 기록은 따로 남기지 않는다(승인은 실사당 한 번). */
  public void seenAgain(String foundId, String sessionId, int qty, String cause, Instant at) {
    jdbc.update(
        "UPDATE count_found_item SET last_session_id = ?, seen_count = seen_count + 1, qty = ?,"
            + " cause = ?, updated_at = ?, version = version + 1 WHERE found_id = ? AND last_session_id <> ?",
        sessionId,
        qty,
        cause,
        Timestamp.from(at),
        foundId,
        sessionId);
  }

  public Optional<FoundRow> find(String foundId, boolean lock) {
    return jdbc
        .query(SELECT + " WHERE found_id = ?" + (lock ? " FOR UPDATE" : ""), this::row, foundId)
        .stream()
        .findFirst();
  }

  public List<FoundRow> list(
      String mfcId, @Nullable String state, int limit, @Nullable String afterId) {
    StringBuilder sql = new StringBuilder(SELECT + " WHERE mfc_id = ?");
    List<Object> args = new ArrayList<>(List.of(mfcId));
    if (state != null) {
      sql.append(" AND state = ?");
      args.add(state);
    }
    if (afterId != null) {
      sql.append(" AND found_id > ?");
      args.add(afterId);
    }
    sql.append(" ORDER BY found_id LIMIT ?");
    args.add(limit);
    return jdbc.query(sql.toString(), this::row, args.toArray());
  }

  public void resolve(
      String foundId,
      String outcome,
      @Nullable String outcomeRef,
      @Nullable String lotId,
      String note,
      String by,
      Instant at) {
    jdbc.update(
        "UPDATE count_found_item SET state = 'RESOLVED', outcome = ?, outcome_ref = ?, lot_id = ?,"
            + " note = ?, resolved_by = ?, resolved_at = ?, updated_at = ?, version = version + 1"
            + " WHERE found_id = ? AND state = 'OPEN'",
        outcome,
        outcomeRef,
        lotId,
        note,
        by,
        Timestamp.from(at),
        Timestamp.from(at),
        foundId);
  }

  private FoundRow row(ResultSet rs, int i) throws SQLException {
    Date exp = rs.getDate("expiry_date");
    Timestamp res = rs.getTimestamp("resolved_at");
    return new FoundRow(
        rs.getString("found_id"),
        rs.getString("mfc_id"),
        rs.getString("session_id"),
        rs.getString("last_session_id"),
        rs.getInt("seen_count"),
        rs.getString("location_code"),
        rs.getString("sku"),
        rs.getString("barcode"),
        exp == null ? null : exp.toLocalDate(),
        rs.getInt("qty"),
        rs.getString("cause"),
        rs.getString("state"),
        rs.getString("outcome"),
        rs.getString("outcome_ref"),
        rs.getString("lot_id"),
        rs.getString("note"),
        rs.getString("resolved_by"),
        res == null ? null : res.toInstant(),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant(),
        rs.getInt("version"));
  }
}
