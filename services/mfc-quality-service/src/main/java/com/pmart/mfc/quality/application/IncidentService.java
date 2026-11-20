package com.pmart.mfc.quality.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.quality.infrastructure.IncidentStore;
import com.pmart.mfc.quality.infrastructure.IncidentStore.IncidentRow;
import com.pmart.mfc.quality.infrastructure.IncidentStore.LotRow;
import com.pmart.mfc.quality.infrastructure.IncidentStore.Resolution;
import com.pmart.mfc.quality.infrastructure.StockLookupClient;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * 품질 사고(MB-2 1차, 사용자 결정 2026-11-19): 자동(결품 보고 reason DAMAGED) + 센터 관리자 직접 등록. 상태 OPEN → DECIDED(영향
 * LOT 모두 격리 해제 APPLIED 또는 폐기 승인) → CLOSED(센터 관리자, 사유, 모두 처리 뒤만). 격리·해제·폐기는 기존 흐름 — 사고는 결과를 LOT 으로
 * 본다.
 */
@Service
public class IncidentService {

  private static final Logger log = LoggerFactory.getLogger(IncidentService.class);

  public static final String AUTO = "AUTO";
  public static final String MANUAL = "MANUAL";
  public static final String LOTS_NOT_RESOLVED = "LOTS_NOT_RESOLVED";
  public static final String LOT_NOT_IN_CENTER = "LOT_NOT_IN_CENTER";
  static final Set<String> KINDS =
      Set.of(
          "TEMPERATURE_EXCURSION",
          "DAMAGE",
          "SPOILAGE",
          "RECALL",
          "EXPIRY",
          "CONTAMINATION",
          "OTHER");
  static final List<String> OPEN_STATES = List.of("OPEN", "DECIDED");

  /** 화면용 — 사고 + LOT 별 처리 상태 + 이어진 판정·폐기. */
  public record View(
      IncidentRow incident,
      List<LotView> lots,
      List<String> decisionIds,
      int remainingLots,
      @Nullable Integer affectedOrderCount) {}

  public record LotView(
      String lotId,
      @Nullable String sku,
      int qty,
      @Nullable String resolvedBy,
      @Nullable String saleHold) {}

  public record NewLot(String lotId, int qty) {}

  private final IncidentStore store;
  private final StockLookupClient stock;
  private final RequestIdempotency idempotency;
  private final TransactionTemplate tx;
  private final Clock clock;

  public IncidentService(
      IncidentStore store,
      StockLookupClient stock,
      RequestIdempotency idempotency,
      org.springframework.transaction.PlatformTransactionManager txManager,
      Clock clock) {
    this.store = store;
    this.stock = stock;
    this.idempotency = idempotency;
    this.tx = new TransactionTemplate(txManager);
    this.clock = clock;
  }

  // ---------- 조회 ----------

  public View get(String incidentId) {
    IncidentRow i =
        store
            .find(incidentId, false)
            .orElseThrow(
                () -> PmartException.notFound("INCIDENT_NOT_FOUND", "품질 사고가 없습니다: " + incidentId));
    Actors.reader(i.mfcId());
    return refreshAndView(incidentId);
  }

  public record Page(List<View> items, @Nullable String nextCursor) {}

  public Page list(
      String mfcId,
      @Nullable List<String> states,
      @Nullable String kind,
      @Nullable String lotId,
      int size,
      @Nullable String cursor) {
    Actors.reader(mfcId);
    for (String id : store.openIds(mfcId)) {
      refreshAndView(id); // 상태(OPEN ↔ DECIDED)를 판정에 맞춘 뒤 거른다
    }
    List<String> st = states == null || states.isEmpty() ? OPEN_STATES : states;
    int limit = Math.max(1, Math.min(size, 200));
    List<String> ids = store.ids(mfcId, st, kind, lotId, cursor, limit + 1);
    boolean more = ids.size() > limit;
    List<String> page = more ? ids.subList(0, limit) : ids;
    List<View> items = new ArrayList<>();
    for (String id : page) {
      items.add(view(store.find(id, false).orElseThrow()));
    }
    return new Page(items, more ? page.get(page.size() - 1) : null);
  }

  // ---------- 직접 등록 ----------

  public View create(
      String requestId,
      String sourceSystem,
      String mfcId,
      String kind,
      String description,
      List<NewLot> lots,
      boolean saleHold) {
    String me = Actors.supervisor(mfcId, null);
    if (!KINDS.contains(kind)) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "사고 종류가 맞지 않습니다: " + kind);
    }
    if (description == null || description.isBlank() || description.length() > 300) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "설명은 1~300자입니다.");
    }
    Map<String, Integer> merged = new LinkedHashMap<>();
    for (NewLot l : lots) {
      if (l.lotId() == null || l.lotId().isBlank() || l.qty() <= 0) {
        throw new PmartException(ErrorCode.VALIDATION_FAILED, "영향 LOT 은 lotId·수량(1 이상)이 필요합니다.");
      }
      merged.merge(l.lotId(), l.qty(), Integer::sum);
    }
    if (merged.isEmpty()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "영향 LOT 이 하나 이상 필요합니다.");
    }
    // 이 센터에 재고가 남은 LOT 인지(트랜잭션 밖 — 조회 실패는 503)
    List<LotRow> rows = new ArrayList<>();
    List<PmartException.Detail> missing = new ArrayList<>();
    for (var e : merged.entrySet()) {
      var sku = stock.skuOfLotInCenter(mfcId, e.getKey());
      if (sku.isEmpty()) {
        missing.add(new PmartException.Detail("lotId", e.getKey()));
      } else {
        rows.add(new LotRow(e.getKey(), sku.get(), e.getValue()));
      }
    }
    if (!missing.isEmpty()) {
      throw new PmartException(
          LOT_NOT_IN_CENTER,
          HttpStatus.UNPROCESSABLE_CONTENT,
          "이 센터에 재고가 없는 LOT 입니다.",
          false,
          missing);
    }
    String id =
        idempotency
            .execute(
                Actors.key(sourceSystem, requestId, "createQualityIncident"),
                List.of(mfcId, kind, description, merged.toString(), String.valueOf(saleHold)),
                String.class,
                () -> {
                  Instant now = clock.instant();
                  String incidentId = "QIN-" + UUID.randomUUID();
                  store.insert(
                      new IncidentRow(
                          incidentId,
                          mfcId,
                          kind,
                          MANUAL,
                          "OPEN",
                          null,
                          null,
                          description.trim(),
                          me,
                          now,
                          null,
                          null,
                          null,
                          null,
                          1),
                      rows,
                      now);
                  if (saleHold) {
                    store.requestSaleHold(incidentId);
                  }
                  log.info(
                      "품질 사고 {} 직접 등록 — 센터 {} {} LOT {}개 by {}",
                      incidentId,
                      mfcId,
                      kind,
                      rows.size(),
                      me);
                  return incidentId;
                })
            .value();
    syncSaleHoldsQuietly(id);
    return refreshAndView(id);
  }

  // ---------- 닫기 ----------

  public View close(
      String requestId,
      String sourceSystem,
      String incidentId,
      @Nullable Integer expectedVersion,
      String reason) {
    IncidentRow found =
        store
            .find(incidentId, false)
            .orElseThrow(
                () -> PmartException.notFound("INCIDENT_NOT_FOUND", "품질 사고가 없습니다: " + incidentId));
    String me = Actors.supervisor(found.mfcId(), null);
    if (reason == null || reason.isBlank() || reason.length() > 300) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "닫는 사유는 1~300자입니다.");
    }
    idempotency.execute(
        Actors.key(sourceSystem, requestId, "closeQualityIncident"),
        List.of(incidentId, String.valueOf(expectedVersion), reason),
        Boolean.class,
        () -> {
          IncidentRow i = store.find(incidentId, true).orElseThrow();
          if ("CLOSED".equals(i.state())) {
            throw new PmartException(
                ErrorCode.INVALID_STATE.name(),
                HttpStatus.CONFLICT,
                "이미 닫힌 사고입니다.",
                false,
                List.of());
          }
          if (expectedVersion != null && expectedVersion != i.version()) {
            throw new PmartException(
                ErrorCode.VERSION_CONFLICT, "다른 곳에서 먼저 바뀌었습니다(지금 버전 " + i.version() + ").");
          }
          View v = view(i);
          if (v.remainingLots() > 0) {
            List<PmartException.Detail> left = new ArrayList<>();
            v.lots().stream()
                .filter(l -> l.resolvedBy() == null)
                .forEach(l -> left.add(new PmartException.Detail("lotId", l.lotId())));
            throw new PmartException(
                LOTS_NOT_RESOLVED,
                HttpStatus.CONFLICT,
                "아직 처리(격리 해제·폐기 판정)되지 않은 LOT 이 있습니다.",
                false,
                left);
          }
          store.close(incidentId, me, reason.trim(), clock.instant());
          log.info("품질 사고 {} 닫음 by {}", incidentId, me);
          return true;
        });
    syncSaleHoldsQuietly(incidentId);
    return view(store.find(incidentId, false).orElseThrow());
  }

  // ---------- 고객 사건 번호(MQ-2) ----------

  /**
   * platform.case.v1 {@code CaseOpened} 중 type MFC_QUALITY_IMPACT(case 0.31.0 — 영향 주문마다 하나) → refs
   * 의 incidentId 사고에 비어 있을 때만 첫 caseId(MQ-2, 2026-11-20). 그 뒤 사건·다른 유형·모르는 사고는 무시(재시도하지 않음) — 사건 전체
   * 목록의 정본은 case.
   */
  public void onCaseEvent(EventEnvelope e) {
    if (!"CaseOpened".equals(e.eventType())) {
      return;
    }
    JsonNode p = e.payload();
    if (!CASE_TYPE_QUALITY_IMPACT.equals(p.path("type").asString(""))) {
      return;
    }
    String caseId = p.path("caseId").asString(null);
    String incidentId = null;
    for (JsonNode r : p.path("refs")) {
      if ("incidentId".equals(r.path("refType").asString(""))) {
        incidentId = r.path("refId").asString(null);
        break;
      }
    }
    if (caseId == null || caseId.isBlank() || incidentId == null || incidentId.isBlank()) {
      log.warn("고객 사건 {} — caseId·incidentId 가 없어 품질 사고에 잇지 않음", e.eventId());
      return;
    }
    String incident = incidentId;
    Boolean linked =
        tx.execute(
            s -> {
              if (store.linkCase(incident, caseId, clock.instant()) == 1) {
                return true;
              }
              if (!store.exists(incident)) {
                log.warn("고객 사건 {} — 품질 사고 {} 가 없어 잇지 않음", caseId, incident);
              }
              return false;
            });
    if (Boolean.TRUE.equals(linked)) {
      log.info("품질 사고 {} ← 고객 사건 {}", incident, caseId);
    }
  }

  static final String CASE_TYPE_QUALITY_IMPACT = "MFC_QUALITY_IMPACT";

  // ---------- 자동(결품 보고 DAMAGED) ----------

  /**
   * mfc.fulfillment.v1 {@code LineShortageReported} 중 reason DAMAGED(사용자 결정 2026-11-19 — 파손·변질만, 단순
   * 부족·분실은 열지 않음). 같은 센터·LOT 의 닫히지 않은 자동 파손 사고가 있으면 거기에 수량을 더한다. LOT·센터가 없는 보고는 건너뛴다.
   */
  public void onFulfillmentEvent(EventEnvelope e) {
    if (!"LineShortageReported".equals(e.eventType())) {
      return;
    }
    JsonNode p = e.payload();
    // 결품 보고 중 품질 문제만(사용자 결정 2026-11-19 "파손·변질만") — DAMAGED → 파손 사고, SPOILED(fulfillment 0.22.0) →
    // 변질 사고
    String reason = p.path("reason").asString("");
    String kind =
        switch (reason) {
          case "DAMAGED" -> "DAMAGE";
          case "SPOILED" -> "SPOILAGE";
          default -> null;
        };
    if (kind == null) {
      return;
    }
    String mfcId = p.path("mfcId").asString(null);
    String lot = p.path("lot").asString(null);
    int qty = p.path("shortQty").asInt(0);
    if (mfcId == null || lot == null || lot.isBlank() || qty <= 0) {
      log.warn("결품 보고 {} — 센터·LOT·수량이 없어 품질 사고를 열지 않음", e.eventId());
      return;
    }
    String sku = p.path("sku").asString(null);
    tx.executeWithoutResult(
        s -> {
          Instant now = clock.instant();
          var existing = store.lockOpenAuto(mfcId, kind, lot);
          if (existing.isPresent()) {
            store.addLot(existing.get(), new LotRow(lot, sku, qty));
            store.bumpVersion(existing.get(), now);
            log.info("결품 보고({}) → 품질 사고 {} 에 LOT {} 수량 +{}", reason, existing.get(), lot, qty);
            return;
          }
          String id = "QIN-" + UUID.randomUUID();
          store.insert(
              new IncidentRow(
                  id,
                  mfcId,
                  kind,
                  AUTO,
                  "OPEN",
                  "LineShortageReported",
                  e.eventId().toString(),
                  null,
                  null,
                  now,
                  null,
                  null,
                  null,
                  null,
                  1),
              List.of(new LotRow(lot, sku, qty)),
              now);
          log.info("결품 보고({}) → 품질 사고 {} 열림 — 센터 {} LOT {}", reason, id, mfcId, lot);
        });
  }

  // ---------- 상태 ----------

  private View refreshAndView(String incidentId) {
    IncidentRow i = store.find(incidentId, false).orElseThrow();
    View v = view(i);
    if (!"CLOSED".equals(i.state())) {
      String want = v.remainingLots() == 0 ? "DECIDED" : "OPEN";
      if (!want.equals(i.state())) {
        store.setState(incidentId, want, clock.instant());
        v = view(store.find(incidentId, false).orElseThrow());
      }
    }
    return v;
  }

  private View view(IncidentRow i) {
    List<LotRow> lots = store.lots(i.incidentId());
    List<Resolution> res = store.resolutions(i, lots.stream().map(LotRow::lotId).toList());
    Map<String, String> holds = store.saleHolds(i.incidentId());
    Map<String, String> byLot = new LinkedHashMap<>();
    Set<String> ids = new LinkedHashSet<>(store.linkedDecisions(i.incidentId()));
    for (Resolution r : res) {
      byLot.putIfAbsent(r.lotId(), r.refId());
      ids.add(r.refId());
    }
    List<LotView> out = new ArrayList<>();
    int remaining = 0;
    for (LotRow l : lots) {
      String by = byLot.get(l.lotId());
      if (by == null) {
        remaining++;
      }
      out.add(new LotView(l.lotId(), l.sku(), l.qty(), by, holds.get(l.lotId())));
    }
    return new View(i, out, List.copyOf(ids), remaining, store.affectedOrderCount(i.incidentId()));
  }

  // ---------- 고객 영향(R6, 2026-11-19) ----------

  private com.pmart.mfc.quality.infrastructure.LotOrdersClient lotOrders;
  private com.pmart.common.messaging.EventPublisher publisher;
  private com.pmart.common.messaging.EventEnvelopeCodec codec;
  private tools.jackson.databind.ObjectMapper mapper;

  @org.springframework.beans.factory.annotation.Value(
      "${pmart.quality.incident-impact-recheck:PT5M}")
  private java.time.Duration impactRecheck = java.time.Duration.ofMinutes(5);

  @org.springframework.beans.factory.annotation.Autowired
  void setImpactParts(
      com.pmart.mfc.quality.infrastructure.LotOrdersClient lotOrders,
      com.pmart.common.messaging.EventPublisher publisher,
      com.pmart.common.messaging.EventEnvelopeCodec codec,
      tools.jackson.databind.ObjectMapper mapper) {
    this.lotOrders = lotOrders;
    this.publisher = publisher;
    this.codec = codec;
    this.mapper = mapper;
  }

  /**
   * 영향 LOT 이 라이더에게 인계된(고객에게 나간) 주문을 센다 — 새 사고·LOT 추가는 곧, 그 밖 열린 사고는 {@code
   * pmart.quality.incident-impact-recheck}(기본 5분)마다(사고는 격리를 걸지 않아 같은 LOT 이 더 나갈 수 있다). 바뀌면 {@code
   * QualityIncidentImpactAssessed}(mfc.quality.v1) — case-service 가 고객 사건을 연다(요청 CS 쪽). 조회 실패는 다음
   * 주기.
   */
  @org.springframework.scheduling.annotation.Scheduled(
      fixedDelayString = "${pmart.quality.sweep-interval:PT30S}",
      initialDelayString = "PT20S")
  public void assessImpacts() {
    for (String id : store.impactDue(clock.instant().minus(impactRecheck), 20)) {
      try {
        assessImpact(id);
      } catch (RuntimeException ex) {
        log.warn("품질 사고 {} 고객 영향 조회 실패 — 다음 주기에: {}", id, ex.getMessage());
      }
    }
  }

  public void assessImpact(String incidentId) {
    IncidentRow i = store.find(incidentId, false).orElseThrow();
    List<LotRow> lots = store.lots(incidentId);
    var res = lotOrders.of(i.mfcId(), lots.stream().map(LotRow::lotId).sorted().toList());
    // 주문별(인계된 줄만), 순서 고정 — 같은 결과면 같은 JSON
    java.util.TreeMap<String, List<Map<String, Object>>> byOrder = new java.util.TreeMap<>();
    for (var l : res.lines()) {
      if (l.handedQty() <= 0) {
        continue;
      }
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("orderLineId", l.orderLineId());
      m.put("sku", l.sku());
      m.put("lotId", l.lotId());
      m.put("qty", l.handedQty());
      byOrder.computeIfAbsent(l.orderId(), k -> new ArrayList<>()).add(m);
    }
    List<Map<String, Object>> orders = new ArrayList<>();
    byOrder.forEach(
        (o, ls) -> {
          Map<String, Object> m = new LinkedHashMap<>();
          m.put("orderId", o);
          m.put("lines", ls);
          orders.add(m);
        });
    String json = mapper.writeValueAsString(orders);
    tx.executeWithoutResult(
        s -> {
          var cur = store.lockImpact(incidentId).orElse(null);
          if (cur == null) {
            return; // 그새 닫힘
          }
          Instant now = clock.instant();
          // jsonb 는 열쇠 순서를 바꾼다 — 글자가 아니라 트리로 비교(순서 무시)
          JsonNode before = cur.ordersJson() == null ? null : mapper.readTree(cur.ordersJson());
          if (cur.count() != null && mapper.readTree(json).equals(before)) {
            store.checked(incidentId, now);
            return;
          }
          int version = cur.version() + 1;
          store.setImpact(incidentId, json, orders.size(), version, now);
          Map<String, Object> p = new LinkedHashMap<>();
          p.put("incidentId", incidentId);
          p.put("mfcId", i.mfcId());
          p.put("kind", i.kind());
          p.put("origin", i.origin());
          p.put("openedAt", i.openedAt().toString());
          p.put(
              "lots",
              lots.stream()
                  .map(
                      l -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("lotId", l.lotId());
                        if (l.sku() != null) {
                          m.put("sku", l.sku());
                        }
                        return m;
                      })
                  .toList());
          p.put("affectedOrderCount", orders.size());
          p.put("affectedOrders", orders);
          p.put("truncated", res.truncated());
          p.put("assessedAt", now.toString());
          publisher.publish(
              codec.create(
                  publisher.sourceSystem(),
                  "QualityIncidentImpactAssessed",
                  1,
                  "QualityIncident",
                  incidentId,
                  version,
                  new com.pmart.common.messaging.EventEnvelope.Actor(
                      com.pmart.common.messaging.EventEnvelope.ActorType.SYSTEM,
                      "mfc-quality-service"),
                  com.pmart.common.messaging.EventEnvelope.EventSource.REAL,
                  p),
              QualityEvents.TOPIC);
          log.info("품질 사고 {} 고객 영향 — 주문 {}건(판단 {})", incidentId, orders.size(), version);
        });
  }

  // ---------- LOT 판매 보류(0.10.0, 사용자 결정 2026-11-20) ----------

  private com.pmart.mfc.quality.infrastructure.InventoryClient inventory;

  @org.springframework.beans.factory.annotation.Autowired
  void setInventory(com.pmart.mfc.quality.infrastructure.InventoryClient inventory) {
    this.inventory = inventory;
  }

  /**
   * 판매 보류를 재고 서비스에 맞춘다({@code pmart.quality.sweep-interval}, 기본 30초) — 걸기 대기는 걸고, LOT 이 처리(해제
   * APPLIED·폐기 승인)됐거나 사고가 닫혔으면 푼다. 실패는 다음 주기.
   */
  @org.springframework.scheduling.annotation.Scheduled(
      fixedDelayString = "${pmart.quality.sweep-interval:PT30S}",
      initialDelayString = "PT25S")
  public void syncSaleHolds() {
    for (String id : store.saleHoldDue(50)) {
      syncSaleHoldsQuietly(id);
    }
  }

  private void syncSaleHoldsQuietly(String incidentId) {
    try {
      syncSaleHolds(incidentId);
    } catch (RuntimeException ex) {
      log.warn("품질 사고 {} 판매 보류 맞추기 실패 — 다음 주기에: {}", incidentId, ex.getMessage());
    }
  }

  public void syncSaleHolds(String incidentId) {
    IncidentRow i = store.find(incidentId, false).orElseThrow();
    View v = view(i);
    boolean closed = "CLOSED".equals(i.state());
    for (LotView l : v.lots()) {
      String state = l.saleHold();
      if (state == null || "RELEASED".equals(state)) {
        continue;
      }
      boolean release = closed || l.resolvedBy() != null;
      if (!release && "HELD".equals(state)) {
        continue;
      }
      if (release && "HELD".equals(state)) {
        store.setSaleHold(incidentId, l.lotId(), "RELEASE_PENDING"); // 실패해도 다음 주기가 푼다
      }
      inventory.lotSaleHold(release, i.mfcId(), l.lotId(), incidentId);
      store.setSaleHold(incidentId, l.lotId(), release ? "RELEASED" : "HELD");
      log.info("품질 사고 {} LOT {} 판매 보류 {}", incidentId, l.lotId(), release ? "풀림" : "걸림");
    }
  }
}
