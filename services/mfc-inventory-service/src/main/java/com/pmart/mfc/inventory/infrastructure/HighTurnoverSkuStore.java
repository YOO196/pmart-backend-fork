package com.pmart.mfc.inventory.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 고회전 상품 순위 {@code high_turnover_sku}(V30, 2026-11-23 R-40). 센터마다 상온 상위 N SKU — 주문 줄 수는 이 서비스의 예약
 * 기록 ({@code reservation}·{@code reservation_part})으로 센다(다른 서비스 호출 없음).
 */
@Repository
public class HighTurnoverSkuStore {

  /** 순위 한 줄. */
  public record Ranked(String sku, int orderLines) {}

  private final JdbcTemplate jdbc;

  public HighTurnoverSkuStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * 집계: {@code since} 뒤에 만든 예약 중 주문이 확정된 것(CONFIRMED·CONSUMED — 결제 전 만료·해제, 취소는 뺌)의 주문 줄 수, 상품 보관
   * 유형 {@code storageType} 만, 많은 순(같으면 SKU 순) 최대 {@code limit}. 주문 줄 = (주문, 주문 줄 번호) — 줄 번호가 없으면 주문
   * 하나를 한 줄로.
   */
  public List<Ranked> rank(String mfcId, String storageType, Instant since, int limit) {
    return jdbc.query(
        "SELECT p.sku, count(DISTINCT r.order_id || '|' || coalesce(p.order_line_id, '')) AS n"
            + " FROM reservation r JOIN reservation_part p ON p.reservation_id = r.reservation_id"
            + " JOIN sku_projection s ON s.sku = p.sku"
            + " WHERE r.mfc_id = ? AND r.created_at >= ? AND r.state IN ('CONFIRMED', 'CONSUMED')"
            + " AND s.storage_type = ?"
            + " GROUP BY p.sku ORDER BY n DESC, p.sku LIMIT ?",
        (rs, i) -> new Ranked(rs.getString("sku"), rs.getInt("n")),
        mfcId,
        Timestamp.from(since),
        storageType,
        limit);
  }

  /** 그 센터 순위를 통째로 바꾼다(같은 트랜잭션 안에서 부를 것). */
  public void replace(String mfcId, List<Ranked> ranked, Instant since, Instant now) {
    jdbc.update("DELETE FROM high_turnover_sku WHERE mfc_id = ?", mfcId);
    int rank = 1;
    for (Ranked r : ranked) {
      jdbc.update(
          "INSERT INTO high_turnover_sku (mfc_id, sku, rank, order_lines, window_from, computed_at)"
              + " VALUES (?, ?, ?, ?, ?, ?)",
          mfcId,
          r.sku(),
          rank++,
          r.orderLines(),
          Timestamp.from(since),
          Timestamp.from(now));
    }
  }

  /** 지금 순위에 든 SKU. */
  public Set<String> skus(String mfcId) {
    return new HashSet<>(
        jdbc.queryForList(
            "SELECT sku FROM high_turnover_sku WHERE mfc_id = ?", String.class, mfcId));
  }

  /** 순위를 매길 센터 — 위치 사본이 있는 센터. */
  public List<String> centers() {
    return jdbc.queryForList(
        "SELECT DISTINCT mfc_id FROM location_projection ORDER BY mfc_id", String.class);
  }
}
