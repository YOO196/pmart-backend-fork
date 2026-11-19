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
      IncidentRow incident, List<LotView> lots, List<String> decisionIds, int remainingLots) {}

  public record LotView(String lotId, @Nullable String sku, int qty, @Nullable String resolvedBy) {}

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
      List<NewLot> lots) {
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
                List.of(mfcId, kind, description, merged.toString()),
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
    return view(store.find(incidentId, false).orElseThrow());
  }

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
    if (!"DAMAGED".equals(p.path("reason").asString(""))) {
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
          var existing = store.lockOpenAuto(mfcId, "DAMAGE", lot);
          if (existing.isPresent()) {
            store.addLot(existing.get(), new LotRow(lot, sku, qty));
            store.bumpVersion(existing.get(), now);
            log.info("결품 보고(파손) → 품질 사고 {} 에 LOT {} 수량 +{}", existing.get(), lot, qty);
            return;
          }
          String id = "QIN-" + UUID.randomUUID();
          store.insert(
              new IncidentRow(
                  id,
                  mfcId,
                  "DAMAGE",
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
          log.info("결품 보고(파손) → 품질 사고 {} 열림 — 센터 {} LOT {}", id, mfcId, lot);
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
      out.add(new LotView(l.lotId(), l.sku(), l.qty(), by));
    }
    return new View(i, out, List.copyOf(ids), remaining);
  }
}
