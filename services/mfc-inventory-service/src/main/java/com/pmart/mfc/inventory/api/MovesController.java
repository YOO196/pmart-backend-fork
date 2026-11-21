package com.pmart.mfc.inventory.api;

import com.pmart.mfc.inventory.api.generated.MovesApi;
import com.pmart.mfc.inventory.api.generated.model.CreateMoveAllocationRequest;
import com.pmart.mfc.inventory.api.generated.model.DropMoveRequest;
import com.pmart.mfc.inventory.api.generated.model.MoveAllocation;
import com.pmart.mfc.inventory.api.generated.model.PickUpMoveRequest;
import com.pmart.mfc.inventory.application.MoveService;
import com.pmart.mfc.inventory.infrastructure.MoveStore.MoveRow;
import java.time.ZoneOffset;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 MovesApi — 재고 위치 이동(IV-03, 0.12.0). 업무 규칙은 {@link MoveService}. */
@RestController
public class MovesController implements MovesApi {

  private final MoveService service;
  private final com.pmart.mfc.inventory.application.ReplenishmentService replenishment;
  private final com.pmart.mfc.inventory.application.StockExceptionService exceptions;

  public MovesController(
      MoveService service,
      com.pmart.mfc.inventory.application.ReplenishmentService replenishment,
      com.pmart.mfc.inventory.application.StockExceptionService exceptions) {
    this.service = service;
    this.replenishment = replenishment;
    this.exceptions = exceptions;
  }

  @Override
  public ResponseEntity<
          com.pmart.mfc.inventory.api.generated.model.ListReplenishmentPolicies200Response>
      listReplenishmentPolicies(String mfcId) {
    var out =
        new com.pmart.mfc.inventory.api.generated.model.ListReplenishmentPolicies200Response();
    out.setItems(replenishment.list(mfcId).stream().map(MovesController::policy).toList());
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<com.pmart.mfc.inventory.api.generated.model.ReplenishmentPolicy>
      putReplenishmentPolicy(
          String mfcId,
          String idempotencyKey,
          String xSourceSystem,
          com.pmart.mfc.inventory.api.generated.model.PutReplenishmentPolicyRequest b) {
    return ResponseEntity.ok(
        policy(
            replenishment.put(
                mfcId,
                b.getLocationCode(),
                b.getSku(),
                b.getMinQty(),
                b.getTargetQty(),
                b.getUnitQty())));
  }

  @Override
  public ResponseEntity<Void> deleteReplenishmentPolicy(
      String mfcId, String locationCode, String sku, String idempotencyKey, String xSourceSystem) {
    replenishment.delete(mfcId, locationCode, sku);
    return ResponseEntity.noContent().build();
  }

  @Override
  public ResponseEntity<
          com.pmart.mfc.inventory.api.generated.model.EvaluateReplenishment200Response>
      evaluateReplenishment(
          String idempotencyKey,
          String xSourceSystem,
          com.pmart.mfc.inventory.api.generated.model.@org.jspecify.annotations.Nullable
              EvaluateReplenishmentRequest
              b) {
    var r = replenishment.evaluate(idempotencyKey, xSourceSystem, b == null ? null : b.getMfcId());
    var out = new com.pmart.mfc.inventory.api.generated.model.EvaluateReplenishment200Response();
    out.setCreated(r.created());
    out.setMoves(r.moves().stream().map(MovesController::dto).toList());
    out.setSkipped(
        r.skipped().stream()
            .map(
                s -> {
                  var x =
                      new com.pmart.mfc.inventory.api.generated.model
                          .EvaluateReplenishment200ResponseSkippedInner();
                  x.setMfcId(s.mfcId());
                  x.setLocationCode(s.locationCode());
                  x.setSku(s.sku());
                  x.setReason(s.reason());
                  return x;
                })
            .toList());
    return ResponseEntity.ok(out);
  }

  static com.pmart.mfc.inventory.api.generated.model.ReplenishmentPolicy policy(
      com.pmart.mfc.inventory.application.ReplenishmentService.Policy p) {
    var out = new com.pmart.mfc.inventory.api.generated.model.ReplenishmentPolicy();
    out.setMfcId(p.mfcId());
    out.setLocationCode(p.locationCode());
    out.setSku(p.sku());
    out.setMinQty(p.minQty());
    out.setTargetQty(p.targetQty());
    out.setUnitQty(p.unitQty());
    out.setUpdatedBy(p.updatedBy());
    out.setUpdatedAt(p.updatedAt().atOffset(ZoneOffset.UTC));
    return out;
  }

  @Override
  public ResponseEntity<MoveAllocation> createMoveAllocation(
      String idempotencyKey, String xSourceSystem, CreateMoveAllocationRequest b) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            dto(
                service.create(
                    idempotencyKey,
                    xSourceSystem,
                    new MoveService.CreateCommand(
                        b.getMoveId(),
                        b.getMfcId(),
                        b.getSku(),
                        b.getLotId(),
                        b.getFromLocation(),
                        b.getToLocation(),
                        b.getQty()))));
  }

  @Override
  public ResponseEntity<MoveAllocation> getMoveAllocation(String moveId) {
    return ResponseEntity.ok(dto(service.get(moveId)));
  }

  @Override
  public ResponseEntity<MoveAllocation> pickUpMove(
      String moveId, String idempotencyKey, String xSourceSystem, PickUpMoveRequest b) {
    return ResponseEntity.ok(
        dto(
            service.pickUp(
                idempotencyKey,
                xSourceSystem,
                moveId,
                b.getExecutionId(),
                b.getCartLocation(),
                b.getQty())));
  }

  @Override
  public ResponseEntity<MoveAllocation> dropMove(
      String moveId, String idempotencyKey, String xSourceSystem, DropMoveRequest b) {
    return ResponseEntity.ok(
        dto(
            service.drop(
                idempotencyKey,
                xSourceSystem,
                moveId,
                b.getExecutionId(),
                b.getToLocation(),
                b.getQty())));
  }

  @Override
  public ResponseEntity<MoveAllocation> cancelMove(
      String moveId, String idempotencyKey, String xSourceSystem) {
    return ResponseEntity.ok(dto(service.cancel(idempotencyKey, xSourceSystem, moveId)));
  }

  @Override
  public ResponseEntity<com.pmart.mfc.inventory.api.generated.model.SuggestPlacement200Response>
      suggestPlacement(
          String mfcId,
          String sku,
          String lotId,
          Integer qty,
          @org.jspecify.annotations.Nullable Integer limit,
          @org.jspecify.annotations.Nullable String purpose) {
    var out = new com.pmart.mfc.inventory.api.generated.model.SuggestPlacement200Response();
    int n = limit == null ? 3 : limit;
    if ("QUARANTINE".equals(purpose)) {
      out.setLocationCodes(service.suggestQuarantine(mfcId, sku, n));
      out.setPlacements(java.util.List.of());
      out.setUnplacedQty(0);
      return ResponseEntity.ok(out);
    }
    out.setLocationCodes(service.suggestPlacement(mfcId, sku, lotId, qty, n));
    // 나눠 넣기(0.44.0, R-30) — 칸 수는 넉넉히(선반 하나 + 기타 + 보충재고 여러 칸)
    var plan = service.planPlacement(mfcId, sku, lotId, qty, Math.max(n, 10));
    out.setPlacements(
        plan.placements().stream()
            .map(
                s -> {
                  var d = new com.pmart.mfc.inventory.api.generated.model.PlacementShare();
                  d.setLocationCode(s.locationCode());
                  d.setQty(s.qty());
                  return d;
                })
            .toList());
    out.setUnplacedQty(plan.unplacedQty());
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<com.pmart.mfc.inventory.api.generated.model.StockExceptionResult>
      reportMoveException(
          String moveId,
          String idempotencyKey,
          String xSourceSystem,
          com.pmart.mfc.inventory.api.generated.model.ReportMoveExceptionRequest b) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            exceptionDto(
                exceptions.reportMove(
                    idempotencyKey,
                    xSourceSystem,
                    moveId,
                    b.getReportId(),
                    b.getReason().getValue(),
                    b.getQty())));
  }

  static com.pmart.mfc.inventory.api.generated.model.StockExceptionResult exceptionDto(
      com.pmart.mfc.inventory.application.StockExceptionService.Result r) {
    var out = new com.pmart.mfc.inventory.api.generated.model.StockExceptionResult();
    out.setReportId(r.reportId());
    out.setCommitId(r.commitId());
    out.setReason(r.reason());
    out.setReportedQty(r.reportedQty());
    out.setQuarantinedQty(r.quarantinedQty());
    out.setHeldQty(r.heldQty());
    out.setBlockedQty(r.blockedQty());
    out.setLocationCode(r.locationCode());
    if (r.move() != null) {
      out.setMove(dto(r.move()));
    }
    return out;
  }

  @Override
  public ResponseEntity<com.pmart.mfc.inventory.api.generated.model.ListUnshippableLots200Response>
      listUnshippableLots(String mfcId) {
    var out = new com.pmart.mfc.inventory.api.generated.model.ListUnshippableLots200Response();
    out.setItems(
        service.unshippable(mfcId).stream()
            .map(
                u -> {
                  var i =
                      new com.pmart.mfc.inventory.api.generated.model
                          .ListUnshippableLots200ResponseItemsInner();
                  i.setSku(u.sku());
                  i.setLotId(u.lotId());
                  i.setExpiryDate(u.expiryDate());
                  i.setManufactureDate(u.manufactureDate());
                  i.setLocationCode(u.locationCode());
                  i.setLocationCategoryId(u.locationCategoryId());
                  i.setOnMiscShelf(u.locationCategoryId() == null && !u.onReserve());
                  i.setQty(u.qty());
                  return i;
                })
            .toList());
    return ResponseEntity.ok(out);
  }

  static MoveAllocation dto(MoveRow m) {
    var out = new MoveAllocation();
    out.setMoveId(m.moveId());
    out.setKind(MoveAllocation.KindEnum.fromValue(m.kind()));
    out.setMfcId(m.mfcId());
    out.setSku(m.sku());
    out.setLotId(m.lotId());
    out.setFromLocation(m.fromLocation());
    out.setToLocation(m.toLocation());
    out.setCartLocation(m.cartLocation());
    out.setQty(m.qty());
    out.setPickedQty(m.pickedQty());
    out.setDroppedQty(m.droppedQty());
    out.setOnCartQty(m.onCart());
    out.setState(MoveAllocation.StateEnum.fromValue(m.state()));
    out.setSizeClass(m.sizeClass());
    out.setDestinationCapacity(m.destinationCapacity());
    out.setCreatedAt(m.createdAt().atOffset(ZoneOffset.UTC));
    out.setUpdatedAt(m.updatedAt().atOffset(ZoneOffset.UTC));
    return out;
  }
}
