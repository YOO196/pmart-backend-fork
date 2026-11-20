package com.pmart.mfc.inventory.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 품질 사고 LOT 판매 보류 표 {@code lot_sale_hold}(V26, 0.39.0). */
@Repository
public class LotSaleHoldStore {

  public record HoldRow(String mfcId, String lotId, String sourceRef, String sku, String state) {}

  /** 센터·LOT 한 쌍(주기 작업 대상). */
  public record LotRef(String mfcId, String lotId) {}

  private final JdbcTemplate jdbc;

  public LotSaleHoldStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 없으면 ACTIVE 로 넣는다. 이미 있으면(풀린 것 포함) 그대로 — 풀린 사고 보류를 같은 요청 재시도가 되살리지 않게. */
  public void insertIfAbsent(String mfcId, String lotId, String sourceRef, String sku, Instant at) {
    jdbc.update(
        "INSERT INTO lot_sale_hold (mfc_id, lot_id, source_ref, sku, state, created_at)"
            + " VALUES (?,?,?,?,'ACTIVE',?) ON CONFLICT (mfc_id, lot_id, source_ref) DO NOTHING",
        mfcId,
        lotId,
        sourceRef,
        sku,
        Timestamp.from(at));
  }

  /** 그 센터·LOT 의 모든 보류 행을 잠근다(걸기·풀기·주기 작업 직렬화). */
  public List<HoldRow> lockLot(String mfcId, String lotId) {
    return jdbc.query(
        "SELECT mfc_id, lot_id, source_ref, sku, state FROM lot_sale_hold"
            + " WHERE mfc_id = ? AND lot_id = ? ORDER BY source_ref FOR UPDATE",
        (rs, i) ->
            new HoldRow(
                rs.getString("mfc_id"),
                rs.getString("lot_id"),
                rs.getString("source_ref"),
                rs.getString("sku"),
                rs.getString("state")),
        mfcId,
        lotId);
  }

  public void release(String mfcId, String lotId, String sourceRef, Instant at) {
    jdbc.update(
        "UPDATE lot_sale_hold SET state = 'RELEASED', released_at = ?"
            + " WHERE mfc_id = ? AND lot_id = ? AND source_ref = ? AND state = 'ACTIVE'",
        Timestamp.from(at),
        mfcId,
        lotId,
        sourceRef);
  }

  /** 보류가 걸린 센터·LOT(주기 작업). */
  public List<LotRef> activeLots(int limit) {
    return jdbc.query(
        "SELECT DISTINCT mfc_id, lot_id FROM lot_sale_hold WHERE state = 'ACTIVE'"
            + " ORDER BY mfc_id, lot_id LIMIT ?",
        (rs, i) -> new LotRef(rs.getString("mfc_id"), rs.getString("lot_id")),
        limit);
  }
}
