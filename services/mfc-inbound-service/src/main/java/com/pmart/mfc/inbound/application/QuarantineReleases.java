package com.pmart.mfc.inbound.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.mfc.inbound.infrastructure.InboundTaskStore;
import com.pmart.mfc.inbound.infrastructure.InboundTaskStore.TaskRow;
import com.pmart.mfc.inbound.infrastructure.InventoryMoveClient;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 품질 해제·폐기 뒤 입고 작업 정리(0.17.0, 사용자 결정 2026-11-20 6번). mfc-quality 사건(mfc.quality.v1, 재고 반영이 끝난 사실만)을
 * inbox 트랜잭션 안에서 받는다.
 *
 * <ul>
 *   <li>{@code QualityDecisionMade}(decision RELEASE, 줄 하나) — ① 그 칸·LOT 의 READY 격리 이동 작업에서 해제된 양을
 *       뺀다(옮길 필요 없음) ② 그 LOT 이 아직 적치 전(NORMAL·UNSTOWED)으로 남은 칸 — 사건의 칸, 그다음 격리 구역 칸 — 에서 PUTAWAY
 *       작업(origin RELEASE). 같은 칸의 READY 적치 작업 잔량은 이미 덮인 것으로 빼서 두 번 만들지 않는다. 사건 줄마다 한 번(origin_ref).
 *       선반 칸 해제는 그 자리에서 판매 가능이라 작업이 없다.
 *   <li>{@code DisposalApproved} — 줄의 칸·LOT 의 READY 격리 이동 작업에서 폐기된 양을 뺀다.
 * </ul>
 *
 * 재고 위치 조회(mfc-inventory 스냅샷·격리 칸 추천)가 실패하면 예외로 사건을 다시 받는다(inbox 롤백).
 */
@Service
public class QuarantineReleases {

  private static final Logger log = LoggerFactory.getLogger(QuarantineReleases.class);

  private final InboundTaskStore tasks;
  private final InventoryMoveClient inventory;
  private final Clock clock;

  public QuarantineReleases(InboundTaskStore tasks, InventoryMoveClient inventory, Clock clock) {
    this.tasks = tasks;
    this.inventory = inventory;
    this.clock = clock;
  }

  public void onQualityEvent(EventEnvelope e) {
    switch (e.eventType()) {
      case "QualityDecisionMade" -> {
        JsonNode p = e.payload();
        if ("RELEASE".equals(p.path("decision").asString())) {
          release(e, p);
        }
      }
      case "DisposalApproved" -> {
        JsonNode p = e.payload();
        String mfcId = p.path("centerId").asString(p.path("mfcId").asString(""));
        for (JsonNode l : p.path("lines")) {
          withdraw(
              mfcId,
              l.path("lot").asString(""),
              l.path("locationCode").asString(""),
              l.path("qty").asInt());
        }
      }
      default -> {
        // 그 밖의 품질 사건은 ReturnReceiptService 등이 본다
      }
    }
  }

  private void release(EventEnvelope e, JsonNode p) {
    String mfcId = p.path("mfcId").asString("");
    String sku = p.path("sku").asString("");
    String lot = p.path("lot").asString("");
    String loc = p.path("locationCode").asString("");
    int qty = p.path("qty").asInt();
    String decisionId = p.path("decisionId").asString(e.aggregateId());
    if (mfcId.isBlank() || sku.isBlank() || lot.isBlank() || loc.isBlank() || qty < 1) {
      log.warn("QualityDecisionMade {} RELEASE — 줄 값이 모자라 건너뜀", e.eventId());
      return;
    }
    withdraw(mfcId, lot, loc, qty);
    Set<String> candidates = new LinkedHashSet<>();
    candidates.add(loc);
    candidates.addAll(inventory.suggestQuarantine(mfcId, sku, lot, qty));
    Instant at = clock.instant();
    int left = qty;
    List<String> created = new ArrayList<>();
    for (String c : candidates) {
      if (left == 0) {
        break;
      }
      int unstowed = 0;
      for (JsonNode u : inventory.unitsAt(mfcId, sku, c).path("items")) {
        if (lot.equals(u.path("lotId").asString())
            && "NORMAL".equals(u.path("qualityState").asString())
            && "UNSTOWED".equals(u.path("workState").asString())) {
          unstowed += u.path("qty").asInt();
        }
      }
      int covered =
          tasks.openAt(mfcId, "PUTAWAY", lot, c).stream().mapToInt(TaskRow::remaining).sum();
      int take = Math.min(left, unstowed - covered);
      if (take <= 0) {
        continue;
      }
      created.add(
          tasks.insert(
              "PUTAWAY",
              mfcId,
              decisionId,
              null,
              sku,
              lot,
              c,
              take,
              "RELEASE",
              "RELEASE:" + decisionId + ":" + e.aggregateVersion() + ":" + c,
              null,
              at));
      left -= take;
    }
    log.info(
        "격리 해제 {} {} {}×{} @{} → 적치 작업 {}{}",
        decisionId,
        sku,
        lot,
        qty,
        loc,
        created,
        left > 0 ? " (적치 전 재고를 못 찾은 양 " + left + " — 선반 해제 또는 이미 적치 중)" : "");
  }

  /** 그 칸·LOT 의 READY 격리 이동 작업에서 qty 만큼 뺀다(오래된 작업부터). */
  private void withdraw(String mfcId, String lot, String loc, int qty) {
    if (mfcId.isBlank() || lot.isBlank() || loc.isBlank() || qty < 1) {
      return;
    }
    Instant at = clock.instant();
    int left = qty;
    for (TaskRow t : tasks.openAt(mfcId, InboundTaskService.QUARANTINE_MOVE, lot, loc)) {
      if (left == 0) {
        break;
      }
      int took = tasks.addExcepted(t.taskId(), left, at);
      if (took > 0) {
        log.info("격리 이동 작업 {} — 품질 해제·폐기로 {}개 뺌", t.taskId(), took);
      }
      left -= took;
    }
  }
}
