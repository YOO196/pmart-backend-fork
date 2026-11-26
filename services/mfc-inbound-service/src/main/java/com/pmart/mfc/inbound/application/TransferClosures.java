package com.pmart.mfc.inbound.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.CommandResponse;
import com.pmart.common.web.CommandStatus;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.inbound.domain.InboundPromiseRules;
import com.pmart.mfc.inbound.domain.InboundPromiseRules.ReceiptLine;
import com.pmart.mfc.inbound.infrastructure.InboundPromiseStore;
import com.pmart.mfc.inbound.infrastructure.InboundPromiseStore.Promise;
import com.pmart.mfc.inbound.infrastructure.InboundPromiseStore.ReceiptRow;
import com.pmart.mfc.inbound.infrastructure.InboundTaskStore;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * CDC 이관(TRF) 입고 종결(2026-11-26, 계약 0.22.0 — 요청 MI-5, WMS CDC-F-027 "손실 확정 전 양측 확인"). "이 TRF 는 더 받을
 * 것 없음"을 {@code TransferReceivingClosed}(mfc.inbound.v1)로 한 번 알린다 — cdc-inventory 는 이것을 받은 뒤에만 운송
 * 손실을 받는다.
 *
 * <ul>
 *   <li>자동({@link #closeIfComplete}, 호출한 쪽 트랜잭션 안): 수령이 재고에 반영됐고(TIN_POSTED) SKU 마다 받은 양 = 출발 수량이며
 *       반영된 수령의 품질검사(INSPECT) 작업이 모두 끝났을 때 — ALL_RECEIVED. 수령 반영 직후와 품질검사 반영 뒤에 부른다.
 *   <li>관리자({@link #close}): 그 센터 MFC_SUPERVISOR. 덜 온 TIN_POSTED → SHORT_CLOSED(상태 그대로), 받은 것 없는
 *       EXPECTED·RECEIVING → 상태 CLOSED·NOT_RECEIVED_CLOSED.
 *   <li>공통 조건: 출발(TOUT)을 받았고, 재고 반영 전 수령이 없고, 품질검사가 끝났다(미판정이 남은 동안은 닫지 않는다).
 * </ul>
 */
@Service
public class TransferClosures {

  static final String SUPERVISOR = "MFC_SUPERVISOR";
  static final String ALL_RECEIVED = "ALL_RECEIVED";
  static final String SHORT_CLOSED = "SHORT_CLOSED";
  static final String NOT_RECEIVED_CLOSED = "NOT_RECEIVED_CLOSED";

  private static final Logger log = LoggerFactory.getLogger(TransferClosures.class);

  /** 관리자 종결 본문. */
  public record CloseCommand(String reason) {}

  record CloseRequest(String trfId, @Nullable Integer expectedVersion, CloseCommand body) {}

  private final InboundPromiseStore store;
  private final InboundTaskStore tasks;
  private final RequestIdempotency idempotency;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;
  private final Clock clock;

  public TransferClosures(
      InboundPromiseStore store,
      InboundTaskStore tasks,
      RequestIdempotency idempotency,
      EventPublisher events,
      EventEnvelopeCodec codec,
      Clock clock) {
    this.store = store;
    this.tasks = tasks;
    this.idempotency = idempotency;
    this.events = events;
    this.codec = codec;
    this.clock = clock;
  }

  /** 품질검사 반영 뒤(같은 트랜잭션) — 그 작업의 receiptId 가 입고 예정 수령이면 자동 종결을 본다. */
  public void afterInspection(String receiptId) {
    store.findReceipt(receiptId).ifPresent(r -> closeIfComplete(r.trfId()));
  }

  /** 자동 종결(ALL_RECEIVED) — 조건이 안 되면 아무것도 하지 않는다. 트랜잭션 안에서 부른다. */
  public void closeIfComplete(String trfId) {
    Promise x = store.lock(trfId).orElse(null);
    if (x == null || x.closedAt != null || !"TIN_POSTED".equals(x.state)) {
      return;
    }
    if (blocker(x) != null || isShort(x)) {
      return;
    }
    closeNow(x, ALL_RECEIVED, null, null);
  }

  /** 관리자 종결 — 그 센터 MFC_SUPERVISOR. */
  public CommandResponse close(
      String requestId,
      String sourceSystem,
      String trfId,
      @Nullable Integer expectedVersion,
      CloseCommand c) {
    Promise found =
        store
            .find(trfId)
            .orElseThrow(
                () -> PmartException.notFound(InboundPromises.NOT_FOUND, "입고 예정이 없습니다: " + trfId));
    String me = InboundTaskService.requireOperator(found.mfcId);
    if (!PmartPrincipal.current().orElseThrow().hasRole(SUPERVISOR)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "입고 종결은 이 센터의 MFC_SUPERVISOR 가 합니다.");
    }
    if (c.reason() == null || c.reason().isBlank()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "종결 사유(reason)가 필요합니다.");
    }
    return idempotency
        .execute(
            new RequestKey(sourceSystem, requestId, "closeInboundPromise", me),
            new CloseRequest(trfId, expectedVersion, c),
            CommandResponse.class,
            () -> {
              Promise x = store.lock(trfId).orElseThrow();
              if (expectedVersion != null && expectedVersion != x.version) {
                throw PmartException.versionConflict(
                    "입고 예정 version 이 다릅니다(지금 " + x.version + ", 요청 " + expectedVersion + ").");
              }
              if (x.closedAt != null) {
                throw PmartException.invalidState(
                    "이미 종결한 입고 예정입니다: " + trfId + " (" + x.closeKind + ")");
              }
              String kind =
                  switch (x.state) {
                    case "TIN_POSTED" -> isShort(x) ? SHORT_CLOSED : ALL_RECEIVED;
                    case "EXPECTED", "RECEIVING" -> NOT_RECEIVED_CLOSED;
                    default ->
                        throw PmartException.invalidState(
                            "종결할 수 없는 상태입니다: " + trfId + " (" + x.state + ")");
                  };
              String why = blocker(x);
              if (why != null) {
                throw PmartException.invalidState(why);
              }
              if (NOT_RECEIVED_CLOSED.equals(kind)) {
                x.state = "CLOSED";
              }
              closeNow(x, kind, me, c.reason().strip());
              return new CommandResponse(
                  CommandStatus.APPLIED, requestId, trfId, 0, x.version, false, null, x.closedAt);
            })
        .value();
  }

  /** 닫으면 안 되는 이유(없으면 null). */
  private @Nullable String blocker(Promise x) {
    if (x.departureId == null) {
      return "출발(TOUT) 전 입고 예정은 종결하지 않습니다 — 출발 전 취소는 SCM 이 합니다: " + x.trfId;
    }
    if (store.hasOpenReceipt(x.trfId)) {
      return "재고 반영 전인 수령이 있습니다 — 그 수령을 마친 뒤 종결합니다: " + x.trfId;
    }
    for (ReceiptRow r : applied(x.trfId)) {
      if (!tasks.list(x.mfcId, "READY", "INSPECT", r.receiptId()).isEmpty()) {
        return "품질검사가 끝나지 않은 수령이 있습니다(" + r.receiptId() + ") — 검사를 마친 뒤 종결합니다.";
      }
    }
    return null;
  }

  private boolean isShort(Promise x) {
    Map<String, Integer> got = receivedBySku(x.trfId);
    for (Map.Entry<String, Integer> e : departedBySku(x).entrySet()) {
      if (got.getOrDefault(e.getKey(), 0) < e.getValue()) {
        return true;
      }
    }
    return false;
  }

  private void closeNow(Promise x, String kind, @Nullable String by, @Nullable String reason) {
    Instant now = clock.instant();
    x.closedAt = now;
    x.closeKind = kind;
    x.closedBy = by;
    x.closeReason = reason;
    x.version++;
    x.updatedAt = now;
    store.save(x);
    publish(x);
    log.info("입고 예정 {} 종결 {} by {}", x.trfId, kind, by == null ? "자동" : by);
  }

  private void publish(Promise x) {
    Map<String, Integer> dep = departedBySku(x);
    Map<String, Integer> got = receivedBySku(x.trfId);
    List<Map<String, Object>> lines = new ArrayList<>();
    int depTotal = 0;
    int gotTotal = 0;
    TreeMap<String, Boolean> skus = new TreeMap<>();
    dep.keySet().forEach(s -> skus.put(s, true));
    got.keySet().forEach(s -> skus.put(s, true));
    for (String sku : skus.keySet()) {
      int d = dep.getOrDefault(sku, 0);
      int g = got.getOrDefault(sku, 0);
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("sku", sku);
      m.put("departedQty", d);
      m.put("receivedQty", g);
      lines.add(m);
      depTotal += d;
      gotTotal += g;
    }
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("trfId", x.trfId);
    p.put("departureId", x.departureId);
    p.put("mfcId", x.mfcId);
    if (x.fromCdc != null) {
      p.put("fromCdc", x.fromCdc);
    }
    p.put("closedAt", java.util.Objects.requireNonNull(x.closedAt).toString());
    p.put("closeKind", x.closeKind);
    p.put("departedQty", depTotal);
    p.put("receivedQty", gotTotal);
    p.put("receiptIds", applied(x.trfId).stream().map(ReceiptRow::receiptId).toList());
    p.put("lines", lines);
    if (x.closedBy != null) {
      p.put("closedBy", x.closedBy);
    }
    if (x.closeReason != null) {
      p.put("reason", x.closeReason);
    }
    EventEnvelope.Actor actor =
        x.closedBy != null
            ? new EventEnvelope.Actor(EventEnvelope.ActorType.OPERATOR, x.closedBy)
            : new EventEnvelope.Actor(EventEnvelope.ActorType.SYSTEM, events.sourceSystem());
    events.publish(
        codec.create(
            events.sourceSystem(),
            "TransferReceivingClosed",
            1,
            "Inbound",
            x.trfId,
            x.version,
            actor,
            EventEnvelope.EventSource.REAL,
            p),
        SupplyRequestService.TOPIC);
  }

  private List<ReceiptRow> applied(String trfId) {
    return store.receiptsOf(trfId).stream().filter(r -> "APPLIED".equals(r.state())).toList();
  }

  private Map<String, Integer> receivedBySku(String trfId) {
    Map<String, Integer> out = new LinkedHashMap<>();
    for (ReceiptRow r : applied(trfId)) {
      for (ReceiptLine l : r.lines()) {
        out.merge(l.sku(), l.total(), Integer::sum);
      }
    }
    return out;
  }

  private static Map<String, Integer> departedBySku(Promise x) {
    return InboundPromiseRules.expectedBySku(
        InboundPromiseRules.expected(x.approvedLines, x.departedLines));
  }
}
