package com.pmart.mfc.inventory.infrastructure;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 곧 품절 기준(V32, 0.47.0 — 사용자 결정 2026-11-25 "하루 판매량 기준"). 정본은 mfc-inbound(인계 실적 이동평균 d·공급 정책 표) —
 * 센터마다 통째로 받아 둔다. 판정: 취급 ON 상품마다 A = E − R, SS = d × safetyDays(그 상품 온도대 정책, 이력이 최소 이력 이상일 때만) → A
 * ≤ 0 품절, 0 &lt; A ≤ SS 곧 품절, A &gt; 0 인데 SS 를 모르면 모름(0 으로 채우지 않는다).
 */
@Repository
public class LowStockStore {

  public record Policy(
      String storageType,
      @Nullable BigDecimal safetyDays,
      int historyDays,
      int minHistoryDays,
      int averageWindowDays) {}

  public record Basis(
      String mfcId, Instant calculatedAt, LocalDate salesThrough, List<Policy> policies) {}

  /** 목록 한 줄. ss·d 는 SS 를 알 때만. */
  public record SkuRow(
      String sku,
      @Nullable String productId,
      @Nullable String productName,
      @Nullable String storageType,
      int available,
      @Nullable Double dailyQty,
      @Nullable Double safetyQty,
      @Nullable String unknownReason) {}

  public enum Kind {
    LOW_STOCK,
    SOLD_OUT,
    LOW_STOCK_UNKNOWN
  }

  /**
   * 취급 ON 상품 판정(센터 걸러서) — 컬럼 mfc_id·sku·a·storage_type·product_id·product_name·d·ss·reason. ss =
   * SS(모르면 NULL), reason = A &gt; 0 인데 SS 를 모르는 이유.
   */
  static final String CLASSIFIED =
      "WITH x AS (SELECT h.center_id AS mfc_id, h.sku, COALESCE(b.sellable - b.reserved, 0) AS a,"
          + " sp.storage_type, sp.product_id, sp.product_name, bs.mfc_id IS NOT NULL AS has_basis,"
          + " pol.safety_days, pol.history_days, pol.min_history_days, COALESCE(dq.daily_qty, 0) AS d"
          + " FROM center_sku_handling h"
          + " LEFT JOIN stock_balance b ON b.mfc_id = h.center_id AND b.sku = h.sku"
          + " LEFT JOIN sku_projection sp ON sp.sku = h.sku"
          + " LEFT JOIN low_stock_basis bs ON bs.mfc_id = h.center_id"
          + " LEFT JOIN low_stock_policy pol ON pol.mfc_id = h.center_id AND pol.storage_type = sp.storage_type"
          + " LEFT JOIN low_stock_daily dq ON dq.mfc_id = h.center_id AND dq.sku = h.sku"
          + " WHERE h.handling_enabled %s),"
          + " y AS (SELECT x.*, CASE WHEN has_basis AND storage_type IS NOT NULL AND safety_days IS NOT NULL"
          + " AND history_days > 0 AND history_days >= min_history_days THEN d * safety_days END AS ss,"
          + " CASE WHEN a <= 0 THEN NULL WHEN NOT has_basis THEN 'BASIS_MISSING'"
          + " WHEN storage_type IS NULL THEN 'STORAGE_TYPE_UNKNOWN' WHEN safety_days IS NULL THEN 'POLICY_MISSING'"
          + " WHEN history_days = 0 OR history_days < min_history_days THEN 'INSUFFICIENT_HISTORY' END AS reason"
          + " FROM x)";

  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate named;

  public LowStockStore(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
    this.jdbc = jdbc;
    this.named = named;
  }

  /** 센터 기준 통째로 바꾸기 — 이미 가진 것보다 앞선 계산이면 바꾸지 않는다(false). 호출자 트랜잭션 안. */
  public boolean replace(Basis b, Map<String, BigDecimal> daily, Instant now) {
    List<Timestamp> have =
        jdbc.query(
            "SELECT calculated_at FROM low_stock_basis WHERE mfc_id = ? FOR UPDATE",
            (rs, i) -> rs.getTimestamp(1),
            b.mfcId());
    if (!have.isEmpty() && have.get(0).toInstant().isAfter(b.calculatedAt())) {
      return false;
    }
    jdbc.update(
        "INSERT INTO low_stock_basis (mfc_id, calculated_at, sales_through, received_at) VALUES (?,?,?,?)"
            + " ON CONFLICT (mfc_id) DO UPDATE SET calculated_at = EXCLUDED.calculated_at,"
            + " sales_through = EXCLUDED.sales_through, received_at = EXCLUDED.received_at",
        b.mfcId(),
        Timestamp.from(b.calculatedAt()),
        Date.valueOf(b.salesThrough()),
        Timestamp.from(now));
    jdbc.update("DELETE FROM low_stock_policy WHERE mfc_id = ?", b.mfcId());
    for (Policy p : b.policies()) {
      jdbc.update(
          "INSERT INTO low_stock_policy (mfc_id, storage_type, safety_days, history_days,"
              + " min_history_days, average_window_days) VALUES (?,?,?,?,?,?)",
          b.mfcId(),
          p.storageType(),
          p.safetyDays(),
          p.historyDays(),
          p.minHistoryDays(),
          p.averageWindowDays());
    }
    jdbc.update("DELETE FROM low_stock_daily WHERE mfc_id = ?", b.mfcId());
    List<Object[]> rows = new ArrayList<>();
    daily.forEach((sku, q) -> rows.add(new Object[] {b.mfcId(), sku, q}));
    if (!rows.isEmpty()) {
      jdbc.batchUpdate("INSERT INTO low_stock_daily (mfc_id, sku, daily_qty) VALUES (?,?,?)", rows);
    }
    return true;
  }

  /** 센터별 기준(받은 센터만). centers 가 null 이면 모두. */
  public Map<String, Basis> bases(@Nullable Collection<String> centers) {
    Map<String, Object> a = new HashMap<>();
    String f = "";
    if (centers != null) {
      if (centers.isEmpty()) {
        return Map.of();
      }
      a.put("centers", centers);
      f = " WHERE b.mfc_id IN (:centers)";
    }
    Map<String, Instant> at = new LinkedHashMap<>();
    Map<String, LocalDate> through = new HashMap<>();
    Map<String, List<Policy>> pol = new HashMap<>();
    named.query(
        "SELECT b.mfc_id, b.calculated_at, b.sales_through, p.storage_type, p.safety_days,"
            + " p.history_days, p.min_history_days, p.average_window_days FROM low_stock_basis b"
            + " LEFT JOIN low_stock_policy p ON p.mfc_id = b.mfc_id"
            + f
            + " ORDER BY b.mfc_id, p.storage_type",
        a,
        rs -> {
          String m = rs.getString("mfc_id");
          at.put(m, rs.getTimestamp("calculated_at").toInstant());
          through.put(m, rs.getDate("sales_through").toLocalDate());
          List<Policy> list = pol.computeIfAbsent(m, k -> new ArrayList<>());
          String st = rs.getString("storage_type");
          if (st != null) {
            list.add(
                new Policy(
                    st,
                    rs.getBigDecimal("safety_days"),
                    rs.getInt("history_days"),
                    rs.getInt("min_history_days"),
                    rs.getInt("average_window_days")));
          }
        });
    Map<String, Basis> out = new LinkedHashMap<>();
    at.forEach(
        (m, t) -> out.put(m, new Basis(m, t, through.get(m), pol.getOrDefault(m, List.of()))));
    return out;
  }

  /** 곧 품절 수 + 모름 수 — 센터 → [low, unknown]. */
  public Map<String, int[]> counts(@Nullable Collection<String> centers) {
    Map<String, Object> a = new HashMap<>();
    String f = "";
    if (centers != null) {
      if (centers.isEmpty()) {
        return Map.of();
      }
      a.put("centers", centers);
      f = " AND h.center_id IN (:centers)";
    }
    Map<String, int[]> out = new HashMap<>();
    named.query(
        String.format(CLASSIFIED, f)
            + " SELECT mfc_id, COUNT(*) FILTER (WHERE a > 0 AND ss IS NOT NULL AND a <= ss) AS low,"
            + " COUNT(*) FILTER (WHERE reason IS NOT NULL) AS unknown FROM y GROUP BY mfc_id",
        a,
        rs -> {
          out.put(rs.getString("mfc_id"), new int[] {rs.getInt("low"), rs.getInt("unknown")});
        });
    return out;
  }

  public record Page(List<SkuRow> items, int total) {}

  /** 한 센터의 kind 목록 — LOW_STOCK 은 남은 날(A ÷ d) 적은 순, 그 밖은 SKU 순. */
  public Page list(String mfcId, Kind kind, int offset, int size) {
    String where =
        switch (kind) {
          case LOW_STOCK -> "a > 0 AND ss IS NOT NULL AND a <= ss";
          case SOLD_OUT -> "a <= 0";
          case LOW_STOCK_UNKNOWN -> "reason IS NOT NULL";
        };
    String order =
        kind == Kind.LOW_STOCK
            ? " ORDER BY CASE WHEN d > 0 THEN a / d END NULLS LAST, sku"
            : " ORDER BY sku";
    Map<String, Object> a = new HashMap<>();
    a.put("m", mfcId);
    a.put("limit", size);
    a.put("offset", offset);
    String base = String.format(CLASSIFIED, " AND h.center_id = :m");
    Integer total =
        named.queryForObject(base + " SELECT COUNT(*) FROM y WHERE " + where, a, Integer.class);
    List<SkuRow> items =
        named.query(
            base
                + " SELECT sku, product_id, product_name, storage_type, a, d, ss, reason FROM y WHERE "
                + where
                + order
                + " LIMIT :limit OFFSET :offset",
            a,
            (rs, i) -> {
              BigDecimal ss = rs.getBigDecimal("ss");
              return new SkuRow(
                  rs.getString("sku"),
                  rs.getString("product_id"),
                  rs.getString("product_name"),
                  rs.getString("storage_type"),
                  rs.getInt("a"),
                  ss == null ? null : rs.getBigDecimal("d").doubleValue(),
                  ss == null ? null : ss.doubleValue(),
                  rs.getString("reason"));
            });
    return new Page(items, total == null ? 0 : total);
  }

  /** 스트림 경계 판정용 — 그 센터 SKU 들의 SS(아는 것만). */
  public Map<String, Double> safetyQty(String mfcId, Collection<String> skus) {
    if (skus.isEmpty()) {
      return Map.of();
    }
    Map<String, Object> a = new HashMap<>();
    a.put("m", mfcId);
    a.put("skus", skus);
    Map<String, Double> out = new HashMap<>();
    named.query(
        String.format(CLASSIFIED, " AND h.center_id = :m AND h.sku IN (:skus)")
            + " SELECT sku, ss FROM y WHERE ss IS NOT NULL",
        a,
        rs -> {
          out.put(rs.getString("sku"), rs.getBigDecimal("ss").doubleValue());
        });
    return out;
  }
}
