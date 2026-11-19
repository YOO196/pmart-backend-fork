package com.pmart.mfc.quality.api;

import com.pmart.mfc.quality.api.generated.IncidentsApi;
import com.pmart.mfc.quality.api.generated.model.CloseQualityIncidentRequest;
import com.pmart.mfc.quality.api.generated.model.CreateQualityIncidentRequest;
import com.pmart.mfc.quality.api.generated.model.IncidentKind;
import com.pmart.mfc.quality.api.generated.model.ListQualityIncidents200Response;
import com.pmart.mfc.quality.api.generated.model.QualityIncident;
import com.pmart.mfc.quality.api.generated.model.QualityIncidentAffectedLotsInner;
import com.pmart.mfc.quality.application.IncidentService;
import com.pmart.mfc.quality.application.IncidentService.View;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 품질 사고(0.6.0, MB-2 1차). 업무 규칙은 {@link IncidentService}. */
@RestController
public class IncidentsController implements IncidentsApi {

  private final IncidentService service;

  public IncidentsController(IncidentService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<ListQualityIncidents200Response> listQualityIncidents(
      String mfcId,
      @Nullable IncidentKind kind,
      @Nullable List<String> state,
      @Nullable String lotId,
      Integer size,
      @Nullable String cursor) {
    var page =
        service.list(
            mfcId,
            state,
            kind == null ? null : kind.getValue(),
            lotId,
            size == null ? 50 : size,
            cursor);
    var out = new ListQualityIncidents200Response();
    out.setItems(page.items().stream().map(IncidentsController::dto).toList());
    out.setNextCursor(page.nextCursor());
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<QualityIncident> getQualityIncident(String incidentId) {
    return ResponseEntity.ok(dto(service.get(incidentId)));
  }

  @Override
  public ResponseEntity<QualityIncident> createQualityIncident(
      String idempotencyKey, String xSourceSystem, CreateQualityIncidentRequest body) {
    var v =
        service.create(
            idempotencyKey,
            xSourceSystem,
            body.getMfcId(),
            body.getKind().getValue(),
            body.getDescription(),
            body.getAffectedLots().stream()
                .map(l -> new IncidentService.NewLot(l.getLotId(), l.getQty()))
                .toList());
    return ResponseEntity.status(HttpStatus.CREATED).body(dto(v));
  }

  @Override
  public ResponseEntity<QualityIncident> closeQualityIncident(
      String incidentId,
      String idempotencyKey,
      String xSourceSystem,
      CloseQualityIncidentRequest body,
      @Nullable Integer xExpectedVersion) {
    return ResponseEntity.ok(
        dto(
            service.close(
                idempotencyKey, xSourceSystem, incidentId, xExpectedVersion, body.getReason())));
  }

  static QualityIncident dto(View v) {
    var i = v.incident();
    var out = new QualityIncident();
    out.setIncidentId(i.incidentId());
    out.setMfcId(i.mfcId());
    out.setKind(IncidentKind.fromValue(i.kind()));
    out.setState(QualityIncident.StateEnum.fromValue(i.state()));
    out.setOrigin(QualityIncident.OriginEnum.fromValue(i.origin()));
    out.setDescription(i.description());
    out.setOpenedBy(i.openedBy());
    out.setSourceEvent(i.sourceEvent());
    out.setSourceRef(i.sourceRef());
    out.setAffectedLots(
        v.lots().stream()
            .map(
                l -> {
                  var x = new QualityIncidentAffectedLotsInner();
                  x.setLotId(l.lotId());
                  x.setSku(l.sku());
                  x.setQty(l.qty());
                  x.setResolved(l.resolvedBy() != null);
                  x.setResolvedBy(l.resolvedBy());
                  return x;
                })
            .toList());
    out.setRemainingLotCount(v.remainingLots());
    out.setDecisionIds(v.decisionIds());
    out.setCaseId(i.caseId());
    out.setAffectedOrderCount(null); // 2차(R6)
    out.setVersion(i.version());
    out.setOpenedAt(time(i.openedAt()));
    out.setClosedAt(time(i.closedAt()));
    out.setClosedBy(i.closedBy());
    out.setCloseReason(i.closeReason());
    return out;
  }

  private static @Nullable OffsetDateTime time(@Nullable Instant t) {
    return t == null ? null : t.atOffset(ZoneOffset.UTC);
  }
}
