package com.pmart.mfc.inbound.api;

import com.pmart.common.web.CommandResponse;
import com.pmart.mfc.inbound.api.generated.InboundPromisesApi;
import com.pmart.mfc.inbound.api.generated.model.CloseInboundPromiseRequest;
import com.pmart.mfc.inbound.api.generated.model.InboundPromise;
import com.pmart.mfc.inbound.api.generated.model.InboundPromiseClosure;
import com.pmart.mfc.inbound.api.generated.model.InboundPromiseLinesInner;
import com.pmart.mfc.inbound.api.generated.model.InboundPromisePackagesInner;
import com.pmart.mfc.inbound.api.generated.model.InboundPromiseSourcesInner;
import com.pmart.mfc.inbound.api.generated.model.InboundPromiseState;
import com.pmart.mfc.inbound.api.generated.model.ListInboundPromises200Response;
import com.pmart.mfc.inbound.api.generated.model.ListInboundPromises200ResponseAsOf;
import com.pmart.mfc.inbound.api.generated.model.ListInboundPromises200ResponsePage;
import com.pmart.mfc.inbound.api.generated.model.Receipt;
import com.pmart.mfc.inbound.api.generated.model.ReceiptDiscrepanciesInner;
import com.pmart.mfc.inbound.api.generated.model.RecordArrival202Response;
import com.pmart.mfc.inbound.api.generated.model.RecordArrivalRequest;
import com.pmart.mfc.inbound.api.generated.model.RecordReceiptRequest;
import com.pmart.mfc.inbound.application.InboundPromises;
import com.pmart.mfc.inbound.application.SkuLabels;
import com.pmart.mfc.inbound.application.TransitClosures;
import com.pmart.mfc.inbound.domain.InboundPromiseRules.ReceiptLine;
import com.pmart.mfc.inbound.infrastructure.InboundPromiseStore.Promise;
import com.pmart.mfc.inbound.infrastructure.InboundPromiseStore.ReceiptRow;
import com.pmart.mfc.inbound.infrastructure.TransitClosureStore;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 InboundPromisesApi(0.16.0) — 입고 예정 조회·도착·수령. 업무 규칙은 {@link InboundPromises}, 여기서는 변환만 한다. 줄
 * 상품명·바코드·온도대는 표시용 SKU 사본(조회 실패면 빈 값).
 */
@RestController
public class InboundPromisesController implements InboundPromisesApi {

  private final InboundPromises service;
  private final SkuLabels labels;
  private final TransitClosures closures;

  public InboundPromisesController(
      InboundPromises service, SkuLabels labels, TransitClosures closures) {
    this.service = service;
    this.labels = labels;
    this.closures = closures;
  }

  @Override
  public ResponseEntity<ListInboundPromises200Response> listInboundPromises(
      String mfcId,
      @Nullable LocalDate date,
      @Nullable InboundPromiseState state,
      Integer size,
      @Nullable String cursor) {
    var page =
        service.list(
            mfcId, state == null ? null : state.getValue(), date, size == null ? 50 : size, cursor);
    var names =
        labels.of(
            page.items().stream().flatMap(v -> v.lines().stream().map(l -> l.sku())).toList());
    var closed = closures.closures(page.items().stream().map(v -> v.promise().trfId).toList());
    var out = new ListInboundPromises200Response();
    out.setItems(
        page.items().stream()
            .map(v -> withClosure(dto(v, names), closed.get(v.promise().trfId)))
            .toList());
    var p = new ListInboundPromises200ResponsePage();
    p.setNextCursor(page.nextCursor());
    p.setSize(page.items().size());
    out.setPage(p);
    var asOf = new ListInboundPromises200ResponseAsOf();
    asOf.setAsOf(OffsetDateTime.now(ZoneOffset.UTC));
    asOf.setFreshness(ListInboundPromises200ResponseAsOf.FreshnessEnum.CURRENT);
    out.setAsOf(asOf);
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<InboundPromise> getInboundPromise(String trfId) {
    var v = service.get(trfId);
    return ResponseEntity.ok(
        withClosure(
            dto(v, labels.of(v.lines().stream().map(l -> l.sku()).toList())),
            closures.closure(trfId).orElse(null)));
  }

  @Override
  public ResponseEntity<RecordArrival202Response> closeInboundPromise(
      String trfId,
      String idempotencyKey,
      String xSourceSystem,
      CloseInboundPromiseRequest b,
      @Nullable Integer xExpectedVersion) {
    return accepted(
        closures.close(
            idempotencyKey,
            xSourceSystem,
            trfId,
            xExpectedVersion,
            new TransitClosures.CloseCommand(b.getReason())));
  }

  /** (0.22.0, MI-5) 입고 종결. */
  private static InboundPromise withClosure(
      InboundPromise out, TransitClosureStore.@Nullable Closure c) {
    if (c == null) {
      return out;
    }
    var d = new InboundPromiseClosure();
    d.setKind(InboundPromiseClosure.KindEnum.fromValue(c.kind()));
    d.setClosedAt(c.closedAt().atOffset(ZoneOffset.UTC));
    d.setClosedBy(c.closedBy());
    d.setReason(c.reason());
    d.setCdcNotifiedAt(c.notifiedAt() == null ? null : c.notifiedAt().atOffset(ZoneOffset.UTC));
    d.setCdcUnreconciledQty(c.unreconciledQty());
    d.setLastError(c.lastError());
    d.setCdcNotifyRequired(c.departureId() != null);
    d.setCdcCancelRequestId(c.cdcCancelRequestId());
    out.setClosure(d);
    return out;
  }

  @Override
  public ResponseEntity<Receipt> getReceipt(String trfId, String receiptId) {
    return ResponseEntity.ok(receipt(service.receipt(trfId, receiptId)));
  }

  @Override
  public ResponseEntity<RecordArrival202Response> recordArrival(
      String trfId,
      String idempotencyKey,
      String xSourceSystem,
      RecordArrivalRequest b,
      @Nullable Integer xExpectedVersion) {
    return accepted(
        service.recordArrival(
            idempotencyKey,
            xSourceSystem,
            trfId,
            xExpectedVersion,
            new InboundPromises.ArrivalCommand(
                b.getArrivedAt().toInstant(),
                b.getTripId(),
                b.getUnloadingBayReservationId(),
                b.getEvidenceRef())));
  }

  @Override
  public ResponseEntity<RecordArrival202Response> recordReceipt(
      String trfId,
      String idempotencyKey,
      String xSourceSystem,
      RecordReceiptRequest b,
      @Nullable Integer xExpectedVersion) {
    List<ReceiptLine> lines =
        b.getLines().stream()
            .map(
                l ->
                    new ReceiptLine(
                        l.getSku(),
                        blankToNull(l.getLotId()),
                        l.getExpiryDate() == null ? null : l.getExpiryDate().toString(),
                        l.getNormalQty(),
                        l.getQuarantinedQty(),
                        l.getPendingQty(),
                        blankToNull(l.getToLocationCode()),
                        l.getDiscrepancyReason() == null
                            ? null
                            : l.getDiscrepancyReason().getValue()))
            .toList();
    return accepted(
        service.recordReceipt(
            idempotencyKey,
            xSourceSystem,
            trfId,
            xExpectedVersion,
            new InboundPromises.ReceiptCommand(
                b.getReceivedAt() == null ? null : b.getReceivedAt().toInstant(),
                b.getEvidenceRef(),
                lines)));
  }

  private static ResponseEntity<RecordArrival202Response> accepted(CommandResponse c) {
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

  private InboundPromise dto(InboundPromises.View v, Map<String, SkuLabels.Label> names) {
    Promise x = v.promise();
    var out = new InboundPromise();
    out.setTrfId(x.trfId);
    out.setDepartureId(x.departureId);
    out.setMfcId(x.mfcId);
    out.setFromCdc(x.fromCdc);
    out.setTrfVersion(x.trfVersion == null ? null : Math.toIntExact(x.trfVersion));
    out.setTrfNumber(x.trfNumber);
    out.setWaveId(x.waveId);
    out.setWaveNumber(x.waveNumber);
    out.setVersion(x.version);
    out.setState(InboundPromiseState.fromValue(x.state));
    out.setPlannedArrivalAt(odt(x.dueAt));
    out.setDeadlineMeaning(x.deadlineMeaning);
    out.setDepartedAt(odt(x.departedAt));
    out.setShipmentManifestId(x.manifestId);
    out.setCancelReason(x.cancelReason);
    out.setEta(null);
    out.setTripId(x.arrivalTripId != null ? x.arrivalTripId : x.tripId);
    out.setArrivedAt(odt(x.arrivedAt));
    out.setLines(
        v.lines().stream()
            .map(
                l -> {
                  var i = new InboundPromiseLinesInner();
                  var label = SkuLabels.get(names, l.sku());
                  i.setSku(l.sku());
                  i.setProductName(label.productName());
                  i.setBarcodes(label.barcodes());
                  i.setTemperatureZone(label.storageType());
                  i.setLotId(l.lot());
                  i.setExpiryDate(l.expiryDate() == null ? null : LocalDate.parse(l.expiryDate()));
                  i.setExpectedQty(l.expectedQty());
                  i.setReceivedQty(l.receivedQty());
                  i.setQuarantinedQty(l.quarantinedQty());
                  i.setPendingQty(l.pendingQty());
                  return i;
                })
            .toList());
    out.setPackages(
        v.packages().stream()
            .map(
                s -> {
                  var i = new InboundPromisePackagesInner();
                  i.setLpn(s.lpn());
                  i.setParentLpn(s.parentLpn());
                  i.setSku(s.sku());
                  i.setLotId(s.lot());
                  i.setQty(s.qty());
                  i.setExpiryDate(s.expiryDate() == null ? null : LocalDate.parse(s.expiryDate()));
                  return i;
                })
            .toList());
    out.setReceipts(v.receipts().stream().map(InboundPromisesController::receipt).toList());
    out.setSources(
        x.sources.entrySet().stream()
            .map(
                e -> {
                  var s = new InboundPromiseSourcesInner();
                  s.setSystem(e.getKey());
                  var a = new ListInboundPromises200ResponseAsOf();
                  a.setAsOf(odt(Instant.parse(e.getValue())));
                  s.setAsOf(a);
                  return s;
                })
            .toList());
    return out;
  }

  private static Receipt receipt(ReceiptRow r) {
    var out = new Receipt();
    out.setReceiptId(r.receiptId());
    out.setTrfId(r.trfId());
    out.setDepartureId(r.departureId());
    out.setMfcId(r.mfcId());
    out.setInventoryApplyStatus(Receipt.InventoryApplyStatusEnum.fromValue(r.state()));
    out.setInventoryCommitId(r.commitId());
    out.setRejectReason(r.rejectReason());
    out.setDiscrepancies(
        r.discrepancies().stream()
            .map(
                d -> {
                  var i = new ReceiptDiscrepanciesInner();
                  i.setSku(d.sku());
                  i.setExpectedQty(d.expectedQty());
                  i.setReceivedQty(d.receivedQty());
                  i.setReason(d.reason());
                  return i;
                })
            .toList());
    out.setReceivedAt(odt(r.receivedAt()));
    out.setPostedAt(odt(r.postedAt()));
    return out;
  }

  private static @Nullable OffsetDateTime odt(@Nullable Instant i) {
    return i == null ? null : OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
  }

  private static @Nullable String blankToNull(@Nullable String s) {
    return s == null || s.isBlank() ? null : s;
  }
}
