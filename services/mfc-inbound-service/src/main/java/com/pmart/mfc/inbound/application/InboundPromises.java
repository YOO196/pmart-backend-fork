package com.pmart.mfc.inbound.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.web.CommandResponse;
import com.pmart.common.web.CommandStatus;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.inbound.domain.InboundPromiseRules;
import com.pmart.mfc.inbound.domain.InboundPromiseRules.ApprovedLine;
import com.pmart.mfc.inbound.domain.InboundPromiseRules.Discrepancy;
import com.pmart.mfc.inbound.domain.InboundPromiseRules.ExpectedLine;
import com.pmart.mfc.inbound.domain.InboundPromiseRules.ReceiptLine;
import com.pmart.mfc.inbound.domain.InboundPromiseRules.ShipLine;
import com.pmart.mfc.inbound.domain.InboundPromiseRules.ViewLine;
import com.pmart.mfc.inbound.infrastructure.InboundPromiseStore;
import com.pmart.mfc.inbound.infrastructure.InboundPromiseStore.Promise;
import com.pmart.mfc.inbound.infrastructure.InboundPromiseStore.ReceiptRow;
import com.pmart.mfc.inbound.infrastructure.InboundTaskStore;
import com.pmart.mfc.inbound.infrastructure.InventoryReceiptClient;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * 입고 예정(InboundPromise, 계약 0.16.0 — 사용자 결정 2026-11-20 "먼저 만들어"). CDC → MFC 이송(TRF)의 MFC 쪽 투영과 현장
 * 도착·수령.
 *
 * <ul>
 *   <li>원천 사건(inbox 트랜잭션 안): replenishment {@code
 *       TransferApproved·Changed·Cancelled}(scm.replenishment.v1 — trfVersion 이 지금보다 클 때만, 취소·변경은
 *       출발 전만), cdc-inventory {@code TransferDeparted}(cdc.inventory.v1 — 실물 사실이라 버전과 상관없이 반영, 같은
 *       departureId 한 번, 한 TRF 한 출발), cdc-outbound {@code
 *       TransferShipmentManifestIssued}(cdc.outbound.v1 — LPN 목록, 사건 버전이 더 클 때만).
 *   <li>도착 등록: EXPECTED → RECEIVING, {@code InboundArrivalRecorded}(mfc.inbound.v1).
 *   <li>수령(IV-02): 현장 사실 기록(CAPTURED, 짧은 트랜잭션) → mfc-inventory ConfirmReceipt(트랜잭션 밖,
 *       Idempotency-Key = receiptId) → APPLIED 면 수령 줄마다 품질검사(INSPECT) 작업 — 초기 보유 반입과 같은 {@link
 *       InboundTaskStore#inspectionsFor} — 예정 TIN_POSTED, 차이가 있으면 {@code
 *       ReceiptDiscrepancyReported}. 결과 모름은 같은 요청 키로 다시.
 * </ul>
 */
@Service
public class InboundPromises {

  public static final String NOT_FOUND = "INBOUND_PROMISE_NOT_FOUND";
  public static final String RECEIPT_NOT_FOUND = "INBOUND_RECEIPT_NOT_FOUND";
  static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
  static final List<String> OPEN = List.of("EXPECTED", "RECEIVING");

  /** 운송 도착 사건으로 자동 등록한 도착의 기록자(사람 아님). */
  public static final String AUTO_ARRIVAL = "SYSTEM:transport-execution";

  private static final Logger log = LoggerFactory.getLogger(InboundPromises.class);

  /** 도착 등록 본문. */
  public record ArrivalCommand(
      Instant arrivedAt,
      @Nullable String tripId,
      @Nullable String unloadingBayReservationId,
      @Nullable String evidenceRef) {}

  /** 수령 등록 본문. */
  public record ReceiptCommand(
      @Nullable Instant receivedAt, @Nullable String evidenceRef, List<ReceiptLine> lines) {}

  record ArrivalRequest(String trfId, @Nullable Integer expectedVersion, ArrivalCommand body) {}

  record ReceiptRequest(String trfId, @Nullable Integer expectedVersion, ReceiptCommand body) {}

  /** 화면용 — 예정·줄·수령·LPN 목록. */
  public record View(
      Promise promise, List<ViewLine> lines, List<ShipLine> packages, List<ReceiptRow> receipts) {}

  /** 목록 한 쪽. */
  public record Page(List<View> items, @Nullable String nextCursor) {}

  private final InboundPromiseStore store;
  private final InboundTaskStore tasks;
  private final InventoryReceiptClient inventory;
  private final RequestIdempotency idempotency;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final TransitClosures closures;
  private final com.pmart.mfc.inbound.infrastructure.CdcTransferIntakeClient cdc;

  public InboundPromises(
      InboundPromiseStore store,
      InboundTaskStore tasks,
      InventoryReceiptClient inventory,
      RequestIdempotency idempotency,
      EventPublisher events,
      EventEnvelopeCodec codec,
      PlatformTransactionManager txm,
      Clock clock,
      TransitClosures closures,
      com.pmart.mfc.inbound.infrastructure.CdcTransferIntakeClient cdc) {
    this.cdc = cdc;
    this.store = store;
    this.tasks = tasks;
    this.inventory = inventory;
    this.idempotency = idempotency;
    this.events = events;
    this.codec = codec;
    this.tx = new TransactionTemplate(txm);
    this.clock = clock;
    this.closures = closures;
  }

  // ---------- 원천 사건 ----------

  /**
   * tms.trip.v1(inbox 트랜잭션 안, 2026-11-27 — 사용자 "MFC 도착 누르면 MFC 에 도착이 떠야"): transport-execution
   * {@code StopArrived} 의 trfIds 마다 그 센터의 입고 예정이 EXPECTED 면 도착 등록(RECEIVING, {@code
   * InboundArrivalRecorded} — 행위자 SYSTEM). 이미 도착·수령·취소된 예정, 다른 센터, 예정이 아직 없는 TRF 는 그대로(현장 도착 등록은
   * 예전처럼 된다). 모르는 사건은 지나간다.
   */
  public void onTripEvent(EventEnvelope e) {
    if (!"StopArrived".equals(e.eventType())) {
      return;
    }
    JsonNode p = e.payload();
    String mfc = text(p, "mfcId");
    String trip = text(p, "tripId");
    Instant at = orElse(instant(p, "arrivedAt"), e.occurredAt());
    for (JsonNode t : p.path("trfIds")) {
      String trf = t.asString();
      Promise x = store.lock(trf).orElse(null);
      if (x == null) {
        log.warn("StopArrived {} — 입고 예정 {} 이 아직 없음(출발 사건 전), 도착은 현장 등록으로", e.eventId(), trf);
        continue;
      }
      if (mfc != null && !mfc.equals(x.mfcId)) {
        log.warn("StopArrived {} — TRF {} 는 {} 예정인데 정차는 {}, 무시", e.eventId(), trf, x.mfcId, mfc);
        continue;
      }
      if (!"EXPECTED".equals(x.state)) {
        continue;
      }
      x.state = "RECEIVING";
      x.arrivedAt = at;
      x.arrivalTripId = trip;
      x.arrivalEvidenceRef = text(p, "stopId");
      x.arrivalRecordedBy = AUTO_ARRIVAL;
      touch(x, e);
      publishArrival(x, null);
      log.info("입고 예정 {} 운송 도착 {} (운행 {})", trf, at, trip);
    }
  }

  /** scm.replenishment.v1·cdc.inventory.v1·cdc.outbound.v1(inbox 트랜잭션 안). 모르는 사건은 그냥 지나간다. */
  public void onTransferEvent(EventEnvelope e) {
    switch (e.eventType()) {
      case "TransferApproved" -> approved(e);
      case "TransferChanged" -> changed(e);
      case "TransferCancelled" -> cancelled(e);
      case "TransferDeparted" -> departed(e);
      case "TransferShipmentManifestIssued" -> manifest(e);
      default -> {
        // 같은 토픽의 다른 사건
      }
    }
  }

  private void approved(EventEnvelope e) {
    JsonNode p = e.payload();
    String trf = text(p, "trfId");
    String to = text(p, "toMfc");
    if (trf == null || to == null) {
      log.warn("TransferApproved {} — trfId·toMfc 없음, 무시", e.eventId());
      return;
    }
    long v = p.path("trfVersion").asLong(0);
    Promise x = store.lock(trf).orElse(null);
    if (x == null) {
      x = fresh(trf, to);
    } else if (x.trfVersion != null && v <= x.trfVersion) {
      log.debug("TransferApproved {} v{} — 이미 v{}, 무시", trf, v, x.trfVersion);
      return;
    } else if ("CANCELLED".equals(x.state)) {
      log.warn("TransferApproved {} v{} — 이미 취소된 예정, 무시", trf, v);
      return;
    }
    x.trfVersion = v;
    x.fromCdc = orElse(text(p, "fromCdc"), x.fromCdc);
    x.approvedLines = approvedLines(p.path("lines"));
    x.deadlineMeaning = orElse(text(p, "deadlineMeaning"), x.deadlineMeaning);
    x.dueAt = orElse(instant(p, "dueAt"), x.dueAt);
    x.priority = orElse(text(p, "priority"), x.priority);
    touch(x, e);
    log.info("입고 예정 {} → {} v{} ({}줄)", trf, x.mfcId, v, x.approvedLines.size());
  }

  private void changed(EventEnvelope e) {
    JsonNode p = e.payload();
    String trf = text(p, "trfId");
    long v = p.path("toVersion").asLong(0);
    Promise x = trf == null ? null : store.lock(trf).orElse(null);
    if (x == null) {
      log.warn("TransferChanged {} — 입고 예정이 없음(승인 사건 전), 무시", trf);
      return;
    }
    if ((x.trfVersion != null && v <= x.trfVersion) || "CANCELLED".equals(x.state)) {
      return;
    }
    x.trfVersion = v;
    if (x.departureId != null) {
      // 변경은 실출발 전만(S5 §8) — 출발 줄이 예정의 근거라 줄은 그대로 둔다
      log.warn("TransferChanged {} v{} — 이미 출발({}), 줄은 바꾸지 않음", trf, v, x.departureId);
    } else if (p.path("lines").isArray() && !p.path("lines").isEmpty()) {
      x.approvedLines = approvedLines(p.path("lines"));
    }
    x.deadlineMeaning = orElse(text(p, "deadlineMeaning"), x.deadlineMeaning);
    x.dueAt = orElse(instant(p, "dueAt"), x.dueAt);
    x.priority = orElse(text(p, "priority"), x.priority);
    touch(x, e);
  }

  private void cancelled(EventEnvelope e) {
    JsonNode p = e.payload();
    String trf = text(p, "trfId");
    long v = p.path("toVersion").asLong(0);
    Promise x = trf == null ? null : store.lock(trf).orElse(null);
    if (x == null) {
      log.warn("TransferCancelled {} — 입고 예정이 없음, 무시", trf);
      return;
    }
    if ((x.trfVersion != null && v <= x.trfVersion) || "CANCELLED".equals(x.state)) {
      return;
    }
    x.trfVersion = v;
    if (x.departureId != null || !"EXPECTED".equals(x.state)) {
      // 출발 후 원TRF 취소 없음(S5 §0) — 실물이 오고 있으니 예정은 그대로
      log.warn("TransferCancelled {} v{} — 이미 출발·도착({}), 취소하지 않음", trf, v, x.state);
      touch(x, e);
      return;
    }
    List<ShipLine> cut = new ArrayList<>();
    for (JsonNode l : p.path("cancelledLines")) {
      cut.add(
          new ShipLine(l.path("sku").asString(), null, null, l.path("cancelledQty").asInt(), null));
    }
    List<ApprovedLine> left = InboundPromiseRules.cancel(x.approvedLines, cut);
    if (left.isEmpty()) {
      x.state = "CANCELLED";
      x.cancelReason = text(p, "reason");
      x.cancelledAt = orElse(instant(p, "cancelledAt"), e.occurredAt());
      log.info("입고 예정 {} 취소 v{}: {}", trf, v, x.cancelReason);
    } else {
      x.approvedLines = left;
      log.info("입고 예정 {} 일부 취소 v{} — 남은 {}줄", trf, v, left.size());
    }
    touch(x, e);
  }

  private void departed(EventEnvelope e) {
    JsonNode p = e.payload();
    String trf = text(p, "trfId");
    String dep = text(p, "departureId");
    if (trf == null || dep == null) {
      log.warn("TransferDeparted {} — trfId·departureId 없음, 무시", e.eventId());
      return;
    }
    Promise x = store.lock(trf).orElse(null);
    if (x == null) {
      String to = text(p, "toMfc");
      if (to == null) {
        log.warn("TransferDeparted {} — 입고 예정이 없고 toMfc 도 없어 둘 곳이 없음", trf);
        return;
      }
      x = fresh(trf, to);
    }
    if (x.departureId != null) {
      if (!x.departureId.equals(dep)) {
        // 1요청·1이관·1출발(S5 §0) — 두 번째 출발은 대사 대상
        log.warn("TransferDeparted {} — 이미 출발 {} 이 있는데 다른 출발 {}, 반영하지 않음", trf, x.departureId, dep);
      }
      return;
    }
    x.departureId = dep;
    x.departureCommitId = text(p, "commitId");
    x.departedLines = shipLines(p.path("lines"));
    x.departedAt = orElse(instant(p, "departedAt"), e.occurredAt());
    x.tripId = orElse(x.tripId, text(p, "tripId"));
    x.fromCdc = orElse(x.fromCdc, text(p, "fromCdc"));
    numbersFrom(x, p);
    x.source = orElse(text(p, "source"), e.source().name());
    if ("CANCELLED".equals(x.state)) {
      // 실물 사실은 버리지 않는다(events.md 역순 수신) — 오고 있는 물건을 받을 수 있게 되살린다
      log.warn("TransferDeparted {} — 취소된 예정에 실제 출발 {}, EXPECTED 로 되살림(대사 필요)", trf, dep);
      x.state = "EXPECTED";
    }
    touch(x, e);
    log.info("입고 예정 {} 출발 {} ({}줄)", trf, dep, x.departedLines.size());
  }

  private void manifest(EventEnvelope e) {
    JsonNode p = e.payload();
    String trf = text(p, "trfId");
    if (trf == null) {
      return;
    }
    Promise x = store.lock(trf).orElse(null);
    if (x == null) {
      String to = text(p, "toMfc");
      if (to == null) {
        log.warn("TransferShipmentManifestIssued {} — 입고 예정이 없고 toMfc 도 없음", trf);
        return;
      }
      x = fresh(trf, to);
    }
    if (x.manifestVersion != null && e.aggregateVersion() <= x.manifestVersion) {
      return;
    }
    x.manifestId = text(p, "shipmentManifestId");
    x.manifestVersion = e.aggregateVersion();
    x.manifestLines = shipLines(p.path("lines"));
    x.tripId = orElse(x.tripId, text(p, "tripId"));
    x.fromCdc = orElse(x.fromCdc, text(p, "fromCdc"));
    numbersFrom(x, p);
    touch(x, e);
  }

  /** (0.25.0, R-52) 사건에 화면 번호가 실려 오면(cdc-outbound 추가 필드) 사본으로. */
  private static void numbersFrom(Promise x, JsonNode p) {
    x.trfNumber = orElse(text(p, "trfNumber"), x.trfNumber);
    x.waveId = orElse(text(p, "waveId"), x.waveId);
    x.waveNumber = orElse(text(p, "waveNumber"), x.waveNumber);
  }

  /**
   * (0.25.0, R-52) cdc-outbound 인수 조회로 화면 번호를 채운다 — 공급 요청 TRF 인수 직후·끝나지 않은 예정 상세를 열었는데 비어 있을 때.
   * 표시용이라 실패는 그대로 둔다. 채웠으면 true.
   */
  public boolean refreshNumbers(String trfId) {
    return cdc.numbers(trfId)
        .map(n -> store.setNumbers(trfId, n.trfNumber(), n.waveId(), n.waveNumber()) > 0)
        .orElse(false);
  }

  /**
   * (ADR-0091) 승인된 센터 발주가 보급 DC 에 보낸 이관(TRF)의 입고 예정 — cdc-outbound 에 보낸 값과 같게(버전 1, READY 기한 —
   * R-27). 호출자 트랜잭션 안. 이미 있으면 그대로(같은 TRF 를 두 번 만들지 않는다).
   */
  public void expectFromSupply(
      String trfId, String mfcId, String fromCdc, Map<String, Integer> lines, Instant dueAt) {
    if (store.lock(trfId).isPresent()) {
      return;
    }
    Promise x = fresh(trfId, mfcId);
    x.trfVersion = 1L;
    x.fromCdc = fromCdc;
    List<ApprovedLine> approved = new ArrayList<>();
    lines.forEach((sku, qty) -> approved.add(new ApprovedLine(sku, qty, "E")));
    x.approvedLines = approved;
    x.deadlineMeaning = "READY";
    x.dueAt = dueAt;
    x.sources.put("mfc-inbound-service", clock.instant().toString());
    touch(x, null);
    log.info("입고 예정 {} ← 센터 발주 이관 요청 {} ({}줄)", trfId, fromCdc, approved.size());
  }

  private Promise fresh(String trfId, String mfcId) {
    Promise x = new Promise();
    x.trfId = trfId;
    x.mfcId = mfcId;
    x.state = "EXPECTED";
    x.createdAt = clock.instant();
    return x;
  }

  private void touch(Promise x, @Nullable EventEnvelope e) {
    if (e != null) {
      x.sources.put(e.sourceSystem(), e.occurredAt().toString());
    }
    x.version++;
    x.updatedAt = clock.instant();
    store.save(x);
  }

  // ---------- 조회 ----------

  public Page list(
      String mfcId,
      @Nullable String state,
      @Nullable LocalDate date,
      int size,
      @Nullable String cursor) {
    InboundTaskService.requireOperator(mfcId);
    List<String> states = state == null ? OPEN : List.of(state);
    Instant from = date == null ? null : date.atStartOfDay(SEOUL).toInstant();
    Instant to = date == null ? null : date.plusDays(1).atStartOfDay(SEOUL).toInstant();
    List<Promise> rows = store.list(mfcId, states, from, to, size + 1, cursor);
    boolean more = rows.size() > size;
    List<Promise> page = more ? rows.subList(0, size) : rows;
    return new Page(
        page.stream().map(this::view).toList(), more ? page.get(page.size() - 1).trfId : null);
  }

  public View get(String trfId) {
    Promise x = find(trfId);
    InboundTaskService.requireOperator(x.mfcId);
    // (0.25.0, R-52) 끝나지 않은 예정의 화면 번호가 비었으면 한 번 더 묻는다(Wave 는 인수 뒤에 묶인다)
    if (OPEN.contains(x.state)
        && (x.trfNumber == null || x.waveNumber == null)
        && refreshNumbers(trfId)) {
      x = find(trfId);
    }
    return view(x);
  }

  public ReceiptRow receipt(String trfId, String receiptId) {
    Promise x = find(trfId);
    InboundTaskService.requireOperator(x.mfcId);
    return store
        .findReceipt(receiptId)
        .filter(r -> r.trfId().equals(trfId))
        .orElseThrow(() -> PmartException.notFound(RECEIPT_NOT_FOUND, "수령 기록이 없습니다: " + receiptId));
  }

  private View view(Promise x) {
    List<ReceiptRow> receipts = store.receiptsOf(x.trfId);
    List<ReceiptLine> applied =
        receipts.stream()
            .filter(r -> "APPLIED".equals(r.state()))
            .flatMap(r -> r.lines().stream())
            .toList();
    List<ShipLine> packages =
        x.manifestLines != null
            ? x.manifestLines
            : x.departedLines != null ? x.departedLines : List.of();
    return new View(x, InboundPromiseRules.view(expected(x), applied), packages, receipts);
  }

  private static List<ExpectedLine> expected(Promise x) {
    return InboundPromiseRules.expected(x.approvedLines, x.departedLines);
  }

  private Promise find(String trfId) {
    return store
        .find(trfId)
        .orElseThrow(() -> PmartException.notFound(NOT_FOUND, "입고 예정이 없습니다: " + trfId));
  }

  // ---------- 도착 등록 ----------

  public CommandResponse recordArrival(
      String requestId,
      String sourceSystem,
      String trfId,
      @Nullable Integer expectedVersion,
      ArrivalCommand c) {
    String me = InboundTaskService.requireOperator(find(trfId).mfcId);
    return idempotency
        .execute(
            new RequestKey(sourceSystem, requestId, "recordInboundArrival", me),
            new ArrivalRequest(trfId, expectedVersion, c),
            CommandResponse.class,
            () -> {
              Promise x = store.lock(trfId).orElseThrow();
              checkVersion(x, expectedVersion);
              if (!"EXPECTED".equals(x.state)) {
                throw PmartException.invalidState(
                    "입고 예정(EXPECTED) 상태가 아닙니다: " + trfId + " (" + x.state + ")");
              }
              Instant now = clock.instant();
              x.state = "RECEIVING";
              x.arrivedAt = c.arrivedAt();
              x.arrivalTripId = c.tripId();
              x.unloadingBayReservationId = c.unloadingBayReservationId();
              x.arrivalEvidenceRef = c.evidenceRef();
              x.arrivalRecordedBy = me;
              touch(x, null);
              publishArrival(x, me);
              log.info("입고 예정 {} 도착 {} by {}", trfId, c.arrivedAt(), me);
              return new CommandResponse(
                  CommandStatus.ACCEPTED, requestId, trfId, 0, x.version, false, null, now);
            })
        .value();
  }

  // ---------- 수령 등록 ----------

  public CommandResponse recordReceipt(
      String requestId,
      String sourceSystem,
      String trfId,
      @Nullable Integer expectedVersion,
      ReceiptCommand c) {
    String me = InboundTaskService.requireOperator(find(trfId).mfcId);
    String rid =
        idempotency
            .execute(
                new RequestKey(sourceSystem, requestId, "recordInboundReceipt", me),
                new ReceiptRequest(trfId, expectedVersion, c),
                String.class,
                () -> capture(trfId, expectedVersion, c, me))
            .value();
    post(rid);
    closures.send(trfId); // 자동 종결이 생겼으면 바로 CDC 에(실패하면 주기 작업이 다시)
    ReceiptRow r = store.findReceipt(rid).orElseThrow();
    Promise x = find(trfId);
    CommandStatus st =
        switch (r.state()) {
          case "APPLIED" -> CommandStatus.APPLIED;
          case "REJECTED" -> CommandStatus.REJECTED;
          default -> CommandStatus.UNKNOWN_RESULT;
        };
    boolean retry = st == CommandStatus.UNKNOWN_RESULT;
    return new CommandResponse(
        st,
        requestId,
        rid,
        r.lines().stream().mapToInt(ReceiptLine::total).sum(),
        x.version,
        retry,
        retry ? "RETRY_SAME_KEY" : null,
        clock.instant());
  }

  private String capture(
      String trfId, @Nullable Integer expectedVersion, ReceiptCommand c, String me) {
    Promise x = store.lock(trfId).orElseThrow();
    checkVersion(x, expectedVersion);
    if (!"RECEIVING".equals(x.state)) {
      throw PmartException.invalidState(
          "수령은 도착 등록 뒤(RECEIVING)에만 합니다: " + trfId + " (" + x.state + ")");
    }
    if (x.departureId == null) {
      throw PmartException.invalidState(
          "출발(TOUT) 사실을 아직 받지 못했습니다 — 원출발 확인 뒤 수령합니다(IV-02): " + trfId);
    }
    if (store.hasOpenReceipt(trfId)) {
      throw PmartException.invalidState("재고 반영 전인 수령이 있습니다 — 그 수령을 같은 요청 키로 다시 보내 주세요.");
    }
    List<ExpectedLine> exp = expected(x);
    InboundPromiseRules.validate(exp, c.lines())
        .ifPresent(
            msg -> {
              throw new PmartException(ErrorCode.VALIDATION_FAILED, msg);
            });
    List<ReceiptLine> lines = InboundPromiseRules.fillLots(exp, c.lines());
    List<Discrepancy> diff = InboundPromiseRules.discrepancies(exp, lines);
    Instant now = clock.instant();
    String rid = "RCV-TRF-" + UUID.randomUUID();
    store.insertReceipt(
        new ReceiptRow(
            rid,
            trfId,
            x.mfcId,
            x.departureId,
            lines,
            diff,
            "CAPTURED",
            null,
            null,
            null,
            c.evidenceRef(),
            me,
            c.receivedAt() != null ? c.receivedAt() : now.truncatedTo(ChronoUnit.MILLIS),
            null),
        now);
    touch(x, null);
    log.info("입고 예정 {} 수령 {} 기록 — {}줄, 차이 {}건 by {}", trfId, rid, lines.size(), diff.size(), me);
    return rid;
  }

  /** 재고 반영 — 반영 전(CAPTURED·UNKNOWN_RESULT) 수령만. inventory 는 receiptId 로 한 번만 계상한다. */
  private void post(String rid) {
    ReceiptRow r = store.findReceipt(rid).orElseThrow();
    if (!"CAPTURED".equals(r.state()) && !"UNKNOWN_RESULT".equals(r.state())) {
      return;
    }
    List<Map<String, Object>> body = new ArrayList<>();
    for (ReceiptLine l : r.lines()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("sku", l.sku());
      if (l.lotId() != null) {
        m.put("sourceLotRef", l.lotId());
      }
      if (l.expiryDate() != null) {
        m.put("expiryDate", l.expiryDate());
      }
      m.put("normalQty", l.normalQty());
      m.put("quarantinedQty", l.quarantinedQty());
      m.put("pendingQty", l.pendingQty());
      m.put("toLocationCode", l.toLocationCode());
      body.add(m);
    }
    // IV-8(2026-11-26): CDC 이관이면 보낸 CDC 를 실어 inventory 가 MfcReceiptPosted 를 낸다(CDC 운송 중 대사)
    String fromCdc = store.find(r.trfId()).map(x -> x.fromCdc).orElse(null);
    InventoryReceiptClient.Result res =
        inventory.postRaw(
            rid, r.trfId(), r.departureId(), r.mfcId(), r.receivedAt(), body, fromCdc);
    Instant now = clock.instant();
    switch (res) {
      case InventoryReceiptClient.Result.Posted p -> {
        List<InventoryReceiptClient.ReceiptLine> got;
        try {
          got = inventory.receiptLines(rid, true);
        } catch (RuntimeException ex) {
          // 재고는 반영됐지만 lotId 를 몰라 품질검사 작업을 못 만든다 — 같은 키로 다시(inventory 는 같은 commitId)
          store.markUnknown(rid, "수령 결과 조회 실패: " + ex.getMessage(), now);
          return;
        }
        tx.executeWithoutResult(
            s -> {
              if (store.markApplied(rid, p.commitId(), now) != 1) {
                return;
              }
              tasks.inspectionsFor(rid, r.mfcId(), got, now);
              Promise x = store.lock(r.trfId()).orElseThrow();
              x.state = "TIN_POSTED";
              touch(x, null);
              // MI-5: 수령은 TRF 하나에 한 번 — 재고 반영 = 더 받을 것 없음(CDC 에 알림은 트랜잭션 밖 주기 작업)
              closures.closeAfterPosting(x, now);
              if (!r.discrepancies().isEmpty()) {
                publishDiscrepancy(x, r, now);
              }
              log.info("입고 예정 {} 수령 {} 재고 반영 {}", r.trfId(), rid, p.commitId());
            });
      }
      case InventoryReceiptClient.Result.Rejected j ->
          tx.executeWithoutResult(
              s -> {
                if (store.markRejected(rid, j.reason(), now) == 1) {
                  store.lock(r.trfId()).ifPresent(x -> touch(x, null));
                  log.warn("입고 예정 {} 수령 {} 재고 거절: {}", r.trfId(), rid, j.reason());
                }
              });
      case InventoryReceiptClient.Result.Retry t -> store.markUnknown(rid, t.error(), now);
    }
  }

  private static void checkVersion(Promise x, @Nullable Integer expected) {
    if (expected != null && expected != x.version) {
      throw PmartException.versionConflict(
          "입고 예정 version 이 다릅니다(지금 " + x.version + ", 요청 " + expected + ").");
    }
  }

  // ---------- 발행(mfc.inbound.v1) ----------

  private void publishArrival(Promise x, @Nullable String operator) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("trfId", x.trfId);
    if (x.departureId != null) {
      p.put("departureId", x.departureId);
    }
    String trip = x.arrivalTripId != null ? x.arrivalTripId : x.tripId;
    if (trip != null) {
      p.put("tripId", trip);
    }
    p.put("mfcId", x.mfcId);
    p.put("arrivedAt", java.util.Objects.requireNonNull(x.arrivedAt).toString());
    publish("InboundArrivalRecorded", x, operator, p);
  }

  private void publishDiscrepancy(Promise x, ReceiptRow r, Instant now) {
    int expected = 0;
    int received = 0;
    int missing = 0;
    int excess = 0;
    List<Map<String, Object>> lines = new ArrayList<>();
    for (Discrepancy d : r.discrepancies()) {
      int miss = Math.max(0, d.expectedQty() - d.receivedQty());
      int over = Math.max(0, d.receivedQty() - d.expectedQty());
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("sku", d.sku());
      m.put("expectedQty", d.expectedQty());
      m.put("receivedQty", d.receivedQty());
      m.put("missingQty", miss);
      m.put("excessQty", over);
      m.put("reason", d.reason());
      lines.add(m);
      missing += miss;
      excess += over;
    }
    for (int q : InboundPromiseRules.expectedBySku(expected(x)).values()) {
      expected += q;
    }
    for (ReceiptLine l : r.lines()) {
      received += l.total();
    }
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("discrepancyId", r.receiptId());
    p.put("trfId", x.trfId);
    p.put("departureId", r.departureId());
    p.put("receiptId", r.receiptId());
    if (x.manifestId != null) {
      p.put("shipmentManifestId", x.manifestId);
    }
    p.put("mfcId", x.mfcId);
    p.put("expectedQty", expected);
    p.put("receivedQty", received);
    p.put("missingQty", missing);
    p.put("excessQty", excess);
    p.put("lines", lines);
    p.put("reportedAt", now.toString());
    publish("ReceiptDiscrepancyReported", x, r.receivedBy(), p);
  }

  private void publish(
      String type, Promise x, @Nullable String operator, Map<String, Object> payload) {
    events.publish(
        codec.create(
            events.sourceSystem(),
            type,
            1,
            "Inbound",
            x.trfId,
            x.version,
            operator == null
                ? new EventEnvelope.Actor(EventEnvelope.ActorType.SYSTEM, events.sourceSystem())
                : new EventEnvelope.Actor(EventEnvelope.ActorType.OPERATOR, operator),
            EventEnvelope.EventSource.REAL,
            payload),
        SupplyRequestService.TOPIC);
  }

  // ---------- 사건 값 ----------

  private static List<ApprovedLine> approvedLines(JsonNode lines) {
    List<ApprovedLine> out = new ArrayList<>();
    for (JsonNode l : lines) {
      String sku = text(l, "sku");
      if (sku != null) {
        out.add(new ApprovedLine(sku, l.path("approvedQty").asInt(0), text(l, "unit")));
      }
    }
    return out;
  }

  private static List<ShipLine> shipLines(JsonNode lines) {
    List<ShipLine> out = new ArrayList<>();
    for (JsonNode l : lines) {
      String sku = text(l, "sku");
      if (sku != null) {
        out.add(
            new ShipLine(
                sku,
                text(l, "lot"),
                text(l, "lpn"),
                l.path("qty").asInt(0),
                text(l, "expiryDate"),
                text(l, "parentLpn"))); // CO-2: 박스가 담긴 롤테이너·팔레트
      }
    }
    return out;
  }

  private static @Nullable String text(JsonNode n, String field) {
    return n.hasNonNull(field) ? n.get(field).asString() : null;
  }

  private static @Nullable Instant instant(JsonNode n, String field) {
    String s = text(n, field);
    if (s == null) {
      return null;
    }
    try {
      return Instant.parse(s);
    } catch (java.time.format.DateTimeParseException ex) {
      return java.time.OffsetDateTime.parse(s).toInstant();
    }
  }

  private static <T> @Nullable T orElse(@Nullable T a, @Nullable T b) {
    return a != null ? a : b;
  }
}
