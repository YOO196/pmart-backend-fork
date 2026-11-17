package com.pmart.mfc.fulfillment.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RejectPartsTest {

  @Test
  void splitsCodeAndDetail() {
    assertThat(TaskService.rejectParts("LOT_MISMATCH: 예약 LOT 이 아닙니다"))
        .containsExactly("LOT_MISMATCH", "예약 LOT 이 아닙니다");
    assertThat(TaskService.rejectParts("INVALID_STATE"))
        .containsExactly("INVALID_STATE", "INVALID_STATE");
    assertThat(TaskService.rejectParts("재고가 거절: 뭔가")).containsExactly("REJECTED", "재고가 거절: 뭔가");
  }
}
