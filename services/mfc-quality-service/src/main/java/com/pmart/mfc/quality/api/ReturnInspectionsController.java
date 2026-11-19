package com.pmart.mfc.quality.api;

import com.pmart.mfc.quality.api.generated.ReturnInspectionsApi;
import com.pmart.mfc.quality.api.generated.model.CreateQualityDecision202Response;
import com.pmart.mfc.quality.api.generated.model.InventoryApplyStatus;
import com.pmart.mfc.quality.api.generated.model.ReturnInspection;
import com.pmart.mfc.quality.api.generated.model.ReturnInspectionRequest;
import com.pmart.mfc.quality.api.generated.model.ReturnInspectionRequestLinesInner;
import com.pmart.mfc.quality.application.ReturnInspectionService;
import java.time.ZoneOffset;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 ReturnInspectionsApi — 회수품 검수(IV-10). 업무 규칙은 {@link ReturnInspectionService}. */
@RestController
public class ReturnInspectionsController implements ReturnInspectionsApi {

  private final ReturnInspectionService service;

  private final com.pmart.mfc.quality.application.QualityLists lists;

  public ReturnInspectionsController(
      ReturnInspectionService service, com.pmart.mfc.quality.application.QualityLists lists) {
    this.lists = lists;
    this.service = service;
  }

  @Override
  public ResponseEntity<CreateQualityDecision202Response> createReturnInspection(
      String idempotencyKey, String xSourceSystem, ReturnInspectionRequest b) {
    var c =
        service.inspect(
            idempotencyKey,
            xSourceSystem,
            new ReturnInspectionService.Command(
                b.getMfcId(),
                b.getClaimId(),
                b.getReturnReceiptId(),
                b.getInspectedBy(),
                b.getLines().stream()
                    .map(
                        l ->
                            new ReturnInspectionService.ILine(
                                l.getOrderLineId(),
                                l.getSku(),
                                l.getLotId(),
                                null,
                                l.getQty(),
                                l.getVerdict().getValue(),
                                l.getTemperatureZone().getValue(),
                                Boolean.TRUE.equals(l.getFresh()),
                                Boolean.TRUE.equals(l.getOpened()),
                                l.getDisposeReason() == null
                                    ? null
                                    : l.getDisposeReason().getValue(),
                                l.getEvidenceRef()))
                    .toList()));
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(Dtos.command(c));
  }

  /** 목록(MB-1, 0.5.0) — 항목은 단건 조회와 같은 변환. */
  @Override
  public ResponseEntity<com.pmart.mfc.quality.api.generated.model.ListReturnInspections200Response>
      listReturnInspections(
          String mfcId,
          java.util.@org.jspecify.annotations.Nullable List<String> state,
          Integer size,
          @org.jspecify.annotations.Nullable String cursor) {
    int limit = size == null ? 50 : size;
    var ids =
        lists.ids(
            com.pmart.mfc.quality.infrastructure.QualityListStore.Kind.INSPECTION,
            mfcId,
            state,
            limit + 1,
            cursor);
    boolean more = ids.size() > limit;
    var page = more ? ids.subList(0, limit) : ids;
    var out = new com.pmart.mfc.quality.api.generated.model.ListReturnInspections200Response();
    out.setItems(page.stream().map(id -> getReturnInspection(id).getBody()).toList());
    out.setNextCursor(more ? page.get(page.size() - 1) : null);
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<ReturnInspection> getReturnInspection(String returnInspectionId) {
    var r = service.view(returnInspectionId);
    var out = new ReturnInspection();
    out.setReturnInspectionId(r.returnInspectionId());
    out.setMfcId(r.mfcId());
    out.setClaimId(r.claimId());
    out.setPurpose(
        com.pmart.mfc.quality.api.generated.model.ReturnInspection.PurposeEnum.fromValue(
            r.purpose()));
    out.setCaseId(r.caseId());
    out.setOriginDeliveryId(r.originDeliveryId());
    out.setReturnReceiptId(r.returnReceiptId());
    out.setInspectedBy(r.inspectedBy());
    out.setVersion(r.version());
    out.setResaleQty(r.resaleQty());
    out.setDisposeQty(r.disposeQty());
    out.setInventoryApplyStatus(InventoryApplyStatus.fromValue(r.applyStatus()));
    out.setInventoryCommitId(r.inventoryCommitId());
    out.setDisposalApprovalId(r.disposalApprovalId());
    out.setInspectedAt(r.inspectedAt().atOffset(ZoneOffset.UTC));
    out.setLines(
        r.lines().stream()
            .map(
                l -> {
                  var i = new ReturnInspectionRequestLinesInner();
                  i.setOrderLineId(l.orderLineId());
                  i.setSku(l.sku());
                  i.setLotId(l.lotId());
                  i.setQty(l.qty());
                  i.setVerdict(
                      ReturnInspectionRequestLinesInner.VerdictEnum.fromValue(l.verdict()));
                  i.setTemperatureZone(
                      ReturnInspectionRequestLinesInner.TemperatureZoneEnum.fromValue(
                          l.temperatureZone()));
                  i.setFresh(l.fresh());
                  i.setOpened(l.opened());
                  if (l.disposeReason() != null) {
                    i.setDisposeReason(
                        ReturnInspectionRequestLinesInner.DisposeReasonEnum.fromValue(
                            l.disposeReason()));
                  }
                  i.setEvidenceRef(l.evidenceRef());
                  return i;
                })
            .toList());
    return ResponseEntity.ok(out);
  }
}
