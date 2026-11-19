package com.pmart.mfc.capacity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

class WorkAreasTest {

  @Test
  void allowedAreasAreThoseOverlappingEffectiveTaskTypesInContractOrder() {
    assertThat(WorkAreas.allowed(Set.of("PACK", "RETURN")))
        .containsExactly("STOCK_MOVE", "PICK_PACK");
    assertThat(WorkAreas.allowed(Set.of("RECEIVE", "INSPECT")))
        .containsExactly("INBOUND", "INSPECTION");
    assertThat(WorkAreas.allowed(Set.of())).isEmpty();
    // 배차 요청(점원)은 어느 업무에도 없다
    assertThat(WorkAreas.allowed(Set.of("DISPATCH"))).isEmpty();
    // (0.24.0) 회수품 검수 → 품질검사, 회수품 반입 → 재고 이동, 실사 → 재고 실사
    assertThat(WorkAreas.allowed(Set.of("RETURN_INSPECT"))).containsExactly("INSPECTION");
    assertThat(WorkAreas.allowed(Set.of("RETURN_RECEIPT"))).containsExactly("STOCK_MOVE");
    assertThat(WorkAreas.allowed(Set.of("CYCLE_COUNT"))).containsExactly("STOCK_COUNT");
  }

  @Test
  void narrowKeepsOnlyChosenAreaWithinEffective() {
    Set<String> eff = Set.of("PICK", "PACK", "MOVE");
    assertThat(WorkAreas.narrow(eff, null)).isEqualTo(eff);
    assertThat(WorkAreas.narrow(eff, "PICK_PACK")).containsExactly("PACK", "PICK");
    assertThat(WorkAreas.narrow(eff, "STOCK_MOVE")).containsExactly("MOVE");
    assertThat(WorkAreas.narrow(eff, "INBOUND")).isEmpty();
    assertThatThrownBy(() -> WorkAreas.narrow(eff, "NOPE"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
