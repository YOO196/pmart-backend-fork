package com.pmart.mfc.inventory.application;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** LOT 번호 일괄 조회(0.30.0, ADR-0070 — 화면은 LOT ID 대신 LOT 번호). 그 센터 운영자, 1~200개. */
@Service
public class LotNumbers {

  public record LotNumber(
      String lotId,
      @Nullable String lotNo,
      String sku,
      @Nullable String productName,
      @Nullable LocalDate expiryDate) {}

  static final int MAX = 200;

  private final NamedParameterJdbcTemplate jdbc;

  public LotNumbers(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(readOnly = true)
  public List<LotNumber> of(String mfcId, @Nullable List<String> lotIds) {
    Operator.requireCenter(mfcId);
    if (lotIds == null || lotIds.isEmpty() || lotIds.size() > MAX) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "lotIds 는 1~" + MAX + "개입니다.");
    }
    List<String> ids = new ArrayList<>(new LinkedHashSet<>(lotIds));
    return jdbc.query(
        "SELECT l.lot_id, l.lot_no, l.sku, p.product_name, l.expiry_date FROM stock_lot l"
            + " LEFT JOIN sku_projection p ON p.sku = l.sku WHERE l.lot_id IN (:ids) ORDER BY l.lot_id",
        Map.of("ids", ids),
        (rs, i) ->
            new LotNumber(
                rs.getString("lot_id"),
                rs.getString("lot_no"),
                rs.getString("sku"),
                rs.getString("product_name"),
                rs.getObject("expiry_date", LocalDate.class)));
  }
}
