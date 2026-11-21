package com.pmart.mfc.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReserveCapacityTest {

  static UnitSize cube(int mm, Integer weightG) {
    return new UnitSize(mm, mm, mm, weightG, "MEASURED", true);
  }

  @Test
  void otherSkusVolumeIsSubtracted() {
    // 100×100×250 × 0.8 = 2,000,000 ÷ 125,000 = 16개
    assertThat(ReserveCapacity.capacity(cube(50, null), 100, 100, 250, null, List.of()))
        .isEqualTo(16);
    var others = List.of(new ReserveCapacity.Occupant(cube(50, null), 10));
    assertThat(ReserveCapacity.capacity(cube(50, null), 100, 100, 250, null, others)).isEqualTo(6);
    // 다른 상품이 이미 넘치면 0(음수 아님)
    var many = List.of(new ReserveCapacity.Occupant(cube(50, null), 40));
    assertThat(ReserveCapacity.capacity(cube(50, null), 100, 100, 250, null, many)).isZero();
  }

  @Test
  void unknownSizesAreLeftOutAndWeightIsSharedToo() {
    var unknown = List.of(new ReserveCapacity.Occupant(null, 1000));
    assertThat(ReserveCapacity.capacity(cube(50, null), 100, 100, 250, null, unknown))
        .isEqualTo(16);
    // 하중 10kg, 다른 상품 10개 6kg → 무게로 4kg ÷ 500g = 8, 부피로 6 → 6
    var heavy = List.of(new ReserveCapacity.Occupant(cube(50, 600), 10));
    assertThat(ReserveCapacity.capacity(cube(50, 500), 100, 100, 250, 10_000, heavy)).isEqualTo(6);
    assertThat(ReserveCapacity.capacity(cube(50, 500), 100, 100, 250, 10_000, List.of()))
        .isEqualTo(16);
    // 작은 다른 상품(부피 거의 없음) 6kg → 무게가 정한다: 8
    var lighter = List.of(new ReserveCapacity.Occupant(cube(10, 600), 10));
    assertThat(ReserveCapacity.capacity(cube(50, 500), 100, 100, 250, 10_000, lighter))
        .isEqualTo(8);
  }

  @Test
  void noDimensionsOrNotFittingItem() {
    assertThat(ReserveCapacity.capacity(cube(50, null), null, 100, 250, null, List.of())).isNull();
    assertThat(ReserveCapacity.capacity(cube(300, null), 100, 100, 250, null, List.of())).isZero();
  }
}
