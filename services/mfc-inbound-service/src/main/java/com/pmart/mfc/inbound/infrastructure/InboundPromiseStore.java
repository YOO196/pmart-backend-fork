package com.pmart.mfc.inbound.infrastructure;

import com.pmart.mfc.inbound.domain.InboundPromiseRules.ApprovedLine;
import com.pmart.mfc.inbound.domain.InboundPromiseRules.Discrepancy;
import com.pmart.mfc.inbound.domain.InboundPromiseRules.ReceiptLine;
import com.pmart.mfc.inbound.domain.InboundPromiseRules.ShipLine;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** 입고 예정·수령 저장(JDBC, V12). 변경은 {@link #lock} 으로 잠근 행을 고쳐 {@link #save} 한다. */
@Repository
public class InboundPromiseStore {

  /** 입고 예정 투영(한 트랜잭션 안에서 읽고 고쳐 저장하는 변경 가능 사본). */
  public static final class Promise {
    public String trfId = "";
    public String mfcId = "";
    public @Nullable String fromCdc;
    public @Nullable Long trfVersion;
    public String state = "EXPECTED";
    public int version;
    public @Nullable String deadlineMeaning;
    public @Nullable Instant dueAt;
    public @Nullable String priority;
    public List<ApprovedLine> approvedLines = List.of();
    public @Nullable String departureId;
    public @Nullable String departureCommitId;
    public @Nullable List<ShipLine> departedLines;
    public @Nullable Instant departedAt;
    public @Nullable String tripId;
    public @Nullable String manifestId;
    public @Nullable Long manifestVersion;
    public @Nullable List<ShipLine> manifestLines;
    public @Nullable Instant arrivedAt;
    public @Nullable String arrivalTripId;
    public @Nullable String unloadingBayReservationId;
    public @Nullable String arrivalEvidenceRef;
    public @Nullable String arrivalRecordedBy;
    public @Nullable String cancelReason;
    public @Nullable Instant cancelledAt;

    /** (V17, R-52) TRF·Wave 화면 번호 사본 — 표시용. */
    public @Nullable String trfNumber;

    public @Nullable String waveId;
    public @Nullable String waveNumber;
    public @Nullable String source;
    public Map<String, String> sources = new LinkedHashMap<>();
    public Instant createdAt = Instant.EPOCH;
    public Instant updatedAt = Instant.EPOCH;
  }

  /** 수령(TIN). */
  public record ReceiptRow(
      String receiptId,
      String trfId,
      String mfcId,
      String departureId,
      List<ReceiptLine> lines,
      List<Discrepancy> discrepancies,
      String state,
      @Nullable String commitId,
      @Nullable String rejectReason,
      @Nullable String lastError,
      @Nullable String evidenceRef,
      String receivedBy,
      Instant receivedAt,
      @Nullable Instant postedAt) {}

  private static final TypeReference<List<ApprovedLine>> APPROVED = new TypeReference<>() {};
  private static final TypeReference<List<ShipLine>> SHIP = new TypeReference<>() {};
  private static final TypeReference<List<ReceiptLine>> RECEIPT = new TypeReference<>() {};
  private static final TypeReference<List<Discrepancy>> DISCREPANCY = new TypeReference<>() {};
  private static final TypeReference<Map<String, String>> SOURCES = new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate named;
  private final ObjectMapper mapper;

  public InboundPromiseStore(
      JdbcTemplate jdbc, NamedParameterJdbcTemplate named, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.named = named;
    this.mapper = mapper;
  }

  public Optional<Promise> find(String trfId) {
    return jdbc
        .query("SELECT * FROM inbound_promise WHERE trf_id = ?", this::promise, trfId)
        .stream()
        .findFirst();
  }

  public Optional<Promise> lock(String trfId) {
    return jdbc
        .query("SELECT * FROM inbound_promise WHERE trf_id = ? FOR UPDATE", this::promise, trfId)
        .stream()
        .findFirst();
  }

  /** 새 행이면 넣고, 있으면 모든 칸을 바꾼다. */
  public void save(Promise p) {
    Instant sortAt = p.dueAt != null ? p.dueAt : p.departedAt != null ? p.departedAt : p.createdAt;
    MapSqlParameterSource m =
        new MapSqlParameterSource()
            .addValue("trf", p.trfId)
            .addValue("mfc", p.mfcId)
            .addValue("fromCdc", p.fromCdc)
            .addValue("trfVersion", p.trfVersion)
            .addValue("state", p.state)
            .addValue("version", p.version)
            .addValue("deadline", p.deadlineMeaning)
            .addValue("dueAt", ts(p.dueAt))
            .addValue("priority", p.priority)
            .addValue("approved", mapper.writeValueAsString(p.approvedLines))
            .addValue("departureId", p.departureId)
            .addValue("departureCommit", p.departureCommitId)
            .addValue(
                "departed",
                p.departedLines == null ? null : mapper.writeValueAsString(p.departedLines))
            .addValue("departedAt", ts(p.departedAt))
            .addValue("tripId", p.tripId)
            .addValue("manifestId", p.manifestId)
            .addValue("manifestVersion", p.manifestVersion)
            .addValue(
                "manifest",
                p.manifestLines == null ? null : mapper.writeValueAsString(p.manifestLines))
            .addValue("arrivedAt", ts(p.arrivedAt))
            .addValue("arrivalTrip", p.arrivalTripId)
            .addValue("bay", p.unloadingBayReservationId)
            .addValue("evidence", p.arrivalEvidenceRef)
            .addValue("recordedBy", p.arrivalRecordedBy)
            .addValue("cancelReason", p.cancelReason)
            .addValue("cancelledAt", ts(p.cancelledAt))
            .addValue("trfNumber", p.trfNumber)
            .addValue("waveId", p.waveId)
            .addValue("waveNumber", p.waveNumber)
            .addValue("source", p.source)
            .addValue("sources", mapper.writeValueAsString(p.sources))
            .addValue("sortAt", ts(sortAt))
            .addValue("createdAt", ts(p.createdAt))
            .addValue("updatedAt", ts(p.updatedAt));
    named.update(
        "INSERT INTO inbound_promise (trf_id, mfc_id, from_cdc, trf_version, state, row_version,"
            + " deadline_meaning, due_at, priority, approved_lines, departure_id, departure_commit_id,"
            + " departed_lines, departed_at, trip_id, manifest_id, manifest_version, manifest_lines,"
            + " arrived_at, arrival_trip_id, unloading_bay_reservation_id, arrival_evidence_ref,"
            + " arrival_recorded_by, cancel_reason, cancelled_at, source, sources, sort_at, created_at,"
            + " updated_at, trf_number, wave_id, wave_number) VALUES (:trf, :mfc, :fromCdc, :trfVersion, :state, :version, :deadline,"
            + " :dueAt, :priority, :approved::jsonb, :departureId, :departureCommit, :departed::jsonb,"
            + " :departedAt, :tripId, :manifestId, :manifestVersion, :manifest::jsonb, :arrivedAt,"
            + " :arrivalTrip, :bay, :evidence, :recordedBy, :cancelReason, :cancelledAt, :source,"
            + " :sources::jsonb, :sortAt, :createdAt, :updatedAt, :trfNumber, :waveId, :waveNumber)"
            + " ON CONFLICT (trf_id) DO UPDATE SET mfc_id = EXCLUDED.mfc_id, from_cdc = EXCLUDED.from_cdc,"
            + " trf_version = EXCLUDED.trf_version, state = EXCLUDED.state, row_version = EXCLUDED.row_version,"
            + " deadline_meaning = EXCLUDED.deadline_meaning, due_at = EXCLUDED.due_at,"
            + " priority = EXCLUDED.priority, approved_lines = EXCLUDED.approved_lines,"
            + " departure_id = EXCLUDED.departure_id, departure_commit_id = EXCLUDED.departure_commit_id,"
            + " departed_lines = EXCLUDED.departed_lines, departed_at = EXCLUDED.departed_at,"
            + " trip_id = EXCLUDED.trip_id, manifest_id = EXCLUDED.manifest_id,"
            + " manifest_version = EXCLUDED.manifest_version, manifest_lines = EXCLUDED.manifest_lines,"
            + " arrived_at = EXCLUDED.arrived_at, arrival_trip_id = EXCLUDED.arrival_trip_id,"
            + " unloading_bay_reservation_id = EXCLUDED.unloading_bay_reservation_id,"
            + " arrival_evidence_ref = EXCLUDED.arrival_evidence_ref,"
            + " arrival_recorded_by = EXCLUDED.arrival_recorded_by, cancel_reason = EXCLUDED.cancel_reason,"
            + " cancelled_at = EXCLUDED.cancelled_at, source = EXCLUDED.source, sources = EXCLUDED.sources,"
            + " sort_at = EXCLUDED.sort_at, updated_at = EXCLUDED.updated_at,"
            // 화면 번호(V17)는 null 로 덮지 않는다 — setNumbers 가 따로 채운 값을 오래된 사본 저장이 지우지 않게
            + " trf_number = COALESCE(EXCLUDED.trf_number, inbound_promise.trf_number),"
            + " wave_id = COALESCE(EXCLUDED.wave_id, inbound_promise.wave_id),"
            + " wave_number = COALESCE(EXCLUDED.wave_number, inbound_promise.wave_number)",
        m);
  }

  /**
   * (V17, R-52) 화면 번호만 채운다 — 값이 있는 칸만, 예정 version·updated_at 은 그대로(현장 화면의 X-Expected-Version 을 흔들지
   * 않는다). 바뀐 행 수.
   */
  public int setNumbers(
      String trfId,
      @Nullable String trfNumber,
      @Nullable String waveId,
      @Nullable String waveNumber) {
    return jdbc.update(
        "UPDATE inbound_promise SET trf_number = COALESCE(?, trf_number),"
            + " wave_id = COALESCE(?, wave_id), wave_number = COALESCE(?, wave_number)"
            + " WHERE trf_id = ?",
        trfNumber,
        waveId,
        waveNumber,
        trfId);
  }

  /** 센터 목록 — 정렬 (sort_at, trf_id), cursor = 앞 쪽 마지막 trfId. day 를 주면 [from, to) 범위의 sort_at 만. */
  public List<Promise> list(
      String mfcId,
      List<String> states,
      @Nullable Instant from,
      @Nullable Instant to,
      int limit,
      @Nullable String cursor) {
    MapSqlParameterSource m =
        new MapSqlParameterSource()
            .addValue("mfc", mfcId)
            .addValue("states", states)
            .addValue("limit", limit);
    StringBuilder sql =
        new StringBuilder(
            "SELECT * FROM inbound_promise p WHERE p.mfc_id = :mfc AND p.state IN (:states)");
    if (from != null && to != null) {
      sql.append(" AND p.sort_at >= :from AND p.sort_at < :to");
      m.addValue("from", Timestamp.from(from)).addValue("to", Timestamp.from(to));
    }
    if (cursor != null && !cursor.isBlank()) {
      sql.append(
          " AND (p.sort_at, p.trf_id) > (SELECT c.sort_at, c.trf_id FROM inbound_promise c WHERE c.trf_id = :cursor)");
      m.addValue("cursor", cursor);
    }
    sql.append(" ORDER BY p.sort_at, p.trf_id LIMIT :limit");
    return named.query(sql.toString(), m, this::promise);
  }

  // ---------- 수령 ----------

  public void insertReceipt(ReceiptRow r, Instant at) {
    jdbc.update(
        "INSERT INTO promise_receipt (receipt_id, trf_id, mfc_id, departure_id, lines, discrepancies,"
            + " state, evidence_ref, received_by, received_at, created_at, updated_at)"
            + " VALUES (?,?,?,?,?::jsonb,?::jsonb,?,?,?,?,?,?)",
        r.receiptId(),
        r.trfId(),
        r.mfcId(),
        r.departureId(),
        mapper.writeValueAsString(r.lines()),
        mapper.writeValueAsString(r.discrepancies()),
        r.state(),
        r.evidenceRef(),
        r.receivedBy(),
        Timestamp.from(r.receivedAt()),
        Timestamp.from(at),
        Timestamp.from(at));
  }

  public Optional<ReceiptRow> findReceipt(String receiptId) {
    return jdbc
        .query("SELECT * FROM promise_receipt WHERE receipt_id = ?", this::receipt, receiptId)
        .stream()
        .findFirst();
  }

  public List<ReceiptRow> receiptsOf(String trfId) {
    return jdbc.query(
        "SELECT * FROM promise_receipt WHERE trf_id = ? ORDER BY created_at, receipt_id",
        this::receipt,
        trfId);
  }

  /** 재고 반영 전(CAPTURED·UNKNOWN_RESULT) 수령이 있는지. */
  public boolean hasOpenReceipt(String trfId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM promise_receipt WHERE trf_id = ? AND state IN ('CAPTURED', 'UNKNOWN_RESULT')",
            Integer.class,
            trfId);
    return n != null && n > 0;
  }

  /** 반영 전인 수령만 APPLIED 로. 바뀐 행 수(동시 재시도는 한 번만 1). */
  public int markApplied(String receiptId, String commitId, Instant at) {
    return jdbc.update(
        "UPDATE promise_receipt SET state = 'APPLIED', commit_id = ?, last_error = NULL, posted_at = ?,"
            + " updated_at = ? WHERE receipt_id = ? AND state IN ('CAPTURED', 'UNKNOWN_RESULT')",
        commitId,
        Timestamp.from(at),
        Timestamp.from(at),
        receiptId);
  }

  public int markRejected(String receiptId, String reason, Instant at) {
    return jdbc.update(
        "UPDATE promise_receipt SET state = 'REJECTED', reject_reason = ?, updated_at = ?"
            + " WHERE receipt_id = ? AND state IN ('CAPTURED', 'UNKNOWN_RESULT')",
        truncate(reason, 1000),
        Timestamp.from(at),
        receiptId);
  }

  public void markUnknown(String receiptId, String error, Instant at) {
    jdbc.update(
        "UPDATE promise_receipt SET state = 'UNKNOWN_RESULT', last_error = ?, updated_at = ?"
            + " WHERE receipt_id = ? AND state IN ('CAPTURED', 'UNKNOWN_RESULT')",
        truncate(error, 500),
        Timestamp.from(at),
        receiptId);
  }

  // ---------- 행 변환 ----------

  private Promise promise(ResultSet rs, int i) throws SQLException {
    Promise p = new Promise();
    p.trfId = rs.getString("trf_id");
    p.mfcId = rs.getString("mfc_id");
    p.fromCdc = rs.getString("from_cdc");
    long tv = rs.getLong("trf_version");
    p.trfVersion = rs.wasNull() ? null : tv;
    p.state = rs.getString("state");
    p.version = rs.getInt("row_version");
    p.deadlineMeaning = rs.getString("deadline_meaning");
    p.dueAt = instant(rs, "due_at");
    p.priority = rs.getString("priority");
    p.approvedLines = mapper.readValue(rs.getString("approved_lines"), APPROVED);
    p.departureId = rs.getString("departure_id");
    p.departureCommitId = rs.getString("departure_commit_id");
    String dl = rs.getString("departed_lines");
    p.departedLines = dl == null ? null : mapper.readValue(dl, SHIP);
    p.departedAt = instant(rs, "departed_at");
    p.tripId = rs.getString("trip_id");
    p.manifestId = rs.getString("manifest_id");
    long mv = rs.getLong("manifest_version");
    p.manifestVersion = rs.wasNull() ? null : mv;
    String ml = rs.getString("manifest_lines");
    p.manifestLines = ml == null ? null : mapper.readValue(ml, SHIP);
    p.arrivedAt = instant(rs, "arrived_at");
    p.arrivalTripId = rs.getString("arrival_trip_id");
    p.unloadingBayReservationId = rs.getString("unloading_bay_reservation_id");
    p.arrivalEvidenceRef = rs.getString("arrival_evidence_ref");
    p.arrivalRecordedBy = rs.getString("arrival_recorded_by");
    p.cancelReason = rs.getString("cancel_reason");
    p.cancelledAt = instant(rs, "cancelled_at");
    p.trfNumber = rs.getString("trf_number");
    p.waveId = rs.getString("wave_id");
    p.waveNumber = rs.getString("wave_number");
    p.source = rs.getString("source");
    p.sources = new LinkedHashMap<>(mapper.readValue(rs.getString("sources"), SOURCES));
    p.createdAt = rs.getTimestamp("created_at").toInstant();
    p.updatedAt = rs.getTimestamp("updated_at").toInstant();
    return p;
  }

  private ReceiptRow receipt(ResultSet rs, int i) throws SQLException {
    return new ReceiptRow(
        rs.getString("receipt_id"),
        rs.getString("trf_id"),
        rs.getString("mfc_id"),
        rs.getString("departure_id"),
        new ArrayList<>(mapper.readValue(rs.getString("lines"), RECEIPT)),
        new ArrayList<>(mapper.readValue(rs.getString("discrepancies"), DISCREPANCY)),
        rs.getString("state"),
        rs.getString("commit_id"),
        rs.getString("reject_reason"),
        rs.getString("last_error"),
        rs.getString("evidence_ref"),
        rs.getString("received_by"),
        rs.getTimestamp("received_at").toInstant(),
        instant(rs, "posted_at"));
  }

  private static @Nullable Instant instant(ResultSet rs, String col) throws SQLException {
    Timestamp t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }

  private static @Nullable Timestamp ts(@Nullable Instant i) {
    return i == null ? null : Timestamp.from(i);
  }

  private static String truncate(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max);
  }
}
