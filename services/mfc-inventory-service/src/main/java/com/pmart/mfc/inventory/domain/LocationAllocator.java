package com.pmart.mfc.inventory.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 예약 조각의 남은 집품량을 선반 위치에 나눈다(IV-05 위치 세분화). 순수 계산.
 *
 * <p>고르는 규칙(Q-08 최적화 전 임시, 2026-11-08): 한 칸에서 다 집을 수 있으면 그런 칸 중 가용이 가장 적은 칸(같으면 코드 순) — 거의 빈 칸을 먼저
 * 비우고 방문 칸 수를 1 로. 없으면 가용 많은 칸부터(같으면 코드 순) — 방문 칸 수를 줄인다. 가용이 모자라면 할 수 있는 만큼만.
 */
public final class LocationAllocator {

  private LocationAllocator() {}

  /**
   * 일반 칸 먼저(R-30, 사용자 결정 2026-11-21 "보충재고 피킹은 모자랄 때만"): {@code later}(보충재고 칸)를 뺀 칸으로 {@link #plan}
   * 하고, 모자란 양만 {@code later} 칸으로 다시 {@link #plan}.
   */
  public static List<Map.Entry<String, Integer>> planNormalFirst(
      int need, Map<String, Integer> free, java.util.Set<String> later) {
    Map<String, Integer> first = new java.util.HashMap<>();
    Map<String, Integer> second = new java.util.HashMap<>();
    free.forEach((k, v) -> (later.contains(k) ? second : first).put(k, v));
    List<Map.Entry<String, Integer>> out = new ArrayList<>(plan(need, first));
    int left = need - out.stream().mapToInt(Map.Entry::getValue).sum();
    out.addAll(plan(left, second));
    return out;
  }

  /**
   * @param need 배정할 양(≥ 0)
   * @param free 위치 코드 → 그 위치 가용(≤ 0 은 무시)
   * @return 위치 코드와 배정량(배정 순서)
   */
  public static List<Map.Entry<String, Integer>> plan(int need, Map<String, Integer> free) {
    List<Map.Entry<String, Integer>> out = new ArrayList<>();
    if (need <= 0) {
      return out;
    }
    List<Map.Entry<String, Integer>> c =
        free.entrySet().stream().filter(e -> e.getValue() > 0).toList();
    var single =
        c.stream()
            .filter(e -> e.getValue() >= need)
            .min(
                Comparator.comparingInt((Map.Entry<String, Integer> e) -> e.getValue())
                    .thenComparing(Map.Entry::getKey));
    if (single.isPresent()) {
      out.add(Map.entry(single.get().getKey(), need));
      return out;
    }
    int left = need;
    for (var e :
        c.stream()
            .sorted(
                Comparator.comparingInt((Map.Entry<String, Integer> x) -> x.getValue())
                    .reversed()
                    .thenComparing(Map.Entry::getKey))
            .toList()) {
      if (left == 0) {
        break;
      }
      int take = Math.min(left, e.getValue());
      out.add(Map.entry(e.getKey(), take));
      left -= take;
    }
    return out;
  }
}
