package com.pmart.mfc.inventory.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** mfc-location 위치 투영(JDBC). 오래된 버전은 {@code ON CONFLICT ... WHERE} 로 막는다. */
@Repository
public class LocationProjectionStore {

  /** 위치 사본. */
  public record LocationRow(
      String locationId,
      String mfcId,
      String locationCode,
      String zone,
      String temperatureZone,
      String state,
      int version,
      boolean highTurnover,
      @org.jspecify.annotations.Nullable Integer widthMm,
      @org.jspecify.annotations.Nullable Integer depthMm,
      @org.jspecify.annotations.Nullable Integer heightMm,
      @org.jspecify.annotations.Nullable Integer maxWeightG,
      @org.jspecify.annotations.Nullable String categoryId) {
    public LocationRow(
        String locationId,
        String mfcId,
        String locationCode,
        String zone,
        String temperatureZone,
        String state,
        int version) {
      this(
          locationId,
          mfcId,
          locationCode,
          zone,
          temperatureZone,
          state,
          version,
          false,
          null,
          null,
          null,
          null,
          null);
    }
  }

  private final JdbcTemplate jdbc;

  public LocationProjectionStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * @return 반영한 행 수(오래된 버전이면 0)
   */
  public int upsert(LocationRow r, Instant at) {
    return jdbc.update(
        "INSERT INTO location_projection (location_id, mfc_id, location_code, zone,"
            + " temperature_zone, state, location_version, updated_at, high_turnover, width_mm,"
            + " depth_mm, height_mm, max_weight_g, category_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
            + " ON CONFLICT (location_id) DO UPDATE SET mfc_id = EXCLUDED.mfc_id,"
            + " location_code = EXCLUDED.location_code, zone = EXCLUDED.zone,"
            + " temperature_zone = EXCLUDED.temperature_zone, state = EXCLUDED.state,"
            + " location_version = EXCLUDED.location_version, updated_at = EXCLUDED.updated_at,"
            + " high_turnover = EXCLUDED.high_turnover, width_mm = EXCLUDED.width_mm,"
            + " depth_mm = EXCLUDED.depth_mm, height_mm = EXCLUDED.height_mm,"
            + " max_weight_g = EXCLUDED.max_weight_g, category_id = EXCLUDED.category_id"
            + " WHERE location_projection.location_version <= EXCLUDED.location_version",
        r.locationId(),
        r.mfcId(),
        r.locationCode(),
        r.zone(),
        r.temperatureZone(),
        r.state(),
        r.version(),
        Timestamp.from(at),
        r.highTurnover(),
        r.widthMm(),
        r.depthMm(),
        r.heightMm(),
        r.maxWeightG(),
        r.categoryId());
  }

  /** 기타 칸(카테고리 없음) 후보: 선반·피킹 구역, ACTIVE, 고회전 아님, 위치 코드 순(#59 복귀 칸 추천). */
  public java.util.List<String> miscShelves(String mfcId, int limit) {
    return jdbc.queryForList(
        "SELECT DISTINCT ON (location_code) location_code FROM location_projection"
            + " WHERE mfc_id = ? AND zone IN ('STORAGE', 'PICKING') AND state = 'ACTIVE'"
            + " AND category_id IS NULL AND NOT high_turnover"
            + " ORDER BY location_code, location_version DESC LIMIT ?",
        String.class,
        mfcId,
        limit);
  }

  /** 격리 구역 칸(0.35.0, IV-6): 구역 QUARANTINE·ACTIVE·그 온도대, 위치 코드 순. */
  public java.util.List<String> quarantineLocations(
      String mfcId, String temperatureZone, int limit) {
    return jdbc.queryForList(
        "SELECT location_code FROM (SELECT DISTINCT ON (location_code) location_code, zone,"
            + " temperature_zone, state FROM location_projection WHERE mfc_id = ?"
            + " ORDER BY location_code, location_version DESC) x"
            + " WHERE zone = 'QUARANTINE' AND state = 'ACTIVE' AND temperature_zone = ?"
            + " ORDER BY location_code LIMIT ?",
        String.class,
        mfcId,
        temperatureZone,
        limit);
  }

  public Optional<LocationRow> byCode(String mfcId, String locationCode) {
    return jdbc
        .query(
            "SELECT location_id, mfc_id, location_code, zone, temperature_zone, state,"
                + " location_version, high_turnover, width_mm, depth_mm, height_mm, max_weight_g,"
                + " category_id"
                + " FROM location_projection WHERE mfc_id = ? AND location_code = ?"
                + " ORDER BY location_version DESC LIMIT 1",
            (rs, i) ->
                new LocationRow(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getString(5),
                    rs.getString(6),
                    rs.getInt(7),
                    rs.getBoolean(8),
                    (Integer) rs.getObject(9),
                    (Integer) rs.getObject(10),
                    (Integer) rs.getObject(11),
                    (Integer) rs.getObject(12),
                    rs.getString(13)),
            mfcId,
            locationCode)
        .stream()
        .findFirst();
  }
}
