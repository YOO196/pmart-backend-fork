package com.pmart.mfc.location.api;

import com.pmart.common.web.CommandResponse;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.location.api.generated.LocationsApi;
import com.pmart.mfc.location.api.generated.model.ChangeLocationStateRequest;
import com.pmart.mfc.location.api.generated.model.GetLocationDistances200Response;
import com.pmart.mfc.location.api.generated.model.GetLocationDistancesRequest;
import com.pmart.mfc.location.api.generated.model.GetLocationStateChange200Response;
import com.pmart.mfc.location.api.generated.model.ListLocations200Response;
import com.pmart.mfc.location.api.generated.model.ListLocations200ResponsePage;
import com.pmart.mfc.location.api.generated.model.ListPutawayCandidates200Response;
import com.pmart.mfc.location.api.generated.model.ListPutawayCandidates200ResponseAsOf;
import com.pmart.mfc.location.api.generated.model.ListPutawayCandidates200ResponseItemsInner;
import com.pmart.mfc.location.api.generated.model.Location;
import com.pmart.mfc.location.api.generated.model.LocationBody;
import com.pmart.mfc.location.api.generated.model.LocationBodyDimensions;
import com.pmart.mfc.location.api.generated.model.LocationState;
import com.pmart.mfc.location.api.generated.model.RetireLocation202Response;
import com.pmart.mfc.location.api.generated.model.TemperatureZone;
import com.pmart.mfc.location.api.generated.model.ZoneKind;
import com.pmart.mfc.location.application.LocationService;
import com.pmart.mfc.location.application.LocationService.Body;
import com.pmart.mfc.location.domain.LocationView;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 LocationsApi. 업무 규칙은 application/domain 에 두고 여기서는 요청·응답 변환만 한다. 폐지(DELETE)는 CLOSED·재고
 * 없음만(2026-11-08).
 */
@RestController
public class LocationsController implements LocationsApi {

  private final LocationService service;

  public LocationsController(LocationService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<Location> createLocation(
      String idempotencyKey, String xSourceSystem, LocationBody body) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(dto(service.create(idempotencyKey, xSourceSystem, body(body))));
  }

  @Override
  public ResponseEntity<Location> updateLocation(
      String locationId,
      String idempotencyKey,
      String xSourceSystem,
      LocationBody body,
      @Nullable Integer xExpectedVersion) {
    return ResponseEntity.ok(
        dto(
            service.update(
                idempotencyKey, xSourceSystem, locationId, body(body), xExpectedVersion)));
  }

  @Override
  public ResponseEntity<RetireLocation202Response> changeLocationState(
      String locationId,
      String idempotencyKey,
      String xSourceSystem,
      ChangeLocationStateRequest body,
      @Nullable Integer xExpectedVersion) {
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(
            command(
                service.changeState(
                    idempotencyKey,
                    xSourceSystem,
                    locationId,
                    body.getTargetState().getValue(),
                    body.getReason(),
                    body.getCountSessionId(),
                    xExpectedVersion)));
  }

  @Override
  public ResponseEntity<RetireLocation202Response> retireLocation(
      String locationId,
      String idempotencyKey,
      String xSourceSystem,
      @Nullable Integer xExpectedVersion) {
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(command(service.retire(idempotencyKey, xSourceSystem, locationId, xExpectedVersion)));
  }

  @Override
  public ResponseEntity<RetireLocation202Response> republishLocations(
      String idempotencyKey, String xSourceSystem, @Nullable String mfcId) {
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(command(service.republish(idempotencyKey, xSourceSystem, mfcId)));
  }

  @Override
  public ResponseEntity<GetLocationDistances200Response> getLocationDistances(
      GetLocationDistancesRequest body) {
    var t = service.distances(body.getMfcId(), body.getLocationCodes());
    var out = new GetLocationDistances200Response();
    out.setMethod(
        GetLocationDistances200Response.MethodEnum.fromValue(
            com.pmart.mfc.location.domain.Distances.METHOD));
    out.setLimitations(List.of(com.pmart.mfc.location.domain.Distances.WALLS_NOT_CONSIDERED));
    out.setCodes(t.codes());
    List<List<java.math.BigDecimal>> rows = new ArrayList<>();
    for (double[] r : t.meters()) {
      List<java.math.BigDecimal> row = new ArrayList<>();
      for (double d : r) {
        row.add(java.math.BigDecimal.valueOf(d));
      }
      rows.add(row);
    }
    out.setMeters(rows);
    out.setMissing(t.missing());
    List<com.pmart.mfc.location.api.generated.model.GetLocationDistances200ResponseAttributesInner>
        attrs = new ArrayList<>();
    for (LocationView v : t.views()) {
      var a =
          new com.pmart.mfc.location.api.generated.model
              .GetLocationDistances200ResponseAttributesInner();
      a.setLocationCode(v.locationCode());
      a.setZone(ZoneKind.fromValue(v.zone()));
      a.setTemperatureZone(TemperatureZone.fromValue(v.temperatureZone()));
      a.setAreaCode(v.areaCode());
      attrs.add(a);
    }
    out.setAttributes(attrs);
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<Location> getLocation(String locationId) {
    return ResponseEntity.ok(dto(service.get(locationId)));
  }

  @Override
  public ResponseEntity<ListLocations200Response> listLocations(
      String mfcId,
      @Nullable ZoneKind zone,
      @Nullable TemperatureZone temperatureZone,
      @Nullable LocationState state,
      Integer size,
      @Nullable String cursor) {
    int n = size == null ? 50 : size;
    var page =
        service.list(
            mfcId,
            zone == null ? null : zone.getValue(),
            temperatureZone == null ? null : temperatureZone.getValue(),
            state == null ? null : state.getValue(),
            n,
            cursor);
    var out = new ListLocations200Response();
    out.setItems(page.items().stream().map(LocationsController::dto).toList());
    var p = new ListLocations200ResponsePage();
    p.setNextCursor(page.nextCursor());
    p.setSize(page.items().size());
    out.setPage(p);
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<ListPutawayCandidates200Response> listPutawayCandidates(
      String mfcId, String sku, @Nullable TemperatureZone temperatureZone, @Nullable Integer qty) {
    var rows =
        service.putawayCandidates(
            mfcId, temperatureZone == null ? null : temperatureZone.getValue());
    List<ListPutawayCandidates200ResponseItemsInner> items = new ArrayList<>();
    int rank = 0;
    for (LocationView v : rows) {
      var i = new ListPutawayCandidates200ResponseItemsInner();
      i.setLocationId(v.locationId());
      i.setLocationCode(v.locationCode());
      i.setZone(ZoneKind.fromValue(v.zone()));
      i.setRemainingSpace(null);
      i.setRank(++rank);
      items.add(i);
    }
    var asOf = new ListPutawayCandidates200ResponseAsOf();
    asOf.setAsOf(time(Instant.now()));
    asOf.setFreshness(ListPutawayCandidates200ResponseAsOf.FreshnessEnum.CURRENT);
    var out = new ListPutawayCandidates200Response();
    out.setAsOf(asOf);
    out.setItems(items);
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<GetLocationStateChange200Response> getLocationStateChange(
      String locationId, String requestId) {
    var r = service.stateChange(locationId, requestId);
    var out = new GetLocationStateChange200Response();
    out.setRequestId(r.requestId());
    out.setStatus(GetLocationStateChange200Response.StatusEnum.APPLIED);
    out.setFromState(LocationState.fromValue(r.fromState()));
    out.setToState(LocationState.fromValue(r.toState()));
    out.setNewVersion(r.newVersion());
    return ResponseEntity.ok(out);
  }

  private static Body body(LocationBody b) {
    if (b.getLocationCode() == null || b.getLocationCode().isBlank()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "locationCode 가 비어 있습니다.");
    }
    LocationBodyDimensions d = b.getDimensions();
    return new Body(
        b.getMfcId(),
        b.getLocationCode().trim(),
        b.getAreaCode(),
        b.getZone().getValue(),
        b.getTemperatureZone().getValue(),
        d == null ? null : d.getWidthMm(),
        d == null ? null : d.getDepthMm(),
        d == null ? null : d.getHeightMm(),
        d == null ? null : d.getSlotCount(),
        d == null ? null : d.getMaxWeightG(),
        b.getPickSequence(),
        Boolean.TRUE.equals(b.getHighTurnover()),
        Boolean.TRUE.equals(b.getReserveStock()),
        b.getCategoryId() == null || b.getCategoryId().isBlank() ? null : b.getCategoryId().trim(),
        b.getPosition() == null ? null : b.getPosition().getxM().doubleValue(),
        b.getPosition() == null ? null : b.getPosition().getzM().doubleValue());
  }

  static Location dto(LocationView v) {
    var out = new Location();
    out.setLocationId(v.locationId());
    out.setMfcId(v.mfcId());
    out.setLocationCode(v.locationCode());
    out.setAreaCode(v.areaCode());
    out.setZone(ZoneKind.fromValue(v.zone()));
    out.setTemperatureZone(TemperatureZone.fromValue(v.temperatureZone()));
    if (v.widthMm() != null
        || v.depthMm() != null
        || v.heightMm() != null
        || v.slotCount() != null
        || v.maxWeightG() != null) {
      var d = new LocationBodyDimensions();
      d.setWidthMm(v.widthMm());
      d.setDepthMm(v.depthMm());
      d.setHeightMm(v.heightMm());
      d.setSlotCount(v.slotCount());
      d.setMaxWeightG(v.maxWeightG());
      out.setDimensions(d);
    }
    out.setPickSequence(v.pickSequence());
    out.setHighTurnover(v.highTurnover());
    out.setReserveStock(v.reserveStock());
    out.setCategoryId(v.categoryId());
    if (v.posXM() != null && v.posZM() != null) {
      var pos = new com.pmart.mfc.location.api.generated.model.FloorPosition();
      pos.setxM(java.math.BigDecimal.valueOf(v.posXM()));
      pos.setzM(java.math.BigDecimal.valueOf(v.posZM()));
      out.setPosition(pos);
    }
    out.setVersion(v.version());
    out.setState(LocationState.fromValue(v.state()));
    out.setStateReason(v.stateReason());
    out.setUpdatedAt(time(v.updatedAt()));
    return out;
  }

  private static RetireLocation202Response command(CommandResponse c) {
    var d = new RetireLocation202Response();
    d.setStatus(RetireLocation202Response.StatusEnum.fromValue(c.status().name()));
    d.setRequestId(c.requestId());
    d.setResultId(c.resultId());
    d.setAppliedQty(c.appliedQty());
    d.setVersion(c.version());
    d.setRetryable(c.retryable());
    d.setNextAction(c.nextAction());
    d.setOccurredAt(time(c.occurredAt()));
    return d;
  }

  static OffsetDateTime time(Instant at) {
    return at.atOffset(ZoneOffset.UTC);
  }
}
