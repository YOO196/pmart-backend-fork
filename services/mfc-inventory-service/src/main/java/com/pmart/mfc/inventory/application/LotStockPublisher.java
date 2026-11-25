package com.pmart.mfc.inventory.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code MfcLotStockChanged}(토픽 mfc.lot-stock.v1, 0.50.0 — MFC 백오피스 재고·LOT 운영자 검색, 사용자 2026-11-25).
 * LOT 하나의 지금 모습을 한 사건으로 — 검색(Logstash, 색인 mfc-lots)이 mfcId:lotId 문서로 덮어쓴다. {@link Ledger} 가
 * H·Q·E·칸이 바뀐 LOT 마다 같은 트랜잭션에서 부른다(예약 R 만 바뀐 것은 안 냄). 다 나간 LOT 은 {@code retainUntil}(마지막 움직임 + 보존
 * 기간, 기본 1년 — 사용자 결정)까지 검색에 남는다.
 */
@Component
public class LotStockPublisher {

  static final String EVENT = "MfcLotStockChanged";
  static final String AGGREGATE = "LotStock";
  static final int MAX_REPUBLISH = 20000;

  /** 바뀐 LOT 하나(센터·SKU·LOT). */
  public record LotRef(String sku, String lotId) {}

  private final JdbcTemplate jdbc;
  private final EventPublisher events;
  private final Duration retention;

  public LotStockPublisher(
      JdbcTemplate jdbc,
      EventPublisher events,
      @Value("${pmart.inventory.lot-search-retention:P365D}") Duration retention) {
    this.jdbc = jdbc;
    this.events = events;
    this.retention = retention;
  }

  public Duration retention() {
    return retention;
  }

  /** 호출자 트랜잭션 안 — 확정 단위에서 바뀐 LOT 들. */
  void changed(String mfcId, Collection<LotRef> lots, Instant at) {
    for (LotRef l : lots) {
      Map<String, Object> p = payload(mfcId, l.sku(), l.lotId(), at);
      if (p != null) {
        publish(mfcId, l.lotId(), p);
      }
    }
  }

  /**
   * 지금 모습 다시 알리기(최초 색인·복구) — 수량이 있는 LOT 과 보존 기간 안의 지난 LOT. lastMovedAt 은 원장의 그 LOT H·Q·E 마지막 확정
   * 시각(없으면 잔액 행 갱신 시각).
   */
  @Transactional
  public int republish(@Nullable String mfcId, Instant now) {
    List<Object> args = new ArrayList<>();
    String filter = "";
    if (mfcId != null) {
      filter = " AND lb.mfc_id = ?";
      args.add(mfcId);
    }
    args.add(Timestamp.from(now.minus(retention)));
    args.add(MAX_REPUBLISH + 1);
    List<Object[]> rows =
        jdbc.query(
            "SELECT lb.mfc_id, lb.sku, lb.lot_id, (SELECT MAX(sc.occurred_at) FROM stock_ledger_entry e"
                + " JOIN stock_commit sc ON sc.commit_id = e.commit_id WHERE e.mfc_id = lb.mfc_id"
                + " AND e.sku = lb.sku AND e.lot_id = lb.lot_id AND e.symbol IN ('H','Q','E')"
                + " AND e.delta <> 0) AS moved, lb.updated_at FROM stock_lot_balance lb WHERE true"
                + filter
                + " AND (lb.on_hand > 0 OR lb.updated_at >= ?) ORDER BY lb.mfc_id, lb.lot_id LIMIT ?",
            (rs, i) -> {
              Timestamp moved = rs.getTimestamp("moved");
              return new Object[] {
                rs.getString("mfc_id"),
                rs.getString("sku"),
                rs.getString("lot_id"),
                (moved != null ? moved : rs.getTimestamp("updated_at")).toInstant()
              };
            },
            args.toArray());
    if (rows.size() > MAX_REPUBLISH) {
      throw new PmartException(
          ErrorCode.DOMAIN_REJECTED, "LOT 이 " + MAX_REPUBLISH + "건을 넘습니다. 센터(mfcId)를 나눠 다시 요청하세요.");
    }
    int n = 0;
    for (Object[] r : rows) {
      Instant moved = (Instant) r[3];
      Map<String, Object> p = payload((String) r[0], (String) r[1], (String) r[2], moved);
      if (p == null) {
        continue;
      }
      Object until = p.get("retainUntil");
      if (until != null && Instant.parse((String) until).isBefore(now)) {
        continue; // 보존 기간 지난 지난 LOT
      }
      p.put("republished", true);
      publish((String) r[0], (String) r[2], p);
      n++;
    }
    return n;
  }

  private void publish(String mfcId, String lotId, Map<String, Object> payload) {
    long version = ((Number) payload.get("version")).longValue();
    events.publish(
        EVENT,
        1,
        AGGREGATE,
        mfcId + ":" + lotId,
        version,
        new EventEnvelope.Actor(EventEnvelope.ActorType.SYSTEM, events.sourceSystem()),
        payload);
  }

  private @Nullable Map<String, Object> payload(
      String mfcId, String sku, String lotId, Instant movedAt) {
    List<Map<String, Object>> head =
        jdbc.query(
            "SELECT lb.on_hand, lb.quarantined, lb.sellable, lb.reserved, lb.row_version,"
                + " l.lot_no, l.source_lot_ref, l.tracking, l.expiry_date, l.manufacture_date,"
                + " l.created_at, sp.product_id, sp.product_name, sp.barcodes, sp.storage_type"
                + " FROM stock_lot_balance lb JOIN stock_lot l ON l.lot_id = lb.lot_id"
                + " LEFT JOIN sku_projection sp ON sp.sku = lb.sku"
                + " WHERE lb.mfc_id = ? AND lb.sku = ? AND lb.lot_id = ?",
            (rs, i) -> {
              Map<String, Object> m = new LinkedHashMap<>();
              int onHand = rs.getInt("on_hand");
              m.put("mfcId", mfcId);
              m.put("lotId", lotId);
              m.put("lotNo", rs.getString("lot_no"));
              m.put("sourceLotRef", rs.getString("source_lot_ref"));
              m.put("tracking", rs.getString("tracking"));
              Date exp = rs.getDate("expiry_date");
              m.put("expiryDate", exp == null ? null : exp.toLocalDate().toString());
              Date mfg = rs.getDate("manufacture_date");
              m.put("manufactureDate", mfg == null ? null : mfg.toLocalDate().toString());
              m.put("sku", sku);
              m.put("productId", rs.getString("product_id"));
              m.put("productName", rs.getString("product_name"));
              String bc = rs.getString("barcodes");
              m.put(
                  "barcodes",
                  bc == null || bc.isBlank()
                      ? List.of()
                      : Arrays.stream(bc.split("\n")).filter(s -> !s.isBlank()).toList());
              m.put("storageType", rs.getString("storage_type"));
              m.put("onHandQty", onHand);
              m.put("quarantinedQty", rs.getInt("quarantined"));
              m.put("sellableQty", rs.getInt("sellable"));
              m.put("reservedQty", rs.getInt("reserved"));
              m.put("depleted", onHand == 0);
              Timestamp created = rs.getTimestamp("created_at");
              m.put("firstReceivedAt", created == null ? null : created.toInstant().toString());
              m.put("lastMovedAt", movedAt.toString());
              m.put("retainUntil", onHand == 0 ? movedAt.plus(retention).toString() : null);
              m.put("version", rs.getLong("row_version"));
              return m;
            },
            mfcId,
            sku,
            lotId);
    if (head.isEmpty()) {
      return null;
    }
    Map<String, Object> p = head.get(0);
    p.put(
        "locations",
        jdbc.query(
            "SELECT u.location_code, u.quality_state, u.work_state, u.qty,"
                + " (SELECT lp.temperature_zone FROM location_projection lp WHERE lp.mfc_id = u.mfc_id"
                + " AND lp.location_code = u.location_code ORDER BY lp.location_version DESC LIMIT 1)"
                + " AS temperature_zone FROM stock_unit u"
                + " WHERE u.mfc_id = ? AND u.sku = ? AND u.lot_id = ? AND u.qty > 0"
                + " ORDER BY u.location_code, u.quality_state, u.work_state",
            (rs, i) -> {
              Map<String, Object> u = new LinkedHashMap<>();
              u.put("locationCode", rs.getString("location_code"));
              u.put("qualityState", rs.getString("quality_state"));
              u.put("workState", rs.getString("work_state"));
              u.put("qty", rs.getInt("qty"));
              u.put("temperatureZone", rs.getString("temperature_zone"));
              return u;
            },
            mfcId,
            sku,
            lotId));
    return p;
  }
}
