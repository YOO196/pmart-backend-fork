package com.pmart.mfc.inventory.api;

import com.pmart.mfc.inventory.api.generated.SkuHandlingsApi;
import com.pmart.mfc.inventory.api.generated.model.GetSnapshot200ResponsePage;
import com.pmart.mfc.inventory.api.generated.model.RepublishSkuHandlings200Response;
import com.pmart.mfc.inventory.api.generated.model.SkuHandling;
import com.pmart.mfc.inventory.api.generated.model.SkuHandlingBatchRequest;
import com.pmart.mfc.inventory.api.generated.model.SkuHandlingBatchResult;
import com.pmart.mfc.inventory.api.generated.model.SkuHandlingChange;
import com.pmart.mfc.inventory.api.generated.model.SkuHandlingPage;
import com.pmart.mfc.inventory.api.generated.model.SkuHandlingResult;
import com.pmart.mfc.inventory.application.Operator;
import com.pmart.mfc.inventory.application.SkuHandlingService;
import com.pmart.mfc.inventory.application.SkuHandlingService.BatchItem;
import com.pmart.mfc.inventory.application.SkuHandlingService.HandlingView;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 SkuHandlingsApi — MFC 운영자의 SKU 취급 ON/OFF. 업무 규칙은 application/domain 에 있고 여기서는 변환만 한다. */
@RestController
public class SkuHandlingsController implements SkuHandlingsApi {

  private final SkuHandlingService service;

  public SkuHandlingsController(SkuHandlingService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<RepublishSkuHandlings200Response> republishSkuHandlings(
      String idempotencyKey, String xSourceSystem, @Nullable String mfcId) {
    // 상태를 바꾸지 않고 지금 상태를 다시 알릴 뿐이라 같은 키 재요청도 다시 발행한다(구독자가 같은 버전을 무시 — 계약 설명)
    return ResponseEntity.ok(
        new RepublishSkuHandlings200Response()
            .publishedCount(service.republish(mfcId == null || mfcId.isBlank() ? null : mfcId)));
  }

  @Override
  public ResponseEntity<SkuHandlingPage> listSkuHandlings(
      String mfcId,
      @Nullable Boolean enabled,
      @Nullable Boolean undecided,
      @Nullable OffsetDateTime introducedSince,
      @Nullable String cursor,
      Integer size) {
    var page =
        service.list(
            mfcId,
            enabled,
            undecided,
            introducedSince == null ? null : introducedSince.toInstant(),
            cursor,
            size == null ? 50 : size);
    var out = new SkuHandlingPage();
    out.setCenterId(mfcId);
    out.setItems(page.items().stream().map(SkuHandlingsController::dto).toList());
    var p = new GetSnapshot200ResponsePage();
    p.setNextCursor(page.nextCursor());
    p.setSize(page.items().size());
    out.setPage(p);
    out.setTotalCount(page.totalCount());
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<SkuHandlingResult> changeSkuHandling(
      String mfcId,
      String idempotencyKey,
      String xSourceSystem,
      SkuHandlingChange body,
      @Nullable Integer xExpectedVersion) {
    var r =
        service
            .change(
                idempotencyKey,
                Operator.forCenterRole(mfcId, xSourceSystem, "MFC_SUPERVISOR", "MD"),
                mfcId,
                body.getSku(),
                Boolean.TRUE.equals(body.getEnabled()),
                body.getReason(),
                xExpectedVersion)
            .value();
    var out = new SkuHandlingResult();
    out.setStatus(SkuHandlingResult.StatusEnum.APPLIED);
    out.setRequestId(r.requestId());
    out.setChanged(r.changed());
    out.setOccurredAt(time(r.occurredAt()));
    out.setHandling(dto(r.handling()));
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<SkuHandlingBatchResult> changeSkuHandlingBatch(
      String mfcId, String idempotencyKey, String xSourceSystem, SkuHandlingBatchRequest body) {
    List<BatchItem> items =
        body.getItems().stream()
            .map(
                i ->
                    new BatchItem(
                        i.getSku(),
                        Boolean.TRUE.equals(i.getEnabled()),
                        i.getReason(),
                        i.getExpectedVersion()))
            .toList();
    var r =
        service
            .changeBatch(
                idempotencyKey,
                Operator.forCenterRole(mfcId, xSourceSystem, "MFC_SUPERVISOR", "MD"),
                mfcId,
                body.getReason(),
                items)
            .value();
    var out = new SkuHandlingBatchResult();
    out.setStatus(SkuHandlingBatchResult.StatusEnum.APPLIED);
    out.setRequestId(r.requestId());
    out.setChangedCount(r.changedCount());
    out.setOccurredAt(time(r.occurredAt()));
    out.setItems(r.items().stream().map(SkuHandlingsController::dto).toList());
    return ResponseEntity.ok(out);
  }

  static SkuHandling dto(HandlingView v) {
    var d = new SkuHandling();
    d.setSku(v.sku());
    d.setHandlingEnabled(v.enabled());
    d.setVersion(v.version());
    d.setSkuState(v.skuState() == null ? null : SkuHandling.SkuStateEnum.fromValue(v.skuState()));
    d.setProductId(v.productId());
    d.setChangedBy(v.changedBy());
    d.setReason(v.reason());
    d.setUpdatedAt(v.updatedAt() == null ? null : time(v.updatedAt()));
    d.setSaleState(
        v.saleState() == null ? null : SkuHandling.SaleStateEnum.fromValue(v.saleState()));
    d.setOffReason(
        v.offReason() == null ? null : SkuHandling.OffReasonEnum.fromValue(v.offReason()));
    d.setIntroducedAt(v.introducedAt() == null ? null : time(v.introducedAt()));
    return d;
  }

  static OffsetDateTime time(Instant i) {
    return i.atOffset(ZoneOffset.UTC);
  }
}
