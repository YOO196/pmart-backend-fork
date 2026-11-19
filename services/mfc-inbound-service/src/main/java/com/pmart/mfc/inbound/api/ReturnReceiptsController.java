package com.pmart.mfc.inbound.api;

import com.pmart.mfc.inbound.api.generated.ReturnReceiptsApi;
import com.pmart.mfc.inbound.api.generated.model.RecordArrival202Response;
import com.pmart.mfc.inbound.api.generated.model.RecordReturnReceiptRequest;
import com.pmart.mfc.inbound.api.generated.model.ReturnReceipt;
import com.pmart.mfc.inbound.api.generated.model.ReturnReceiptLinesInner;
import com.pmart.mfc.inbound.application.ReturnReceiptService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 ReturnReceiptsApi — 회수품 반입(IV-10). 업무 규칙은 {@link ReturnReceiptService}. */
@RestController
public class ReturnReceiptsController implements ReturnReceiptsApi {

  private final ReturnReceiptService service;

  private final com.pmart.mfc.inbound.application.InboundLists lists;

  public ReturnReceiptsController(
      ReturnReceiptService service, com.pmart.mfc.inbound.application.InboundLists lists) {
    this.lists = lists;
    this.service = service;
  }

  @Override
  public ResponseEntity<RecordArrival202Response> recordReturnReceipt(
      String idempotencyKey, String xSourceSystem, RecordReturnReceiptRequest b) {
    var c =
        service.record(
            idempotencyKey,
            xSourceSystem,
            new ReturnReceiptService.Command(
                b.getMfcId(),
                b.getToLocationCode(),
                b.getClaimId(),
                b.getReturnPickupId(),
                b.getOrderId(),
                b.getReceivedAt() == null ? null : b.getReceivedAt().toInstant(),
                (b.getLines() == null
                        ? java.util.List
                            .<com.pmart.mfc.inbound.api.generated.model
                                    .RecordReturnReceiptRequestLinesInner>
                                of()
                        : b.getLines())
                    .stream()
                        .map(
                            l ->
                                new ReturnReceiptService.Line(
                                    l.getOrderLineId(),
                                    l.getSku(),
                                    l.getQty(),
                                    l.getSourceLotRef(),
                                    l.getExpiryDate(),
                                    l.getConditionNote(),
                                    l.getEvidenceRef(),
                                    null))
                        .toList(),
                b.getArrivalId()));
    var out = new RecordArrival202Response();
    out.setStatus(RecordArrival202Response.StatusEnum.fromValue(c.status().name()));
    out.setRequestId(c.requestId());
    out.setResultId(c.resultId());
    out.setAppliedQty(c.appliedQty());
    out.setVersion(c.version());
    out.setRetryable(c.retryable());
    out.setNextAction(c.nextAction());
    out.setOccurredAt(c.occurredAt().atOffset(ZoneOffset.UTC));
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(out);
  }

  /** 목록(MB-1, 0.12.0) — 항목은 단건과 같은 변환. */
  @Override
  public ResponseEntity<com.pmart.mfc.inbound.api.generated.model.ListReturnReceipts200Response>
      listReturnReceipts(
          String mfcId,
          java.time.@org.jspecify.annotations.Nullable OffsetDateTime from,
          java.time.@org.jspecify.annotations.Nullable OffsetDateTime to,
          java.util.@org.jspecify.annotations.Nullable List<String> state,
          Integer size,
          @org.jspecify.annotations.Nullable String cursor) {
    int limit = size == null ? 50 : size;
    var ids =
        lists.ids(
            com.pmart.mfc.inbound.infrastructure.InboundListStore.Kind.RETURN,
            mfcId,
            state,
            from == null ? null : from.toInstant(),
            to == null ? null : to.toInstant(),
            limit + 1,
            cursor);
    boolean more = ids.size() > limit;
    var page = more ? ids.subList(0, limit) : ids;
    var out = new com.pmart.mfc.inbound.api.generated.model.ListReturnReceipts200Response();
    out.setItems(page.stream().map(id -> getReturnReceipt(id).getBody()).toList());
    out.setNextCursor(more ? page.get(page.size() - 1) : null);
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<ReturnReceipt> getReturnReceipt(String returnReceiptId) {
    var r = service.view(returnReceiptId);
    var out = new ReturnReceipt();
    out.setReturnReceiptId(r.returnReceiptId());
    out.setClaimId(r.claimId());
    out.setReturnPickupId(r.returnPickupId());
    out.setMfcId(r.mfcId());
    out.setOrderId(r.orderId());
    out.setPurpose(com.pmart.mfc.inbound.api.generated.model.ReturnPurpose.fromValue(r.purpose()));
    out.setCaseId(r.caseId());
    out.setOriginDeliveryId(r.originDeliveryId());
    out.setArrivalId(r.arrivalId());
    out.setToLocationCode(r.toLocation());
    out.setReceivedBy(r.receivedBy());
    out.setRejectReason(r.rejectReason());
    out.setInventoryApplyStatus(ReturnReceipt.InventoryApplyStatusEnum.fromValue(r.state()));
    out.setInventoryCommitId(r.commitId());
    out.setInspectionState(ReturnReceipt.InspectionStateEnum.fromValue(r.inspectionState()));
    out.setReceivedAt(OffsetDateTime.ofInstant(r.receivedAt(), ZoneOffset.UTC));
    out.setLines(
        r.lines().stream()
            .map(
                l -> {
                  var i = new ReturnReceiptLinesInner();
                  i.setOrderLineId(l.orderLineId());
                  i.setSku(l.sku());
                  i.setQty(l.qty());
                  i.setLotId(l.lotId());
                  i.setConditionNote(l.conditionNote());
                  return i;
                })
            .toList());
    out.setPutawayTaskIds(service.putawayTasks(returnReceiptId));
    return ResponseEntity.ok(out);
  }

  private com.pmart.mfc.inbound.application.ReturnArrivals arrivals;
  private com.pmart.mfc.inbound.application.SkuLabels labels;

  @org.springframework.beans.factory.annotation.Autowired
  void setArrivals(
      com.pmart.mfc.inbound.application.ReturnArrivals arrivals,
      com.pmart.mfc.inbound.application.SkuLabels labels) {
    this.arrivals = arrivals;
    this.labels = labels;
  }

  /** 반납 도착 대기(0.13.0, MI-2) — 상품명은 표시용. */
  @Override
  public ResponseEntity<com.pmart.mfc.inbound.api.generated.model.ListReturnArrivals200Response>
      listReturnArrivals(String mfcId, String state, Integer size) {
    var items = arrivals.list(mfcId, state, size == null ? 50 : size);
    java.util.Set<String> skus = new java.util.LinkedHashSet<>();
    items.forEach(
        a ->
            a.lines()
                .forEach(
                    l -> {
                      if (l.sku() != null) skus.add(l.sku());
                    }));
    var names =
        skus.isEmpty()
            ? java.util.Map.<String, com.pmart.mfc.inbound.application.SkuLabels.Label>of()
            : labels.of(skus);
    var out = new com.pmart.mfc.inbound.api.generated.model.ListReturnArrivals200Response();
    out.setItems(
        items.stream()
            .map(
                a -> {
                  var x = new com.pmart.mfc.inbound.api.generated.model.ReturnArrival();
                  x.setArrivalId(a.arrivalId());
                  x.setMfcId(a.mfcId());
                  x.setPurpose(
                      com.pmart.mfc.inbound.api.generated.model.ReturnPurpose.fromValue(
                          a.purpose()));
                  x.setClaimId(a.claimId());
                  x.setCaseId(a.caseId());
                  x.setOriginDeliveryId(a.originDeliveryId());
                  x.setOrderId(a.orderId());
                  x.setRiderId(a.riderId());
                  x.setState(
                      a.waiting()
                          ? com.pmart.mfc.inbound.api.generated.model.ReturnArrival.StateEnum
                              .WAITING
                          : com.pmart.mfc.inbound.api.generated.model.ReturnArrival.StateEnum
                              .RECEIVED);
                  x.setReturnReceiptId(a.waiting() ? null : a.returnReceiptId());
                  x.setArrivedAt(OffsetDateTime.ofInstant(a.arrivedAt(), ZoneOffset.UTC));
                  x.setLines(
                      a.lines().stream()
                          .map(
                              l -> {
                                var y =
                                    new com.pmart.mfc.inbound.api.generated.model
                                        .ReturnArrivalLinesInner();
                                y.setOrderLineId(l.orderLineId());
                                y.setSku(l.sku());
                                y.setQty(l.qty());
                                y.setProductName(
                                    l.sku() == null
                                        ? null
                                        : com.pmart.mfc.inbound.application.SkuLabels.get(
                                                names, l.sku())
                                            .productName());
                                return y;
                              })
                          .toList());
                  return x;
                })
            .toList());
    return ResponseEntity.ok(out);
  }
}
