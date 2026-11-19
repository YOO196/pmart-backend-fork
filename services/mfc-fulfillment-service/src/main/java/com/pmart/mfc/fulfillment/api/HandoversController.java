package com.pmart.mfc.fulfillment.api;

import com.pmart.mfc.fulfillment.api.generated.HandoversApi;
import com.pmart.mfc.fulfillment.api.generated.model.AuthorizePackageHandoverRequest;
import com.pmart.mfc.fulfillment.api.generated.model.CheckPackageHandoverRequest;
import com.pmart.mfc.fulfillment.api.generated.model.CommitPackageHandoverRequest;
import com.pmart.mfc.fulfillment.api.generated.model.FenceState;
import com.pmart.mfc.fulfillment.api.generated.model.HandoverAuthorization;
import com.pmart.mfc.fulfillment.api.generated.model.HandoverCommitResult;
import com.pmart.mfc.fulfillment.api.generated.model.HandoverResolution;
import com.pmart.mfc.fulfillment.api.generated.model.HandoverResolutionFence;
import com.pmart.mfc.fulfillment.api.generated.model.HandoverResolutionLastCheck;
import com.pmart.mfc.fulfillment.api.generated.model.ManualRetryBody;
import com.pmart.mfc.fulfillment.api.generated.model.PackageState;
import com.pmart.mfc.fulfillment.api.generated.model.ShipmentState;
import com.pmart.mfc.fulfillment.application.HandoverService;
import com.pmart.mfc.fulfillment.application.ManualRetry;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 HandoversApi(라이더 인계, 슬라이스 6c — ADR-0047). 업무 규칙은 {@link HandoverService}·{@code
 * domain/HandoverRules}, 여기서는 요청·응답 변환만 한다. 재포장은 인터페이스 default 의 501.
 */
@RestController
public class HandoversController implements HandoversApi {

  private final HandoverService handovers;

  public HandoversController(HandoverService handovers) {
    this.handovers = handovers;
  }

  @Override
  public ResponseEntity<HandoverAuthorization> authorizeHandoverByCode(
      String idempotencyKey,
      String xSourceSystem,
      com.pmart.mfc.fulfillment.api.generated.model.AuthorizeHandoverByCodeRequest body) {
    return created(
        handovers.authorizeByCode(
            idempotencyKey,
            xSourceSystem,
            body.getRiderId(),
            body.getDeliveryId(),
            body.getHandoverCode()));
  }

  @Override
  public ResponseEntity<HandoverAuthorization> authorizePackageHandover(
      String packageId,
      String idempotencyKey,
      String xSourceSystem,
      AuthorizePackageHandoverRequest body,
      @Nullable Integer xExpectedVersion) {
    return created(
        handovers.authorize(
            packageId,
            idempotencyKey,
            xSourceSystem,
            new HandoverService.AuthorizeCommand(
                body.getRiderId(), body.getDeliveryId(), body.getScannedCode())));
  }

  private static ResponseEntity<HandoverAuthorization> created(HandoverService.Authorization a) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            new HandoverAuthorization()
                .handoverAuthorizationId(a.handoverAuthorizationId())
                .packageId(a.packageId())
                .packageVersion(a.packageVersion())
                .fenceVersion(a.fenceVersion())
                .riderId(a.riderId())
                .deliveryId(a.deliveryId())
                .fenceExpiresAt(time(a.fenceExpiresAt()))
                .authorizedAt(time(a.authorizedAt())));
  }

  @Override
  public ResponseEntity<HandoverCommitResult> commitPackageHandover(
      String packageId,
      String idempotencyKey,
      String xSourceSystem,
      CommitPackageHandoverRequest body,
      @Nullable Integer xExpectedVersion) {
    var r =
        handovers.commit(
            packageId,
            new HandoverService.CommitCommand(
                body.getHandoverAuthorizationId(), body.getFenceVersion(), body.getRiderId()));
    HandoverCommitResult out =
        new HandoverCommitResult()
            .status(HandoverCommitResult.StatusEnum.fromValue(r.status()))
            .requestId(idempotencyKey)
            .handoverId(r.handoverId())
            .inventoryCommitId(r.inventoryCommitId())
            .packageState(PackageState.fromValue(r.packageState()))
            .shipmentState(ShipmentState.fromValue(r.shipmentState()))
            .nextAction(r.nextAction())
            .rejectReason(r.rejectReason());
    if (r.handedOverAt() != null) {
      out.setHandedOverAt(time(r.handedOverAt()));
    }
    return ResponseEntity.status(
            "APPLIED".equals(r.status()) ? HttpStatus.CREATED : HttpStatus.ACCEPTED)
        .body(out);
  }

  @Override
  public ResponseEntity<HandoverCommitResult> retryPackageHandover(
      String handoverId,
      String idempotencyKey,
      String xSourceSystem,
      @Nullable ManualRetryBody body) {
    var r =
        handovers.retry(
            handoverId,
            ManualRetry.of(
                body == null ? null : body.getOperatorId(),
                body == null ? null : body.getReason(),
                xSourceSystem));
    HandoverCommitResult out =
        new HandoverCommitResult()
            .status(HandoverCommitResult.StatusEnum.fromValue(r.status()))
            .requestId(idempotencyKey)
            .handoverId(r.handoverId())
            .inventoryCommitId(r.inventoryCommitId())
            .packageState(PackageState.fromValue(r.packageState()))
            .shipmentState(ShipmentState.fromValue(r.shipmentState()))
            .nextAction(r.nextAction())
            .rejectReason(r.rejectReason());
    if (r.handedOverAt() != null) {
      out.setHandedOverAt(time(r.handedOverAt()));
    }
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<HandoverResolution> getHandoverResolution(
      String packageId, @Nullable String stopRequestId) {
    return ResponseEntity.ok(dto(handovers.resolution(packageId, stopRequestId)));
  }

  @Override
  public ResponseEntity<HandoverResolution> checkPackageHandover(
      String packageId,
      String idempotencyKey,
      String xSourceSystem,
      CheckPackageHandoverRequest body) {
    return ResponseEntity.ok(
        dto(
            handovers.check(
                packageId,
                idempotencyKey,
                xSourceSystem,
                body.getResult().getValue(),
                body.getNote())));
  }

  @Override
  public ResponseEntity<
          com.pmart.mfc.fulfillment.api.generated.model.ListPackagesToCheck200Response>
      listPackagesToCheck(String mfcId, Boolean checkRequired) {
    if (!Boolean.TRUE.equals(checkRequired)) {
      throw new com.pmart.common.web.PmartException(
          com.pmart.common.web.ErrorCode.VALIDATION_FAILED, "지금은 checkRequired=true 만 됩니다.");
    }
    var out = new com.pmart.mfc.fulfillment.api.generated.model.ListPackagesToCheck200Response();
    out.setItems(handovers.toCheck(mfcId).stream().map(this::dto).toList());
    var a = new com.pmart.mfc.fulfillment.api.generated.model.ListTasks200ResponseAsOf();
    a.setAsOf(time(java.time.Instant.now()));
    a.setFreshness(
        com.pmart.mfc.fulfillment.api.generated.model.ListTasks200ResponseAsOf.FreshnessEnum
            .CURRENT);
    out.setAsOf(a);
    return ResponseEntity.ok(out);
  }

  private HandoverResolution dto(HandoverService.Resolution r) {
    var p = r.pkg();
    String fenceState =
        "HANDOVER_IN_PROGRESS".equals(p.fenceState()) && !r.fenceLive()
            ? "EXPIRED"
            : p.fenceState();
    String apply =
        r.handover() == null
            ? "NOT_REQUESTED"
            : switch (r.handover().state()) {
              case "APPLIED" -> "APPLIED";
              case "REJECTED" -> "REJECTED";
              case "UNKNOWN_RESULT" -> "UNKNOWN_RESULT";
              default -> "PROCESSING";
            };
    HandoverResolutionFence fence =
        new HandoverResolutionFence()
            .state(FenceState.fromValue(fenceState))
            .handoverAuthorizationId(p.authorizationId())
            .fenceVersion(p.fenceVersion());
    if (p.fenceExpiresAt() != null) {
      fence.setExpiresAt(time(p.fenceExpiresAt()));
    }
    var check = handovers.lastCheck(p.packageId()).orElse(null);
    var label = labels.packageLabel(p.packageId(), p.orderId()); // DL-1 화면 표기
    return new HandoverResolution()
        .checkRequired(
            "HANDOVER_IN_PROGRESS".equals(p.fenceState()) && !r.fenceLive() && r.handover() == null)
        .lastCheck(
            check == null
                ? null
                : new HandoverResolutionLastCheck()
                    .result(HandoverResolutionLastCheck.ResultEnum.fromValue(check.result()))
                    .checkedBy(check.checkedBy())
                    .checkedAt(time(check.checkedAt())))
        .packageId(p.packageId())
        .handoverCode(label.handoverCode())
        .orderNumber(label.orderNumber())
        .shipmentId(p.shipmentId())
        .orderId(p.orderId())
        .packingLocation(p.packingLocation())
        .riderId(p.fenceRiderId())
        .packageVersion(p.packageVersion())
        .packageState(PackageState.fromValue(p.state()))
        .verdict(HandoverResolution.VerdictEnum.fromValue(r.verdict().name()))
        .fence(fence)
        .handoverId(r.handover() == null ? null : r.handover().handoverId())
        .inventoryCommitId(r.handover() == null ? null : r.handover().inventoryCommitId())
        .inventoryApplyStatus(HandoverResolution.InventoryApplyStatusEnum.fromValue(apply))
        .stopRequestId(r.stopRequestId());
  }

  private static OffsetDateTime time(Instant i) {
    return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
  }

  private com.pmart.mfc.fulfillment.infrastructure.CenterOpsStore labels;

  @org.springframework.beans.factory.annotation.Autowired
  void setLabels(com.pmart.mfc.fulfillment.infrastructure.CenterOpsStore labels) {
    this.labels = labels;
  }
}
