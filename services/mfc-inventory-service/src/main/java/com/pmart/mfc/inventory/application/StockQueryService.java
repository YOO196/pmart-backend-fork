package com.pmart.mfc.inventory.application;

import com.pmart.common.web.PmartException;
import com.pmart.mfc.inventory.domain.StockRules.Balance;
import com.pmart.mfc.inventory.infrastructure.SkuHandlingStore;
import com.pmart.mfc.inventory.infrastructure.SkuHandlingStore.HandlingRow;
import com.pmart.mfc.inventory.infrastructure.StockStore;
import com.pmart.mfc.inventory.infrastructure.StockStore.CommitRow;
import com.pmart.mfc.inventory.infrastructure.StockStore.EntryRow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 가용·원결과 조회(서비스 간). 조회 사본이며 성공 확정은 commitId/requestId 로 한다. */
@Service
public class StockQueryService {

  /** 가용 한 행. 원장 행이 없으면 수량 0, version 0. */
  /** shippableAvailable = 출하 가능(IV-1) — 적격 LOT 의 E − R(≤ A), 판정 불가면 null. */
  public record AvailabilityView(
      String sku, Balance balance, boolean handlingEnabled, @Nullable Integer shippableAvailable) {}

  public record CommitView(CommitRow commit, List<EntryRow> entries) {}

  /** 요청 원결과. 수량 변화가 없던 요청은 commit 이 null. */
  public record RequestView(String requestId, @Nullable CommitRow commit, Instant occurredAt) {}

  /**
   * 스냅샷 한 줄. NORMAL·STORED 줄만 allocated(위치 배정 남은 양)·held(조사 보류)·available(= qty − held − allocated,
   * 0 미만은 0)을 채운다(V11). 위치 배정 전 예약(LOT 단위)은 위치를 정할 근거가 없어 빼지 않는다.
   */
  public record SnapshotLine(
      StockStore.SnapshotRow row,
      @Nullable Integer allocated,
      @Nullable Integer held,
      @Nullable Integer pickable) {} // 칸에서 집을 수 있는 양 — 판매 가능량 아님(상품 판매 가능량은 availability A = E − R)

  private final StockStore stock;
  private final SkuHandlingStore handlings;
  private final com.pmart.mfc.inventory.infrastructure.AllocationStore allocations;
  private final ReservationService reservations;

  public StockQueryService(
      StockStore stock,
      SkuHandlingStore handlings,
      com.pmart.mfc.inventory.infrastructure.AllocationStore allocations,
      ReservationService reservations) {
    this.reservations = reservations;
    this.stock = stock;
    this.handlings = handlings;
    this.allocations = allocations;
  }

  @Transactional(readOnly = true)
  public List<SnapshotLine> snapshotLines(
      String mfcId,
      @Nullable String sku,
      @Nullable String locationCode,
      @Nullable String cursor,
      int limit) {
    var rows = stock.snapshot(mfcId, sku, locationCode, cursor, limit);
    List<String> keys = new ArrayList<>();
    Map<String, Map<com.pmart.mfc.inventory.domain.StockUnit.Key, Integer>> holdsBySku =
        new java.util.HashMap<>();
    for (var r : rows) {
      var k = r.unit().key();
      if (stored(k)) {
        keys.add(k.lotId() + "|" + k.locationCode());
        holdsBySku.computeIfAbsent(k.sku(), s -> stock.openHolds(mfcId, s));
      }
    }
    Map<String, Integer> alloc = allocations.outstanding(mfcId, keys);
    List<SnapshotLine> out = new ArrayList<>();
    for (var r : rows) {
      var k = r.unit().key();
      if (!stored(k)) {
        out.add(new SnapshotLine(r, null, null, null));
        continue;
      }
      int a = alloc.getOrDefault(k.lotId() + "|" + k.locationCode(), 0);
      int h = holdsBySku.get(k.sku()).getOrDefault(k, 0);
      out.add(new SnapshotLine(r, a, h, Math.max(0, r.unit().qty() - h - a)));
    }
    return out;
  }

  private static boolean stored(com.pmart.mfc.inventory.domain.StockUnit.Key k) {
    return k.quality() == com.pmart.mfc.inventory.domain.StockUnit.Quality.NORMAL
        && k.work() == com.pmart.mfc.inventory.domain.StockUnit.Work.STORED;
  }

  @Transactional(readOnly = true)
  public List<AvailabilityView> availability(String mfcId, List<String> skus) {
    return availability(mfcId, skus, null);
  }

  /** 가용 + 출하 가능(IV-1). deliveryDate 없으면 오늘(Asia/Seoul). */
  @Transactional(readOnly = true)
  public List<AvailabilityView> availability(
      String mfcId, List<String> skus, java.time.@Nullable LocalDate deliveryDate) {
    Map<String, Balance> balances = stock.balances(mfcId, skus);
    Map<String, HandlingRow> flags = handlings.findAll(mfcId, skus);
    java.time.LocalDate date =
        deliveryDate != null
            ? deliveryDate
            : java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
    Map<String, @Nullable Integer> shippable = reservations.shippable(mfcId, skus, date);
    List<AvailabilityView> out = new ArrayList<>();
    for (String s : skus) {
      HandlingRow h = flags.get(s);
      Balance b = balances.getOrDefault(s, Balance.empty(s));
      Integer sh = shippable.get(s);
      int a = b.available();
      out.add(
          new AvailabilityView(
              s, b, h != null && h.enabled(), sh == null ? null : Math.max(0, Math.min(sh, a))));
    }
    return out;
  }

  @Transactional(readOnly = true)
  public CommitView commit(String commitId) {
    CommitRow c =
        stock
            .commit(commitId)
            .orElseThrow(
                () -> PmartException.notFound("COMMIT_NOT_FOUND", "전기가 없습니다: " + commitId));
    return new CommitView(c, stock.entries(commitId));
  }

  /** 호출자(토큰 주체)가 보낸 요청의 원결과. 기록이 없으면 404 — 반영되지 않았다. */
  @Transactional(readOnly = true)
  public RequestView request(String requestId) {
    String actor = Callers.actor();
    Optional<String> source = stock.requestSource(requestId, actor);
    if (source.isEmpty()) {
      throw PmartException.notFound("REQUEST_NOT_FOUND", "기록된 요청이 없습니다: " + requestId);
    }
    Optional<CommitRow> c = stock.commitByRequest(source.get(), requestId);
    Instant at =
        c.map(CommitRow::occurredAt)
            .or(() -> stock.requestRecordedAt(source.get(), requestId))
            .orElseGet(Instant::now);
    return new RequestView(requestId, c.orElse(null), at);
  }

  /** 센터 재고 스냅샷(SCM·보충 조회, 서비스 호출). 단위 행 그대로 — 예약 R 은 LOT 단위라 줄에 나누지 않는다. */
  @org.springframework.transaction.annotation.Transactional(readOnly = true)
  public List<StockStore.SnapshotRow> snapshot(
      String mfcId,
      @Nullable String sku,
      @Nullable String locationCode,
      @Nullable String cursor,
      int limit) {
    return stock.snapshot(mfcId, sku, locationCode, cursor, limit);
  }
}
