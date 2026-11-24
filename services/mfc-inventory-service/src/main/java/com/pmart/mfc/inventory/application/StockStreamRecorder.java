package com.pmart.mfc.inventory.application;

import com.pmart.mfc.inventory.domain.StockRules.Balance;
import com.pmart.mfc.inventory.domain.StockUnit.Change;
import com.pmart.mfc.inventory.domain.StockUnit.Key;
import com.pmart.mfc.inventory.infrastructure.StockStreamStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 재고 실시간 스트림 기록(0.46.0). {@link Ledger} 가 수량을 바꾼 확정 단위마다 같은 트랜잭션에서 부른다 — 바뀐 뒤 센터 값·단위 증감·보류 증감을 한
 * 줄로 남기고 알린다(커밋 뒤에만 나간다). 보내기는 {@code api.StockStreams}.
 */
@Component
public class StockStreamRecorder {

  /** SKU 하나의 바뀐 뒤 값과 이번 증감(E·R). */
  record SkuAfter(Balance after, int de, int dr) {}

  private final StockStreamStore store;
  private final ObjectMapper mapper;

  public StockStreamRecorder(StockStreamStore store, ObjectMapper mapper) {
    this.store = store;
    this.mapper = mapper;
  }

  void record(
      String commitId,
      String commitType,
      String mfcId,
      Map<String, SkuAfter> skus,
      List<Change> units,
      List<Ledger.HoldChange> holds,
      Instant at) {
    if (skus.isEmpty()) {
      return;
    }
    List<Map<String, Object>> items = new ArrayList<>();
    List<String> crossed = new ArrayList<>();
    skus.forEach(
        (sku, s) -> {
          Balance b = s.after();
          int a = b.available();
          int delta = s.de() - s.dr();
          if ((a - delta <= 0) != (a <= 0)) {
            crossed.add(sku);
          }
          Map<String, Object> m = new LinkedHashMap<>();
          m.put("sku", sku);
          m.put("onHandQty", b.onHand());
          m.put("quarantinedQty", b.quarantined());
          m.put("sellableQty", b.sellable());
          m.put("reservedQty", b.reserved());
          m.put("availableQty", a);
          m.put("availableDelta", delta);
          m.put("version", b.version());
          items.add(m);
        });
    Map<String, Integer> unitNet = new TreeMap<>();
    Map<String, Key> unitKeys = new TreeMap<>();
    for (Change c : units) {
      String k = String.valueOf(c.key());
      unitNet.merge(k, c.delta(), Integer::sum);
      unitKeys.putIfAbsent(k, c.key());
    }
    List<Map<String, Object>> unitOut = new ArrayList<>();
    unitNet.forEach(
        (k, d) -> {
          if (d == 0) {
            return;
          }
          Key key = unitKeys.get(k);
          Map<String, Object> m = new LinkedHashMap<>();
          m.put("sku", key.sku());
          m.put("lotId", key.lotId());
          m.put("locationCode", key.locationCode());
          m.put("qualityState", key.quality().name());
          m.put("workState", key.work().name());
          m.put("delta", d);
          unitOut.add(m);
        });
    List<Map<String, Object>> holdOut = new ArrayList<>();
    for (Ledger.HoldChange h : holds) {
      if (h.delta() == 0) {
        continue;
      }
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("sku", h.key().sku());
      m.put("lotId", h.key().lotId());
      m.put("locationCode", h.key().locationCode());
      m.put("delta", h.delta());
      holdOut.add(m);
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("mfcId", mfcId);
    payload.put("commitId", commitId);
    payload.put("cause", commitType);
    payload.put("occurredAt", at.toString());
    payload.put("items", items);
    payload.put("units", unitOut);
    payload.put("holds", holdOut);
    payload.put("summarySkus", crossed);
    store.append(mfcId, commitId, commitType, at, mapper.writeValueAsString(payload));
  }
}
