package com.pmart.mfc.location.infrastructure;

import com.pmart.mfc.location.domain.LocationView;
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
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 위치 저장(JDBC). 변경은 호출자가 {@link #lock} 으로 잠근 행에. 코드 중복은 유일 키가 막는다. */
@Repository
public class LocationStore {

  /** 상태 변경 기록. */
  public record StateChangeRow(
      String requestId, String fromState, String toState, int newVersion) {}

  private static final String COLUMNS =
      "location_id, mfc_id, location_code, area_code, zone, temperature_zone, state, state_reason,"
          + " width_mm, depth_mm, height_mm, slot_count, max_weight_g, pick_sequence, row_version,"
          + " updated_at, high_turnover, reserve_stock, category_id, pos_x_m, pos_z_m";

  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate named;

  public LocationStore(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
    this.jdbc = jdbc;
    this.named = named;
  }

  /**
   * @return 넣었으면 true, 같은 센터에 같은 코드가 있으면 false
   */
  public boolean insert(LocationView v, String createdBy, Instant at) {
    return jdbc.update(
            "INSERT INTO location (location_id, mfc_id, location_code, area_code, zone,"
                + " temperature_zone, state, state_reason, width_mm, depth_mm, height_mm, slot_count,"
                + " max_weight_g, pick_sequence, row_version, created_by, created_at, updated_at,"
                + " high_turnover, reserve_stock, category_id, pos_x_m, pos_z_m)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
                + " ON CONFLICT (mfc_id, location_code) DO NOTHING",
            v.locationId(),
            v.mfcId(),
            v.locationCode(),
            v.areaCode(),
            v.zone(),
            v.temperatureZone(),
            v.state(),
            v.stateReason(),
            v.widthMm(),
            v.depthMm(),
            v.heightMm(),
            v.slotCount(),
            v.maxWeightG(),
            v.pickSequence(),
            v.version(),
            createdBy,
            Timestamp.from(at),
            Timestamp.from(at),
            v.highTurnover(),
            v.reserveStock(),
            v.categoryId(),
            v.posXM(),
            v.posZM())
        == 1;
  }

  public void updateAttributes(LocationView v) {
    jdbc.update(
        "UPDATE location SET area_code = ?, zone = ?, temperature_zone = ?, width_mm = ?,"
            + " depth_mm = ?, height_mm = ?, slot_count = ?, max_weight_g = ?, pick_sequence = ?,"
            + " row_version = ?, updated_at = ?, high_turnover = ?, reserve_stock = ?, category_id = ?,"
            + " pos_x_m = ?, pos_z_m = ? WHERE location_id = ?",
        v.areaCode(),
        v.zone(),
        v.temperatureZone(),
        v.widthMm(),
        v.depthMm(),
        v.heightMm(),
        v.slotCount(),
        v.maxWeightG(),
        v.pickSequence(),
        v.version(),
        Timestamp.from(v.updatedAt()),
        v.highTurnover(),
        v.reserveStock(),
        v.categoryId(),
        v.posXM(),
        v.posZM(),
        v.locationId());
  }

  public void updateState(String locationId, String state, String reason, int version, Instant at) {
    jdbc.update(
        "UPDATE location SET state = ?, state_reason = ?, row_version = ?, updated_at = ?"
            + " WHERE location_id = ?",
        state,
        reason,
        version,
        Timestamp.from(at),
        locationId);
  }

  public Optional<LocationView> find(String locationId) {
    return jdbc
        .query(
            "SELECT " + COLUMNS + " FROM location WHERE location_id = ?",
            (rs, i) -> view(rs),
            locationId)
        .stream()
        .findFirst();
  }

  public Optional<LocationView> lock(String locationId) {
    return jdbc
        .query(
            "SELECT " + COLUMNS + " FROM location WHERE location_id = ? FOR UPDATE",
            (rs, i) -> view(rs),
            locationId)
        .stream()
        .findFirst();
  }

  /** 목록(코드 순, 커서 = 마지막 코드). */
  public List<LocationView> list(
      String mfcId,
      @Nullable String zone,
      @Nullable String temperatureZone,
      @Nullable String state,
      @Nullable String afterCode,
      int limit) {
    StringBuilder sql =
        new StringBuilder("SELECT " + COLUMNS + " FROM location WHERE mfc_id = :mfc");
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("mfc", mfcId);
    if (zone != null) {
      sql.append(" AND zone = :zone");
      p.put("zone", zone);
    }
    if (temperatureZone != null) {
      sql.append(" AND temperature_zone = :temp");
      p.put("temp", temperatureZone);
    }
    if (state != null) {
      sql.append(" AND state = :state");
      p.put("state", state);
    }
    if (afterCode != null) {
      sql.append(" AND location_code > :after");
      p.put("after", afterCode);
    }
    sql.append(" ORDER BY location_code LIMIT :limit");
    p.put("limit", limit);
    return named.query(sql.toString(), p, (rs, i) -> view(rs));
  }

  /** 적치 후보: 사용 중(ACTIVE)인 선반·피킹 위치, 동선 순서 → 코드. */
  public List<LocationView> putawayCandidates(String mfcId, String temperatureZone, int limit) {
    return jdbc.query(
        "SELECT "
            + COLUMNS
            + " FROM location WHERE mfc_id = ? AND temperature_zone = ? AND state = 'ACTIVE'"
            + " AND zone IN ('STORAGE', 'PICKING')"
            + " ORDER BY pick_sequence NULLS LAST, location_code LIMIT ?",
        (rs, i) -> view(rs),
        mfcId,
        temperatureZone,
        limit);
  }

  /** 센터의 코드 목록으로(거리표, 0.7.0). */
  public List<LocationView> byCodes(String mfcId, List<String> codes) {
    if (codes.isEmpty()) {
      return List.of();
    }
    return jdbc.query(
        "SELECT " + COLUMNS + " FROM location WHERE mfc_id = ? AND location_code = ANY (?)",
        (rs, i) -> view(rs),
        mfcId,
        codes.toArray(new String[0]));
  }

  /** 다시 알림 대상(센터 지정 가능, 위치 ID 순). */
  public List<LocationView> all(@Nullable String mfcId, int limit) {
    if (mfcId == null) {
      return jdbc.query(
          "SELECT " + COLUMNS + " FROM location ORDER BY location_id LIMIT ?",
          (rs, i) -> view(rs),
          limit);
    }
    return jdbc.query(
        "SELECT " + COLUMNS + " FROM location WHERE mfc_id = ? ORDER BY location_id LIMIT ?",
        (rs, i) -> view(rs),
        mfcId,
        limit);
  }

  public void insertStateChange(
      String locationId,
      String requestId,
      String sourceSystem,
      String from,
      String to,
      int newVersion,
      String reason,
      @Nullable String countSessionId,
      String changedBy,
      Instant at) {
    jdbc.update(
        "INSERT INTO location_state_change (location_id, request_id, source_system, from_state,"
            + " to_state, new_version, reason, count_session_id, changed_by, changed_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
        locationId,
        requestId,
        sourceSystem,
        from,
        to,
        newVersion,
        reason,
        countSessionId,
        changedBy,
        Timestamp.from(at));
  }

  public Optional<StateChangeRow> stateChange(String locationId, String requestId) {
    return jdbc
        .query(
            "SELECT request_id, from_state, to_state, new_version FROM location_state_change"
                + " WHERE location_id = ? AND request_id = ?",
            (rs, i) ->
                new StateChangeRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4)),
            locationId,
            requestId)
        .stream()
        .findFirst();
  }

  private static @Nullable Integer intOrNull(ResultSet rs, String col) throws SQLException {
    int v = rs.getInt(col);
    return rs.wasNull() ? null : v;
  }

  private static @Nullable Double doubleOrNull(ResultSet rs, String col) throws SQLException {
    java.math.BigDecimal v = rs.getBigDecimal(col);
    return v == null ? null : v.doubleValue();
  }

  private static LocationView view(ResultSet rs) throws SQLException {
    return new LocationView(
        rs.getString("location_id"),
        rs.getString("mfc_id"),
        rs.getString("location_code"),
        rs.getString("area_code"),
        rs.getString("zone"),
        rs.getString("temperature_zone"),
        rs.getString("state"),
        rs.getString("state_reason"),
        intOrNull(rs, "width_mm"),
        intOrNull(rs, "depth_mm"),
        intOrNull(rs, "height_mm"),
        intOrNull(rs, "slot_count"),
        intOrNull(rs, "max_weight_g"),
        intOrNull(rs, "pick_sequence"),
        rs.getBoolean("high_turnover"),
        rs.getBoolean("reserve_stock"),
        rs.getString("category_id"),
        doubleOrNull(rs, "pos_x_m"),
        doubleOrNull(rs, "pos_z_m"),
        rs.getInt("row_version"),
        rs.getTimestamp("updated_at").toInstant());
  }
}
