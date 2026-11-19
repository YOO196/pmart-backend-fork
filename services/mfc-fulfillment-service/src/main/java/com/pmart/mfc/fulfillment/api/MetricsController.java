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
}
