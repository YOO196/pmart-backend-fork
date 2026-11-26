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
      @org.jspecify.annotations.Nullable String categoryId,
      boolean reserveStock) {
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
          null,
          false);
    }

    /** 한 상품 한 자리·나눠 두기 규칙에서 빠지는 칸(고회전 또는 보충재고 — R-30). */
    public boolean placementExempt() {
      return highTurnover || reserveStock;
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
            + " depth_mm, height_mm, max_weight_g, category_id, reserve_stock)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
            + " ON CONFLICT (location_id) DO UPDATE SET mfc_id = EXCLUDED.mfc_id,"
            + " location_code = EXCLUDED.location_code, zone = EXCLUDED.zone,"
            + " temperature_zone = EXCLUDED.temperature_zone, state = EXCLUDED.state,"
            + " location_version = EXCLUDED.location_version, updated_at = EXCLUDED.updated_at,"
            + " high_turnover = EXCLUDED.high_turnover, width_mm = EXCLUDED.width_mm,"
            + " depth_mm = EXCLUDED.depth_mm, height_mm = EXCLUDED.height_mm,"
            + " max_weight_g = EXCLUDED.max_weight_g, category_id = EXCLUDED.category_id,"
            + " reserve_stock = EXCLUDED.reserve_stock"
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
        r.categoryId(),
        r.reserveStock());
  }

  /**
   * 기타 칸(카테고리 없음) 후보: 선반·피킹 구역, ACTIVE, 그 온도대, 고회전·보충재고(R-30) 아님, 위치 코드 순(#59 복귀 칸 추천). 칸마다 최신 버전으로
   * 본다. 온도대는 2026-11-23(R-39) — 전에는 안 봐서 코드 순 앞의 상온 칸만 50개 나왔다.
   */
  public java.util.List<String> miscShelves(String mfcId, String temperatureZone, int limit) {
    return jdbc.queryForList(
        "SELECT location_code FROM (SELECT DISTINCT ON (location_code) location_code, zone, state,"
            + " temperature_zone, category_id, high_turnover, reserve_stock FROM location_projection"
            + " WHERE mfc_id = ? ORDER BY location_code, location_version DESC) x"
            + " WHERE zone IN ('STORAGE', 'PICKING') AND state = 'ACTIVE' AND temperature_zone = ?"
            + " AND category_id IS NULL AND NOT high_turnover AND NOT reserve_stock"
            + " ORDER BY location_code LIMIT ?",
        String.class,
        mfcId,
        temperatureZone,
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

  /** 보충재고 칸(0.44.0, R-30): 보관 구역·ACTIVE·그 온도대, 위치 코드 순(칸마다 최신 버전). */
  /**
   * 고회전 구역 칸(2026-11-23 R-40): 선반·피킹 구역, ACTIVE, 그 온도대, 위치 고회전(highTurnover), 위치 코드 둘째 마디가 {@code
   * area} 로 시작(예 F → {@code A-F1-01-1-01}, 업무 문서 2-1 "F 상온 고회전" — 코드 문법 ADR-0042), 위치 코드 순. 고회전 시험
   * 칸(예 A-H1-…)은 구역 글자가 달라 빠진다.
   */
  public java.util.List<String> highTurnoverShelves(
      String mfcId, String temperatureZone, String area, int limit) {
    return jdbc.queryForList(
        "SELECT location_code FROM (SELECT DISTINCT ON (location_code) location_code, zone, state,"
            + " temperature_zone, high_turnover FROM location_projection WHERE mfc_id = ?"
            + " ORDER BY location_code, location_version DESC) x"
            + " WHERE zone IN ('STORAGE', 'PICKING') AND state = 'ACTIVE' AND high_turnover"
            + " AND temperature_zone = ? AND split_part(location_code, '-', 2) LIKE ?"
            + " ORDER BY location_code LIMIT ?",
        String.class,
        mfcId,
        temperatureZone,
        area + "%",
        limit);
  }

  public java.util.List<String> reserveLocations(String mfcId, String temperatureZone, int limit) {
    return jdbc.queryForList(
        "SELECT location_code FROM (SELECT DISTINCT ON (location_code) location_code, zone,"
            + " temperature_zone, state, reserve_stock FROM location_projection WHERE mfc_id = ?"
            + " ORDER BY location_code, location_version DESC) x"
            + " WHERE zone = 'STORAGE' AND state = 'ACTIVE' AND reserve_stock AND temperature_zone = ?"
            + " ORDER BY location_code LIMIT ?",
        String.class,
        mfcId,
        temperatureZone,
        limit);
  }

  /** 보충재고 칸 하나 — 코드·치수(적치 추천이 빈 칸 용량으로 거른다, 2026-11-27). */
  public record ReserveSlot(
      String locationCode,
      @org.jspecify.annotations.Nullable Integer widthMm,
      @org.jspecify.annotations.Nullable Integer depthMm,
      @org.jspecify.annotations.Nullable Integer heightMm,
      @org.jspecify.annotations.Nullable Integer maxWeightG) {}

  /**
   * 그 온도대의 ACTIVE 보충재고 칸 전부(치수 포함, 위치 코드 순) — {@link #reserveLocations} 는 앞에서 limit 개만이라 큰 칸이 뒤에 있으면
   * 추천에서 빠졌다(2026-11-27 LAN 부산진구: 작은 칸 210개 뒤의 큰 칸 10개).
   */
  public java.util.List<ReserveSlot> reserveSlots(String mfcId, String temperatureZone) {
    return jdbc.query(
        "SELECT location_code, width_mm, depth_mm, height_mm, max_weight_g FROM (SELECT DISTINCT ON"
            + " (location_code) location_code, zone, temperature_zone, state, reserve_stock, width_mm,"
            + " depth_mm, height_mm, max_weight_g FROM location_projection WHERE mfc_id = ?"
            + " ORDER BY location_code, location_version DESC) x"
            + " WHERE zone = 'STORAGE' AND state = 'ACTIVE' AND reserve_stock AND temperature_zone = ?"
            + " ORDER BY location_code",
        (rs, i) ->
            new ReserveSlot(
                rs.getString("location_code"),
                (Integer) rs.getObject("width_mm"),
                (Integer) rs.getObject("depth_mm"),
                (Integer) rs.getObject("height_mm"),
                (Integer) rs.getObject("max_weight_g")),
        mfcId,
        temperatureZone);
  }

  /** 이 센터의 보충재고 칸 코드 전부(칸마다 최신 버전, 상태 무관) — 피킹 위치 배정·보충 출발지 구분. */
  public java.util.Set<String> reserveCodes(String mfcId) {
    return new java.util.HashSet<>(
        jdbc.queryForList(
            "SELECT location_code FROM (SELECT DISTINCT ON (location_code) location_code,"
                + " reserve_stock FROM location_projection WHERE mfc_id = ?"
                + " ORDER BY location_code, location_version DESC) x WHERE reserve_stock",
            String.class,
            mfcId));
  }

  public Optional<LocationRow> byCode(String mfcId, String locationCode) {
    return jdbc
        .query(
            "SELECT location_id, mfc_id, location_code, zone, temperature_zone, state,"
                + " location_version, high_turnover, width_mm, depth_mm, height_mm, max_weight_g,"
                + " category_id, reserve_stock"
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
                    rs.getString(13),
                    rs.getBoolean(14)),
            mfcId,
            locationCode)
        .stream()
        .findFirst();
  }
}
