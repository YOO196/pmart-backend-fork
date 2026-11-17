package com.pmart.mfc.fulfillment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pmart.mfc.fulfillment.domain.CancelReturnRules.Packed;
import com.pmart.mfc.fulfillment.domain.CancelReturnRules.Return;
import com.pmart.mfc.fulfillment.domain.CancelReturnRules.Unit;
import java.util.List;
import org.junit.jupiter.api.Test;

class CancelReturnRulesTest {

  @Test
  void pickedMinusPackedAtSameLocationIsPickedRestIsPacked() {
    List<Return> r =
        CancelReturnRules.returns(
            List.of(
                new Unit("L1", "S1", "A", "PAK-1", 2),
                new Unit("L1", "S1", "A", "PAK-1", 1),
                new Unit("L2", "S2", "C", "PAK-2", 1)),
            List.of(packed("L1", "S1", "A", "PAK-1", 2), packed("L2", "S2", "C", "PAK-2", 1)));
    assertThat(r)
        .containsExactly(
            new Return("L1", "S1", "A", "PAK-1", "PICKED", 1),
            new Return("L1", "S1", "A", "PAK-1", "PACKED", 2),
            new Return("L2", "S2", "C", "PAK-2", "PACKED", 1));
  }

  @Test
  void packedMoreThanPickedIsInconsistent() {
    assertThatThrownBy(
            () ->
                CancelReturnRules.returns(
                    List.of(new Unit("L1", "S1", "A", "PAK-1", 1)),
                    List.of(packed("L1", "S1", "A", "PAK-1", 2))))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                CancelReturnRules.returns(List.of(), List.of(packed("L1", "S1", "A", "PAK-9", 1))))
        .isInstanceOf(IllegalStateException.class);
  }

  /** 장바구니로 집품 → 패킹대에서 포장(2026-11-17): 남은 것은 장바구니 PICKED, 포장분은 패킹대 PACKED. */
  @Test
  void cartPickPackedAtStationSplitsByWhereUnitsAre() {
    List<Return> r =
        CancelReturnRules.returns(
            List.of(new Unit("L1", "S1", "A", "CRT-1", 2), new Unit("L1", "S1", "A", "CRT-1", 1)),
            List.of(new Packed("L1", "S1", "A", "CRT-1", "PAK-1", 2)));
    assertThat(r)
        .containsExactly(
            new Return("L1", "S1", "A", "CRT-1", "PICKED", 1),
            new Return("L1", "S1", "A", "PAK-1", "PACKED", 2));
    // 다른 장바구니에서 온 포장은 맞출 집품이 없다
    assertThatThrownBy(
            () ->
                CancelReturnRules.returns(
                    List.of(new Unit("L1", "S1", "A", "CRT-1", 1)),
                    List.of(new Packed("L1", "S1", "A", "CRT-2", "PAK-1", 1))))
        .isInstanceOf(IllegalStateException.class);
  }

  private static Packed packed(String line, String sku, String lot, String location, int qty) {
    return new Packed(line, sku, lot, location, location, qty);
  }

  @Test
  void remainingSubtractsMovedPerLineLotLocation() {
    List<Return> returns =
        List.of(
            new Return("L1", "S1", "A", "PAK-1", "PICKED", 1),
            new Return("L1", "S1", "A", "PAK-1", "PACKED", 2));
    var left = CancelReturnRules.remaining(returns, List.of(new Unit("L1", "S1", "A", "PAK-1", 2)));
    assertThat(left).containsEntry(CancelReturnRules.key("L1", "S1", "A", "PAK-1"), 1);
  }
}
