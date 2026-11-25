package com.pmart.mfc.inventory.infrastructure;

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
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 재고 이력(0.48.0) — 원장 {@code stock_commit}·{@code stock_ledger_entry} 를 SKU 하나로 읽는다. 확정 단위 순서 = 그
 * SKU 줄의 가장 큰 entry_id(같은 SKU 는 잔액 행을 잠그고 쓰므로 줄 번호 순서가 반영 순서). 확정 단위 뒤 센터 합계 = 지금 합계 − 그 뒤 증감 합.
 */
@Repository
public class StockLedgerStore {

  public record Totals(int h, int q, int e, int r) {
    public int available() {
      return e - r;
    }
  }

  public record Entry(
      String symbol,
      int delta,
      @Nullable String lotId,
      @Nullable String lotNo,
      @Nullable LocalDate expiryDate,
      @Nullable String locationCode,
      @Nullable String qualityState,
      @Nullable String workState) {}

  public record CommitRow(
      long orderKey,
      String commitId,
      String cause,
      Instant occurredAt,
      String sourceSystem,
      @Nullable String sourceRef,
      @Nullable String orderId,
      @Nullable String actorOperatorId,
      Totals delta,
      Totals after,
      List<Entry> entries) {}

  public record Filter(
      @Nullable String lotId,
      @Nullable String locationCode,
      @Nullable Collection<String> causes,
      @Nullable Instant from,
      @Nullable Instant to,
      @Nullable Long before,
      @Nullable String orderId) {}

  private final NamedParameterJdbcTemplate named;

  public StockLedgerStore(NamedParameterJdbcTemplate named) {
    this.named = named;
  }

  /** 지금 센터 합계(재고 행이 없으면 0). */
  public Totals current(String mfcId, String sku) {
    List<Totals> t =
        named.query(
            "SELECT on_hand, quarantined, sellable, reserved FROM stock_balance"
                + " WHERE mfc_id = :m AND sku = :s",
            Map.of("m", mfcId, "s", sku),
            (rs, i) -> new Totals(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)));
    return t.isEmpty() ? new Totals(0, 0, 0, 0) : t.get(0);
  }

  public @Nullable String productName(String sku) {
    List<String> n =
        named.queryForList(
            "SELECT product_name FROM sku_projection WHERE sku = :s",
            Map.of("s", sku),
            String.class);
    return n.isEmpty() ? null : n.get(0);
  }

  /** 최신순 확정 단위 최대 limit 개(줄 포함). */
  public List<CommitRow> commits(String mfcId, String sku, Filter f, Totals now, int limit) {
    Map<String, Object> a = new HashMap<>();
    a.put("m", mfcId);
    a.put("s", sku);
    a.put("limit", limit);
    a.put("nh", now.h());
    a.put("nq", now.q());
    a.put("ne", now.e());
    a.put("nr", now.r());
    StringBuilder where = new StringBuilder(" WHERE true");
    if (f.before() != null) {
      a.put("before", f.before());
      where.append(" AND x.k < :before");
    }
    if (f.from() != null) {
      a.put("from", Timestamp.from(f.from()));
      where.append(" AND sc.occurred_at >= :from");
    }
    if (f.to() != null) {
      a.put("to", Timestamp.from(f.to()));
      where.append(" AND sc.occurred_at < :to");
    }
    if (f.orderId() != null) {
      a.put("order", f.orderId());
      where.append(" AND r.order_id = :order");
    }
    if (f.causes() != null && !f.causes().isEmpty()) {
      a.put("causes", f.causes());
      where.append(" AND sc.commit_type IN (:causes)");
    }
    if (f.lotId() != null || f.locationCode() != null) {
      where.append(
          " AND EXISTS (SELECT 1 FROM stock_ledger_entry z WHERE z.commit_id = x.commit_id"
              + " AND z.mfc_id = :m AND z.sku = :s");
      if (f.lotId() != null) {
        a.put("lot", f.lotId());
        where.append(" AND z.lot_id = :lot");
      }
      if (f.locationCode() != null) {
        a.put("loc", f.locationCode());
        where.append(" AND z.location_code = :loc");
      }
      where.append(")");
    }
    String sql =
        "WITH c AS (SELECT commit_id, MAX(entry_id) AS k,"
            + " COALESCE(SUM(delta) FILTER (WHERE symbol = 'H'), 0) AS dh,"
            + " COALESCE(SUM(delta) FILTER (WHERE symbol = 'Q'), 0) AS dq,"
            + " COALESCE(SUM(delta) FILTER (WHERE symbol = 'E'), 0) AS de,"
            + " COALESCE(SUM(delta) FILTER (WHERE symbol = 'R'), 0) AS dr"
            + " FROM stock_ledger_entry WHERE mfc_id = :m AND sku = :s GROUP BY commit_id),"
            // 그 뒤(더 큰 k) 확정 단위들의 증감 합 — 지금 합계에서 빼면 그 확정 단위 뒤 합계
            + " x AS (SELECT c.*,"
            + " COALESCE(SUM(dh) OVER w, 0) AS lh, COALESCE(SUM(dq) OVER w, 0) AS lq,"
            + " COALESCE(SUM(de) OVER w, 0) AS le, COALESCE(SUM(dr) OVER w, 0) AS lr FROM c"
            + " WINDOW w AS (ORDER BY k DESC ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING))"
            + " SELECT x.*, sc.commit_type, sc.occurred_at, sc.source_system, sc.source_ref,"
            + " r.order_id,"
            // 처리한 사람(0.49.0): 작업 실행의 작업자, 아니면 직원이 직접 부른 요청의 주체(서비스·익명은 빼기)
            + " COALESCE(se.performed_by, CASE WHEN rr.actor <> 'anonymous'"
            + " AND rr.actor <> sc.source_system THEN rr.actor END) AS actor"
            + " FROM x JOIN stock_commit sc ON sc.commit_id = x.commit_id"
            + " LEFT JOIN stock_execution se ON se.commit_id = x.commit_id"
            + " LEFT JOIN package_handover_posting ph ON ph.commit_id = x.commit_id"
            // 주문(0.49.0): 실적의 예약(PICK·PACK) → 인계의 예약 → 출처가 예약인 확정(예약·풀림·만료·결품·취소 복귀)
            + " LEFT JOIN reservation r ON r.reservation_id = COALESCE(se.reservation_id,"
            + " ph.reservation_id, sc.source_ref) AND r.mfc_id = sc.mfc_id"
            + " LEFT JOIN request_record rr ON rr.source_system = sc.source_system"
            + " AND rr.request_id = sc.request_id"
            + where
            + " ORDER BY x.k DESC LIMIT :limit";
    List<CommitRow> rows = new ArrayList<>();
    named.query(
        sql,
        a,
        rs -> {
          rows.add(
              new CommitRow(
                  rs.getLong("k"),
                  rs.getString("commit_id"),
                  rs.getString("commit_type"),
                  rs.getTimestamp("occurred_at").toInstant(),
                  rs.getString("source_system"),
                  rs.getString("source_ref"),
                  rs.getString("order_id"),
                  rs.getString("actor"),
                  new Totals(rs.getInt("dh"), rs.getInt("dq"), rs.getInt("de"), rs.getInt("dr")),
                  new Totals(
                      now.h() - rs.getInt("lh"),
                      now.q() - rs.getInt("lq"),
                      now.e() - rs.getInt("le"),
                      now.r() - rs.getInt("lr")),
                  new ArrayList<>()));
        });
    if (rows.isEmpty()) {
      return rows;
    }
    Map<String, List<Entry>> byCommit = new LinkedHashMap<>();
    rows.forEach(r -> byCommit.put(r.commitId(), r.entries()));
    named.query(
        "SELECT e.commit_id, e.symbol, e.delta, e.lot_id, l.lot_no, l.expiry_date, e.location_code,"
            + " e.quality_state, e.work_state FROM stock_ledger_entry e"
            + " LEFT JOIN stock_lot l ON l.lot_id = e.lot_id"
            + " WHERE e.mfc_id = :m AND e.sku = :s AND e.commit_id IN (:ids) AND e.delta <> 0"
            + " ORDER BY e.entry_id",
        Map.of("m", mfcId, "s", sku, "ids", byCommit.keySet()),
        rs -> {
          java.sql.Date exp = rs.getDate("expiry_date");
          byCommit
              .get(rs.getString("commit_id"))
              .add(
                  new Entry(
                      rs.getString("symbol"),
                      rs.getInt("delta"),
                      rs.getString("lot_id"),
                      rs.getString("lot_no"),
                      exp == null ? null : exp.toLocalDate(),
                      rs.getString("location_code"),
                      rs.getString("quality_state"),
                      rs.getString("work_state")));
        });
    return rows;
  }
}
