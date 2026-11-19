package com.pmart.mfc.inventory.application;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * LOT 을 집품·인계한 주문 줄(0.31.0, 품질 사고 고객 영향 R6) — 서비스 간(mfc-quality). 예약 조각의 lot 이 같고 집품 또는 인계가 있는 줄만.
 * 인계(handed) > 0 = 고객에게 나감.
 */
@Service
public class LotOrders {

  public record Line(
      String orderId,
      String reservationId,
      @Nullable String orderLineId,
      String lotId,
      String sku,
      int pickedQty,
      int packedQty,
      int handedQty) {}

  public record Result(List<Line> items, boolean truncated) {}

  static final int MAX_LOTS = 50;
  static final int MAX_ROWS = 1000;

  private final NamedParameterJdbcTemplate jdbc;

  public LotOrders(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(readOnly = true)
  public Result of(String mfcId, @Nullable List<String> lotIds) {
    if (mfcId == null || mfcId.isBlank()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "mfcId 가 필요합니다.");
    }
    if (lotIds == null || lotIds.isEmpty() || lotIds.size() > MAX_LOTS) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "lotIds 는 1~" + MAX_LOTS + "개입니다.");
    }
    List<Line> rows =
        jdbc.query(
            "SELECT r.order_id, p.reservation_id, p.order_line_id, p.lot_id, p.sku, p.picked_qty,"
                + " p.packed_qty, p.handed_qty FROM reservation_part p"
                + " JOIN reservation r ON r.reservation_id = p.reservation_id"
                + " WHERE r.mfc_id = :mfc AND p.lot_id IN (:lots) AND (p.picked_qty > 0 OR p.handed_qty > 0)"
                + " ORDER BY r.order_id, p.reservation_id, p.order_line_id, p.lot_id LIMIT :lim",
            Map.of(
                "mfc",
                mfcId,
                "lots",
                new ArrayList<>(new LinkedHashSet<>(lotIds)),
                "lim",
                MAX_ROWS + 1),
            (rs, i) ->
                new Line(
                    rs.getString("order_id"),
                    rs.getString("reservation_id"),
                    rs.getString("order_line_id"),
                    rs.getString("lot_id"),
                    rs.getString("sku"),
                    rs.getInt("picked_qty"),
                    rs.getInt("packed_qty"),
                    rs.getInt("handed_qty")));
    boolean more = rows.size() > MAX_ROWS;
    return new Result(more ? rows.subList(0, MAX_ROWS) : rows, more);
  }
}
