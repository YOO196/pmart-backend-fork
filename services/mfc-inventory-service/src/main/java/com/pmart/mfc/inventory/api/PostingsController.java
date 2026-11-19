package com.pmart.mfc.inventory.api;

import com.pmart.mfc.inventory.api.generated.PostingsApi;
import com.pmart.mfc.inventory.api.generated.model.CommitTaskExecutionRequest;
import com.pmart.mfc.inventory.api.generated.model.ConfirmReceiptRequest;
import com.pmart.mfc.inventory.api.generated.model.ReceiptResult;
import com.pmart.mfc.inventory.api.generated.model.ReceiptResultLinesInner;
import com.pmart.mfc.inventory.api.generated.model.ReserveInventory201Response;
import com.pmart.mfc.inventory.api.generated.model.StockCorrectionRequest;
import com.pmart.mfc.inventory.application.CorrectionService;
import com.pmart.mfc.inventory.application.CorrectionService.CorrectionCommand;
import com.pmart.mfc.inventory.application.CorrectionService.CorrectionLine;
import com.pmart.mfc.inventory.application.ReceiptService;
import com.pmart.mfc.inventory.application.ReceiptService.ReceiptCommand;
import com.pmart.mfc.inventory.application.TaskExecutionService;
import com.pmart.mfc.inventory.application.TaskExecutionService.ExecCommand;
import com.pmart.mfc.inventory.application.TaskExecutionService.ExecLine;
import com.pmart.mfc.inventory.domain.StockRules.ReceiptLine;
import com.pmart.mfc.inventory.domain.StockUnit.Quality;
import com.pmart.mfc.inventory.domain.StockUnit.Work;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 PostingsApi. 수령 전기(ConfirmReceipt)·수령 결과 조회·작업 실행(INSPECT·PUTAWAY)을 구현했다. 인계·품질 반영도 구현. 조정은
 * 인터페이스 default 의 501. 업무 규칙은 application/domain 에 두고 여기서는 요청·응답 변환만 한다.
 */
@RestController
public class PostingsController implements PostingsApi {

  private final ReceiptService receipts;
  private final TaskExecutionService executions;
  private final CorrectionService corrections;
  private final com.pmart.mfc.inventory.application.ShortageService shortages;
  private final com.pmart.mfc.inventory.application.HandoverPostingService handovers;
  private final com.pmart.mfc.inventory.application.QualityPostingService quality;
  private final com.pmart.mfc.inventory.application.StockExceptionService stockExceptions;

  @Override
  public ResponseEntity<ReserveInventory201Response> postQualityDecision(
      String idempotencyKey,
      String xSourceSystem,
      com.pmart.mfc.inventory.api.generated.model.QualityPostingRequest body,
      @Nullable Integer xExpectedVersion) {
    var r =
        quality.post(
            idempotencyKey,
            xSourceSystem,
            new com.pmart.mfc.inventory.application.QualityPostingService.Posting(
                body.getPostingType().getValue(),
                body.getMfcId(),
                body.getQualityDecisionId(),
                body.getApprovalRef(),
                body.getReason(),
                body.getLines().stream()
                    .map(
                        l ->
                            new com.pmart.mfc.inventory.application.QualityPostingService.Line(
                                l.getSku(),
                                l.getLotId(),
                                l.getLocationCode(),
                                l.getQty(),
                                l.getWorkState() == null ? null : l.getWorkState().getValue()))
                    .toList()));
    return ResponseEntity.status(HttpStatus.CREATED).body(Commands.dto(r));
  }

  public PostingsController(
      ReceiptService receipts,
      TaskExecutionService executions,
      CorrectionService corrections,
      com.pmart.mfc.inventory.application.ShortageService shortages,
      com.pmart.mfc.inventory.application.HandoverPostingService handovers,
      com.pmart.mfc.inventory.application.QualityPostingService quality,
      com.pmart.mfc.inventory.application.StockExceptionService stockExceptions) {
    this.stockExceptions = stockExceptions;
    this.quality = quality;
    this.receipts = receipts;
    this.executions = executions;
    this.corrections = corrections;
    this.shortages = shortages;
    this.handovers = handovers;
  }

  @Override
  public ResponseEntity<ReserveInventory201Response> commitPackageHandover(
      String idempotencyKey,
      String xSourceSystem,
      com.pmart.mfc.inventory.api.generated.model.CommitPackageHandoverRequest body,
      @Nullable Integer xExpectedVersion) {
    var r =
        handovers.post(
            idempotencyKey,
            xSourceSystem,
            new com.pmart.mfc.inventory.application.HandoverPostingService.Posting(
                body.getPackageId(),
                body.getHandoverId(),
                body.getHandoverAuthorizationId(),
                body.getFenceVersion(),
                body.getMfcId(),
                body.getOrderId(),
                body.getReservationId(),
                body.getShipmentId(),
                body.getDeliveryId(),
                body.getRiderId(),
                body.getLocationCode(),
                body.getLines().stream()
                    .map(
                        l ->
                            new com.pmart.mfc.inventory.application.HandoverPostingService.Line(
                                l.getOrderLineId(), l.getSku(), l.getLotId(), l.getQty()))
                    .toList()));
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            Commands.dto(
                new com.pmart.common.web.CommandResponse(
                    com.pmart.common.web.CommandStatus.APPLIED,
                    idempotencyKey,
                    r.commitId(),
                    r.qty(),
                    1,
                    false,
                    null,
                    java.time.Instant.now())));
  }

  @Override
  public ResponseEntity<com.pmart.mfc.inventory.api.generated.model.ShortageReportResult>
      reportShortage(
          String idempotencyKey,
          String xSourceSystem,
          com.pmart.mfc.inventory.api.generated.model.ShortageReportRequest body) {
    var r =
        shortages.report(
            idempotencyKey,
            xSourceSystem,
            new com.pmart.mfc.inventory.application.ShortageService.Report(
                body.getReportId(),
                body.getMfcId(),
                body.getReservationId(),
                body.getOrderLineId(),
                body.getSku(),
                body.getLotId(),
                body.getLocationCode(),
                body.getQty(),
                body.getReason().getValue(),
                body.getTaskId()));
    var out = new com.pmart.mfc.inventory.api.generated.model.ShortageReportResult();
    out.setReportId(r.reportId());
    out.setCommitId(r.commitId());
    out.setReportedQty(r.reportedQty());
    out.setQuarantinedQty(r.quarantinedQty());
    out.setHeldQty(r.heldQty());
    out.setResecuredQty(r.resecuredQty());
    out.setBlockedQty(r.blockedQty());
    var parts =
        new java.util.ArrayList<
            com.pmart.mfc.inventory.api.generated.model.ShortageReportResultResecuredPartsInner>();
    for (var m : r.resecuredParts()) {
      var pi =
          new com.pmart.mfc.inventory.api.generated.model.ShortageReportResultResecuredPartsInner();
      pi.setLotId((String) m.get("lotId"));
      pi.setQty(((Number) m.get("qty")).intValue());
      parts.add(pi);
    }
    out.setResecuredParts(parts);
    return ResponseEntity.status(HttpStatus.CREATED).body(out);
  }

  @Override
  public ResponseEntity<ReserveInventory201Response> confirmReceipt(
      String idempotencyKey,
      String xSourceSystem,
      ConfirmReceiptRequest body,
      @Nullable String traceparent) {
    var lines =
        body.getLines().stream()
            .map(
                l ->
                    new ReceiptLine(
                        l.getSku(),
                        l.getSourceLotRef(),
                        l.getExpiryDate(),
                        l.getToLocationCode(),
                        l.getNormalQty(),
                        l.getQuarantinedQty(),
                        l.getPendingQty()))
            .toList();
    var r =
        receipts.confirm(
            idempotencyKey,
            xSourceSystem,
            new ReceiptCommand(
                body.getReceiptId(),
                body.getTrfId(),
                body.getDepartureId(),
                body.getMfcId(),
                body.getReceivedAt() == null ? null : body.getReceivedAt().toInstant(),
                lines));
    return ResponseEntity.status(HttpStatus.CREATED).body(Commands.dto(r));
  }

  @Override
  public ResponseEntity<ReceiptResult> getReceipt(String receiptId) {
    var v = receipts.result(receiptId);
    var out = new ReceiptResult();
    out.setReceiptId(receiptId);
    out.setCommitId(v.commitId());
    out.setLines(
        v.lines().stream()
            .map(
                l -> {
                  var d = new ReceiptResultLinesInner();
                  d.setLineNo(l.lineNo());
                  d.setSku(l.sku());
                  d.setLotId(l.lotId());
                  d.setLocationCode(l.locationCode());
                  d.setNormalQty(l.normalQty());
                  d.setQuarantinedQty(l.quarantinedQty());
                  d.setPendingQty(l.pendingQty());
                  return d;
                })
            .toList());
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<ReserveInventory201Response> commitTaskExecution(
      String idempotencyKey,
      String xSourceSystem,
      CommitTaskExecutionRequest body,
      @Nullable Integer xExpectedVersion) {
    var lines =
        body.getLines().stream()
            .map(
                l ->
                    new ExecLine(
                        l.getSku(),
                        l.getLotId(),
                        l.getQty(),
                        l.getResultQuality() == null
                            ? null
                            : Quality.valueOf(l.getResultQuality().getValue()),
                        l.getSourceLotRef(),
                        l.getExpiryDate(),
                        l.getManufactureDate(),
                        l.getOrderLineId()))
            .toList();
    var r =
        executions.commit(
            idempotencyKey,
            xSourceSystem,
            new ExecCommand(
                TaskExecutionService.Type.valueOf(body.getExecutionType().getValue()),
                body.getMfcId(),
                body.getTaskId(),
                body.getExecutionId(),
                body.getFromLocation(),
                body.getToLocation(),
                lines,
                body.getReservationId()));
    return ResponseEntity.status(HttpStatus.CREATED).body(Commands.dto(r));
  }

  @Override
  public ResponseEntity<com.pmart.mfc.inventory.api.generated.model.StockExceptionResult>
      reportStockException(
          String idempotencyKey,
          String xSourceSystem,
          com.pmart.mfc.inventory.api.generated.model.ReportStockExceptionRequest b) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            MovesController.exceptionDto(
                stockExceptions.reportStock(
                    idempotencyKey,
                    xSourceSystem,
                    new com.pmart.mfc.inventory.application.StockExceptionService.StockReport(
                        b.getReportId(),
                        b.getMfcId(),
                        b.getSku(),
                        b.getLotId(),
                        b.getLocationCode(),
                        b.getWorkState() == null ? null : b.getWorkState().getValue(),
                        b.getReason().getValue(),
                        b.getQty(),
                        b.getSourceRef()))));
  }

  @Override
  public ResponseEntity<ReserveInventory201Response> correctStock(
      String mfcId, String idempotencyKey, String xSourceSystem, StockCorrectionRequest body) {
    var lines =
        body.getLines().stream()
            .map(
                l ->
                    new CorrectionLine(
                        l.getSku(),
                        l.getLotId(),
                        l.getFromLocation(),
                        Quality.valueOf(l.getQualityState().getValue()),
                        Work.valueOf(l.getWorkState().getValue()),
                        l.getQty(),
                        l.getToLocation(),
                        l.getToSourceLotRef(),
                        l.getToExpiryDate(),
                        l.getToManufactureDate()))
            .toList();
    var r =
        corrections.correct(
            idempotencyKey,
            xSourceSystem,
            new CorrectionCommand(mfcId, body.getReason(), body.getApprovalRef(), lines));
    return ResponseEntity.status(HttpStatus.CREATED).body(Commands.dto(r));
  }
}
