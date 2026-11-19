package com.pmart.mfc.fulfillment.api;

import com.pmart.mfc.fulfillment.api.generated.ShipmentsApi;
import com.pmart.mfc.fulfillment.api.generated.model.DispatchRequest;
import com.pmart.mfc.fulfillment.api.generated.model.FenceState;
import com.pmart.mfc.fulfillment.api.generated.model.ModelPackage;
import com.pmart.mfc.fulfillment.api.generated.model.PackageContentLine;
import com.pmart.mfc.fulfillment.api.generated.model.PackageFence;
import com.pmart.mfc.fulfillment.api.generated.model.PackageState;
import com.pmart.mfc.fulfillment.api.generated.model.RequestDispatchRequest;
import com.pmart.mfc.fulfillment.api.generated.model.SealPackageRequest;
import com.pmart.mfc.fulfillment.api.generated.model.Shipment;
import com.pmart.mfc.fulfillment.api.generated.model.ShipmentState;
import com.pmart.mfc.fulfillment.application.DispatchRequestService;
import com.pmart.mfc.fulfillment.application.PackageService;
import com.pmart.mfc.fulfillment.application.PackageService.SealCommand;
import com.pmart.mfc.fulfillment.domain.TaskRules.Content;
import com.pmart.mfc.fulfillment.domain.TaskRules.ContentRequest;
import com.pmart.mfc.fulfillment.infrastructure.TaskStore.PackageRow;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 계약 ShipmentsApi — 출고 조회·포장 밀봉(슬라이스 6b)·재포장(WK-07). 업무 규칙은 {@link PackageService}·{@link
 * com.pmart.mfc.fulfillment.application.RepackService}.
 */
@RestController
public class ShipmentsController implements ShipmentsApi {

  private final PackageService service;
  private final ObjectMapper mapper;

  private final com.pmart.mfc.fulfillment.application.RepackService repacks;
  private final com.pmart.mfc.fulfillment.infrastructure.HandoverStore handovers;
  private final DispatchRequestService dispatch;
  private final com.pmart.mfc.fulfillment.application.OrderNumbers orderNumbers;
  private final com.pmart.mfc.fulfillment.application.CenterShipmentService centerShipments;

  /** 센터 출고 목록(F-9, 0.15.0). */
  @Override
  public ResponseEntity<
          com.pmart.mfc.fulfillment.api.generated.model.ListCenterShipments200Response>
      listCenterShipments(
          String mfcId,
          @org.jspecify.annotations.Nullable
              List<com.pmart.mfc.fulfillment.api.generated.model.ShipmentState> state,
          Integer size,
          @org.jspecify.annotations.Nullable String cursor) {
    int limit = size == null ? 50 : size;
    var rows =
        centerShipments.list(
            mfcId,
            state == null ? null : state.stream().map(x -> x.getValue()).toList(),
            limit + 1,
            cursor);
    boolean more = rows.size() > limit;
    var page = more ? rows.subList(0, limit) : rows;
    var out = new com.pmart.mfc.fulfillment.api.generated.model.ListCenterShipments200Response();
    out.setItems(page.stream().map(ShipmentsController::board).toList());
    out.setNextCursor(more ? page.get(page.size() - 1).shipment().shipmentId() : null);
    out.setAsOf(TasksController.time(java.time.Instant.now()));
    return ResponseEntity.ok(out);
  }

  private static com.pmart.mfc.fulfillment.api.generated.model.CenterShipment board(
      com.pmart.mfc.fulfillment.application.CenterShipmentService.Board b) {
    var r = b.shipment();
    var out = new com.pmart.mfc.fulfillment.api.generated.model.CenterShipment();
    out.setShipmentId(r.shipmentId());
    out.setOrderId(r.orderId());
    out.setOrderNumber(b.orderNumber());
    out.setFulfillmentId(r.fulfillmentId());
    out.setMfcId(r.mfcId());
    out.setWorkOrderKind(r.workOrderKind());
    out.setState(com.pmart.mfc.fulfillment.api.generated.model.ShipmentState.fromValue(r.state()));
    out.setCreatedAt(TasksController.time(r.createdAt()));
    out.setReadyAt(TasksController.time(r.readyAt()));
    out.setDispatchRequestedAt(TasksController.time(r.dispatchRequestedAt()));
    out.setPick(task(b.pick()));
    out.setPack(task(b.pack()));
    var counts = new com.pmart.mfc.fulfillment.api.generated.model.CenterShipmentPackageCounts();
    counts.setTotal(b.packages().size());
    counts.setSealed((int) b.packages().stream().filter(p -> "SEALED".equals(p.state())).count());
    counts.setHandedOver(
        (int) b.packages().stream().filter(p -> "HANDED_OVER".equals(p.state())).count());
    out.setPackageCounts(counts);
    out.setPackages(
        b.packages().stream()
            .map(
                p -> {
                  var x =
                      new com.pmart.mfc.fulfillment.api.generated.model
                          .CenterShipmentPackagesInner();
                  x.setPackageId(p.packageId());
                  x.setState(PackageState.fromValue(p.state()));
                  x.setFenceState(p.fenceState());
                  x.setHandoverAuthorizedAt(TasksController.time(p.handoverAuthorizedAt()));
                  x.setHandedOverAt(TasksController.time(p.handedOverAt()));
                  x.setRiderId(p.riderId());
                  x.setHandoverCode(p.handoverCode()); // DL-1·DL-2(0.21.0)
                  return x;
                })
            .toList());
    return out;
  }

  private static com.pmart.mfc.fulfillment.api.generated.model.@org.jspecify.annotations.Nullable
      CenterShipmentTask
      task(
          com.pmart.mfc.fulfillment.infrastructure.CenterShipmentStore.@org.jspecify.annotations
                  .Nullable
              TaskRow
              t) {
    if (t == null) {
      return null;
    }
    var x = new com.pmart.mfc.fulfillment.api.generated.model.CenterShipmentTask();
    x.setState(com.pmart.mfc.fulfillment.api.generated.model.TaskState.fromValue(t.state()));
    x.setBlockReason(t.blockReason());
    x.setStartedAt(TasksController.time(t.startedAt()));
    return x;
  }

  public ShipmentsController(
      PackageService service,
      com.pmart.mfc.fulfillment.application.RepackService repacks,
      com.pmart.mfc.fulfillment.infrastructure.HandoverStore handovers,
      ObjectMapper mapper,
      DispatchRequestService dispatch,
      com.pmart.mfc.fulfillment.application.OrderNumbers orderNumbers,
      com.pmart.mfc.fulfillment.application.CenterShipmentService centerShipments) {
    this.centerShipments = centerShipments;
    this.orderNumbers = orderNumbers;
    this.dispatch = dispatch;
    this.handovers = handovers;
    this.service = service;
    this.repacks = repacks;
    this.mapper = mapper;
  }

  @Override
  public ResponseEntity<com.pmart.mfc.fulfillment.api.generated.model.RegroupPackages201Response>
      regroupPackages(
          String shipmentId,
          String idempotencyKey,
          String xSourceSystem,
          com.pmart.mfc.fulfillment.api.generated.model.RegroupPackagesRequest body) {
    List<com.pmart.mfc.fulfillment.application.RepackService.NewPackage> pkgs = new ArrayList<>();
    for (var np : body.getPackages()) {
      List<ContentRequest> contents = new ArrayList<>();
      for (PackageContentLine c : np.getContents()) {
        contents.add(new ContentRequest(c.getOrderLineId(), c.getSku(), c.getLotId(), c.getQty()));
      }
      pkgs.add(
          new com.pmart.mfc.fulfillment.application.RepackService.NewPackage(
              np.getBagCount(), contents));
    }
    List<PackageRow> made =
        repacks.regroup(
            shipmentId,
            idempotencyKey,
            new com.pmart.mfc.fulfillment.application.RepackService.RegroupCommand(
                body.getReason().getValue(), body.getPackageIds(), pkgs));
    String orderId = service.orderIdOf(shipmentId);
    var out = new com.pmart.mfc.fulfillment.api.generated.model.RegroupPackages201Response();
    out.setPackages(made.stream().map(p -> dto(p, orderId)).toList());
    return ResponseEntity.status(HttpStatus.CREATED).body(out);
  }

  @Override
  public ResponseEntity<ModelPackage> repackPackage(
      String packageId,
      String idempotencyKey,
      String xSourceSystem,
      com.pmart.mfc.fulfillment.api.generated.model.RepackPackageRequest body,
      @Nullable Integer xExpectedVersion) {
    List<ContentRequest> contents = null;
    if (body.getContents() != null) {
      contents = new ArrayList<>();
      for (PackageContentLine c : body.getContents()) {
        contents.add(new ContentRequest(c.getOrderLineId(), c.getSku(), c.getLotId(), c.getQty()));
      }
    }
    PackageRow p =
        repacks.repack(
            packageId,
            idempotencyKey,
            new com.pmart.mfc.fulfillment.application.RepackService.RepackCommand(
                body.getReason().getValue(), contents));
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(dto(p, service.orderIdOf(p.shipmentId())));
  }

  @Override
  public ResponseEntity<ModelPackage> sealPackage(
      String shipmentId,
      String idempotencyKey,
      String xSourceSystem,
      SealPackageRequest body,
      @Nullable Integer xExpectedVersion) {
    List<ContentRequest> contents = new ArrayList<>();
    for (PackageContentLine c : body.getContents()) {
      contents.add(new ContentRequest(c.getOrderLineId(), c.getSku(), c.getLotId(), c.getQty()));
    }
    PackageRow p =
        service.seal(
            shipmentId,
            idempotencyKey,
            new SealCommand(
                contents,
                body.getBagCount(),
                Boolean.TRUE.equals(body.getContainsAlcohol()),
                body.getTemperatureZone() == null ? null : body.getTemperatureZone().getValue(),
                body.getWeightG(),
                body.getVolumeCm3(),
                body.getPackingLocation(),
                body.getIcePackCount()));
    return ResponseEntity.status(HttpStatus.CREATED).body(dto(p, service.orderIdOf(shipmentId)));
  }

  @Override
  public ResponseEntity<ModelPackage> getPackage(String packageId) {
    PackageRow p = service.pkg(packageId);
    return ResponseEntity.ok(dto(p, service.orderIdOf(p.shipmentId())));
  }

  @Override
  public ResponseEntity<Shipment> getShipment(String shipmentId) {
    var v = service.shipment(shipmentId);
    var out = new Shipment();
    out.setShipmentId(v.shipment().shipmentId());
    out.setFulfillmentId(v.shipment().fulfillmentId());
    out.setOrderId(v.orderId());
    out.setOrderNumber(orderNumbers.of(v.orderId()));
    out.setDeliveryId(v.deliveryId());
    out.setVersion(v.shipment().version());
    out.setState(ShipmentState.fromValue(v.shipment().state()));
    List<ModelPackage> pkgs = new ArrayList<>();
    int bags = 0;
    boolean alcohol = false;
    int weight = 0;
    for (PackageRow p : v.packages()) {
      if (!"SEALED".equals(p.state()) && !"HANDED_OVER".equals(p.state())) {
        continue;
      }
      pkgs.add(dto(p, v.orderId()));
      bags += p.bagCount();
      alcohol |= p.containsAlcohol();
      weight += p.weightG() == null ? 0 : p.weightG();
    }
    out.setPackages(pkgs);
    out.setBagCount(bags);
    out.setContainsAlcohol(alcohol);
    out.setWeightG(weight);
    out.setReadyAt(TasksController.time(v.readyAt()));
    out.setDispatchRequest(dispatch.find(shipmentId).map(ShipmentsController::dto).orElse(null));
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<DispatchRequest> requestDispatch(
      String shipmentId, String idempotencyKey, String xSourceSystem, RequestDispatchRequest body) {
    var o = dispatch.request(shipmentId, body.getEstimatedPackMinutes());
    return ResponseEntity.status(o.created() ? HttpStatus.CREATED : HttpStatus.OK)
        .body(dto(o.request()));
  }

  static DispatchRequest dto(DispatchRequestService.Request r) {
    return new DispatchRequest()
        .shipmentId(r.shipmentId())
        .trigger(DispatchRequest.TriggerEnum.fromValue(r.trigger()))
        .estimatedPackMinutes(r.estimatedPackMinutes())
        .estimatedReadyAt(TasksController.time(r.estimatedReadyAt()))
        .requestedAt(TasksController.time(r.requestedAt()))
        .requestedBy(r.requestedBy());
  }

  /** 출하대기 랙에 올림(0.20.0, F-12). */
  @Override
  public ResponseEntity<ModelPackage> stagePackage(
      String packageId,
      String idempotencyKey,
      String xSourceSystem,
      com.pmart.mfc.fulfillment.api.generated.model.StagePackageRequest body) {
    PackageRow p = service.stage(idempotencyKey, packageId, body.getStagingLocation());
    return ResponseEntity.ok(dto(p, service.orderIdOf(p.shipmentId())));
  }

  private ModelPackage dto(PackageRow p, String orderId) {
    var out = new ModelPackage();
    out.setPackageId(p.packageId());
    out.setPackageVersion(p.packageVersion());
    out.setShipmentId(p.shipmentId());
    out.setOrderId(orderId);
    out.setOrderNumber(orderNumbers.of(orderId));
    out.setHandoverCode(p.handoverCode());
    out.setVersion(p.version());
    out.setState(PackageState.fromValue("OPEN".equals(p.state()) ? "OPEN" : p.state()));
    out.setQrCode(p.qrCode());
    List<PackageContentLine> lines = new ArrayList<>();
    for (Content c : mapper.readValue(p.contents(), new TypeReference<List<Content>>() {})) {
      var l = new PackageContentLine();
      l.setOrderLineId(c.orderLineId());
      l.setSku(c.sku());
      l.setLotId(c.lotId());
      l.setQty(c.qty());
      lines.add(l);
    }
    out.setContents(lines);
    out.setBagCount(p.bagCount());
    out.setContainsAlcohol(p.containsAlcohol());
    out.setTemperatureZone(p.temperatureZone());
    out.setIcePackCount(p.icePackCount());
    out.setWeightG(p.weightG());
    var fence = new PackageFence();
    fence.setState(FenceState.NONE);
    fence.setFenceVersion(0);
    handovers
        .find(p.packageId())
        .ifPresent(
            f -> {
              fence.setState(FenceState.fromValue(f.fenceState()));
              fence.setFenceVersion(f.fenceVersion());
              fence.setHandoverAuthorizationId(f.authorizationId());
              fence.setRiderId(f.fenceRiderId());
              fence.setExpiresAt(TasksController.time(f.fenceExpiresAt()));
              out.setHandedOverAt(TasksController.time(f.handedOverAt()));
            });
    out.setFence(fence);
    out.setSealedAt(TasksController.time(p.sealedAt()));
    out.setPackingLocation(p.packingLocation());
    out.setStagingLocation(p.stagingLocation());
    out.setInvalidatedReason(
        "VOIDED".equals(p.state()) || "INVALIDATED".equals(p.state()) ? p.rejectReason() : null);
    return out;
  }

  private com.pmart.mfc.fulfillment.application.CenterOpsService ops;

  @org.springframework.beans.factory.annotation.Autowired
  void setOps(com.pmart.mfc.fulfillment.application.CenterOpsService ops) {
    this.ops = ops;
  }

  /** 출하 약속 요약(0.21.0, OE-2) — 임박 기본 15분. */
  @Override
  public org.springframework.http.ResponseEntity<
          com.pmart.mfc.fulfillment.api.generated.model.GetShipmentPromiseSummary200Response>
      getShipmentPromiseSummary(String mfcId, Integer dueWithinMinutes) {
    var s = ops.promiseSummary(mfcId, dueWithinMinutes);
    var out =
        new com.pmart.mfc.fulfillment.api.generated.model.GetShipmentPromiseSummary200Response();
    out.setOverdue(s.counts().overdue());
    out.setDueSoon(s.counts().dueSoon());
    out.setOnTime(s.counts().onTime());
    out.setNoPromise(s.counts().noPromise());
    out.setDueWithinMinutes(s.dueWithinMinutes());
    out.setAsOf(java.time.OffsetDateTime.ofInstant(s.asOf(), java.time.ZoneOffset.UTC));
    return org.springframework.http.ResponseEntity.ok(out);
  }
}
