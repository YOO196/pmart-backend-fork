package com.pmart.mfc.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MoveRulesTest {

  static String check(
      boolean fromHt,
      boolean toHt,
      int qty,
      int pickable,
      int available,
      Map<String, Boolean> shelves,
      int remainingAtFrom,
      int destAfter,
      int capacity) {
    return MoveRules.rejection(
        "A",
        "B",
        fromHt,
        toHt,
        qty,
        pickable,
        available,
        null,
        null,
        null,
        shelves,
        remainingAtFrom,
        destAfter,
        capacity);
  }

  static String category(String toCategory, List<String> paths, Boolean shippable) {
    return MoveRules.rejection(
        "A",
        "B",
        false,
        false,
        1,
        5,
        5,
        toCategory,
        paths,
        shippable,
        Map.of("A", false),
        0,
        1,
        20);
  }

  @Test
  void wholeMoveToAMiscShelfIsAllowed() {
    assertThat(check(false, false, 10, 10, 10, Map.of("A", false), 0, 10, 20)).isNull();
  }

  @Test
  void quantityIsLimitedByPickableAndLotAvailability() {
    assertThat(check(false, false, 5, 4, 10, Map.of(), 0, 5, 20))
        .isEqualTo(MoveRules.MOVE_QTY_EXCEEDS_PICKABLE);
    assertThat(check(false, false, 5, 10, 4, Map.of(), 0, 5, 20))
        .isEqualTo(MoveRules.MOVE_QTY_EXCEEDS_AVAILABLE);
  }

  @Test
  void categoryShelvesTakeOnlyTheirCategoryAndShippableLots() {
    assertThat(category("SNACK", List.of("FOOD/SNACK/CHIPS"), true)).isNull(); // 하위 카테고리 포함
    assertThat(category("SNACK", List.of("FOOD/DRINK"), true))
        .isEqualTo(MoveRules.CATEGORY_MISMATCH);
    assertThat(category("SNACK", null, true)).isEqualTo(MoveRules.SKU_CATEGORY_UNKNOWN);
    assertThat(category("SNACK", List.of("FOOD/SNACK"), false))
        .isEqualTo(MoveRules.LOT_NOT_SHIPPABLE);
    // 기타 칸(카테고리 없음)은 아무 상품·부적격 LOT 도 받는다
    assertThat(category(null, null, false)).isNull();
    // 기준이 없어 판정 못 하는 LOT(null)은 막지 않는다
    assertThat(category("SNACK", List.of("FOOD/SNACK"), null)).isNull();
  }

  @Test
  void unshippableLotRecallToMiscShelfIgnoresOneShelfRule() {
    // 상품이 다른 칸(C)에 있어도 출하 부적격 LOT 을 기타 칸(카테고리 없음)으로 거두는 것은 허용(#59) — 출하 가능 LOT 은 그대로 막음
    Map<String, Boolean> shelves = Map.of("C", false);
    assertThat(
            MoveRules.rejection(
                "A", "B", false, false, 1, 5, 5, null, null, false, shelves, 0, 1, 20))
        .isNull();
    assertThat(
            MoveRules.rejection(
                "A", "B", false, false, 1, 5, 5, null, null, true, shelves, 0, 1, 20))
        .isEqualTo(MoveRules.SKU_ALREADY_SHELVED);
  }

  @Test
  void oneShelfPerSkuExceptHighTurnover() {
    assertThat(check(false, false, 1, 5, 5, Map.of("A", false, "C", false), 0, 1, 20))
        .isEqualTo(MoveRules.SKU_ALREADY_SHELVED);
    assertThat(check(false, false, 1, 5, 5, Map.of("A", false), 4, 1, 20))
        .isEqualTo(MoveRules.SKU_SPLIT_NOT_ALLOWED);
    assertThat(check(false, true, 1, 5, 5, Map.of("A", false, "C", false), 4, 1, 20)).isNull();
    assertThat(check(true, false, 1, 5, 5, Map.of("A", true), 4, 1, 20)).isNull();
    assertThat(check(false, false, 5, 5, 5, Map.of("A", false, "H", true), 0, 5, 20)).isNull();
  }

  @Test
  void capacityCountsWhatIsThereAndWhatIsComing() {
    assertThat(check(false, true, 3, 5, 5, Map.of(), 2, 21, 20))
        .isEqualTo(MoveRules.LOCATION_CAPACITY_EXCEEDED);
  }

  @Test
  void dummySizeIsStableAndCapacityNeedsDimensions() {
    assertThat(SkuSize.dummyFor("E-00000001")).isEqualTo(SkuSize.dummyFor("E-00000001"));
    // 1000×600×400 칸: 작은 것 1,000개, 중간 128개, 큰 것 16개(채움률 0.8)
    assertThat(SkuSize.S.capacity(1000, 600, 400, null)).isEqualTo(1000);
    assertThat(SkuSize.M.capacity(1000, 600, 400, null)).isEqualTo(128);
    assertThat(SkuSize.L.capacity(1000, 600, 400, null)).isEqualTo(16);
    assertThat(SkuSize.L.capacity(1000, 600, 400, 10_000)).isEqualTo(4); // 하중 10kg ÷ 2.5kg
    assertThat(SkuSize.S.capacity(null, 600, 400, null)).isNull();
  }

  static SkuProjection product(Integer w, Integer d, Integer h, Integer weightG, String source) {
    return new SkuProjection(
        "E-00000001",
        "PRD-1",
        "BP-1",
        "E",
        1,
        weightG,
        null,
        "AMBIENT",
        false,
        null,
        null,
        null,
        List.of(),
        w,
        d,
        h,
        source,
        SkuProjection.SkuState.ACTIVE,
        1,
        java.time.Instant.EPOCH);
  }

  @Test
  void productDimensionsDecideCapacityAndDummyIsOnlyTheFallback() {
    // 상품 치수 100×100×100(1L), 600g: 1000×600×400 칸 = 240,000,000 × 0.8 ÷ 1,000,000 = 192
    UnitSize real = UnitSize.resolve("E-00000001", product(100, 100, 100, 600, "MEASURED"), true);
    assertThat(real).isNotNull();
    assertThat(real.label()).isEqualTo("MEASURED");
    assertThat(real.capacity(1000, 600, 400, null)).isEqualTo(192);
    assertThat(real.capacity(1000, 600, 400, 30_000)).isEqualTo(50); // 하중 30kg ÷ 600g
    assertThat(real.capacity(null, 600, 400, null)).isNull();
    // 무게를 모르면 하중은 보지 않는다(지어내지 않음)
    assertThat(
            UnitSize.resolve("E-1", product(100, 100, 100, null, null), false)
                .capacity(1000, 600, 400, 1))
        .isEqualTo(192);
    assertThat(UnitSize.resolve("E-1", product(100, 100, 100, null, null), false).label())
        .isEqualTo(UnitSize.PRODUCT);

    // 치수 없음(일부만 있어도) → 더미 허용이면 더미 등급 그대로, 아니면 null(CONFIG_REQUIRED)
    UnitSize dummy = UnitSize.resolve("E-00000001", product(100, null, 100, 600, null), true);
    assertThat(dummy.label()).isEqualTo(SkuSize.dummyFor("E-00000001").name());
    assertThat(dummy.capacity(1000, 600, 400, null))
        .isEqualTo(SkuSize.dummyFor("E-00000001").capacity(1000, 600, 400, null));
    assertThat(UnitSize.resolve("E-00000001", product(null, null, null, 600, null), false))
        .isNull();
    assertThat(UnitSize.resolve("E-00000001", null, false)).isNull();
  }

  @Test
  void productThatFitsNoOrientationHasNoCapacity() {
    // 긴 상품 50×1100×50: 부피로는 들어가도(1,000×600×400 칸) 가장 긴 변이 칸 가장 긴 변보다 길면 0
    UnitSize longItem = UnitSize.resolve("E-1", product(50, 1100, 50, 100, "ESTIMATED"), false);
    assertThat(longItem.capacity(1000, 600, 400, null)).isZero();
    // 돌려 놓으면 들어간다: 300×900×50 → 칸 1000×600×400 (작은 변끼리 50≤400, 300≤600, 900≤1000)
    assertThat(
            UnitSize.resolve("E-1", product(300, 900, 50, 100, null), false).fitsIn(1000, 600, 400))
        .isTrue();
    assertThat(
            UnitSize.resolve("E-1", product(700, 700, 50, 100, null), false).fitsIn(1000, 600, 400))
        .isFalse();
  }

  /** 보충재고 칸(R-30): 도착이 보충재고면 다른 일반 칸에 그 상품이 있어도·출발에 남아도 된다(호출자가 예외 칸으로 넘긴다). */
  static String toReserve(Boolean shippable, Map<String, Boolean> shelves, int remainingAtFrom) {
    return MoveRules.rejection(
        "A",
        "R",
        false,
        true,
        true,
        2,
        5,
        5,
        null,
        null,
        shippable,
        shelves,
        remainingAtFrom,
        2,
        20);
  }

  @Test
  void reserveStockTakesOverflowBesideTheShelfButNotUnshippableLots() {
    // 선반 A 에 남기고 일부를 보충재고 R 로 — 나눠 두기·한 자리 규칙 예외
    assertThat(toReserve(true, Map.of("A", false, "C", false), 3)).isNull();
    // 보충재고는 기타 칸이 아니다 — 출하 부적격 LOT 은 못 둔다(회수 예외 없음)
    assertThat(toReserve(false, Map.of("A", false), 0)).isEqualTo(MoveRules.LOT_NOT_SHIPPABLE);
    // 보충재고에 있는 양(예외 칸 = true)은 일반 선반으로 옮길 때 "다른 자리"로 세지 않는다
    assertThat(
            MoveRules.rejection(
                "R",
                "A",
                true,
                false,
                false,
                2,
                5,
                5,
                null,
                null,
                true,
                Map.of("R", true),
                3,
                2,
                20))
        .isNull();
    // 용량은 그대로 본다
    assertThat(
            MoveRules.rejection(
                "A",
                "R",
                false,
                true,
                true,
                2,
                5,
                5,
                null,
                null,
                true,
                Map.of("A", false),
                0,
                21,
                20))
        .isEqualTo(MoveRules.LOCATION_CAPACITY_EXCEEDED);
  }
}
