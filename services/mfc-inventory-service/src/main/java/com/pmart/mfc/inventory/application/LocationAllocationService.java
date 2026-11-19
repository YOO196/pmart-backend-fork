package com.pmart.mfc.inventory.application;

import com.pmart.common.web.PmartException;
import com.pmart.mfc.inventory.domain.LocationAllocator;
import com.pmart.mfc.inventory.domain.ReservationState;
import com.pmart.mfc.inventory.domain.StockUnit;
import com.pmart.mfc.inventory.infrastructure.AllocationStore;
import com.pmart.mfc.inventory.infrastructure.ReservationStore;
import com.pmart.mfc.inventory.infrastructure.ReservationStore.PickPart;
import com.pmart.mfc.inventory.infrastructure.ReservationStore.ReservationRow;
import com.pmart.mfc.inventory.infrastructure.StockStore;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예약의 위치 배정(IV-05·WK-10 위치 세분화, V11). R 을 바꾸지 않고 확정 조각의 남은 집품량을 선반 위치에 나눠 적는다.
 *
 * <ul>
 *   <li>배정: 피킹 시작 때 fulfillment 가 부른다. 위치 가용 = 선반 NORMAL·STORED − 조사 보류 − 다른 조각의 남은 배정. 고르는 규칙은
 *       {@link LocationAllocator}. 모자라면 할 수 있는 만큼(나머지는 미배정, 오류 아님).
 *   <li>집품: 배정된 칸에서 집으면 그 배정을 소진. 다른 칸에서 집으면 그 칸에 다른 주문 몫을 빼고 남는 게 있을 때만 허용하고(DEVIATION 기록), 이 조각의
 *       다른 배정을 그만큼 줄인다. 다른 주문 몫을 집으려 하면 409 LOCATION_ALLOCATED_TO_OTHER.
 *   <li>실사로 잠긴 칸(계획 잠금·mfc-location COUNT_LOCKED)은 배정하지 않는다(0.36.0). 이미 배정된 몫은 그대로 둔다.
 * </ul>
 *
 * 잠금 순서는 예약 행 → 조각 → 센터 요약 → LOT 요약(다른 재고 명령과 같다).
 */
@Service
public class LocationAllocationService {

  public static final String LOCATION_ALLOCATED_TO_OTHER = "LOCATION_ALLOCATED_TO_OTHER";

  private static final Logger log = LoggerFactory.getLogger(LocationAllocationService.class);

  /** 지시 위치 한 줄: 그 위치의 LOT 선반 잔량(보류 뺀 값)과 이 조각에 배정된 남은 양. */
  public record PlanLocation(String locationCode, int qty, int allocatedQty) {}

  /** 피킹 지시 조각 + 위치 배정. */
  public record PlanPart(
      ReservationService.PickPlanPart base,
      List<Map.Entry<String, Integer>> allocations,
      int unallocatedQty,
      List<PlanLocation> locations) {}

  public record Plan(ReservationRow reservation, List<PlanPart> parts) {}

  private final ReservationStore reservations;
  private final AllocationStore allocations;
  private final StockStore stock;
  private final ReservationService reservationService;
  private final Locations locations;
  private final Clock clock;

  public LocationAllocationService(
      ReservationStore reservations,
      AllocationStore allocations,
      StockStore stock,
      ReservationService reservationService,
      Locations locations,
      Clock clock) {
    this.reservations = reservations;
    this.allocations = allocations;
    this.stock = stock;
    this.reservationService = reservationService;
    this.locations = locations;
    this.clock = clock;
  }

  /** 피킹 지시(배정 포함). 배정된 위치가 앞(배정 순서), 나머지는 코드 순. */
  @Transactional(readOnly = true)
  public Plan plan(String reservationId) {
    var base = reservationService.pickPlan(reservationId);
    List<PlanPart> out = new java.util.ArrayList<>();
    for (var bp : base.parts()) {
      var p = bp.part();
      List<Map.Entry<String, Integer>> mine = new java.util.ArrayList<>();
      Map<String, Integer> mineByLoc = new java.util.LinkedHashMap<>();
      if (p.state() == ReservationState.CONFIRMED) {
        for (var a : allocations.active(p.partId())) {
          if (a.remaining() > 0) {
            mineByLoc.merge(a.locationCode(), a.remaining(), Integer::sum);
          }
        }
      }
      mineByLoc.forEach((k, v) -> mine.add(Map.entry(k, v)));
      int allocated = mineByLoc.values().stream().mapToInt(Integer::intValue).sum();
      int outstanding =
          p.state() == ReservationState.CONFIRMED ? Math.max(0, p.qty() - p.pickedQty()) : 0;
      Map<String, Integer> shelf = new java.util.LinkedHashMap<>();
      bp.locations().forEach(e -> shelf.put(e.getKey(), e.getValue()));
      List<PlanLocation> locs = new java.util.ArrayList<>();
      mineByLoc.forEach((k, v) -> locs.add(new PlanLocation(k, shelf.getOrDefault(k, 0), v)));
      shelf.forEach(
          (k, v) -> {
            if (!mineByLoc.containsKey(k)) {
              locs.add(new PlanLocation(k, v, 0));
            }
          });
      out.add(new PlanPart(bp, mine, Math.max(0, outstanding - allocated), locs));
    }
    return new Plan(base.reservation(), out);
  }

  /** 확정 예약의 미배정 남은 집품량을 배정. 다시 불러도 이미 배정된 양은 그대로(멱등). */
  @Transactional
  public void allocate(String reservationId) {
    ReservationRow r =
        reservations
            .lock(reservationId)
            .orElseThrow(
                () ->
                    PmartException.notFound(
                        ReservationService.RESERVATION_NOT_FOUND, "예약이 없습니다: " + reservationId));
    if (r.state() != ReservationState.CONFIRMED) {
      throw PmartException.invalidState(
          "예약 " + reservationId + " 는 " + r.state() + " 상태라 위치를 배정하지 않습니다(확정 예약만).");
    }
    List<PickPart> parts = reservations.pickParts(reservationId, true);
    lockStock(r.mfcId(), parts);
    Instant at = clock.instant();
    reservations.markPickingStarted(reservationId, at); // 이후 보류분은 결품 사가가(#5 안 C)
    int total = 0;
    for (PickPart p : parts) {
      if (p.state() != ReservationState.CONFIRMED) {
        continue;
      }
      int outstanding = p.qty() - p.pickedQty();
      int allocated =
          allocations.active(p.partId()).stream().mapToInt(AllocationStore.Alloc::remaining).sum();
      int need = outstanding - allocated;
      if (need <= 0) {
        continue;
      }
      Map<String, Integer> free = free(r.mfcId(), p.sku(), p.lotId());
      // 실사로 잠긴 칸(계획 잠금·COUNT_LOCKED)은 집품이 409 LOCATION_CLOSED 라 고르지 않는다(0.36.0) — 남은 칸으로 모자라면 미배정
      free.keySet().removeIf(loc -> locations.countLocked(r.mfcId(), loc));
      for (var e : LocationAllocator.plan(need, free)) {
        allocations.insert(
            p.partId(),
            reservationId,
            r.mfcId(),
            p.sku(),
            p.lotId(),
            e.getKey(),
            e.getValue(),
            0,
            "PLAN",
            at);
        total += e.getValue();
      }
    }
    if (total > 0) {
      log.info("예약 {} 위치 배정 {}개 ({})", reservationId, total, r.mfcId());
    }
  }

  /**
   * PICK 실적 한 건을 배정에 반영한다. 조각 잠금·집품량 증가 뒤, 같은 트랜잭션에서 호출.
   *
   * @param takes 조각 ID → 이번에 집은 양
   */
  void onPick(
      String mfcId,
      String reservationId,
      String fromLocation,
      Map<String, Integer> takes,
      List<PickPart> partsBefore,
      Instant at) {
    lockStock(mfcId, partsBefore);
    for (var t : takes.entrySet()) {
      PickPart p =
          partsBefore.stream().filter(x -> x.partId().equals(t.getKey())).findFirst().orElseThrow();
      int q = t.getValue();
      List<AllocationStore.Alloc> mine = allocations.active(p.partId());
      int mineHere =
          mine.stream()
              .filter(a -> a.locationCode().equals(fromLocation))
              .mapToInt(AllocationStore.Alloc::remaining)
              .sum();
      int othersHere =
          allocations.outstandingByLocation(mfcId, p.lotId()).getOrDefault(fromLocation, 0)
              - mineHere;
      int usable = usable(mfcId, p.sku(), p.lotId()).getOrDefault(fromLocation, 0);
      if (q > usable - othersHere) {
        throw new PmartException(
            LOCATION_ALLOCATED_TO_OTHER,
            HttpStatus.CONFLICT,
            fromLocation
                + " 의 "
                + p.lotId()
                + " 은 다른 주문 몫으로 배정돼 있습니다(이 칸에서 이 주문이 집을 수 있는 양 "
                + Math.max(0, usable - othersHere)
                + ") — 배정된 칸에서 집습니다.",
            false,
            List.of(new PmartException.Detail(p.sku(), LOCATION_ALLOCATED_TO_OTHER)));
      }
      int left = q;
      for (AllocationStore.Alloc a : mine) {
        if (left == 0) {
          break;
        }
        if (a.locationCode().equals(fromLocation) && a.remaining() > 0) {
          int use = Math.min(left, a.remaining());
          allocations.addPicked(a.allocationId(), use, at);
          left -= use;
        }
      }
      if (left > 0) {
        allocations.insert(
            p.partId(),
            reservationId,
            mfcId,
            p.sku(),
            p.lotId(),
            fromLocation,
            left,
            left,
            "DEVIATION",
            at);
        log.info(
            "예약 {} 조각 {} 배정 밖 칸 {} 에서 {}개 집품 — 다른 배정을 줄인다",
            reservationId,
            p.partId(),
            fromLocation,
            left);
      }
      allocations.trim(p.partId(), p.qty() - (p.pickedQty() + q), at);
    }
  }

  /** 위치 → 가용(선반 − 보류 − 남은 배정). */
  Map<String, Integer> free(String mfcId, String sku, String lotId) {
    Map<String, Integer> out = usable(mfcId, sku, lotId);
    allocations
        .outstandingByLocation(mfcId, lotId)
        .forEach((loc, n) -> out.computeIfPresent(loc, (k, v) -> v - n));
    return out;
  }

  /** 위치 → 이 LOT 의 선반 NORMAL·STORED − 조사 보류. */
  Map<String, Integer> usable(String mfcId, String sku, String lotId) {
    var holds = stock.openHolds(mfcId, sku);
    Map<String, Integer> out = new TreeMap<>();
    for (var u : stock.units(mfcId, sku)) {
      if (u.key().lotId().equals(lotId)
          && u.key().quality() == StockUnit.Quality.NORMAL
          && u.key().work() == StockUnit.Work.STORED
          && u.qty() > 0) {
        int n = u.qty() - holds.getOrDefault(u.key(), 0);
        if (n > 0) {
          out.merge(u.key().locationCode(), n, Integer::sum);
        }
      }
    }
    return out;
  }

  private void lockStock(String mfcId, List<PickPart> parts) {
    var skus = new TreeSet<String>();
    parts.forEach(p -> skus.add(p.sku()));
    if (skus.isEmpty()) {
      return;
    }
    stock.lockBalances(mfcId, skus);
    stock.lockLotBalances(mfcId, skus);
  }
}
