package com.pmart.mfc.inventory.infrastructure;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 센터별 품절 상품 수(IV-5, 2026-11-19) — 취급 ON 상품 중 가용(E − R) ≤ 0. */
@Repository
public class StockSummaryStore {

  public record Summary(String mfcId, int handled, int soldOut) {}

  private final NamedParameterJdbcTemplate named;

  public StockSummaryStore(NamedParameterJdbcTemplate named) {
    this.named = named;
  }

  /** centers 가 null 이면 모든 센터. */
  public List<Summary> summaries(@Nullable Collection<String> centers) {
    Map<String, Object> a = new LinkedHashMap<>();
    String filter = "";
    if (centers != null) {
      if (centers.isEmpty()) {
        return List.of();
      }
      a.put("centers", centers);
      filter = " AND h.center_id IN (:centers)";
    }
    return named.query(
        "SELECT h.center_id, COUNT(*) AS handled,"
            + " COUNT(*) FILTER (WHERE COALESCE(b.sellable - b.reserved, 0) <= 0) AS sold_out"
            + " FROM center_sku_handling h LEFT JOIN stock_balance b"
            + " ON b.mfc_id = h.center_id AND b.sku = h.sku"
            + " WHERE h.handling_enabled"
            + filter
            + " GROUP BY h.center_id ORDER BY h.center_id",
        a,
        (rs, i) ->
            new Summary(rs.getString("center_id"), rs.getInt("handled"), rs.getInt("sold_out")));
  }
}
