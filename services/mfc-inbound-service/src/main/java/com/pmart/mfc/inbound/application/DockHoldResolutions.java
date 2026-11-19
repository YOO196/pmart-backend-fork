package com.pmart.mfc.inbound.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.mfc.inbound.infrastructure.InboundTaskStore;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 수령장 분실 조사 보류 정리 뒤 적치(0.18.0, 사용자 결정 2026-11-20 "수령장 분실 조사 보류는 센터 관리자 재고 정정으로"). mfc-inventory 가
 * 센터 관리자 정정(holdResolutions)으로 적치 전(NORMAL·UNSTOWED) 보류를 풀면 {@code
 * StockHoldResolved}(mfc.inventory.v1)를 낸다 — 찾은 양(foundQty)은 다시 적치할 수 있으므로 그 칸·LOT 에서 PUTAWAY
 * 작업(origin HOLD_FOUND)을 만든다. 보류를 만든 적치 예외(sourceRefs = 이 서비스의 예외 ID)가 있으면 그 작업의 입고 건·작업을
 * 이어받는다(receiptId·parentTaskId). 못 찾은 양(lostQty)은 재고에서 이미 빠졌다 — 작업 없음. 사건 줄마다 한 번(origin_ref).
 * inbox 트랜잭션 안.
 */
@Service
public class DockHoldResolutions {

  static final String ORIGIN = "HOLD_FOUND";

  private static final Logger log = LoggerFactory.getLogger(DockHoldResolutions.class);

  private final InboundTaskStore tasks;
  private final JdbcTemplate jdbc;
  private final Clock clock;

  public DockHoldResolutions(InboundTaskStore tasks, JdbcTemplate jdbc, Clock clock) {
    this.tasks = tasks;
    this.jdbc = jdbc;
    this.clock = clock;
  }

  public void onInventoryEvent(EventEnvelope e) {
    if (!"StockHoldResolved".equals(e.eventType())) {
      return;
    }
    JsonNode p = e.payload();
    String mfcId = p.path("mfcId").asString("");
    String resolutionId = p.path("resolutionId").asString(e.aggregateId());
    Instant at = clock.instant();
    List<String> created = new ArrayList<>();
    for (JsonNode l : p.path("lines")) {
      int found = l.path("foundQty").asInt();
      String sku = l.path("sku").asString("");
      String lot = l.path("lot").asString("");
      String loc = l.path("locCode").asString("");
      if (found < 1
          || !"UNSTOWED".equals(l.path("workState").asString("UNSTOWED"))
          || mfcId.isBlank()
          || sku.isBlank()
          || lot.isBlank()
          || loc.isBlank()) {
        continue;
      }
      List<String> refs = new ArrayList<>();
      l.path("sourceRefs").forEach(r -> refs.add(r.asString()));
      String receiptId = resolutionId;
      String parent = null;
      for (String ref : refs) {
        var src =
            jdbc.queryForList(
                "SELECT t.task_id, t.receipt_id FROM task_exception x"
                    + " JOIN inbound_task t ON t.task_id = x.target_id"
                    + " WHERE x.exception_id = ? AND x.mfc_id = ? AND x.target_type = 'PUTAWAY'",
                ref,
                mfcId);
        if (!src.isEmpty()) {
          parent = (String) src.get(0).get("task_id");
          receiptId = (String) src.get(0).get("receipt_id");
          break;
        }
      }
      created.add(
          tasks.insert(
              "PUTAWAY",
              mfcId,
              receiptId,
              parent,
              sku,
              lot,
              loc,
              found,
              ORIGIN,
              originRef(resolutionId, loc, lot),
              null,
              at));
    }
    if (!created.isEmpty()) {
      log.info("분실 조사 보류 정리 {} — 찾은 양 적치 작업 {}", resolutionId, created);
    }
  }

  /** 사건 줄마다 한 번 — 길면(정정 키가 긴 경우) 이름 기반 UUID 로. */
  static String originRef(String resolutionId, String loc, String lot) {
    String ref = ORIGIN + ":" + resolutionId + ":" + loc + ":" + lot;
    return ref.length() <= 200
        ? ref
        : ORIGIN + ":" + UUID.nameUUIDFromBytes(ref.getBytes(StandardCharsets.UTF_8));
  }
}
