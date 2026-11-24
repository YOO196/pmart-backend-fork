package com.pmart.mfc.inbound.infrastructure;

import com.pmart.mfc.inbound.domain.SupplyRecommendationRules.Policy;
import java.sql.Array;
import java.sql.Date;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 발주 수량 추천(MI-4)이 읽는 이 서비스의 표 — 공급 정책·인계 실적·입고 예정·승인 전 요청. */
@Component
public class SupplyRecommendationStore {

  /** 인계 실적 한 줄(센터·SKU·영업일 합). */
  public record DailySale(String sku, LocalDate businessDate, long qty) {}

  private final JdbcTemplate jdbc;

  public SupplyRecommendationStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 온도대 → 정책. 센터 줄이 전 센터 기본('*')보다 우선. */
  public Map<String, Policy> policies(String mfcId) {
    Map<String, Policy> out = new HashMap<>();
    jdbc.query(
        "SELECT * FROM supply_policy WHERE mfc_id IN (?, '*') ORDER BY (mfc_id = '*') DESC",
        rs -> {
          Integer multiple = (Integer) rs.getObject("order_multiple");
          Integer min = (Integer) rs.getObject("min_order_qty");
          out.put(
              rs.getString("storage_type"),
              new Policy(
                  rs.getString("mfc_id"),
                  rs.getBigDecimal("review_days"),
                  rs.getBigDecimal("lead_days"),
                  rs.getBigDecimal("safety_days"),
                  multiple,
                  min,
                  rs.getInt("average_window_days"),
                  rs.getInt("min_history_days")));
        },
        mfcId);
    return out;
  }

  /** 그 센터 첫 인계일(아무 SKU) — 없으면 비어 있음. */
  public Optional<LocalDate> firstSaleDate(String mfcId) {
    Date d =
        jdbc.queryForObject(
            "SELECT MIN(business_date) FROM handover_sale_daily WHERE mfc_id = ?",
            Date.class,
            mfcId);
    return Optional.ofNullable(d).map(Date::toLocalDate);
  }

  public List<DailySale> sales(
      String mfcId, Collection<String> skus, LocalDate from, LocalDate to) {
    return jdbc.query(
        "SELECT sku, business_date, qty FROM handover_sale_daily WHERE mfc_id = ? AND sku = ANY(?)"
            + " AND business_date BETWEEN ? AND ?",
        (rs, i) ->
            new DailySale(
                rs.getString("sku"), rs.getDate("business_date").toLocalDate(), rs.getLong("qty")),
        mfcId,
        array(skus),
        Date.valueOf(from),
        Date.valueOf(to));
  }

  /**
   * 입고 예정 수량 — 초기 보유·승인된 발주의 EXPECTED(initial_stock_load), TRF 입고 예정의 EXPECTED·RECEIVING(출발 줄이 있으면
   * 출발 수량, 없으면 승인 수량).
   */
  public Map<String, Long> expected(String mfcId, Collection<String> skus) {
    Map<String, Long> out = new HashMap<>();
    Array a = array(skus);
    jdbc.query(
        "SELECT l.sku, SUM(l.qty) AS q FROM initial_stock_load s JOIN initial_stock_load_line l"
            + " ON l.receipt_id = s.receipt_id WHERE s.mfc_id = ? AND s.state = 'EXPECTED' AND"
            + " l.sku = ANY(?) GROUP BY l.sku",
        rs -> {
          out.merge(rs.getString("sku"), rs.getLong("q"), Long::sum);
        },
        mfcId,
        a);
    jdbc.query(
        "SELECT x->>'sku' AS sku, SUM((x->>'qty')::bigint) AS q FROM inbound_promise p,"
            + " jsonb_array_elements(COALESCE(p.departed_lines, p.approved_lines)) x WHERE"
            + " p.mfc_id = ? AND p.state IN ('EXPECTED', 'RECEIVING') AND x->>'sku' = ANY(?)"
            + " GROUP BY x->>'sku'",
        rs -> {
          out.merge(rs.getString("sku"), rs.getLong("q"), Long::sum);
        },
        mfcId,
        a);
    return out;
  }

  /** 승인 전(SUBMITTED·ACKNOWLEDGED) 공급 요청 수량. */
  public Map<String, Long> pendingRequested(String mfcId, Collection<String> skus) {
    Map<String, Long> out = new HashMap<>();
    jdbc.query(
        "SELECT x->>'sku' AS sku, SUM((x->>'qty')::bigint) AS q FROM supply_request r,"
            + " jsonb_array_elements(r.lines) x WHERE r.mfc_id = ? AND r.state IN ('SUBMITTED',"
            + " 'ACKNOWLEDGED') AND x->>'sku' = ANY(?) GROUP BY x->>'sku'",
        rs -> {
          out.merge(rs.getString("sku"), rs.getLong("q"), Long::sum);
        },
        mfcId,
        array(skus));
    return out;
  }

  private Array array(Collection<String> skus) {
    return jdbc.execute(
        (ConnectionCallback<Array>) c -> c.createArrayOf("varchar", skus.toArray()));
  }
}
