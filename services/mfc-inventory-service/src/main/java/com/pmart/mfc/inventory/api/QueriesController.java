package com.pmart.mfc.inventory.api;

import com.pmart.mfc.inventory.api.generated.QueriesApi;
import com.pmart.mfc.inventory.api.generated.model.Availability;
import com.pmart.mfc.inventory.api.generated.model.CenterStock;
import com.pmart.mfc.inventory.api.generated.model.CenterStockLotsInner;
import com.pmart.mfc.inventory.api.generated.model.CenterStockUnitsInner;
import com.pmart.mfc.inventory.api.generated.model.Commit;
import com.pmart.mfc.inventory.api.generated.model.CommitEntriesInner;
import com.pmart.mfc.inventory.api.generated.model.GetAvailability200Response;
import com.pmart.mfc.inventory.api.generated.model.GetAvailability200ResponseAsOf;
import com.pmart.mfc.inventory.api.generated.model.GetCenterStocks200Response;
import com.pmart.mfc.inventory.api.generated.model.GetRequestResult200Response;
import com.pmart.mfc.inventory.api.generated.model.InventoryApplyStatus;
import com.pmart.mfc.inventory.api.generated.model.QualityState;
import com.pmart.mfc.inventory.api.generated.model.WorkState;
import com.pmart.mfc.inventory.application.CorrectionService;
import com.pmart.mfc.inventory.application.StockQueryService;
import com.pmart.mfc.inventory.infrastructure.StockStore;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 QueriesApi — 가용(A = E − R, 원장 행 없음 = 0, 취급 여부 함께), 전기 조회(commitId), 원결과 조회(requestId). 스냅샷은
 * 인터페이스 default 의 501.
 */
@RestController
public class QueriesController implements QueriesApi {

  private final StockQueryService queries;
  private final CorrectionService corrections;
  private final com.pmart.mfc.inventory.application.StockLedgerService ledger;

  public QueriesController(
      StockQueryService queries,
      CorrectionService corrections,
      com.pmart.mfc.inventory.application.StockLedgerService ledger) {
    this.queries = queries;
    this.corrections = corrections;
    this.ledger = ledger;
  }

  /** 센터 재고 목록(MB-1, 0.26.0) — 0.33.0 시뮬레이터 R-15 위치 앞부분·구역·온도대 거르기, asOf. */
  @Override
  public ResponseEntity<com.pmart.mfc.inventory.api.generated.model.ListStockUnits200Response>
      listStockUnits(
          String mfcId,
          @Nullable String sku,
          @Nullable String locationCode,
          @Nullable String lotId,
          com.pmart.mfc.inventory.api.generated.model.@Nullable QualityState qualityState,
          com.pmart.mfc.inventory.api.generated.model.@Nullable WorkState workState,
          @Nullable String locationPrefix,
          @Nullable String zone,
          @Nullable String temperatureZone,
          Integer size,
          @Nullable String cursor) {
    int limit = size == null ? 50 : size;
    Instant asOf = Instant.now();
    var rows =
        queries.centerUnits(
            mfcId,
            new StockStore.CenterUnitFilter(
                sku,
                locationCode,
                locationPrefix,
                lotId,
                qualityState == null ? null : qualityState.getValue(),
                workState == null ? null : workState.getValue(),
                zone,
                temperatureZone),
            limit + 1,
            cursor);
    boolean more = rows.size() > limit;
    var page = more ? rows.subList(0, limit) : rows;
    var out = new com.pmart.mfc.inventory.api.generated.model.ListStockUnits200Response();
    out.setItems(
        page.stream()
            .map(
                r -> {
                  var x =
                      new com.pmart.mfc.inventory.api.generated.model
                          .ListStockUnits200ResponseItemsInner();
                  x.setLocationCode(r.locationCode());
                  x.setSku(r.sku());
                  x.setProductName(r.productName());
                  x.setLotId(r.lotId());
                  x.setLotNo(r.lotNo());
                  x.setExpiryDate(r.expiryDate());
                  x.setQualityState(QualityState.fromValue(r.qualityState()));
                  x.setWorkState(WorkState.fromValue(r.workState()));
                  x.setQty(r.qty());
                  x.setZone(r.zone());
                  x.setTemperatureZone(r.temperatureZone());
                  return x;
                })
            .toList());
    out.setAsOf(SkuHandlingsController.time(asOf));
    out.setNextCursor(more ? StockQueryService.cursorOf(page.get(page.size() - 1)) : null);
    return ResponseEntity.ok(out);
  }

  /**
   * 열린 분실 조사 보류 목록(0.38.0) — UNSTOWED 줄은 정정 holdResolutions 한 줄(CORRECTION), STORED 줄은 실사(COUNT).
   */
  @Override
  public ResponseEntity<com.pmart.mfc.inventory.api.generated.model.ListStockHolds200Response>
      listStockHolds(
          String mfcId,
          @Nullable String locationCode,
          @Nullable String sku,
          @Nullable WorkState workState,
          Integer size,
          @Nullable String cursor) {
    int limit = size == null ? 50 : size;
    Instant asOf = Instant.now();
    var rows =
        queries.openHolds(
            mfcId,
            locationCode,
            sku,
            workState == null ? null : workState.getValue(),
            limit + 1,
            cursor);
    boolean more = rows.size() > limit;
    var page = more ? rows.subList(0, limit) : rows;
    var out = new com.pmart.mfc.inventory.api.generated.model.ListStockHolds200Response();
    out.setAsOf(SkuHandlingsController.time(asOf));
    out.setItems(page.stream().map(QueriesController::hold).toList());
    out.setNextCursor(more ? StockQueryService.cursorOf(page.get(page.size() - 1).unit()) : null);
    return ResponseEntity.ok(out);
  }

  private static com.pmart.mfc.inventory.api.generated.model.StockHold hold(
      StockQueryService.HoldView v) {
    var u = v.unit();
    var k = u.key();
    var x = new com.pmart.mfc.inventory.api.generated.model.StockHold();
    x.setLocationCode(k.locationCode());
    x.setSku(k.sku());
    x.setProductName(u.productName());
    x.setLotId(k.lotId());
    x.setLotNo(u.lotNo());
    x.setExpiryDate(u.expiryDate());
    x.setQualityState(QualityState.fromValue(k.quality().name()));
    x.setWorkState(WorkState.fromValue(k.work().name()));
    x.setHeldQty(u.heldQty());
    x.setUnitQty(u.unitQty());
    x.setZone(u.zone());
    // 정정 holdResolutions 는 NORMAL·UNSTOWED 만(CorrectionService.holdKey) — 선반 보류는 실사가 푼다
    x.setResolution(
        k.quality() == com.pmart.mfc.inventory.domain.StockUnit.Quality.NORMAL
                && k.work() == com.pmart.mfc.inventory.domain.StockUnit.Work.UNSTOWED
            ? com.pmart.mfc.inventory.api.generated.model.StockHold.ResolutionEnum.CORRECTION
            : com.pmart.mfc.inventory.api.generated.model.StockHold.ResolutionEnum.COUNT);
    x.setFirstHeldAt(SkuHandlingsController.time(u.firstHeldAt()));
    x.setSources(
        v.sources().stream()
            .map(
                s -> {
                  var y = new com.pmart.mfc.inventory.api.generated.model.StockHoldSourcesInner();
                  y.setReportId(s.reportId());
                  y.setKind(s.kind());
                  y.setReason(s.reason());
                  y.setQty(s.qty());
                  y.setHeldAt(SkuHandlingsController.time(s.heldAt()));
                  return y;
                })
            .toList());
    return x;
  }

  @Override
  public ResponseEntity<GetAvailability200Response> getAvailability(
      String mfcId, List<String> sku, java.time.@Nullable LocalDate deliveryDate) {
    List<Availability> items =
        queries.availability(mfcId, sku.stream().distinct().toList(), deliveryDate).stream()
            .map(
                v -> {
                  var a = new Availability();
                  a.setSku(v.sku());
                  a.setOnHand(v.balance().onHand());
                  a.setQuarantined(v.balance().quarantined());
                  a.setSellable(v.balance().sellable());
                  a.setReserved(v.balance().reserved());
                  a.setAvailable(v.balance().available());
                  a.setShippableAvailable(v.shippableAvailable());
                  a.setHandlingEnabled(v.handlingEnabled());
                  a.setVersion((int) v.balance().version());
                  return a;
                })
            .toList();
    var asOf = new GetAvailability200ResponseAsOf();
    asOf.setAsOf(SkuHandlingsController.time(Instant.now()));
    asOf.setFreshness(GetAvailability200ResponseAsOf.FreshnessEnum.CURRENT);
    var out = new GetAvailability200Response();
    out.setMfcId(mfcId);
    out.setAsOf(asOf);
    out.setItems(items);
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<com.pmart.mfc.inventory.api.generated.model.GetLotAvailability200Response>
      getLotAvailability(
          String mfcId,
          String sku,
          List<String> lotId,
          java.time.@Nullable LocalDate deliveryDate) {
    var out = new com.pmart.mfc.inventory.api.generated.model.GetLotAvailability200Response();
    out.setMfcId(mfcId);
    out.setSku(sku);
    out.setDeliveryDate(deliveryDate);
    out.setItems(
        queries
            .lotAvailability(mfcId, sku, lotId.stream().distinct().toList(), deliveryDate)
            .stream()
            .map(
                v -> {
                  var a = new com.pmart.mfc.inventory.api.generated.model.LotAvailability();
                  a.setLotId(v.lotId());
                  a.setFound(v.found());
                  a.setLotNo(v.lotNo());
                  a.setExpiryDate(v.expiryDate());
                  a.setAvailable(v.available());
                  a.setShippableAvailable(v.shippableAvailable());
                  return a;
                })
            .toList());
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<Commit> getCommit(String commitId) {
    var v = queries.commit(commitId);
    var c = v.commit();
    var out = new Commit();
    out.setCommitId(c.commitId());
    out.setRequestId(c.requestId());
    out.setCommitType(Commit.CommitTypeEnum.fromValue(c.commitType()));
    out.setMfcId(c.mfcId());
    out.setSourceSystem(c.sourceSystem());
    out.setSourceRef(c.sourceRef());
    out.setAppliedQty(c.appliedQty());
    out.setOccurredAt(SkuHandlingsController.time(c.occurredAt()));
    out.setEntries(
        v.entries().stream()
            .map(
                e -> {
                  var d = new CommitEntriesInner();
                  d.setSku(e.sku());
                  d.setLotId(e.lotId());
                  d.setLocationCode(e.locationCode());
                  d.setQualityState(e.qualityState());
                  d.setWorkState(e.workState());
                  d.setSymbol(CommitEntriesInner.SymbolEnum.fromValue(e.symbol()));
                  d.setDelta(e.delta());
                  d.setBalanceVersion((int) e.balanceVersion());
                  return d;
                })
            .toList());
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<GetRequestResult200Response> getRequestResult(String requestId) {
    var v = queries.request(requestId);
    var out = new GetRequestResult200Response();
    out.setRequestId(v.requestId());
    out.setApplyStatus(InventoryApplyStatus.APPLIED);
    out.setCommitId(v.commit() == null ? null : v.commit().commitId());
    out.setAppliedQty(v.commit() == null ? 0 : v.commit().appliedQty());
    out.setOccurredAt(SkuHandlingsController.time(v.occurredAt()));
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<CenterStock> getCenterStock(String mfcId, String sku) {
    return ResponseEntity.ok(centerStock(mfcId, sku, corrections.stock(mfcId, sku)));
  }

  /** 센터 재고 상세 여러 SKU(시뮬레이터 R-16, 0.33.0) — 요청 순서, 같은 값은 한 번. */
  @Override
  public ResponseEntity<GetCenterStocks200Response> getCenterStocks(
      String mfcId, List<String> sku) {
    Instant asOf = Instant.now();
    var views = corrections.stocks(mfcId, sku);
    var out = new GetCenterStocks200Response();
    out.setMfcId(mfcId);
    out.setAsOf(SkuHandlingsController.time(asOf));
    out.setItems(
        views.entrySet().stream().map(e -> centerStock(mfcId, e.getKey(), e.getValue())).toList());
    return ResponseEntity.ok(out);
  }

  private static CenterStock centerStock(
      String mfcId, String sku, CorrectionService.CenterStockView v) {
    var out = new CenterStock();
    out.setMfcId(mfcId);
    out.setSku(sku);
    out.setProductName(v.sku() == null ? null : v.sku().productName());
    out.setBarcodes(
        v.sku() == null
            ? new java.util.ArrayList<>()
            : new java.util.ArrayList<>(v.sku().barcodes()));
    out.setLots(
        v.lots().stream()
            .map(
                b -> {
                  var l = new CenterStockLotsInner();
                  l.setLotId(b.lotId());
                  l.setLotNo(b.lotNo());
                  l.setTracking(CenterStockLotsInner.TrackingEnum.fromValue(b.tracking()));
                  l.setExpiryDate(b.expiryDate());
                  l.setManufactureDate(b.manufactureDate());
                  l.setOnHand(b.onHand());
                  l.setQuarantined(b.quarantined());
                  l.setSellable(b.sellable());
                  l.setReserved(b.reserved());
                  l.setAvailable(b.available());
                  return l;
                })
            .toList());
    out.setUnits(
        v.units().stream()
            .map(
                u -> {
                  var d = new CenterStockUnitsInner();
                  d.setLocationCode(u.key().locationCode());
                  d.setLotId(u.key().lotId());
                  d.setQualityState(QualityState.fromValue(u.key().quality().name()));
                  d.setWorkState(WorkState.fromValue(u.key().work().name()));
                  d.setQty(u.qty());
                  return d;
                })
            .toList());
    return out;
  }

  @Override
  public ResponseEntity<com.pmart.mfc.inventory.api.generated.model.GetSnapshot200Response>
      getSnapshot(
          String mfcId,
          @Nullable String sku,
          @Nullable String locationCode,
          @Nullable Integer size,
          @Nullable String cursor) {
    int n = size == null ? 50 : size;
    var rows = queries.snapshotLines(mfcId, sku, locationCode, cursor, n + 1);
    boolean more = rows.size() > n;
    var page = more ? rows.subList(0, n) : rows;
    var out = new com.pmart.mfc.inventory.api.generated.model.GetSnapshot200Response();
    var asOf = new com.pmart.mfc.inventory.api.generated.model.GetAvailability200ResponseAsOf();
    asOf.setAsOf(SkuHandlingsController.time(java.time.Instant.now()));
    asOf.setFreshness(
        com.pmart.mfc.inventory.api.generated.model.GetAvailability200ResponseAsOf.FreshnessEnum
            .CURRENT);
    out.setAsOf(asOf);
    out.setItems(
        page.stream()
            .map(
                line -> {
                  var r = line.row();
                  var b = new com.pmart.mfc.inventory.api.generated.model.Balance();
                  var k = r.unit().key();
                  b.setMfcId(mfcId);
                  b.setLocationCode(k.locationCode());
                  b.setSku(k.sku());
                  b.setLotId(k.lotId());
                  b.setExpiryDate(r.expiryDate());
                  b.setQualityState(QualityState.fromValue(k.quality().name()));
                  b.setWorkState(WorkState.fromValue(k.work().name()));
                  b.setQty(r.unit().qty());
                  b.setVersion((int) r.unit().version());
                  b.setAllocatedQty(line.allocated());
                  b.setHeldQty(line.held());
                  b.setPickableQty(line.pickable());
                  return b;
                })
            .toList());
    var p = new com.pmart.mfc.inventory.api.generated.model.GetSnapshot200ResponsePage();
    p.setSize(page.size());
    if (more) {
      var k = page.get(page.size() - 1).row().unit().key();
      p.setNextCursor(
          k.sku() + "|" + k.lotId() + "|" + k.locationCode() + "|" + k.quality() + "|" + k.work());
    }
    out.setPage(p);
    return ResponseEntity.ok(out);
  }

  /** 재고 이력(0.48.0) — SKU 하나의 확정 단위 최신순. */
  @Override
  public ResponseEntity<com.pmart.mfc.inventory.api.generated.model.ListStockLedger200Response>
      listStockLedger(
          String mfcId,
          String sku,
          @Nullable String lotId,
          @Nullable String locationCode,
          @Nullable List<String> cause,
          java.time.@Nullable OffsetDateTime from,
          java.time.@Nullable OffsetDateTime to,
          Integer size,
          @Nullable String cursor,
          @Nullable String orderId) {
    var page =
        ledger.ledger(
            mfcId,
            sku,
            lotId,
            locationCode,
            cause,
            from == null ? null : from.toInstant(),
            to == null ? null : to.toInstant(),
            size == null ? 50 : size,
            cursor,
            orderId);
    var out = new com.pmart.mfc.inventory.api.generated.model.ListStockLedger200Response();
    out.setMfcId(mfcId);
    out.setSku(sku);
    out.setProductName(page.productName());
    out.setCurrent(totals(page.current()));
    out.setNextCursor(page.nextCursor());
    out.setAsOf(java.time.OffsetDateTime.ofInstant(page.asOf(), java.time.ZoneOffset.UTC));
    out.setItems(
        page.items().stream()
            .map(
                c -> {
                  var x = new com.pmart.mfc.inventory.api.generated.model.StockLedgerCommit();
                  x.setCommitId(c.commitId());
                  x.setCause(c.cause());
                  x.setOccurredAt(
                      java.time.OffsetDateTime.ofInstant(c.occurredAt(), java.time.ZoneOffset.UTC));
                  x.setSourceSystem(c.sourceSystem());
                  x.setSourceRef(c.sourceRef());
                  x.setOrderId(c.orderId());
                  x.setActorOperatorId(c.actorOperatorId());
                  var d = new com.pmart.mfc.inventory.api.generated.model.StockLedgerCommitDelta();
                  d.setH(c.delta().h());
                  d.setQ(c.delta().q());
                  d.setE(c.delta().e());
                  d.setR(c.delta().r());
                  x.setDelta(d);
                  x.setAfter(totals(c.after()));
                  x.setEntries(
                      c.entries().stream()
                          .map(
                              e -> {
                                var y =
                                    new com.pmart.mfc.inventory.api.generated.model
                                        .StockLedgerCommitEntriesInner();
                                y.setSymbol(
                                    com.pmart.mfc.inventory.api.generated.model
                                        .StockLedgerCommitEntriesInner.SymbolEnum.fromValue(
                                        e.symbol()));
                                y.setDelta(e.delta());
                                y.setLotId(e.lotId());
                                y.setLotNo(e.lotNo());
                                y.setExpiryDate(e.expiryDate());
                                y.setLocationCode(e.locationCode());
                                y.setQualityState(e.qualityState());
                                y.setWorkState(e.workState());
                                return y;
                              })
                          .toList());
                  return x;
                })
            .toList());
    return ResponseEntity.ok(out);
  }

  private static com.pmart.mfc.inventory.api.generated.model.StockLedgerTotals totals(
      com.pmart.mfc.inventory.infrastructure.StockLedgerStore.Totals t) {
    var x = new com.pmart.mfc.inventory.api.generated.model.StockLedgerTotals();
    x.setOnHandQty(t.h());
    x.setQuarantinedQty(t.q());
    x.setSellableQty(t.e());
    x.setReservedQty(t.r());
    x.setAvailableQty(t.available());
    return x;
  }
}
