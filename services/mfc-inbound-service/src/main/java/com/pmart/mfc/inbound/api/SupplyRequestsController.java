package com.pmart.mfc.inbound.api;

import com.pmart.mfc.inbound.api.generated.SupplyRequestsApi;
import com.pmart.mfc.inbound.api.generated.model.ApproveSupplyRequestRequest;
import com.pmart.mfc.inbound.api.generated.model.RecordArrival202Response;
import com.pmart.mfc.inbound.api.generated.model.RejectSupplyRequestRequest;
import com.pmart.mfc.inbound.api.generated.model.SupplyRecommendation;
import com.pmart.mfc.inbound.api.generated.model.SupplyRequest;
import com.pmart.mfc.inbound.api.generated.model.SupplyRequestAllOfApprovedLines;
import com.pmart.mfc.inbound.api.generated.model.SupplyRequestAllOfTransfers;
import com.pmart.mfc.inbound.api.generated.model.SupplyRequestBody;
import com.pmart.mfc.inbound.api.generated.model.SupplyRequestBodyLinesInner;
import com.pmart.mfc.inbound.api.generated.model.SupplyRequestState;
import com.pmart.mfc.inbound.api.generated.model.WithdrawSupplyRequestRequest;
import com.pmart.mfc.inbound.application.SupplyRecommendations;
import com.pmart.mfc.inbound.application.SupplyRequestService;
import com.pmart.mfc.inbound.application.SupplyRequestService.Row;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 SupplyRequestsApi — 로컬 공급 요청(WK-14), (0.21.0, MI-4) 센터 관리자 승인·거절과 발주 수량 추천. 업무 규칙은 {@link
 * SupplyRequestService}·{@link SupplyRecommendations}.
 */
@RestController
public class SupplyRequestsController implements SupplyRequestsApi {

  private final SupplyRequestService service;

  private final com.pmart.mfc.inbound.application.InboundLists lists;

  private final SupplyRecommendations recommendations;

  private final com.pmart.mfc.inbound.application.AutoSupply autoSupply;

  public SupplyRequestsController(
      SupplyRequestService service,
      com.pmart.mfc.inbound.application.InboundLists lists,
      SupplyRecommendations recommendations,
      com.pmart.mfc.inbound.application.AutoSupply autoSupply) {
    this.autoSupply = autoSupply;
    this.recommendations = recommendations;
    this.lists = lists;
    this.service = service;
  }

  @Override
  public ResponseEntity<SupplyRequest> createSupplyRequest(
      String idempotencyKey, String xSourceSystem, SupplyRequestBody body) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(dto(service.create(idempotencyKey, xSourceSystem, body(body))));
  }

  /** 목록(MB-1, 0.12.0) — 항목은 단건과 같은 변환. */
  @Override
  public ResponseEntity<com.pmart.mfc.inbound.api.generated.model.ListSupplyRequests200Response>
      listSupplyRequests(
          String mfcId,
          java.util.@org.jspecify.annotations.Nullable List<String> state,
          Integer size,
          @org.jspecify.annotations.Nullable String cursor) {
    int limit = size == null ? 50 : size;
    var ids =
        lists.ids(
            com.pmart.mfc.inbound.infrastructure.InboundListStore.Kind.SUPPLY,
            mfcId,
            state,
            null,
            null,
            limit + 1,
            cursor);
    boolean more = ids.size() > limit;
    var page = more ? ids.subList(0, limit) : ids;
    var out = new com.pmart.mfc.inbound.api.generated.model.ListSupplyRequests200Response();
    out.setItems(page.stream().map(id -> getSupplyRequest(id).getBody()).toList());
    out.setNextCursor(more ? page.get(page.size() - 1) : null);
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<SupplyRequest> getSupplyRequest(String supplyRequestId) {
    return ResponseEntity.ok(dto(service.find(supplyRequestId)));
  }

  @Override
  public ResponseEntity<SupplyRequest> updateSupplyRequest(
      String supplyRequestId,
      String idempotencyKey,
      String xSourceSystem,
      SupplyRequestBody body,
      @Nullable Integer xExpectedVersion) {
    return ResponseEntity.ok(dto(service.update(supplyRequestId, xExpectedVersion, body(body))));
  }

  @Override
  public ResponseEntity<RecordArrival202Response> withdrawSupplyRequest(
      String supplyRequestId,
      String idempotencyKey,
      String xSourceSystem,
      WithdrawSupplyRequestRequest body,
      @Nullable Integer xExpectedVersion) {
    Row r = service.withdraw(supplyRequestId, body.getReason());
    var out = new RecordArrival202Response();
    out.setStatus(RecordArrival202Response.StatusEnum.APPLIED);
    out.setRequestId(idempotencyKey);
    out.setResultId(r.supplyRequestId());
    out.setVersion(r.version());
    out.setRetryable(false);
    out.setOccurredAt(OffsetDateTime.now(ZoneOffset.UTC));
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(out);
  }

  @Override
  public ResponseEntity<SupplyRequest> approveSupplyRequest(
      String supplyRequestId,
      String idempotencyKey,
      String xSourceSystem,
      ApproveSupplyRequestRequest body,
      @Nullable Integer xExpectedVersion) {
    List<SupplyRequestService.Line> lines =
        body.getLines() == null
            ? List.of()
            : body.getLines().stream()
                .map(l -> new SupplyRequestService.Line(l.getSku(), l.getApprovedQty()))
                .toList();
    var approved =
        service.approve(
            idempotencyKey,
            xSourceSystem,
            supplyRequestId,
            xExpectedVersion,
            new SupplyRequestService.Approval(lines, body.getNote()));
    if (approved.transferCdcId() != null && approved.transferSentAt() == null) {
      autoSupply.send(supplyRequestId); // ADR-0091 — 바로 DC 에(실패하면 주기 작업이 다시)
      approved = service.findRow(supplyRequestId).orElse(approved);
    }
    return ResponseEntity.ok(dto(approved));
  }

  @Override
  public ResponseEntity<SupplyRequest> rejectSupplyRequest(
      String supplyRequestId,
      String idempotencyKey,
      String xSourceSystem,
      RejectSupplyRequestRequest body,
      @Nullable Integer xExpectedVersion) {
    return ResponseEntity.ok(
        dto(
            service.reject(
                idempotencyKey,
                xSourceSystem,
                supplyRequestId,
                xExpectedVersion,
                body.getReason())));
  }

  @Override
  public ResponseEntity<com.pmart.mfc.inbound.api.generated.model.SupplyRecommendations>
      listSupplyRecommendations(String mfcId, List<String> sku, @Nullable String storageType) {
    SupplyRecommendations.View v = recommendations.recommend(mfcId, storageType, sku);
    var out = new com.pmart.mfc.inbound.api.generated.model.SupplyRecommendations();
    out.setMfcId(v.mfcId());
    out.setCalculatedAt(odt(v.calculatedAt()));
    out.setAvailabilityAsOf(v.availabilityAsOf() == null ? null : odt(v.availabilityAsOf()));
    out.setSalesThrough(v.salesThrough());
    out.setItems(v.items().stream().map(SupplyRequestsController::recommendation).toList());
    return ResponseEntity.ok(out);
  }

  private static SupplyRecommendation recommendation(SupplyRecommendations.Item i) {
    var r = i.result();
    var out = new SupplyRecommendation();
    out.setSku(i.sku());
    out.setProductName(i.productName());
    out.setStorageType(i.storageType());
    out.setRecommendedQty(r.recommendedQty());
    out.setReason(r.reason());
    out.setMissingPolicyFields(r.missingPolicyFields());
    out.setForecastDailyQty(dec(r.forecastDailyQty()));
    out.setHistoryDays(i.historyDays());
    out.setAvailableQty(i.availableQty());
    out.setExpectedQty(Math.toIntExact(i.expectedQty()));
    out.setPendingRequestedQty(Math.toIntExact(i.pendingRequestedQty()));
    out.setInventoryPosition(dec(r.inventoryPosition()));
    out.setSafetyStockQty(dec(r.safetyStockQty()));
    out.setTargetQty(dec(r.targetQty()));
    out.setShortfallQty(dec(r.shortfallQty()));
    var p = i.policy();
    if (p != null) {
      out.setReviewDays(p.reviewDays());
      out.setLeadDays(p.leadDays());
      out.setSafetyDays(p.safetyDays());
      out.setOrderMultiple(p.orderMultiple());
      out.setMinOrderQty(p.minOrderQty());
      out.setPolicySource(p.source());
    }
    return out;
  }

  private static @Nullable BigDecimal dec(@Nullable Double v) {
    return v == null ? null : BigDecimal.valueOf(v);
  }

  private static SupplyRequestService.Body body(SupplyRequestBody b) {
    return new SupplyRequestService.Body(
        b.getMfcId(),
        b.getRequestedReadyAt().toInstant(),
        b.getUrgency().getValue(),
        b.getReason(),
        b.getRecommendationRef(),
        b.getLines().stream()
            .map(l -> new SupplyRequestService.Line(l.getSku(), l.getQty()))
            .toList());
  }

  private static SupplyRequest dto(Row r) {
    var out = new SupplyRequest();
    out.setSupplyRequestId(r.supplyRequestId());
    out.setMfcId(r.mfcId());
    out.setVersion(r.version());
    out.setState(SupplyRequestState.fromValue(r.state()));
    out.setRequestedReadyAt(odt(r.requestedReadyAt()));
    out.setUrgency(SupplyRequest.UrgencyEnum.fromValue(r.urgency()));
    out.setReason(r.reason());
    out.setRecommendationRef(r.recommendationRef());
    out.setLines(
        r.lines().stream()
            .map(
                l -> {
                  var x = new SupplyRequestBodyLinesInner();
                  x.setSku(l.sku());
                  x.setQty(l.qty());
                  return x;
                })
            .toList());
    out.setSubmittedBy(r.submittedBy());
    out.setSubmittedAt(odt(r.submittedAt()));
    out.setAcknowledgedAt(r.acknowledgedAt() == null ? null : odt(r.acknowledgedAt()));
    out.setLinkedTrfId(r.linkedTrfId());
    out.setApprovedLines(
        r.approvedLines().stream()
            .map(
                l -> {
                  var x = new SupplyRequestAllOfApprovedLines();
                  x.setSku(l.sku());
                  x.setRequestedQty(l.requestedQty());
                  x.setApprovedQty(l.approvedQty());
                  return x;
                })
            .toList());
    out.setDecidedBy(r.decidedBy());
    out.setDecidedAt(r.decidedAt() == null ? null : odt(r.decidedAt()));
    out.setDecisionNote(r.decisionNote());
    out.setRejectReason(r.rejectReason());
    out.setLinkedReceiptId(r.linkedReceiptId());
    out.setTransferCdcId(r.transferCdcId());
    out.setTransferDueAt(r.transferDueAt() == null ? null : odt(r.transferDueAt()));
    out.setTransferSentAt(r.transferSentAt() == null ? null : odt(r.transferSentAt()));
    out.setTransferError(r.transferError());
    out.setTransfers(
        r.transfers().stream()
            .map(
                t -> {
                  var x = new SupplyRequestAllOfTransfers();
                  x.setTrfId(t.trfId());
                  x.setTemperatureZone(
                      SupplyRequestAllOfTransfers.TemperatureZoneEnum.fromValue(
                          t.temperatureZone()));
                  x.setCdcId(t.cdcId());
                  x.setDueAt(odt(t.dueAt()));
                  x.setSentAt(t.sentAt() == null ? null : odt(t.sentAt()));
                  x.setError(t.error());
                  x.setTrfNumber(t.trfNumber());
                  x.setWaveId(t.waveId());
                  x.setWaveNumber(t.waveNumber());
                  x.setLines(
                      t.lines().entrySet().stream()
                          .map(
                              e -> {
                                var l =
                                    new com.pmart.mfc.inbound.api.generated.model
                                        .SupplyRequestAllOfLines();
                                l.setSku(e.getKey());
                                l.setQty(e.getValue());
                                return l;
                              })
                          .toList());
                  return x;
                })
            .toList());
    return out;
  }

  private static OffsetDateTime odt(Instant i) {
    return i.atOffset(ZoneOffset.UTC);
  }
}
