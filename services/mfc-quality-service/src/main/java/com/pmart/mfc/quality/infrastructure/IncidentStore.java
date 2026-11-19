package com.pmart.mfc.quality.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 품질 사고(V4, MB-2 1차 2026-11-19). */
@Repository
public class IncidentStore {

  public record IncidentRow(
      String incidentId,
      String mfcId,
      String kind,
      String origin,
      String state,
      @Nullable String sourceEvent,
      @Nullable String sourceRef,
      @Nullable String description,
      @Nullable String openedBy,
      Instant openedAt,
      @Nullable String closedBy,
      @Nullable Instant closedAt,
      @Nullable String closeReason,
      @Nullable String caseId,
      int version) {}

  public record LotRow(String lotId, @Nullable String sku, int qty) {}

  /** 한 LOT 을 처리한 판정·폐기(격리 해제 APPLIED 또는 폐기 승인). */
  public record Resolution(String lotId, String refId) {}

  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate named;

  public IncidentStore(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
    this.jdbc = jdbc;
    this.named = named;
  }

  public void insert(IncidentRow r, List<LotRow> lots, Instant at) {
    jdbc.update(
        "INSERT INTO quality_incident (incident_id, mfc_id, kind, origin, state, source_event,"
            + " source_ref, description, opened_by, opened_at, version, updated_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,1,?)",
        r.incidentId(),
        r.mfcId(),
        r.kind(),
        r.origin(),
        r.state(),
        r.sourceEvent(),
        r.sourceRef(),
        r.description(),
        r.openedBy(),
        Timestamp.from(r.openedAt()),
        Timestamp.from(at));
    for (LotRow l : lots) {
      addLot(r.incidentId(), l);
    }
  }

  /** LOT 을 더한다(이미 있으면 수량을 더한다). */
  public void addLot(String incidentId, LotRow l) {
    jdbc.update(
        "INSERT INTO quality_incident_lot (incident_id, lot_id, sku, qty) VALUES (?,?,?,?)"
            + " ON CONFLICT (incident_id, lot_id) DO UPDATE SET qty = quality_incident_lot.qty + EXCLUDED.qty,"
            + " sku = COALESCE(quality_incident_lot.sku, EXCLUDED.sku)",
        incidentId,
        l.lotId(),
        l.sku(),
        l.qty());
  }

  public Optional<IncidentRow> find(String incidentId, boolean lock) {
    return jdbc
        .query(
            "SELECT * FROM quality_incident WHERE incident_id = ?" + (lock ? " FOR UPDATE" : ""),
            (rs, i) -> row(rs),
            incidentId)
        .stream()
        .findFirst();
  }

  public List<LotRow> lots(String incidentId) {
    return jdbc.query(
        "SELECT lot_id, sku, qty FROM quality_incident_lot WHERE incident_id = ? ORDER BY lot_id",
        (rs, i) -> new LotRow(rs.getString("lot_id"), rs.getString("sku"), rs.getInt("qty")),
        incidentId);
  }

  /** 같은 센터·LOT 의 닫히지 않은 자동 파손 사고(묶기용) — 잠금. */
  public Optional<String> lockOpenAuto(String mfcId, String kind, String lotId) {
    return jdbc
        .queryForList(
            "SELECT i.incident_id FROM quality_incident i JOIN quality_incident_lot l"
                + " ON l.incident_id = i.incident_id WHERE i.mfc_id = ? AND i.kind = ?"
                + " AND i.origin = 'AUTO' AND i.state <> 'CLOSED' AND l.lot_id = ?"
                + " ORDER BY i.opened_at LIMIT 1 FOR UPDATE OF i",
            String.class,
            mfcId,
            kind,
            lotId)
        .stream()
        .findFirst();
  }

  /**
   * 영향 LOT 을 처리한 판정·폐기 — 격리 해제(RELEASE) APPLIED 또는 폐기 승인(APPROVED 이후). 사고 ID 로 이어졌거나(판정) 같은 센터·LOT
   * 에서 사고가 열린 뒤 만든 것.
   */
  public List<Resolution> resolutions(IncidentRow i, Collection<String> lotIds) {
    if (lotIds.isEmpty()) {
      return List.of();
    }
    Map<String, Object> a = new LinkedHashMap<>();
    a.put("mfc", i.mfcId());
    a.put("incident", i.incidentId());
    a.put("since", Timestamp.from(i.openedAt()));
    a.put("lots", lotIds);
    List<Resolution> out = new ArrayList<>();
    out.addAll(
        named.query(
            "SELECT d.decision_id AS ref, l->>'lotId' AS lot FROM quality_decision d,"
                + " jsonb_array_elements(d.lines) l WHERE d.mfc_id = :mfc AND d.decision_type = 'RELEASE'"
                + " AND d.state = 'APPLIED' AND l->>'lotId' IN (:lots)"
                + " AND (d.incident_id = :incident OR d.created_at >= :since)"
                + " ORDER BY d.created_at",
            a,
            (rs, n) -> new Resolution(rs.getString("lot"), rs.getString("ref"))));
    out.addAll(
        named.query(
            "SELECT d.disposal_approval_id AS ref, l->>'lotId' AS lot FROM disposal_approval d,"
                + " jsonb_array_elements(d.lines) l WHERE d.mfc_id = :mfc"
                + " AND d.state IN ('APPROVED', 'PARTIALLY_EXECUTED', 'COMPLETED')"
                + " AND l->>'lotId' IN (:lots) AND d.requested_at >= :since ORDER BY d.requested_at",
            a,
            (rs, n) -> new Resolution(rs.getString("lot"), rs.getString("ref"))));
    return out;
  }

  /** 이 사고로 이어진 판정(incident_id) — 처리 여부와 무관하게 보인다. */
  public List<String> linkedDecisions(String incidentId) {
    return jdbc.queryForList(
        "SELECT decision_id FROM quality_decision WHERE incident_id = ? ORDER BY created_at",
        String.class,
        incidentId);
  }

  public void setState(String incidentId, String state, Instant at) {
    jdbc.update(
        "UPDATE quality_incident SET state = ?, updated_at = ? WHERE incident_id = ? AND state <> 'CLOSED'"
            + " AND state <> ?",
        state,
        Timestamp.from(at),
        incidentId,
        state);
  }

  public void close(String incidentId, String by, String reason, Instant at) {
    jdbc.update(
        "UPDATE quality_incident SET state = 'CLOSED', closed_by = ?, closed_at = ?, close_reason = ?,"
            + " version = version + 1, updated_at = ? WHERE incident_id = ?",
        by,
        Timestamp.from(at),
        reason,
        Timestamp.from(at),
        incidentId);
  }

  public void bumpVersion(String incidentId, Instant at) {
    jdbc.update(
        "UPDATE quality_incident SET version = version + 1, updated_at = ? WHERE incident_id = ?",
        Timestamp.from(at),
        incidentId);
  }

  /** 닫히지 않은 그 센터 사고 ID(상태 다시 맞추기용). */
  public List<String> openIds(String mfcId) {
    return jdbc.queryForList(
        "SELECT incident_id FROM quality_incident WHERE mfc_id = ? AND state <> 'CLOSED'",
        String.class,
        mfcId);
  }

  /** 목록 — 오래된 순, 커서 = 마지막 ID. */
  public List<String> ids(
      String mfcId,
      Collection<String> states,
      @Nullable String kind,
      @Nullable String lotId,
      @Nullable String after,
      int limit) {
    Map<String, Object> a = new LinkedHashMap<>();
    a.put("mfc", mfcId);
    a.put("states", states);
    a.put("limit", limit);
    StringBuilder sql =
        new StringBuilder(
            "SELECT i.incident_id FROM quality_incident i WHERE i.mfc_id = :mfc AND i.state IN (:states)");
    if (kind != null) {
      sql.append(" AND i.kind = :kind");
      a.put("kind", kind);
    }
    if (lotId != null) {
      sql.append(
          " AND EXISTS (SELECT 1 FROM quality_incident_lot l WHERE l.incident_id = i.incident_id"
              + " AND l.lot_id = :lot)");
      a.put("lot", lotId);
    }
    if (after != null) {
      sql.append(
          " AND (i.opened_at, i.incident_id) > (SELECT opened_at, incident_id FROM quality_incident"
              + " WHERE incident_id = :after)");
      a.put("after", after);
    }
    sql.append(" ORDER BY i.opened_at, i.incident_id LIMIT :limit");
    return named.queryForList(sql.toString(), a, String.class);
  }

  private static IncidentRow row(ResultSet rs) throws SQLException {
    Timestamp c = rs.getTimestamp("closed_at");
    return new IncidentRow(
        rs.getString("incident_id"),
        rs.getString("mfc_id"),
        rs.getString("kind"),
        rs.getString("origin"),
        rs.getString("state"),
        rs.getString("source_event"),
        rs.getString("source_ref"),
        rs.getString("description"),
        rs.getString("opened_by"),
        rs.getTimestamp("opened_at").toInstant(),
        rs.getString("closed_by"),
        c == null ? null : c.toInstant(),
        rs.getString("close_reason"),
        rs.getString("case_id"),
        rs.getInt("version"));
  }
}
