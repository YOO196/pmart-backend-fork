package com.pmart.mfc.inventory.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 센터 SKU 취급 저장(JDBC). 레코드가 없으면 OFF. 변경은 version 조건부(첫 변경은 INSERT ... ON CONFLICT DO NOTHING)라서 경합이면
 * 0 행이 바뀌고 호출자가 VERSION_CONFLICT 로 돌려준다.
 */
@Repository
public class SkuHandlingStore {

  /** 취급 레코드. */
  public record HandlingRow(
      String centerId,
      String sku,
      boolean enabled,
      String changedBy,
      @Nullable String reason,
      Instant updatedAt,
      int version) {}

  /** 목록 행(SKU 투영 LEFT JOIN 취급). 레코드가 없으면 handling 열이 null. */
  public record ListedRow(
      String sku,
      @Nullable String productId,
      String skuState,
      @Nullable Boolean enabled,
      @Nullable Integer version,
      @Nullable String changedBy,
      @Nullable String reason,
      @Nullable Instant updatedAt,
      @Nullable String saleState,
      @Nullable Instant introducedAt) {}

  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate named;

  public SkuHandlingStore(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
    this.jdbc = jdbc;
    this.named = named;
  }

  public Optional<HandlingRow> find(String centerId, String sku) {
    return jdbc
        .query(
            "SELECT center_id, sku, handling_enabled, changed_by, reason, updated_at, row_version"
                + " FROM center_sku_handling WHERE center_id = ? AND sku = ?",
            (rs, i) ->
                new HandlingRow(
                    rs.getString("center_id"),
                    rs.getString("sku"),
                    rs.getBoolean("handling_enabled"),
                    rs.getString("changed_by"),
                    rs.getString("reason"),
                    rs.getTimestamp("updated_at").toInstant(),
                    rs.getInt("row_version")),
            centerId,
            sku)
        .stream()
        .findFirst();
  }

  public Map<String, HandlingRow> findAll(String centerId, Collection<String> skus) {
    Map<String, HandlingRow> out = new HashMap<>();
    if (skus.isEmpty()) {
      return out;
    }
    named.query(
        "SELECT center_id, sku, handling_enabled, changed_by, reason, updated_at, row_version"
            + " FROM center_sku_handling WHERE center_id = :center AND sku IN (:skus)",
        Map.of("center", centerId, "skus", skus),
        rs -> {
          out.put(
              rs.getString("sku"),
              new HandlingRow(
                  rs.getString("center_id"),
                  rs.getString("sku"),
                  rs.getBoolean("handling_enabled"),
                  rs.getString("changed_by"),
                  rs.getString("reason"),
                  rs.getTimestamp("updated_at").toInstant(),
                  rs.getInt("row_version")));
        });
    return out;
  }

  /** 첫 변경(레코드 없음 → version 1). 다른 요청이 먼저 만들었으면 false. */
  public boolean insertFirst(
      String centerId,
      String sku,
      boolean enabled,
      String changedBy,
      @Nullable String reason,
      Instant at) {
    return jdbc.update(
            "INSERT INTO center_sku_handling (center_id, sku, handling_enabled, changed_by, reason,"
                + " updated_at, row_version) VALUES (?,?,?,?,?,?,1)"
                + " ON CONFLICT (center_id, sku) DO NOTHING",
            centerId,
            sku,
            enabled,
            changedBy,
            reason,
            Timestamp.from(at))
        == 1;
  }

  /** version 조건부 갱신. 다른 요청이 먼저 바꿨으면 false. */
  public boolean update(
      String centerId,
      String sku,
      boolean enabled,
      String changedBy,
      @Nullable String reason,
      Instant at,
      int expectedVersion) {
    return jdbc.update(
            "UPDATE center_sku_handling SET handling_enabled = ?, changed_by = ?, reason = ?,"
                + " updated_at = ?, row_version = row_version + 1"
                + " WHERE center_id = ? AND sku = ? AND row_version = ?",
            enabled,
            changedBy,
            reason,
            Timestamp.from(at),
            centerId,
            sku,
            expectedVersion)
        == 1;
  }

  public void insertHistory(
      String centerId,
      String sku,
      boolean from,
      boolean to,
      String changedBy,
      @Nullable String reason,
      String requestId,
      int newVersion,
      Instant at) {
    jdbc.update(
        "INSERT INTO center_sku_handling_history (center_id, sku, from_enabled, to_enabled, changed_by,"
            + " reason, request_id, row_version, changed_at) VALUES (?,?,?,?,?,?,?,?,?)",
        centerId,
        sku,
        from,
        to,
        changedBy,
        reason,
        requestId,
        newVersion,
        Timestamp.from(at));
  }

  /**
   * 투영된 SKU 전체를 이 센터의 취급 여부와 함께 SKU 오름차순으로. {@code enabled} 가 false 면 레코드 없음도 포함한다.
   *
   * @param limit 최대 행 수(다음 페이지 판단은 호출자가 limit+1 로)
   */
  public List<ListedRow> list(
      String centerId, @Nullable Boolean enabled, @Nullable String afterSku, int limit) {
    return list(centerId, enabled, null, null, afterSku, limit);
  }

  /** (0.27.0) undecided = 레코드 없음·단종 아님만, introducedSince = 그 뒤 생긴 상품만. */
  public List<ListedRow> list(
      String centerId,
      @Nullable Boolean enabled,
      @Nullable Boolean undecided,
      @Nullable Instant introducedSince,
      @Nullable String afterSku,
      int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT p.sku, p.product_id, p.state, h.handling_enabled, h.row_version, h.changed_by,"
                + " h.reason, h.updated_at, p.sale_state, p.introduced_at FROM sku_projection p"
                + " LEFT JOIN center_sku_handling h ON h.center_id = ? AND h.sku = p.sku WHERE 1 = 1");
    List<Object> args = new ArrayList<>();
    args.add(centerId);
    filters(sql, args, enabled, undecided, introducedSince);
    if (afterSku != null) {
      sql.append(" AND p.sku > ?");
      args.add(afterSku);
    }
    sql.append(" ORDER BY p.sku LIMIT ?");
    args.add(limit);
    return jdbc.query(
        sql.toString(),
        (rs, i) -> {
          Timestamp ts = rs.getTimestamp("updated_at");
          return new ListedRow(
              rs.getString("sku"),
              rs.getString("product_id"),
              rs.getString("state"),
              rs.getObject("handling_enabled", Boolean.class),
              rs.getObject("row_version", Integer.class),
              rs.getString("changed_by"),
              rs.getString("reason"),
              ts == null ? null : ts.toInstant(),
              rs.getString("sale_state"),
              rs.getTimestamp("introduced_at") == null
                  ? null
                  : rs.getTimestamp("introduced_at").toInstant());
        },
        args.toArray());
  }

  /** 같은 거르기의 전체 건수(0.27.0 — 배너 숫자). */
  public int count(
      String centerId,
      @Nullable Boolean enabled,
      @Nullable Boolean undecided,
      @Nullable Instant introducedSince) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT COUNT(*) FROM sku_projection p LEFT JOIN center_sku_handling h"
                + " ON h.center_id = ? AND h.sku = p.sku WHERE 1 = 1");
    List<Object> args = new ArrayList<>();
    args.add(centerId);
    filters(sql, args, enabled, undecided, introducedSince);
    Integer n = jdbc.queryForObject(sql.toString(), Integer.class, args.toArray());
    return n == null ? 0 : n;
  }

  private static void filters(
      StringBuilder sql,
      List<Object> args,
      @Nullable Boolean enabled,
      @Nullable Boolean undecided,
      @Nullable Instant introducedSince) {
    if (enabled != null) {
      sql.append(" AND COALESCE(h.handling_enabled, false) = ?");
      args.add(enabled);
    }
    if (Boolean.TRUE.equals(undecided)) {
      sql.append(" AND h.sku IS NULL AND p.sale_state IS DISTINCT FROM 'DISCONTINUED'");
    }
    if (introducedSince != null) {
      sql.append(" AND p.introduced_at >= ?");
      args.add(Timestamp.from(introducedSince));
    }
  }

  /** 이 SKU 를 켜 둔 센터와 그 레코드(단종 처리용, 잠금). */
  public List<HandlingRow> lockEnabledCenters(String sku) {
    return jdbc.query(
        "SELECT center_id, sku, handling_enabled, changed_by, reason, updated_at, row_version"
            + " FROM center_sku_handling WHERE sku = ? AND handling_enabled ORDER BY center_id FOR UPDATE",
        (rs, i) ->
            new HandlingRow(
                rs.getString("center_id"),
                rs.getString("sku"),
                rs.getBoolean("handling_enabled"),
                rs.getString("changed_by"),
                rs.getString("reason"),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getInt("row_version")),
        sku);
  }

  /** 재발행용 취급 레코드(센터·SKU 오름차순). 상품 id 는 SKU 투영에서(없으면 null). */
  public record RepublishRow(
      String centerId,
      String sku,
      @Nullable String productId,
      boolean enabled,
      int version,
      Instant updatedAt) {}

  /**
   * 취급 레코드 전체(또는 한 센터). 레코드 없음(= OFF, 버전 0)은 알릴 상태 변경이 없으므로 넣지 않는다.
   *
   * @param limit 최대 행 수(상한 초과 판단은 호출자가 limit+1 로)
   */
  public List<RepublishRow> forRepublish(@Nullable String centerId, int limit) {
    String sql =
        "SELECT h.center_id, h.sku, p.product_id, h.handling_enabled, h.row_version, h.updated_at"
            + " FROM center_sku_handling h LEFT JOIN sku_projection p ON p.sku = h.sku"
            + (centerId == null ? "" : " WHERE h.center_id = ?")
            + " ORDER BY h.center_id, h.sku LIMIT ?";
    Object[] args = centerId == null ? new Object[] {limit} : new Object[] {centerId, limit};
    return jdbc.query(
        sql,
        (rs, i) ->
            new RepublishRow(
                rs.getString("center_id"),
                rs.getString("sku"),
                rs.getString("product_id"),
                rs.getBoolean("handling_enabled"),
                rs.getInt("row_version"),
                rs.getTimestamp("updated_at").toInstant()),
        args);
  }
}
