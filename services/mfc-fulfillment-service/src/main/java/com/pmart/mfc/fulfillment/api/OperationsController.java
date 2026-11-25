package com.pmart.mfc.fulfillment.api;

import com.pmart.mfc.fulfillment.api.generated.OperationsApi;
import com.pmart.mfc.fulfillment.api.generated.model.FailedStopRequest;
import com.pmart.mfc.fulfillment.api.generated.model.FulfillmentLookupShipment;
import com.pmart.mfc.fulfillment.api.generated.model.FulfillmentLookupShipmentPackagesInner;
import com.pmart.mfc.fulfillment.api.generated.model.FulfillmentLookupShipmentShortagesInner;
import com.pmart.mfc.fulfillment.api.generated.model.FulfillmentLookupShipmentTasksInner;
import com.pmart.mfc.fulfillment.api.generated.model.FulfillmentLookupStop;
import com.pmart.mfc.fulfillment.api.generated.model.FulfillmentLookupWorkOrder;
import com.pmart.mfc.fulfillment.api.generated.model.LastRetry;
import com.pmart.mfc.fulfillment.api.generated.model.ListFailedStopRequests200Response;
import com.pmart.mfc.fulfillment.api.generated.model.ListRejectedHandovers200Response;
import com.pmart.mfc.fulfillment.api.generated.model.ListTasks200ResponseAsOf;
import com.pmart.mfc.fulfillment.api.generated.model.ListTasks200ResponsePage;
import com.pmart.mfc.fulfillment.api.generated.model.OrderFulfillmentView;
import com.pmart.mfc.fulfillment.api.generated.model.PackageState;
import com.pmart.mfc.fulfillment.api.generated.model.RejectedHandover;
import com.pmart.mfc.fulfillment.api.generated.model.ShipmentState;
import com.pmart.mfc.fulfillment.api.generated.model.TaskState;
import com.pmart.mfc.fulfillment.application.OrderLookupService;
import com.pmart.mfc.fulfillment.application.PendingListService;
import com.pmart.mfc.fulfillment.infrastructure.PendingListStore;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 백오피스 처리 대기(BO-12, 요청 F-4, 0.9.0) — 멈춘 중지·거절된 인계 목록. 통합 조회 MFC 칸(BO-11, 요청 F-3, 0.10.0). */
@RestController
public class OperationsController implements OperationsApi {

  private static final java.util.Set<String> ENDED =
      java.util.Set.of("COMPLETED", "CLOSED_PARTIAL", "CANCELLED", "FAILED");

  private final PendingListService lists;
  private final OrderLookupService lookups;
  private final Clock clock;

  public OperationsController(PendingListService lists, OrderLookupService lookups, Clock clock) {
    this.lists = lists;
    this.lookups = lookups;
    this.clock = clock;
  }

  @Override
  public ResponseEntity<OrderFulfillmentView> getOrderFulfillment(
      @Nullable String orderId, @Nullable String orderNumber) {
    var v = lookups.find(orderId, orderNumber);
    var out = new OrderFulfillmentView();
    out.setOrderId(v.orderId());
    out.setOrderNumber(v.orderNumber());
    out.setWorkOrders(v.workOrders().stream().map(OperationsController::workOrder).toList());
    out.setStopRequests(
        v.stops().stream()
            .map(
                s ->
                    new FulfillmentLookupStop()
                        .stopRequestId(s.stopRequestId())
                        .scope(FulfillmentLookupStop.ScopeEnum.fromValue(s.scope()))
                        .state(FulfillmentLookupStop.StateEnum.fromValue(s.state()))
                        .lastError(s.lastError())
                        .requestedAt(time(s.requestedAt()))
                        .failedAt(time(s.failedAt()))
                        .resolvedAt(time(s.resolvedAt())))
            .toList());
    out.setAsOf(
        new ListTasks200ResponseAsOf()
            .asOf(time(v.asOf()))
            .freshness(ListTasks200ResponseAsOf.FreshnessEnum.CURRENT));
    return ResponseEntity.ok(out);
  }

  private static FulfillmentLookupWorkOrder workOrder(OrderLookupService.WorkOrderView v) {
    var w = v.workOrder();
    return new FulfillmentLookupWorkOrder()
        .fulfillmentId(w.fulfillmentId())
        .kind(FulfillmentLookupWorkOrder.KindEnum.fromValue(w.kind()))
        .mfcId(w.mfcId())
        .workVersion(w.workVersion())
        .acceptance(FulfillmentLookupWorkOrder.AcceptanceEnum.fromValue(w.acceptance()))
        .rejectReason(w.rejectReason())
        .rejectMessage(w.rejectMessage())
        .decidedAt(time(w.decidedAt()))
        .preparationStartedAt(time(w.preparationStartedAt()))
        .createdAt(time(w.createdAt()))
        .shipments(v.shipments().stream().map(OperationsController::shipment).toList());
  }

  private static FulfillmentLookupShipment shipment(OrderLookupService.ShipmentView v) {
    var s = v.shipment();
    return new FulfillmentLookupShipment()
        .shipmentId(s.shipmentId())
        .state(ShipmentState.fromValue(s.state()))
        .readyAt(time(s.readyAt()))
        .dispatchRequestedAt(time(s.dispatchRequestedAt()))
        .tasks(
            v.tasks().stream()
                .map(
                    t ->
                        new FulfillmentLookupShipmentTasksInner()
                            .taskId(t.taskId())
                            .taskType(
                                FulfillmentLookupShipmentTasksInner.TaskTypeEnum.fromValue(
                                    t.taskType()))
                            .state(TaskState.fromValue(t.state()))
                            .workerId(t.workerId())
                            .startedBy(t.startedBy())
                            .startedAt(time(t.startedAt()))
                            .endedAt(ENDED.contains(t.state()) ? time(t.updatedAt()) : null)
                            .blockReason(t.blockReason())
                            .plannedQty(t.plannedQty())
                            .completedQty(t.completedQty())
                            .cancelledQty(t.cancelledQty())
                            .remainingQty(
                                Math.max(0, t.plannedQty() - t.completedQty() - t.cancelledQty())))
                .toList())
        .shortages(
            v.shortages().stream()
                .map(
                    x ->
                        new FulfillmentLookupShipmentShortagesInner()
                            .taskId(x.taskId())
                            .orderLineId(x.orderLineId())
                            .sku(x.sku())
                            .qty(x.qty())
                            .reason(x.reason())
                            .reportedAt(time(x.reportedAt()))
                            .executionState(
                                FulfillmentLookupShipmentShortagesInner.ExecutionStateEnum
                                    .fromValue(x.executionState())))
                .toList())
        .packages(
            v.packages().stream()
                .map(
                    p ->
                        new FulfillmentLookupShipmentPackagesInner()
                            .packageId(p.packageId())
                            .state(PackageState.fromValue(p.state()))
                            .sealedAt(time(p.sealedAt()))
                            .fenceState(p.fenceState())
                            .handoverAuthorizedAt(time(p.authorizedAt()))
                            .handoverStatus(p.handoverStatus())
                            .handoverRejectReason(p.handoverRejectReason())
                            .handedOverAt(time(p.handedOverAt())))
                .toList());
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
