package com.pmart.mfc.capacity.api;

import com.pmart.mfc.capacity.api.generated.CountWindowsApi;
import com.pmart.mfc.capacity.api.generated.model.CountWindow;
import com.pmart.mfc.capacity.api.generated.model.CountWindows;
import com.pmart.mfc.capacity.api.generated.model.ReplaceCountWindowsRequest;
import com.pmart.mfc.capacity.application.CountWindowService;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 재고 실사 시간대(0.33.0). */
@RestController
public class CountWindowsController implements CountWindowsApi {

  private final CountWindowService service;

  public CountWindowsController(CountWindowService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<CountWindows> getCountWindows(String mfcId) {
    return ResponseEntity.ok(dto(service.get(mfcId)));
  }

  @Override
  public ResponseEntity<CountWindows> replaceCountWindows(
      String mfcId,
      String idempotencyKey,
      String xSourceSystem,
      ReplaceCountWindowsRequest body,
      @Nullable Integer xExpectedVersion) {
    var raw =
        body.getWindows().stream()
            .map(w -> new String[] {w.getStartTime(), w.getEndTime()})
            .toList();
    return ResponseEntity.ok(
        dto(service.replace(idempotencyKey, xSourceSystem, mfcId, xExpectedVersion, raw)));
  }

  private static CountWindows dto(CountWindowService.Setting s) {
    var out = new CountWindows();
    out.setMfcId(s.mfcId());
    out.setWindows(
        s.windows().stream()
            .map(
                w -> {
                  var d = new CountWindow();
                  d.setStartTime(w.start().toString());
                  d.setEndTime(w.end().toString());
                  return d;
                })
            .toList());
    out.setSource(CountWindows.SourceEnum.fromValue(s.source()));
    out.setVersion(s.version());
    out.setUpdatedBy(s.updatedBy());
    out.setUpdatedAt(s.updatedAt() == null ? null : s.updatedAt().atOffset(ZoneOffset.UTC));
    out.setActiveNow(s.activeNow());
    return out;
  }
}
