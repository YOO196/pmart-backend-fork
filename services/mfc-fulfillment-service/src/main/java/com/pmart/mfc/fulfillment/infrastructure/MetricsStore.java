package com.pmart.mfc.fulfillment.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 결품률 지표(F-10, 2026-11-19) — 날짜(Asia/Seoul, 주문 피킹 시작)·센터별 PICK 줄·결품 줄. */
@Repository
public class MetricsStore {

  /** 칸 단위(0.34.0) — SQL date_trunc 단위. */
  public enum Bucket {
    HOUR,
    DAY,
    WEEK,
    MONTH
  }

  /** {@code start} = 칸 시작(Asia/Seoul 현지 시각 — DAY·WEEK·MONTH 는 자정). */
  public record PickingLines(
      LocalDateTime start,
      String mfcId,
      int pickedLines,
      int shortLines,
      int shortShort,
      int shortDamaged,
      int shortBlocked,
      int shortSpoiled) {}

  private final NamedParameterJdbcTemplate named;

  public MetricsStore(NamedParameterJdbcTemplate named) {
    this.named = named;
  }

  /** [from, to) 시각, centers 가 null 이면 모든 센터. 원래 주문 작업만(재발송 제외). */
  public List<PickingLines> pickingLines(
      Instant from, Instant to, @Nullable Collection<String> centers, Bucket bucket) {
    Map<String, Object> a = new LinkedHashMap<>();
    a.put("from", Timestamp.from(from));
    a.put("to", Timestamp.from(to));
    String centerFilter = "";
    if (centers != null) {
      if (centers.isEmpty()) {
        return List.of();
      }
      a.put("centers", centers);
      centerFilter = " AND w.mfc_id IN (:centers)";
    }
    String sql =
        "WITH lines AS (SELECT DISTINCT date_trunc('"
            + bucket.name().toLowerCase(java.util.Locale.ROOT)
            + "', w.preparation_started_at AT TIME ZONE 'Asia/Seoul') AS d,"
            + " w.mfc_id, t.task_id, l.order_line_id FROM work_order w"
            + " JOIN shipment s ON s.fulfillment_id = w.fulfillment_id"
            + " JOIN task t ON t.shipment_id = s.shipment_id AND t.task_type = 'PICK'"
            + " JOIN task_line l ON l.task_id = t.task_id"
            + " WHERE w.kind = 'ORIGINAL' AND w.preparation_started_at >= :from"
            + " AND w.preparation_started_at < :to"
            + centerFilter
            + "), marked AS (SELECT x.d, x.mfc_id,"
            // 관리자 부분 종료(PARTIAL_CLOSE, 0.30.0)는 재고 결품이 아니라 세지 않는다
            + " EXISTS (SELECT 1 FROM line_shortage z WHERE z.task_id = x.task_id AND z.order_line_id = x.order_line_id AND z.reason <> 'PARTIAL_CLOSE') AS any_short,"
            + " EXISTS (SELECT 1 FROM line_shortage z WHERE z.task_id = x.task_id AND z.order_line_id = x.order_line_id AND z.reason = 'SHORT') AS s_short,"
            + " EXISTS (SELECT 1 FROM line_shortage z WHERE z.task_id = x.task_id AND z.order_line_id = x.order_line_id AND z.reason = 'DAMAGED') AS s_damaged,"
            + " EXISTS (SELECT 1 FROM line_shortage z WHERE z.task_id = x.task_id AND z.order_line_id = x.order_line_id AND z.reason = 'BLOCKED') AS s_blocked,"
            + " EXISTS (SELECT 1 FROM line_shortage z WHERE z.task_id = x.task_id AND z.order_line_id = x.order_line_id AND z.reason = 'SPOILED') AS s_spoiled"
            + " FROM lines x)"
            + " SELECT d, mfc_id, COUNT(*) AS picked, COUNT(*) FILTER (WHERE any_short) AS short_lines,"
            + " COUNT(*) FILTER (WHERE s_short) AS r_short, COUNT(*) FILTER (WHERE s_damaged) AS r_damaged,"
            + " COUNT(*) FILTER (WHERE s_blocked) AS r_blocked, COUNT(*) FILTER (WHERE s_spoiled) AS r_spoiled FROM marked GROUP BY d, mfc_id ORDER BY d, mfc_id";
    return named.query(
        sql,
        a,
        (rs, i) ->
            new PickingLines(
                rs.getObject("d", LocalDateTime.class),
                rs.getString("mfc_id"),
                rs.getInt("picked"),
                rs.getInt("short_lines"),
                rs.getInt("r_short"),
                rs.getInt("r_damaged"),
                rs.getInt("r_blocked"),
                rs.getInt("r_spoiled")));
  }

  /** 출하 약속(0.35.0) 한 칸 — {@code start} = 칸 시작(Asia/Seoul 현지 시각). late = due − onTime. */
  public record ShipPromises(LocalDateTime start, String mfcId, int due, int onTime, int pending) {}

  /**
   * 출하 약속 준수(0.35.0, 사용자 결정 2026-11-24) — 약속 = 배차 요청 예상 준비 시각, 끝 = 출고 READY 시각. 약속이 [from, to) 인 취소
   * 안 된 출고, centers 가 null 이면 모든 센터. due·pending 은 now 로 가른다.
   */
  public List<ShipPromises> shipPromises(
      Instant from, Instant to, Instant now, @Nullable Collection<String> centers, Bucket bucket) {
    Map<String, Object> a = new LinkedHashMap<>();
    a.put("from", Timestamp.from(from));
    a.put("to", Timestamp.from(to));
    a.put("now", Timestamp.from(now));
    String centerFilter = "";
    if (centers != null) {
      if (centers.isEmpty()) {
        return List.of();
      }
      a.put("centers", centers);
      centerFilter = " AND w.mfc_id IN (:centers)";
    }
    String sql =
        "SELECT date_trunc('"
            + bucket.name().toLowerCase(java.util.Locale.ROOT)
            + "', s.dispatch_estimated_ready_at AT TIME ZONE 'Asia/Seoul') AS d, w.mfc_id,"
            + " COUNT(*) FILTER (WHERE s.dispatch_estimated_ready_at < :now) AS due,"
            + " COUNT(*) FILTER (WHERE s.dispatch_estimated_ready_at < :now AND s.ready_at IS NOT NULL"
            + " AND s.ready_at <= s.dispatch_estimated_ready_at) AS on_time,"
            + " COUNT(*) FILTER (WHERE s.dispatch_estimated_ready_at >= :now) AS pending"
            + " FROM shipment s JOIN work_order w ON w.fulfillment_id = s.fulfillment_id"
            + " WHERE s.state <> 'CANCELLED' AND s.dispatch_estimated_ready_at >= :from"
            + " AND s.dispatch_estimated_ready_at < :to"
            + centerFilter
            + " GROUP BY d, w.mfc_id ORDER BY d, w.mfc_id";
    return named.query(
        sql,
        a,
        (rs, i) ->
            new ShipPromises(
                rs.getObject("d", LocalDateTime.class),
                rs.getString("mfc_id"),
                rs.getInt("due"),
                rs.getInt("on_time"),
                rs.getInt("pending")));
  }

  public record SkuLines(
      String sku,
      @Nullable String productName,
      int pickedLines,
      int shortLines,
      int shortShort,
      int shortDamaged,
      int shortBlocked,
      int shortSpoiled) {}

  /**
   * 상품별(F-13, 2026-11-20) — {@link #pickingLines} 와 같은 줄·결품 판정을 기간 전체에 대해 SKU 로 묶는다. 결품 0 SKU 제외.
   *
   * @param byRate true 면 결품률 순(피킹 줄 ≥ minPicked 만), false 면 결품 줄 순
   */
  public List<SkuLines> pickingLinesBySku(
      Instant from,
      Instant to,
      @Nullable Collection<String> centers,
      boolean byRate,
      int minPicked,
      int limit) {
    Map<String, Object> a = new LinkedHashMap<>();
    a.put("from", Timestamp.from(from));
    a.put("to", Timestamp.from(to));
    a.put("minPicked", byRate ? minPicked : 0);
    a.put("limit", limit);
    String centerFilter = "";
    if (centers != null) {
      if (centers.isEmpty()) {
        return List.of();
      }
      a.put("centers", centers);
      centerFilter = " AND w.mfc_id IN (:centers)";
    }
    String order =
        byRate
            ? " ORDER BY short_lines::numeric / picked DESC, short_lines DESC, sku"
            : " ORDER BY short_lines DESC, picked DESC, sku";
    String sql =
        "WITH lines AS (SELECT DISTINCT t.task_id, l.order_line_id, sl.sku, sl.product_name,"
            + " w.preparation_started_at AS started FROM work_order w"
            + " JOIN shipment s ON s.fulfillment_id = w.fulfillment_id"
            + " JOIN task t ON t.shipment_id = s.shipment_id AND t.task_type = 'PICK'"
            + " JOIN task_line l ON l.task_id = t.task_id"
            + " JOIN shipment_line sl ON sl.shipment_id = l.shipment_id AND sl.order_line_id = l.order_line_id"
            + " WHERE w.kind = 'ORIGINAL' AND w.preparation_started_at >= :from"
            + " AND w.preparation_started_at < :to"
            + centerFilter
            + "), marked AS (SELECT x.sku, x.product_name, x.started,"
            // 관리자 부분 종료(PARTIAL_CLOSE)는 재고 결품이 아니라 세지 않는다 — F-10 과 같게
            + " EXISTS (SELECT 1 FROM line_shortage z WHERE z.task_id = x.task_id AND z.order_line_id = x.order_line_id AND z.reason <> 'PARTIAL_CLOSE') AS any_short,"
            + " EXISTS (SELECT 1 FROM line_shortage z WHERE z.task_id = x.task_id AND z.order_line_id = x.order_line_id AND z.reason = 'SHORT') AS s_short,"
            + " EXISTS (SELECT 1 FROM line_shortage z WHERE z.task_id = x.task_id AND z.order_line_id = x.order_line_id AND z.reason = 'DAMAGED') AS s_damaged,"
            + " EXISTS (SELECT 1 FROM line_shortage z WHERE z.task_id = x.task_id AND z.order_line_id = x.order_line_id AND z.reason = 'BLOCKED') AS s_blocked,"
            + " EXISTS (SELECT 1 FROM line_shortage z WHERE z.task_id = x.task_id AND z.order_line_id = x.order_line_id AND z.reason = 'SPOILED') AS s_spoiled"
            + " FROM lines x), agg AS (SELECT sku,"
            // 상품명 사본은 기간 안 가장 최근 것
            + " (ARRAY_AGG(product_name ORDER BY started DESC) FILTER (WHERE product_name IS NOT NULL))[1] AS product_name,"
            + " COUNT(*) AS picked, COUNT(*) FILTER (WHERE any_short) AS short_lines,"
            + " COUNT(*) FILTER (WHERE s_short) AS r_short, COUNT(*) FILTER (WHERE s_damaged) AS r_damaged,"
            + " COUNT(*) FILTER (WHERE s_blocked) AS r_blocked, COUNT(*) FILTER (WHERE s_spoiled) AS r_spoiled"
            + " FROM marked GROUP BY sku)"
            + " SELECT * FROM agg WHERE short_lines > 0 AND picked >= :minPicked"
            + order
            + " LIMIT :limit";
    return named.query(
        sql,
        a,
        (rs, i) ->
            new SkuLines(
                rs.getString("sku"),
                rs.getString("product_name"),
                rs.getInt("picked"),
                rs.getInt("short_lines"),
                rs.getInt("r_short"),
                rs.getInt("r_damaged"),
                rs.getInt("r_blocked"),
                rs.getInt("r_spoiled")));
  }
}
