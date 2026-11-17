package com.pmart.mfc.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.mfc.inventory.domain.HqApprovalRules.Line;
import com.pmart.mfc.inventory.domain.HqApprovalRules.Price;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HqApprovalRulesTest {

  static final long LOSS = 100_000;
  static final int QTY = 20;

  @Test
  void belowBothThresholdsIsCenterOnly() {
    var a =
        HqApprovalRules.assess(
            List.of(new Line("E-1", 5, 5, Map.of())),
            Map.of("E-1", new Price(10_000, 3)),
            LOSS,
            QTY);
    assertThat(a.hqRequired()).isFalse();
    assertThat(a.loss()).isEqualTo(50_000);
    assertThat(a.lines().get(0)).containsEntry("requestedAmount", 50_000L);
  }

  @Test
  void lossAtThresholdNeedsHq() {
    var a =
        HqApprovalRules.assess(
            List.of(new Line("E-1", 4, 4, Map.of()), new Line("E-2", 2, 2, Map.of())),
            Map.of("E-1", new Price(20_000, 1), "E-2", new Price(10_000, 1)),
            LOSS,
            QTY);
    assertThat(a.loss()).isEqualTo(100_000);
    assertThat(a.hqRequired()).isTrue();
    assertThat(a.reasons()).anyMatch(r -> r.startsWith("손실"));
  }

  @Test
  void skuQtyAtThresholdNeedsHqEvenWithoutLoss() {
    // 정정(총량 불변) — 손실 0, 한 SKU 20개
    var a =
        HqApprovalRules.assess(
            List.of(new Line("E-1", 12, 0, Map.of()), new Line("E-1", 8, 0, Map.of())),
            Map.of(),
            LOSS,
            QTY);
    assertThat(a.hqRequired()).isTrue();
    assertThat(a.lines()).hasSize(1);
    assertThat(a.lines().get(0))
        .containsEntry("requestedQty", 20)
        .doesNotContainKey("requestedAmount");
  }

  @Test
  void unpricedDecreaseIsUnevaluatedAndGoesToHq() {
    var a = HqApprovalRules.assess(List.of(new Line("E-1", 1, 1, Map.of())), Map.of(), LOSS, QTY);
    assertThat(a.hqRequired()).isTrue();
    @SuppressWarnings("unchecked")
    Map<String, Object> attrs = (Map<String, Object>) a.lines().get(0).get("attributes");
    assertThat(attrs).containsEntry("unpriced", true);
  }

  @Test
  void gainWithoutPriceIsNotUnpriced() {
    var a = HqApprovalRules.assess(List.of(new Line("E-1", 3, 0, Map.of())), Map.of(), LOSS, QTY);
    assertThat(a.hqRequired()).isFalse();
  }

  @Test
  void scopeComparesMergedQtyWithApproved() {
    List<Line> lines = List.of(new Line("E-1", 15, 15, Map.of()), new Line("E-1", 10, 0, Map.of()));
    assertThat(HqApprovalRules.outOfScope(lines, Map.of("E-1", 25))).isNull();
    assertThat(HqApprovalRules.outOfScope(lines, Map.of("E-1", 24))).contains("넘습니다");
    assertThat(HqApprovalRules.outOfScope(lines, Map.of("E-2", 25))).contains("없는 SKU");
  }
}
