package com.pmart.mfc.inventory.domain;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * 재고 위치 이동 배정 판정(IV-03). 순수 계산. 사용자 결정(2026-11-08, 마트 진열대처럼):
 *
 * <ul>
 *   <li>옮길 수 있는 양 = 출발 칸의 집을 수 있는 양(정상·적치 − 보류 − 다른 주문 칸 배정)이고 LOT 가용(A)까지 — 이동 중 물건은 판매 가능에서 빠지므로
 *       예약(R)이 E 를 넘지 않게.
 *   <li>칸에 여러 상품 가능. 카테고리 칸에는 그 카테고리(또는 하위) 상품만, 카테고리 없는 칸 = 기타(아무 상품 — 세일 상품 등).
 *   <li>출하 기준을 못 맞추는 LOT 은 카테고리 칸에 못 둔다(기타 칸으로 빨리 뺀다).
 *   <li>같은 상품은 한 자리(고회전 선반 제외): 도착 칸이 고회전이 아니면, 출발·도착 말고 그 상품이 있는(또는 들어올) 고회전 아닌 칸이 없어야 하고, 출발 칸도
 *       고회전이 아니면 출발 칸에 남는 양이 없어야 한다(나눠 두기 금지).
 *   <li>용량: 도착 칸의 그 상품(지금 + 들어올 예정 + 이번) ≤ 칸 용량.
 * </ul>
 */
public final class MoveRules {

  private MoveRules() {}

  public static final String MOVE_QTY_EXCEEDS_PICKABLE = "MOVE_QTY_EXCEEDS_PICKABLE";
  public static final String MOVE_QTY_EXCEEDS_AVAILABLE = "MOVE_QTY_EXCEEDS_AVAILABLE";
  public static final String CATEGORY_MISMATCH = "CATEGORY_MISMATCH";
  public static final String SKU_CATEGORY_UNKNOWN = "SKU_CATEGORY_UNKNOWN";
  public static final String LOT_NOT_SHIPPABLE = "LOT_NOT_SHIPPABLE";
  public static final String SKU_ALREADY_SHELVED = "SKU_ALREADY_SHELVED";
  public static final String SKU_SPLIT_NOT_ALLOWED = "SKU_SPLIT_NOT_ALLOWED";
  public static final String LOCATION_CAPACITY_EXCEEDED = "LOCATION_CAPACITY_EXCEEDED";
  public static final String SAME_LOCATION = "SAME_LOCATION";

  /** 상품 카테고리 경로 중 하나라도 그 칸 카테고리를 지나는가(경로 마디 = 카테고리 ID). */
  public static boolean inCategory(List<String> skuPaths, String shelfCategory) {
    for (String path : skuPaths) {
      for (String seg : path.split("/")) {
        if (seg.equals(shelfCategory)) {
          return true;
        }
      }
    }
    return false;
  }

  /** 보충재고 칸이 아닌 도착 칸(예전 호출). */
  public static @Nullable String rejection(
      String from,
      String to,
      boolean fromHighTurnover,
      boolean toHighTurnover,
      int qty,
      int pickableAtFrom,
      int lotAvailable,
      @Nullable String toCategory,
      @Nullable List<String> skuPaths,
      @Nullable Boolean lotShippable,
      Map<String, Boolean> skuShelves,
      int remainingAtFromAfter,
      int destQtyAfter,
      int capacity) {
    return rejection(
        from,
        to,
        fromHighTurnover,
        toHighTurnover,
        false,
        qty,
        pickableAtFrom,
        lotAvailable,
        toCategory,
        skuPaths,
        lotShippable,
        skuShelves,
        remainingAtFromAfter,
        destQtyAfter,
        capacity);
  }

  /**
   * 보충재고 칸(R-30, 2026-11-21): 고회전 칸처럼 한 상품 한 자리·나눠 두기 규칙에서 빠진다 — 호출자는 {@code
   * fromHighTurnover}·{@code toHighTurnover}·{@code skuShelves} 값에 "규칙 예외 칸"(고회전 또는 보충재고)을 넣는다.
   * 보충재고는 팔 물건을 두는 곳이라 기타 칸이 아니다 — 출하 부적격 LOT 을 받지 않고(회수 예외 없음), 카테고리는 보지 않는다.
   *
   * @param toReserve 도착 칸이 보충재고 칸
   * @param toCategory 도착 칸 카테고리(null = 기타 칸)
   * @param skuPaths 상품 카테고리 경로(null = 모름)
   * @param lotShippable 그 LOT 이 오늘 출하 기준을 맞추나(null = 기준 없음 — 막지 않는다)
   * @param skuShelves 이 상품이 있거나 들어올 예정인 선반·피킹 칸 → 규칙 예외 칸(고회전·보충재고) 여부(출발·도착 포함)
   * @param remainingAtFromAfter 이동 뒤 출발 칸에 남는 이 상품 양(모든 LOT·상태)
   * @param destQtyAfter 이동 뒤 도착 칸의 이 상품 양(지금 + 들어올 예정 + 이번)
   * @param capacity 도착 칸 용량(이 상품 기준 — 보충재고 칸은 다른 상품이 차지한 부피·무게를 뺀 값)
   * @return 거절 코드, 괜찮으면 null
   */
  public static @Nullable String rejection(
      String from,
      String to,
      boolean fromHighTurnover,
      boolean toHighTurnover,
      boolean toReserve,
      int qty,
      int pickableAtFrom,
      int lotAvailable,
      @Nullable String toCategory,
      @Nullable List<String> skuPaths,
      @Nullable Boolean lotShippable,
      Map<String, Boolean> skuShelves,
      int remainingAtFromAfter,
      int destQtyAfter,
      int capacity) {
    if (from.equals(to)) {
      return SAME_LOCATION;
    }
    if (qty > pickableAtFrom) {
      return MOVE_QTY_EXCEEDS_PICKABLE;
    }
    if (qty > lotAvailable) {
      return MOVE_QTY_EXCEEDS_AVAILABLE;
    }
    if (toReserve && Boolean.FALSE.equals(lotShippable)) {
      return LOT_NOT_SHIPPABLE;
    }
    if (toCategory != null && !toReserve) {
      if (Boolean.FALSE.equals(lotShippable)) {
        return LOT_NOT_SHIPPABLE;
      }
      if (skuPaths == null || skuPaths.isEmpty()) {
        return SKU_CATEGORY_UNKNOWN;
      }
      if (!inCategory(skuPaths, toCategory)) {
        return CATEGORY_MISMATCH;
      }
    }
    // 출하 부적격 LOT 을 기타 칸으로 거두는 것은 "한 상품 한 자리" 예외(회수·세일 칸 — 2026-11-08 #59, 그러지 않으면 거둘 곳이 없다)
    boolean recallToMisc = toCategory == null && !toReserve && Boolean.FALSE.equals(lotShippable);
    if (!toHighTurnover && !recallToMisc) {
      for (var e : skuShelves.entrySet()) {
        if (!e.getKey().equals(from) && !e.getKey().equals(to) && !e.getValue()) {
          return SKU_ALREADY_SHELVED;
        }
      }
      if (!fromHighTurnover && remainingAtFromAfter > 0) {
        return SKU_SPLIT_NOT_ALLOWED;
      }
    }
    if (destQtyAfter > capacity) {
      return LOCATION_CAPACITY_EXCEEDED;
    }
    return null;
  }
}
