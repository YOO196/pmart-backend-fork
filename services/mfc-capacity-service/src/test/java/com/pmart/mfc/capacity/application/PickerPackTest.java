package com.pmart.mfc.capacity.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.mfc.capacity.infrastructure.FulfillmentClient.TaskView;
import org.junit.jupiter.api.Test;

/** 피킹한 사람만 그 출고 PACK(사용자 결정 2026-11-18, mfc-app 요청 R1·R2). */
class PickerPackTest {

  static TaskView pack(String pickedBy) {
    return new TaskView("S1-PACK", "PACK", "READY", 1, null, null, 2, 2, pickedBy);
  }

  @Test
  void onlyPickerTakesPackOfShipmentItPicked() {
    assertThat(AssignmentService.pickerMayTake(pack("w1"), "w1")).isTrue();
    assertThat(AssignmentService.pickerMayTake(pack("w1"), "w2")).isFalse();
  }

  @Test
  void packWithUnknownPickerAndOtherTypesAreOpenToAnyone() {
    assertThat(AssignmentService.pickerMayTake(pack(null), "w2")).isTrue();
    TaskView pick = new TaskView("S1-PICK", "PICK", "READY", 1, null, null, 2, 2, "w1");
    assertThat(AssignmentService.pickerMayTake(pick, "w2")).isTrue();
  }

  @Test
  void ownPackComesBeforeAnyOtherCandidate() {
    for (String type :
        new String[] {"PICK", "PACK", "REPLENISH", "INSPECT", "PUTAWAY", "MOVE", "RETURN"}) {
      assertThat(AssignmentService.OWN_PACK_RANK).isLessThan(AssignmentService.rank(type));
    }
  }
}
