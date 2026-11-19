package com.pmart.mfc.fulfillment.api;

import com.pmart.mfc.fulfillment.api.generated.MetricsApi;
import com.pmart.mfc.fulfillment.api.generated.model.GetPickingLineMetrics200Response;
import com.pmart.mfc.fulfillment.api.generated.model.GetPickingLineMetrics200ResponseItemsInner;
import com.pmart.mfc.fulfillment.api.generated.model.GetPickingLineMetrics200ResponseItemsInnerShortLinesByReason;
import com.pmart.mfc.fulfillment.application.MetricsService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 결품률 지표(0.19.0, 요청 F-10). */
@RestController
public class MetricsController implements MetricsApi {

  private final MetricsService service;

  public MetricsController(MetricsService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<GetPickingLineMetrics200Response> getPickingLineMetrics(
      LocalDate from, LocalDate to, @Nullable String mfcId) {
    var out = new GetPickingLineMetrics200Response();
    out.setItems(
        service.pickingLines(from, to, mfcId).stream()
            .map(
                r -> {
                  var by = new GetPickingLineMetrics200ResponseItemsInnerShortLinesByReason();
                  by.setSHORT(r.shortShort());
                  by.setDAMAGED(r.shortDamaged());
                  by.setBLOCKED(r.shortBlocked());
                  by.setSPOILED(r.shortSpoiled());
                  var x = new GetPickingLineMetrics200ResponseItemsInner();
                  x.setDate(r.date());
                  x.setMfcId(r.mfcId());
                  x.setPickedLines(r.pickedLines());
                  x.setShortLines(r.shortLines());
                  x.setShortLinesByReason(by);
                  return x;
                })
            .toList());
    out.setAsOf(OffsetDateTime.now(ZoneOffset.UTC));
    return ResponseEntity.ok(out);
  }

  private com.pmart.mfc.fulfillment.application.CenterOpsService ops;

  @org.springframework.beans.factory.annotation.Autowired
  void setOps(com.pmart.mfc.fulfillment.application.CenterOpsService ops) {
    this.ops = ops;
  }

  /** 센터 결품 보고 목록(0.21.0, OE-1). */
  @Override
  public org.springframework.http.ResponseEntity<
          com.pmart.mfc.fulfillment.api.generated.model.ListShortages200Response>
      listShortages(
          String mfcId,
          java.util.@org.jspecify.annotations.Nullable List<String> reason,
          java.time.@org.jspecify.annotations.Nullable OffsetDateTime from,
          java.time.@org.jspecify.annotations.Nullable OffsetDateTime to,
          Integer size,
          @org.jspecify.annotations.Nullable String cursor) {
    var page =
        ops.shortages(
            mfcId,
            reason,
            from == null ? null : from.toInstant(),
            to == null ? null : to.toInstant(),
            size == null ? 50 : size,
            cursor);
    var out = new com.pmart.mfc.fulfillment.api.generated.model.ListShortages200Response();
    out.setTotal(page.total());
    out.setNextCursor(page.nextCursor());
    out.setAsOf(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
    out.setItems(
        page.items().stream()
            .map(
                it -> {
                  var r = it.row();
                  var x =
                      new com.pmart.mfc.fulfillment.api.generated.model
                          .ListShortages200ResponseItemsInner();
                  x.setShortageId(String.valueOf(r.shortageId()));
                  x.setReason(
                      com.pmart.mfc.fulfillment.api.generated.model
                          .ListShortages200ResponseItemsInner.ReasonEnum.fromValue(r.reason()));
                  x.setTaskId(r.taskId());
                  x.setTaskType(r.taskType());
                  x.setTaskState(r.taskState());
                  x.setShipmentId(r.shipmentId());
                  x.setOrderId(r.orderId());
                  x.setOrderNumber(r.orderNumber());
                  x.setOrderLineId(r.orderLineId());
                  x.setSku(r.sku());
                  x.setProductName(it.productName());
                  x.setLotId(r.lotId());
                  x.setLocationCode(r.locationCode());
                  x.setQty(r.qty());
                  x.setReportedBy(r.reportedBy());
                  x.setReportedAt(
                      java.time.OffsetDateTime.ofInstant(r.reportedAt(), java.time.ZoneOffset.UTC));
                  return x;
                })
            .toList());
    return org.springframework.http.ResponseEntity.ok(out);
  }
}
