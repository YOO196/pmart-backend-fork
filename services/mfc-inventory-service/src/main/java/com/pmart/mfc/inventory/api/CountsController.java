package com.pmart.mfc.inventory.api;

import com.pmart.mfc.inventory.api.generated.CountsApi;
import com.pmart.mfc.inventory.api.generated.model.CancelCountSessionRequest;
import com.pmart.mfc.inventory.api.generated.model.CountPlan;
import com.pmart.mfc.inventory.api.generated.model.CountPlanSessionsInner;
import com.pmart.mfc.inventory.api.generated.model.CountSession;
import com.pmart.mfc.inventory.api.generated.model.CountSessionDifferencesInner;
import com.pmart.mfc.inventory.api.generated.model.CountSessionExceptionsInner;
import com.pmart.mfc.inventory.api.generated.model.CountSessionSheetInner;
import com.pmart.mfc.inventory.api.generated.model.CountSessionUnresolvedLinesInner;
import com.pmart.mfc.inventory.api.generated.model.CreateCountPlanRequest;
import com.pmart.mfc.inventory.api.generated.model.CreateCountSessionRequest;
import com.pmart.mfc.inventory.api.generated.model.DecideCountSessionRequest;
import com.pmart.mfc.inventory.api.generated.model.ListCountWork200Response;
import com.pmart.mfc.inventory.api.generated.model.ListCountWork200ResponseItemsInner;
import com.pmart.mfc.inventory.api.generated.model.ReportCountExceptionRequest;
import com.pmart.mfc.inventory.api.generated.model.ResumeCountSessionRequest;
import com.pmart.mfc.inventory.api.generated.model.SubmitCountRequest;
import com.pmart.mfc.inventory.application.CountService;
import com.pmart.mfc.inventory.application.SkuProjector;
import com.pmart.mfc.inventory.domain.CountRules;
import com.pmart.mfc.inventory.domain.CountRules.CountLine;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 CountsApi — 순환 실사(IV-11, 0.10.0). 업무 규칙은 {@link CountService}·{@code CountRules}. */
@RestController
public class CountsController implements CountsApi {

  private final CountService service;
  private final SkuProjector skus;

  public CountsController(CountService service, SkuProjector skus) {
    this.service = service;
    this.skus = skus;
  }

  @Override
  public ResponseEntity<CountSession> createCountSession(
      String mfcId, String idempotencyKey, String xSourceSystem, CreateCountSessionRequest body) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            dto(
                service.create(
                    mfcId,
                    idempotencyKey,
                    xSourceSystem,
                    body.getLocationCodes(),
                    body.getReason())));
  }

  /** 실사 목록(MB-1, 0.26.0) — 항목은 단건과 같은 변환. */
  @Override
  public ResponseEntity<com.pmart.mfc.inventory.api.generated.model.ListCountSessions200Response>
      listCountSessions(
          String mfcId,
          java.util.@org.jspecify.annotations.Nullable List<String> state,
          Integer size,
          @org.jspecify.annotations.Nullable String cursor) {
    int limit = size == null ? 50 : size;
    var ids = service.sessionIds(mfcId, state, limit + 1, cursor);
    boolean more = ids.size() > limit;
    var page = more ? ids.subList(0, limit) : ids;
    var out = new com.pmart.mfc.inventory.api.generated.model.ListCountSessions200Response();
    out.setItems(page.stream().map(id -> dto(service.view(mfcId, id))).toList());
    out.setNextCursor(more ? page.get(page.size() - 1) : null);
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<CountSession> getCountSession(String mfcId, String sessionId) {
    return ResponseEntity.ok(dto(service.view(mfcId, sessionId)));
  }

  @Override
  public ResponseEntity<CountSession> submitCount(
      String mfcId,
      String sessionId,
      String idempotencyKey,
      String xSourceSystem,
      SubmitCountRequest body) {
    var lines =
        body.getLines().stream()
            .map(
                l ->
                    new CountLine(l.getLocationCode(), l.getSku(), l.getLotId(), l.getCountedQty()))
            .toList();
    // (0.34.0, BR-52 R19-6) 계수표 밖에서 찾은 상품
    var unexpected =
        body.getUnexpectedLines() == null
            ? java.util.List.<CountRules.Unexpected>of()
            : body.getUnexpectedLines().stream()
                .map(
                    u ->
                        new CountRules.Unexpected(
                            u.getLocationCode(),
                            u.getSku(),
                            u.getBarcode(),
                            u.getLotId(),
                            u.getExpiryDate(),
                            u.getQty()))
                .toList();
    return ResponseEntity.ok(dto(service.count(mfcId, sessionId, lines, unexpected)));
  }

  // ---------- 0.34.0 실사 계획·취소·예외(BR-52 R14·R19-7) ----------

  @Override
  public ResponseEntity<CountPlan> createCountPlan(
      String mfcId, String idempotencyKey, String xSourceSystem, CreateCountPlanRequest body) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            plan(
                service.createPlan(
                    mfcId,
                    idempotencyKey,
                    xSourceSystem,
                    body.getLocationCodes(),
                    body.getReason())));
  }

  @Override
  public ResponseEntity<CountPlan> getCountPlan(String mfcId, String planId) {
    return ResponseEntity.ok(plan(service.planView(mfcId, planId)));
  }

  @Override
  public ResponseEntity<CountPlan> cancelCountPlan(
      String mfcId,
      String planId,
      String idempotencyKey,
      String xSourceSystem,
      @Nullable CancelCountSessionRequest body) {
    return ResponseEntity.ok(
        plan(service.cancelPlan(mfcId, planId, body == null ? null : body.getReason())));
  }

  @Override
  public ResponseEntity<CountSession> cancelCountSession(
      String mfcId,
      String sessionId,
      String idempotencyKey,
      String xSourceSystem,
      @Nullable CancelCountSessionRequest body) {
    return ResponseEntity.ok(
        dto(service.cancel(mfcId, sessionId, body == null ? null : body.getReason())));
  }

  @Override
  public ResponseEntity<CountSession> reportCountException(
      String mfcId,
      String sessionId,
      String idempotencyKey,
      String xSourceSystem,
      ReportCountExceptionRequest body) {
    return ResponseEntity.ok(
        dto(
            service.reportException(
                mfcId,
                sessionId,
                idempotencyKey,
                xSourceSystem,
                new CountService.CountException(
                    "",
                    body.getReason().getValue(),
                    body.getLocationCode(),
                    body.getSku(),
                    body.getLotId(),
                    body.getQty(),
                    body.getNote(),
                    "",
                    ""))));
  }

  /** 예외로 멈춘 실사 다시 시작(0.35.0, BR-52 R19-7 후속). */
  @Override
  public ResponseEntity<CountSession> resumeCountSession(
      String mfcId,
      String sessionId,
      String idempotencyKey,
      String xSourceSystem,
      @Nullable ResumeCountSessionRequest body) {
    return ResponseEntity.ok(
        dto(service.resume(mfcId, sessionId, body == null ? null : body.getNote())));
  }

  @Override
  public ResponseEntity<ListCountWork200Response> listCountWork(String mfcId) {
    var out = new ListCountWork200Response();
    out.setItems(
        service.countWork(mfcId).stream()
            .map(
                w -> {
                  var x = new ListCountWork200ResponseItemsInner();
                  x.setSessionId(w.sessionId());
                  x.setPlanId(w.planId());
                  x.setState(ListCountWork200ResponseItemsInner.StateEnum.fromValue(w.state()));
                  x.setLocationCodes(new java.util.ArrayList<>(w.locations()));
                  x.setExcludedOperatorIds(new java.util.ArrayList<>(w.excluded()));
                  x.setBaselineAt(SkuHandlingsController.time(w.baselineAt()));
                  return x;
                })
            .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new)));
    return ResponseEntity.ok(out);
  }

  private static CountPlan plan(CountService.PlanView v) {
    var p = v.plan();
    var out = new CountPlan();
    out.setPlanId(p.planId());
    out.setMfcId(p.mfcId());
    out.setState(CountPlan.StateEnum.fromValue(v.state()));
    out.setReason(p.reason());
    out.setCreatedBy(p.createdBy());
    out.setCreatedAt(SkuHandlingsController.time(p.createdAt()));
    out.setCancelledBy(p.cancelledBy());
    out.setCancelledAt(
        p.cancelledAt() == null ? null : SkuHandlingsController.time(p.cancelledAt()));
    out.setLockedLocationCodes(
        v.sessions().stream()
            .filter(
                s ->
                    s.state().equals("COUNTING")
                        || s.state().equals("RECOUNT_REQUIRED")
                        || s.state().equals("PENDING_APPROVAL"))
            .map(CountService.PlanSessionView::locationCode)
            .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new)));
    out.setSessions(
        v.sessions().stream()
            .map(
                s -> {
                  var x = new CountPlanSessionsInner();
                  x.setSessionId(s.sessionId());
                  x.setLocationCode(s.locationCode());
                  x.setState(CountPlanSessionsInner.StateEnum.fromValue(s.state()));
                  x.setExceptionCount(s.exceptionCount());
                  x.setOpenExceptionCount(s.openExceptionCount());
                  return x;
                })
            .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new)));
    return out;
  }

  @Override
  public ResponseEntity<CountSession> decideCountSession(
      String mfcId,
      String sessionId,
      String idempotencyKey,
      String xSourceSystem,
      DecideCountSessionRequest body) {
    return ResponseEntity.ok(
        dto(
            service.decide(
                mfcId,
                sessionId,
                body.getAction().getValue(),
                body.getApprovalRef(),
                body.getNote())));
  }

  private CountSession dto(CountService.View v) {
    var s = v.row();
    // (0.16.0, BR-52 R19-1) 계수표 줄의 상품명·바코드 — 이 서비스의 SKU 사본(상품 사건 투영). 사본이 없으면 null·빈 배열
    var names = skus.projections(v.book().stream().map(b -> b.sku()).distinct().toList());
    var out = new CountSession();
    out.setSessionId(s.sessionId());
    out.setMfcId(s.mfcId());
    out.setState(CountSession.StateEnum.fromValue(s.state()));
    out.setLocationCodes(v.locations());
    out.setReason(s.reason());
    out.setCreatedBy(s.createdBy());
    out.setFirstCounter(s.firstCounter());
    out.setRecounter(s.recounter());
    out.setApproverId(s.approverId());
    out.setApprovalRef(s.approvalRef());
    out.setBaselineAt(SkuHandlingsController.time(s.baselineAt()));
    out.setSheet(
        v.book().stream()
            .map(
                b -> {
                  var x = new CountSessionSheetInner();
                  x.setLocationCode(b.locationCode());
                  x.setSku(b.sku());
                  var p = names.get(b.sku());
                  x.setProductName(p == null ? null : p.productName());
                  x.setBarcodes(
                      p == null
                          ? new java.util.ArrayList<>()
                          : new java.util.ArrayList<>(p.barcodes()));
                  x.setLotId(b.lotId());
                  var lot = v.lots().get(b.lotId());
                  x.setLotNo(lot == null ? null : lot.lotNo());
                  x.setExpiryDate(lot == null ? null : lot.expiryDate());
                  return x;
                })
            .toList());
    out.setDifferences(
        v.differences() == null
            ? null
            : v.differences().stream()
                .map(
                    d -> {
                      var x = new CountSessionDifferencesInner();
                      x.setLocationCode(d.locationCode());
                      x.setSku(d.sku());
                      x.setLotId(d.lotId());
                      x.setBookQty(d.bookQty());
                      x.setHeldQty(d.heldQty());
                      x.setCountedQty(d.countedQty());
                      x.setDeltaQty(d.delta());
                      return x;
                    })
                .toList());
    out.setPlanId(s.planId());
    out.setCancelReason(s.cancelReason());
    out.setUnresolvedLines(
        v.unresolved() == null
            ? null
            : v.unresolved().stream()
                .map(
                    u -> {
                      var x = new CountSessionUnresolvedLinesInner();
                      x.setLocationCode(u.locationCode());
                      x.setSku(u.sku());
                      x.setBarcode(u.barcode());
                      x.setExpiryDate(u.expiryDate());
                      x.setQty(u.qty());
                      x.setCause(CountSessionUnresolvedLinesInner.CauseEnum.fromValue(u.cause()));
                      return x;
                    })
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new)));
    out.setExceptions(
        v.exceptions().stream()
            .map(
                e -> {
                  var x = new CountSessionExceptionsInner();
                  x.setExceptionId(e.exceptionId());
                  x.setReason(CountSessionExceptionsInner.ReasonEnum.fromValue(e.reason()));
                  x.setLocationCode(e.locationCode());
                  x.setSku(e.sku());
                  x.setLotId(e.lotId());
                  x.setQty(e.qty());
                  x.setNote(e.note());
                  x.setReportedBy(e.reportedBy());
                  x.setReportedAt(
                      SkuHandlingsController.time(java.time.Instant.parse(e.reportedAt())));
                  x.setResolvedBy(e.resolvedBy());
                  x.setResolvedAt(
                      e.resolvedAt() == null
                          ? null
                          : SkuHandlingsController.time(java.time.Instant.parse(e.resolvedAt())));
                  x.setResolutionNote(e.resolutionNote());
                  return x;
                })
            .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new)));
    out.setCommitId(s.commitId());
    out.setVersion(s.version());
    out.setUpdatedAt(SkuHandlingsController.time(s.updatedAt()));
    return out;
  }
}
