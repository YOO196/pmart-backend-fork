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

  public ShipmentsController(
      PackageService service,
      com.pmart.mfc.fulfillment.application.RepackService repacks,
      com.pmart.mfc.fulfillment.infrastructure.HandoverStore handovers,
      ObjectMapper mapper,
      DispatchRequestService dispatch,
      com.pmart.mfc.fulfillment.application.OrderNumbers orderNumbers) {
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
                body.getPackingLocation()));
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
    out.setInvalidatedReason(
        "VOIDED".equals(p.state()) || "INVALIDATED".equals(p.state()) ? p.rejectReason() : null);
    return out;
  }
}
