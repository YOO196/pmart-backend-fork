package com.pmart.mfc.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LocationAllocatorTest {

  @Test
  void oneShelfThatFitsWinsAndTheTightestOneIsChosen() {
    var plan = LocationAllocator.plan(4, Map.of("A-01", 10, "A-02", 4, "A-03", 6));
    assertThat(plan).containsExactly(Map.entry("A-02", 4));
  }

  @Test
  void otherwiseLargestFirstAndPartialWhenShort() {
    assertThat(LocationAllocator.plan(8, Map.of("A-01", 3, "A-02", 5, "A-03", 0)))
        .containsExactly(Map.entry("A-02", 5), Map.entry("A-01", 3));
    assertThat(LocationAllocator.plan(10, Map.of("A-01", 3, "A-02", 5)))
        .containsExactly(Map.entry("A-02", 5), Map.entry("A-01", 3));
    assertThat(LocationAllocator.plan(0, Map.of("A-01", 3))).isEqualTo(List.of());
  }

  @Test
  void tiesBreakByLocationCode() {
    assertThat(LocationAllocator.plan(2, Map.of("B-01", 2, "A-09", 2)))
        .containsExactly(Map.entry("A-09", 2));
  }

  /** 보충재고 칸(R-30, "모자랄 때만"): 일반 칸으로 먼저 계획하고 모자란 양만 보충재고. */
  @Test
  void reserveLocationsOnlyForWhatNormalShelvesLack() {
    var free = Map.of("B-01", 12, "R-01", 20, "R-02", 6);
    var later = java.util.Set.of("R-01", "R-02");
    assertThat(LocationAllocator.planNormalFirst(10, free, later))
        .containsExactly(Map.entry("B-01", 10));
    assertThat(LocationAllocator.planNormalFirst(15, free, later))
        .containsExactly(Map.entry("B-01", 12), Map.entry("R-02", 3));
    assertThat(LocationAllocator.planNormalFirst(50, free, later))
        .containsExactly(Map.entry("B-01", 12), Map.entry("R-01", 20), Map.entry("R-02", 6));
    assertThat(LocationAllocator.planNormalFirst(5, Map.of("R-01", 20), later))
        .containsExactly(Map.entry("R-01", 5));
  }
}
