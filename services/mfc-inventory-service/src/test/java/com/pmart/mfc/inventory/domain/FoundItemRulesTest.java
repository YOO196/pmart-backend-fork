package com.pmart.mfc.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FoundItemRulesTest {

  @Test
  void existingLotNeedsReferenceAndLot() {
    assertThat(FoundItemRules.rejection("EXISTING_LOT", null, "LOT-1", "칸 오기"))
        .contains("outcomeRef");
    assertThat(FoundItemRules.rejection("EXISTING_LOT", "COR-1", null, "칸 오기")).contains("lotId");
    assertThat(FoundItemRules.rejection("EXISTING_LOT", "COR-1", "LOT-1", "칸 오기")).isNull();
  }

  @Test
  void sourceRecoveredNeedsReferenceAndNoLot() {
    assertThat(FoundItemRules.rejection("SOURCE_RECOVERED", " ", null, "입고 누락"))
        .contains("outcomeRef");
    assertThat(FoundItemRules.rejection("SOURCE_RECOVERED", "RCV-1", "LOT-1", "입고 누락"))
        .contains("lotId");
    assertThat(FoundItemRules.rejection("SOURCE_RECOVERED", "RCV-1", null, "입고 누락")).isNull();
  }

  @Test
  void disposedNeedsOnlyNoteAndUnknownOutcomeIsRejected() {
    assertThat(FoundItemRules.rejection("DISPOSED", null, null, "원인 불명")).isNull();
    assertThat(FoundItemRules.rejection("DISPOSED", null, null, " ")).contains("note");
    assertThat(FoundItemRules.rejection("ADJUSTED", "X", null, "n")).contains("outcome");
  }
}
