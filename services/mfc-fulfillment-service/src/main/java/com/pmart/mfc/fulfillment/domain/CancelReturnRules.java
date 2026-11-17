package com.pmart.mfc.fulfillment.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 집품 뒤 취소(IV-08, ADR-0046) 계산. 순수 함수.
 *
 * <ul>
 *   <li>복귀 대상: 반영된 PICK 실적(도착 위치)에서 밀봉 포장(포장 위치)을 뺀 양 = PICKED, 밀봉 포장 = PACKED. inventory PACK 은
 *       제자리(PICKED → PACKED)라 같은 위치끼리 뺀다.
 *   <li>복귀 실행 잔량: 복귀 대상에서 반영된 RETURN 실적(출발 위치)을 뺀 양 — 주문 줄·LOT·위치별.
 * </ul>
 */
public final class CancelReturnRules {

  private CancelReturnRules() {}

  public static final String PICKED = "PICKED";
  public static final String PACKED = "PACKED";

  /** 위치에 놓인 수량 한 조각(집품 실적·포장 내용·복귀 실적). */
  public record Unit(String orderLineId, String sku, String lotId, String location, int qty) {
    String key() {
      return orderLineId + "|" + sku + "|" + lotId + "|" + location;
    }
  }

  /**
   * 밀봉 포장 내용 한 조각. from = 집품 도착지(장바구니·포장 위치 — 집품과 맞출 위치), at = 포장이 놓인 위치(패킹대). 장바구니 → 패킹대로 옮겨 포장하면
   * 둘이 다르다(2026-11-17), 예전 흐름은 같다.
   */
  public record Packed(
      String orderLineId, String sku, String lotId, String from, String at, int qty) {
    Unit source() {
      return new Unit(orderLineId, sku, lotId, from, qty);
    }

    Unit placed() {
      return new Unit(orderLineId, sku, lotId, at, qty);
    }
  }

  /** inventory cancel-returns 의 returns[] 한 줄. */
  public record Return(
      String orderLineId,
      String sku,
      String lotId,
      String locationCode,
      String workState,
      int qty) {}

  /**
   * 집품 도착지별로 (집품 − 그곳에서 가져간 포장) = PICKED(그 도착지), 포장은 놓인 위치의 PACKED.
   *
   * @throws IllegalStateException 포장이 같은 도착지의 집품보다 많으면(기록 불일치 — 재고 확인 필요)
   */
  public static List<Return> returns(List<Unit> picked, List<Packed> packed) {
    Map<String, Unit> loose = sum(picked);
    Map<String, Unit> sealed = sum(packed.stream().map(Packed::source).toList());
    List<Return> out = new ArrayList<>();
    for (Unit p : loose.values()) {
      Unit s = sealed.get(p.key());
      int left = p.qty() - (s == null ? 0 : s.qty());
      if (left < 0) {
        throw new IllegalStateException("포장이 집품보다 많습니다: " + p.key());
      }
      if (left > 0) {
        out.add(new Return(p.orderLineId(), p.sku(), p.lotId(), p.location(), PICKED, left));
      }
    }
    for (Unit s : sealed.values()) {
      if (!loose.containsKey(s.key())) {
        throw new IllegalStateException("집품 없이 포장된 조각이 있습니다: " + s.key());
      }
    }
    for (Unit s : sum(packed.stream().map(Packed::placed).toList()).values()) {
      out.add(new Return(s.orderLineId(), s.sku(), s.lotId(), s.location(), PACKED, s.qty()));
    }
    return out;
  }

  /** 복귀 실행 잔량(주문 줄·SKU·LOT·출발 위치 → 남은 양). moved = 반영됐거나 진행 중인 RETURN 실적. */
  public static Map<String, Integer> remaining(List<Return> returns, List<Unit> moved) {
    Map<String, Integer> left = new LinkedHashMap<>();
    for (Return r : returns) {
      left.merge(
          new Unit(r.orderLineId(), r.sku(), r.lotId(), r.locationCode(), 0).key(),
          r.qty(),
          Integer::sum);
    }
    for (Unit m : moved) {
      left.merge(m.key(), -m.qty(), Integer::sum);
    }
    return left;
  }

  public static String key(String orderLineId, String sku, String lotId, String location) {
    return new Unit(orderLineId, sku, lotId, location, 0).key();
  }

  private static Map<String, Unit> sum(List<Unit> units) {
    Map<String, Unit> out = new LinkedHashMap<>();
    for (Unit u : units) {
      if (u.qty() <= 0) {
        continue;
      }
      out.merge(
          u.key(),
          u,
          (a, b) -> new Unit(a.orderLineId(), a.sku(), a.lotId(), a.location(), a.qty() + b.qty()));
    }
    return out;
  }
}
