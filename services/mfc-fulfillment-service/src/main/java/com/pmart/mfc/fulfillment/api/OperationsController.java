package com.pmart.mfc.fulfillment.api;

import com.pmart.mfc.fulfillment.api.generated.OperationsApi;
import com.pmart.mfc.fulfillment.api.generated.model.FailedStopRequest;
import com.pmart.mfc.fulfillment.api.generated.model.LastRetry;
import com.pmart.mfc.fulfillment.api.generated.model.ListFailedStopRequests200Response;
import com.pmart.mfc.fulfillment.api.generated.model.ListRejectedHandovers200Response;
import com.pmart.mfc.fulfillment.api.generated.model.ListTasks200ResponseAsOf;
import com.pmart.mfc.fulfillment.api.generated.model.ListTasks200ResponsePage;
import com.pmart.mfc.fulfillment.api.generated.model.RejectedHandover;
import com.pmart.mfc.fulfillment.application.PendingListService;
import com.pmart.mfc.fulfillment.infrastructure.PendingListStore;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 백오피스 처리 대기(BO-12, 요청 F-4, 0.9.0) — 멈춘 중지·거절된 인계 목록. */
@RestController
public class OperationsController implements OperationsApi {

  private final PendingListService lists;
  private final Clock clock;

  public OperationsController(PendingListService lists, Clock clock) {
    this.lists = lists;
    this.clock = clock;
  }

  @Override
  public ResponseEntity<ListFailedStopRequests200Response> listFailedStopRequests(
      Boolean failed, @Nullable String mfcId, Integer size, @Nullable String cursor) {
    var page = lists.failedStops(Boolean.TRUE.equals(failed), mfcId, size, cursor);
    var out = new ListFailedStopRequests200Response();
    out.setItems(
        page.items().stream()
            .map(
                s ->
                    new FailedStopRequest()
                        .stopRequestId(s.stopRequestId())
                        .orderId(s.orderId())
                        .claimId(s.claimId())
                        .fulfillmentId(s.fulfillmentId())
                        .mfcId(s.mfcId())
                        .state(FailedStopRequest.StateEnum.PENDING)
                        .scope(FailedStopRequest.ScopeEnum.fromValue(s.scope()))
                        .lastError(s.lastError())
                        .failedAt(time(s.failedAt()))
                        .requestedAt(time(s.requestedAt()))
                        .lastRetry(retry(page.lastRetries().get(s.stopRequestId()))))
            .toList());
    out.setPage(new ListTasks200ResponsePage().nextCursor(page.nextCursor()).size(size));
    out.setAsOf(asOf());
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<ListRejectedHandovers200Response> listRejectedHandovers(
      String status, @Nullable String mfcId, Integer size, @Nullable String cursor) {
    var page = lists.rejectedHandovers(status, mfcId, size, cursor);
    var out = new ListRejectedHandovers200Response();
    out.setItems(
        page.items().stream()
            .map(
                h ->
                    new RejectedHandover()
                        .handoverId(h.handoverId())
                        .packageId(h.packageId())
                        .shipmentId(h.shipmentId())
                        .fulfillmentId(h.fulfillmentId())
                        .orderId(h.orderId())
                        .deliveryId(h.deliveryId())
                        .riderId(h.riderId())
                        .mfcId(h.mfcId())
                        .status(RejectedHandover.StatusEnum.REJECTED)
                        .rejectReason(h.rejectReason())
                        .rejectedAt(time(h.rejectedAt()))
                        .requestedAt(time(h.requestedAt()))
                        .lastRetry(retry(page.lastRetries().get(h.handoverId()))))
            .toList());
    out.setPage(new ListTasks200ResponsePage().nextCursor(page.nextCursor()).size(size));
    out.setAsOf(asOf());
    return ResponseEntity.ok(out);
  }

  private ListTasks200ResponseAsOf asOf() {
    return new ListTasks200ResponseAsOf()
        .asOf(time(clock.instant()))
        .freshness(ListTasks200ResponseAsOf.FreshnessEnum.CURRENT);
  }

  private static @Nullable LastRetry retry(PendingListStore.@Nullable Retry r) {
    return r == null
        ? null
        : new LastRetry()
            .operatorId(r.operatorId())
            .reason(r.reason())
            .retriedAt(time(r.retriedAt()));
  }

  private static @Nullable OffsetDateTime time(@Nullable Instant t) {
    return t == null ? null : t.atOffset(ZoneOffset.UTC);
  }
}
