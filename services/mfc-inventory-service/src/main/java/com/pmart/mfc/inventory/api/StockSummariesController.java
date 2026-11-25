package com.pmart.mfc.inventory.api;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.inventory.api.generated.StockApi;
import com.pmart.mfc.inventory.api.generated.model.GetStockSummaries200Response;
import com.pmart.mfc.inventory.api.generated.model.GetStockSummaries200ResponseItemsInner;
import com.pmart.mfc.inventory.api.generated.model.ListStockSummarySkus200Response;
import com.pmart.mfc.inventory.api.generated.model.ListStockSummarySkus200ResponseItemsInner;
import com.pmart.mfc.inventory.api.generated.model.LowStockBasisView;
import com.pmart.mfc.inventory.api.generated.model.LowStockBasisViewPoliciesInner;
import com.pmart.mfc.inventory.api.generated.model.PutLowStockBasis200Response;
import com.pmart.mfc.inventory.api.generated.model.PutLowStockBasisRequest;
import com.pmart.mfc.inventory.application.StockSummaryService;
import com.pmart.mfc.inventory.infrastructure.LowStockStore;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 센터별 품절 상품 수(0.28.0, 요청 IV-5) + 곧 품절(0.47.0, LS-1~2). */
@RestController
public class StockSummariesController implements StockApi {

  private final StockSummaryService service;

  public StockSummariesController(StockSummaryService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<GetStockSummaries200Response> getStockSummaries(@Nullable String mfcId) {
    var out = new GetStockSummaries200Response();
    out.setItems(
        service.summaries(mfcId).stream()
            .map(
                r -> {
                  var x = new GetStockSummaries200ResponseItemsInner();
                  x.setMfcId(r.summary().mfcId());
                  x.setHandledSkuCount(r.summary().handled());
                  x.setSoldOutSkuCount(r.summary().soldOut());
                  x.setLowStockSkuCount(r.lowStock());
                  x.setLowStockUnknownSkuCount(r.lowStockUnknown());
                  x.setLowStockBasis(view(r.basis()));
                  return x;
                })
            .toList());
    out.setAsOf(OffsetDateTime.now(ZoneOffset.UTC));
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<ListStockSummarySkus200Response> listStockSummarySkus(
      String mfcId, String kind, Integer size, @Nullable String cursor) {
    LowStockStore.Kind k;
    try {
      k = LowStockStore.Kind.valueOf(kind);
    } catch (IllegalArgumentException e) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED, "kind 는 LOW_STOCK·SOLD_OUT·LOW_STOCK_UNKNOWN 입니다.");
    }
    int n = size == null ? 50 : size;
    var r = service.skus(mfcId, k, n, cursor);
    var out = new ListStockSummarySkus200Response();
    out.setMfcId(mfcId);
    out.setKind(ListStockSummarySkus200Response.KindEnum.fromValue(k.name()));
    out.setTotalCount(r.page().total());
    int next = r.offset() + r.page().items().size();
    out.setNextCursor(next < r.page().total() ? Integer.toString(next) : null);
    out.setLowStockBasis(view(r.basis()));
    out.setItems(
        r.page().items().stream()
            .map(
                s -> {
                  var x = new ListStockSummarySkus200ResponseItemsInner();
                  x.setSku(s.sku());
                  x.setProductId(s.productId());
                  x.setProductName(s.productName());
                  x.setStorageType(s.storageType());
                  x.setAvailableQty(s.available());
                  x.setDailyQty(dec(s.dailyQty()));
                  x.setSafetyQty(dec(s.safetyQty()));
                  x.setCoverDays(
                      k == LowStockStore.Kind.LOW_STOCK && s.dailyQty() != null && s.dailyQty() > 0
                          ? dec((double) s.available() / s.dailyQty())
                          : null);
                  x.setUnknownReason(s.unknownReason());
                  return x;
                })
            .toList());
    out.setAsOf(OffsetDateTime.ofInstant(r.asOf(), ZoneOffset.UTC));
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<PutLowStockBasis200Response> putLowStockBasis(
      String mfcId, String idempotencyKey, String xSourceSystem, PutLowStockBasisRequest body) {
    List<LowStockStore.Policy> policies =
        body.getPolicies().stream()
            .map(
                p ->
                    new LowStockStore.Policy(
                        p.getStorageType().getValue(),
                        p.getSafetyDays(),
                        p.getHistoryDays(),
                        p.getMinHistoryDays(),
                        p.getAverageWindowDays()))
            .toList();
    Map<String, BigDecimal> daily = new LinkedHashMap<>();
    body.getDailyQty().forEach(d -> daily.merge(d.getSku(), d.getQty(), BigDecimal::add));
    boolean applied =
        service.putBasis(
            new LowStockStore.Basis(
                mfcId, body.getCalculatedAt().toInstant(), body.getSalesThrough(), policies),
            daily);
    var out = new PutLowStockBasis200Response();
    out.setApplied(applied);
    return ResponseEntity.ok(out);
  }

  private com.pmart.mfc.inventory.application.LotStockPublisher lots;
  private java.time.Clock clock;

  @org.springframework.beans.factory.annotation.Autowired
  void setLots(com.pmart.mfc.inventory.application.LotStockPublisher lots, java.time.Clock clock) {
    this.lots = lots;
    this.clock = clock;
  }

  /** LOT 지금 모습 다시 알리기(0.50.0) — 재고는 안 바꿈, 같은 키도 다시 낸다(구독자가 같은 version 무시). */
  @Override
  public ResponseEntity<com.pmart.mfc.inventory.api.generated.model.RepublishLotStocks200Response>
      republishLotStocks(String idempotencyKey, String xSourceSystem, @Nullable String mfcId) {
    return ResponseEntity.ok(
        new com.pmart.mfc.inventory.api.generated.model.RepublishLotStocks200Response()
            .publishedCount(
                lots.republish(mfcId == null || mfcId.isBlank() ? null : mfcId, clock.instant()))
            .retentionDays((int) lots.retention().toDays()));
  }

  private static @Nullable LowStockBasisView view(LowStockStore.@Nullable Basis b) {
    if (b == null) {
      return null;
    }
    var v = new LowStockBasisView();
    v.setSalesThrough(b.salesThrough());
    v.setCalculatedAt(OffsetDateTime.ofInstant(b.calculatedAt(), ZoneOffset.UTC));
    v.setPolicies(
        b.policies().stream()
            .map(
                p -> {
                  var x = new LowStockBasisViewPoliciesInner();
                  x.setStorageType(p.storageType());
                  x.setSafetyDays(p.safetyDays());
                  x.setHistoryDays(p.historyDays());
                  x.setMinHistoryDays(p.minHistoryDays());
                  x.setAverageWindowDays(p.averageWindowDays());
                  return x;
                })
            .toList());
    // 정책이 있는 온도대의 safetyDays·averageWindowDays 가 모두 같으면 한 줄로
    List<LowStockStore.Policy> set =
        b.policies().stream().filter(p -> p.safetyDays() != null).toList();
    boolean uniform =
        !set.isEmpty()
            && set.stream()
                    .map(p -> p.safetyDays().stripTrailingZeros() + "/" + p.averageWindowDays())
                    .distinct()
                    .count()
                == 1;
    v.setUniform(uniform);
    v.setSafetyDays(uniform ? Objects.requireNonNull(set.get(0).safetyDays()) : null);
    v.setAverageWindowDays(uniform ? set.get(0).averageWindowDays() : null);
    return v;
  }

  private static @Nullable BigDecimal dec(@Nullable Double v) {
    return v == null ? null : BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
  }
}
