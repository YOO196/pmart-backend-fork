package com.pmart.mfc.inbound.api;

import com.pmart.mfc.inbound.api.generated.WorkActivitiesApi;
import com.pmart.mfc.inbound.api.generated.model.ListWorkActivities200Response;
import com.pmart.mfc.inbound.api.generated.model.WorkActivity;
import com.pmart.mfc.inbound.application.WorkActivities;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 작업자 처리 내역(F-7 R3, 내부 — mfc-capacity 가 모은다). */
@RestController
public class WorkActivitiesController implements WorkActivitiesApi {

  private final WorkActivities activities;

  public WorkActivitiesController(WorkActivities activities) {
    this.activities = activities;
  }

  @Override
  public ResponseEntity<ListWorkActivities200Response> listWorkActivities(
      String mfcId, String operatorId, OffsetDateTime from, OffsetDateTime to) {
    var out = new ListWorkActivities200Response();
    out.setItems(
        activities.list(mfcId, operatorId, from.toInstant(), to.toInstant()).stream()
            .map(
                a -> {
                  var w = new WorkActivity();
                  w.setActivityType(a.activityType());
                  w.setRefId(a.refId());
                  w.setTaskId(a.taskId());
                  w.setSku(a.sku());
                  w.setQty(a.qty());
                  w.setResult(a.result());
                  w.setAt(a.at().atOffset(ZoneOffset.UTC));
                  return w;
                })
            .toList());
    return ResponseEntity.ok(out);
  }
}
