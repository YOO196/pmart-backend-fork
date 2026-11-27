package com.pmart.mfc.inbound.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.CommandResponse;
import com.pmart.common.web.CommandStatus;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.inbound.infrastructure.CdcTransitClosureClient;
import com.pmart.mfc.inbound.infrastructure.InboundPromiseStore;
import com.pmart.mfc.inbound.infrastructure.InboundPromiseStore.Promise;
import com.pmart.mfc.inbound.infrastructure.TransitClosureStore;
import com.pmart.mfc.inbound.infrastructure.TransitClosureStore.Closure;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * CDC 이관 입고 종결(요청 MI-5, 2026-11-26 사용자 "구현해"·"전부 여기서") — "이 TRF 는 더 받을 것 없음"을 cdc-inventory 에 알린다
 * (WMS CDC-F-027, CDC 는 이 기록이 있어야 운송 손실을 받는다).
 *
 * <ul>
 *   <li>자동(AUTO): 수령은 TRF 하나에 한 번(RECEIVING 일 때만)이라 재고 반영(TIN_POSTED) 순간 더 받을 것이 없다 — {@link
 *       InboundPromises} 가 같은 트랜잭션에서 {@link #closeAfterPosting} 을 부른다.
 *   <li>관리자(MANUAL): 물건이 오지 않은 이관 — 그 센터 MFC_SUPERVISOR 가 사유와 함께 종결(EXPECTED·RECEIVING, 열린 수령 없음).
 *       예정은 CLOSED. (0.26.0, R-54) CDC 출발(fromCdc·departureId)을 받지 못한 예정도 종결하되 CDC 에는 알리지 않는다.
 * </ul>
 *
 * 자동 종결은 CDC 출발이 있는 예정만. 알림은 트랜잭션 밖({@link #sendDue}, 주기 작업) — 실패하면 1분부터 두 배(최대 30분)로 다시, CDC 가
 * 409(다른 내용)면 멈춘다.
 */
@Service
public class TransitClosures {

  static final String SUPERVISOR = "MFC_SUPERVISOR";
  static final Duration FIRST_RETRY = Duration.ofMinutes(1);
  static final Duration MAX_RETRY = Duration.ofMinutes(30);

  private static final Logger log = LoggerFactory.getLogger(TransitClosures.class);

  /** 관리자 종결 요청. */
  public record CloseCommand(String reason) {}

  record CloseRequest(String trfId, @Nullable Integer expectedVersion, CloseCommand c) {}

  private final InboundPromiseStore promises;
  private final TransitClosureStore store;
  private final CdcTransitClosureClient cdc;
  private final com.pmart.mfc.inbound.infrastructure.CdcTransferIntakeClient intake;
  private final RequestIdempotency idempotency;
  private final Clock clock;

  public TransitClosures(
      InboundPromiseStore promises,
      TransitClosureStore store,
      CdcTransitClosureClient cdc,
      com.pmart.mfc.inbound.infrastructure.CdcTransferIntakeClient intake,
      RequestIdempotency idempotency,
      Clock clock) {
    this.intake = intake;
    this.promises = promises;
    this.store = store;
    this.cdc = cdc;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  /** 재고 반영 뒤 자동 종결(호출자 트랜잭션 안). CDC 출발이 없는 예정은 건너뛴다. */
  void closeAfterPosting(Promise x, Instant now) {
    if (!fromCdc(x)) {
      return;
    }
    if (store.insert(
        x.trfId, x.mfcId, x.fromCdc, x.departureId, "AUTO", now, "SYSTEM", "TIN_POSTED")) {
      log.info("입고 예정 {} 자동 종결(재고 반영) — CDC {} 에 알림 예정", x.trfId, x.fromCdc);
    }
  }

  /** 관리자 종결 — 오지 않은 이관. */
  public CommandResponse close(
      String requestId,
      String sourceSystem,
      String trfId,
      @Nullable Integer expectedVersion,
      CloseCommand c) {
    Promise found =
        promises
            .find(trfId)
            .orElseThrow(
                () -> PmartException.notFound(InboundPromises.NOT_FOUND, "입고 예정이 없습니다: " + trfId));
    String me = InboundTaskService.requireOperator(found.mfcId);
    if (!PmartPrincipal.current().orElseThrow().hasRole(SUPERVISOR)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "입고 종결은 이 센터의 MFC_SUPERVISOR 가 합니다.");
    }
    if (c.reason() == null || c.reason().isBlank()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "종결 사유가 필요합니다.");
    }
    // (0.27.0, CO-3 ①) 출발 없는 공급 요청 TRF 는 DC 미출발 취소가 먼저 — 취소되거나 DC 에 없을 때만 종결(트랜잭션 밖 호출)
    String cancelKey = null;
    if (OPEN.contains(found.state) && !fromCdc(found) && !promises.hasOpenReceipt(trfId)) {
      String supplyRequestId = store.supplyRequestOf(trfId).orElse(null);
      if (supplyRequestId != null) {
        cancelKey = cancelAtCdc(found, supplyRequestId, me, requestId);
      }
    }
    String cdcCancelKey = cancelKey;
    CommandResponse r =
        idempotency
            .execute(
                new RequestKey(sourceSystem, requestId, "closeInboundPromise", me),
                new CloseRequest(trfId, expectedVersion, c),
                CommandResponse.class,
                () -> {
                  Promise x = promises.lock(trfId).orElseThrow();
                  if (expectedVersion != null && expectedVersion != x.version) {
                    throw PmartException.versionConflict(
                        "입고 예정 version 이 다릅니다(지금 " + x.version + ", 요청 " + expectedVersion + ").");
                  }
                  if (!"EXPECTED".equals(x.state) && !"RECEIVING".equals(x.state)) {
                    throw PmartException.invalidState(
                        "종결은 도착 전·수령 전(EXPECTED·RECEIVING)에만 합니다 — 재고 반영된 예정은 자동으로 종결됩니다: "
                            + trfId
                            + " ("
                            + x.state
                            + ")");
                  }
                  if (promises.hasOpenReceipt(trfId)) {
                    throw PmartException.invalidState(
                        "재고 반영 전인 수령이 있습니다 — 그 수령을 먼저 끝내 주세요: " + trfId);
                  }
                  Instant now = clock.instant();
                  x.state = "CLOSED";
                  x.version++;
                  x.updatedAt = now;
                  promises.save(x);
                  // (0.26.0, R-54) CDC 출발이 없는 예정(시험 송장·출발 전 멈춘 TRF)도 종결 — CDC 알림은 없음
                  boolean departed = fromCdc(x);
                  store.insert(
                      x.trfId,
                      x.mfcId,
                      departed ? x.fromCdc : null,
                      departed ? x.departureId : null,
                      "MANUAL",
                      now,
                      me,
                      c.reason());
                  if (!departed) {
                    store.stopUnsentTransfer(trfId, "MFC 관리자 종결 — DC 에 보내지 않음");
                    if (cdcCancelKey != null) {
                      store.setCdcCancelRequest(trfId, cdcCancelKey);
                    }
                  }
                  log.info(
                      "입고 예정 {} 관리자 종결 by {}{} — {}",
                      trfId,
                      me,
                      departed ? "" : "(CDC 출발 없음 — 알림 없음)",
                      c.reason());
                  return new CommandResponse(
                      CommandStatus.ACCEPTED, requestId, trfId, 0, x.version, false, null, now);
                })
            .value();
    send(trfId);
    return r;
  }

  private static final java.util.Set<String> OPEN = java.util.Set.of("EXPECTED", "RECEIVING");

  /**
   * DC 미출발 취소(CO-3 ①). 취소됨이면 요청 키, DC 에 없음이면 null 을 돌려주고, 아니면 예외 — 피킹 시작 등 DC 거절은 409(DC 코드 그대로),
   * 결과 불명은 503 UNKNOWN_RESULT(같은 Idempotency-Key 로 다시 누르면 같은 키로 DC 에 다시), 장애는 503.
   */
  private @Nullable String cancelAtCdc(
      Promise x, String supplyRequestId, String me, String requestId) {
    String key = "mfc-inbound-close:" + x.trfId + ":" + requestId;
    var res = intake.cancel(x.trfId, x.mfcId, supplyRequestId, me, key);
    return switch (res) {
      case com.pmart.mfc.inbound.infrastructure.CdcTransferIntakeClient.Cancel.Cancelled ok -> {
        log.info("입고 예정 {} DC 미출발 취소됨 — 종결로", x.trfId);
        yield key;
      }
      case com.pmart.mfc.inbound.infrastructure.CdcTransferIntakeClient.Cancel.NotAtCdc no -> null;
      case com.pmart.mfc.inbound.infrastructure.CdcTransferIntakeClient.Cancel.Pending p ->
          throw PmartException.unknownResult(p.detail() + " — 같은 요청으로 다시 눌러 주세요: " + x.trfId);
      case com.pmart.mfc.inbound.infrastructure.CdcTransferIntakeClient.Cancel.Refused f ->
          throw new PmartException(
              f.code(),
              org.springframework.http.HttpStatus.CONFLICT,
              "DC 가 미출발 취소를 받지 않았습니다(" + f.code() + ") — 종결하지 않습니다: " + f.detail(),
              false,
              java.util.List.of());
      case com.pmart.mfc.inbound.infrastructure.CdcTransferIntakeClient.Cancel.Unavailable u ->
          throw new PmartException(
              ErrorCode.DEPENDENCY_UNAVAILABLE,
              "DC(cdc-outbound) 미출발 취소를 부르지 못했습니다: " + u.detail());
    };
  }

  public Optional<Closure> closure(String trfId) {
    return store.find(trfId);
  }

  public Map<String, Closure> closures(Collection<String> trfIds) {
    return store.findAll(trfIds).stream()
        .collect(Collectors.toMap(Closure::trfId, Function.identity()));
  }

  /** 아직 CDC 에 알리지 못한 종결을 보낸다. */
  @Scheduled(
      fixedDelayString = "${pmart.inbound.transit-closure-interval:PT15S}",
      initialDelayString = "${pmart.inbound.transit-closure-initial-delay:PT20S}")
  public void sendDue() {
    for (String trfId : store.due(clock.instant(), 20)) {
      send(trfId);
    }
  }

  void send(String trfId) {
    Closure c = store.find(trfId).orElse(null);
    if (c == null || c.notifiedAt() != null || c.departureId() == null || c.fromCdc() == null) {
      return; // (0.26.0) 출발 없는 종결은 CDC 에 알릴 것이 없다
    }
    CdcTransitClosureClient.Result r =
        cdc.send(c.trfId(), c.departureId(), c.mfcId(), c.closedAt(), "inbound-promise:" + trfId);
    Instant now = clock.instant();
    switch (r) {
      case CdcTransitClosureClient.Result.Recorded ok -> {
        store.notified(trfId, ok.unreconciledQty(), now);
        log.info("입고 종결 {} CDC {} 기록됨 — 미정산 {}", trfId, c.fromCdc(), ok.unreconciledQty());
      }
      case CdcTransitClosureClient.Result.Rejected no -> {
        store.failed(trfId, no.reason(), null);
        log.warn("입고 종결 {} CDC 거절(다시 보내지 않음): {}", trfId, no.reason());
      }
      case CdcTransitClosureClient.Result.Retry t -> {
        store.failed(trfId, t.error(), now.plus(backoff(c.attempts())));
        log.warn("입고 종결 {} CDC 알림 실패 — 다시: {}", trfId, t.error());
      }
    }
  }

  static Duration backoff(int attempts) {
    Duration d = FIRST_RETRY.multipliedBy(1L << Math.min(attempts, 5));
    return d.compareTo(MAX_RETRY) > 0 ? MAX_RETRY : d;
  }

  private static boolean fromCdc(Promise x) {
    return x.fromCdc != null
        && !x.fromCdc.isBlank()
        && x.departureId != null
        && !x.departureId.isBlank();
  }
}
