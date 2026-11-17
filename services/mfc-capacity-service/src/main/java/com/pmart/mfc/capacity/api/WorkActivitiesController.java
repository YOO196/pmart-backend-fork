package com.pmart.mfc.capacity.api;

import com.pmart.mfc.capacity.api.generated.WorkActivitiesApi;
import com.pmart.mfc.capacity.api.generated.model.WorkerActivities;
import com.pmart.mfc.capacity.api.generated.model.WorkerActivitiesItemsInner;
import com.pmart.mfc.capacity.api.generated.model.WorkerActivitiesTotalsInner;
import com.pmart.mfc.capacity.application.WorkActivityService;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 작업자 오늘 업무 내역(F-7, 0.17.0). */
@RestController
public class WorkActivitiesController implements WorkActivitiesApi {

  private final WorkActivityService service;

  public WorkActivitiesController(WorkActivityService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<WorkerActivities> listWorkerActivities(
      String mfcId, String workerId, @Nullable LocalDate date) {
    var a = service.list(mfcId, workerId, date);
    var out = new WorkerActivities();
    out.setMfcId(a.mfcId());
    out.setWorkerId(a.workerId());
    out.setDate(a.date());
    out.setItems(
        a.items().stream()
            .map(
                i -> {
                  var x = new WorkerActivitiesItemsInner();
                  x.setSource(i.source());
                  x.setActivityType(i.activityType());
                  x.setRefId(i.refId());
                  x.setTaskId(i.taskId());
                  x.setSku(i.sku());
                  x.setQty(i.qty());
                  x.setResult(i.result());
                  x.setAt(i.at().atOffset(ZoneOffset.UTC));
                  return x;
                })
            .toList());
    out.setTotals(
        a.totals().stream()
            .map(
                t -> {
                  var x = new WorkerActivitiesTotalsInner();
                  x.setSource(t.source());
                  x.setActivityType(t.activityType());
                  x.setCount(t.count());
                  x.setQty(t.qty());
                  return x;
                })
            .toList());
    out.setUnavailableSources(new ArrayList<>(a.unavailableSources()));
    return ResponseEntity.ok(out);
  }
}
