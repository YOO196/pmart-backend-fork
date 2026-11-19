package com.pmart.mfc.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.mfc.inventory.domain.CountRules.BookLine;
import com.pmart.mfc.inventory.domain.CountRules.CountLine;
import com.pmart.mfc.inventory.domain.CountRules.Difference;
import java.util.List;
import org.junit.jupiter.api.Test;

class CountRulesTest {

  static final List<BookLine> BOOK =
      List.of(new BookLine("A-1", "E-1", "LOT-A", 5, 0), new BookLine("A-1", "E-1", "LOT-B", 2, 0));

  @Test
  void missingLinesCountAsZeroAndFoundLotsAreAdded() {
    List<Difference> t =
        CountRules.reconcile(
            BOOK,
            List.of(
                new CountLine("A-1", "E-1", "LOT-A", 4), new CountLine("A-1", "E-1", "LOT-C", 1)));
    assertThat(t)
        .extracting(
            Difference::lotId, Difference::bookQty, Difference::countedQty, Difference::delta)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("LOT-A", 5, 4, -1),
            org.assertj.core.groups.Tuple.tuple("LOT-B", 2, 0, -2),
            org.assertj.core.groups.Tuple.tuple("LOT-C", 0, 1, 1));
    assertThat(CountRules.matches(t)).isFalse();
  }

  @Test
  void stateFlowsThroughRecountAndApprovalWhenNeeded() {
    // 첫 계수 = 장부, 보류 없음 → 끝
    assertThat(CountRules.next("COUNTING", true, false)).isEqualTo("MATCHED");
    // 첫 계수 차이 → 재계수
    assertThat(CountRules.next("COUNTING", false, false)).isEqualTo("RECOUNT_REQUIRED");
    // 장부와 같아도 조사 보류가 있으면 승인 뒤 해소
    assertThat(CountRules.next("COUNTING", true, true)).isEqualTo("PENDING_APPROVAL");
    // 재계수 차이 → 승인
    assertThat(CountRules.next("RECOUNT_REQUIRED", false, false)).isEqualTo("PENDING_APPROVAL");
    assertThat(CountRules.next("RECOUNT_REQUIRED", true, false)).isEqualTo("MATCHED");
  }

  /** R19-6: 목록 밖 줄 LOT — lotId 우선, 아니면 소비기한이 같은 LOT 이 하나뿐일 때만. */
  @Test
  void unexpectedLineLotIsGivenOrTheOnlyLotWithThatExpiry() {
    var d = java.time.LocalDate.of(2026, 12, 1);
    var withLot = new CountRules.Unexpected("A-1", "E-1", null, "LOT-X", null, 1);
    assertThat(CountRules.lotOf(withLot, List.of())).isEqualTo("LOT-X");
    var byExpiry = new CountRules.Unexpected("A-1", "E-1", null, null, d, 1);
    assertThat(CountRules.lotOf(byExpiry, List.of("LOT-A"))).isEqualTo("LOT-A");
    assertThat(CountRules.lotOf(byExpiry, List.of("LOT-A", "LOT-B"))).isNull();
    assertThat(CountRules.lotCause(byExpiry, List.of("LOT-A", "LOT-B")))
        .isEqualTo(CountRules.LOT_AMBIGUOUS);
    assertThat(CountRules.lotCause(byExpiry, List.of())).isEqualTo(CountRules.LOT_UNKNOWN);
    var noExpiry = new CountRules.Unexpected("A-1", "E-1", null, null, null, 1);
    assertThat(CountRules.lotOf(noExpiry, List.of("LOT-A"))).isNull();
    // 정하지 못한 줄이 있으면 장부와 같아도 맞음이 아니다
    assertThat(CountRules.matches(List.of(), List.of())).isTrue();
    assertThat(
            CountRules.matches(
                List.of(),
                List.of(
                    new CountRules.Unresolved(
                        "A-1", null, "880", null, 1, CountRules.SKU_UNKNOWN))))
        .isFalse();
  }

  @Test
  void startersAndCountersCannotApprove() {
    assertThat(CountRules.approvalRejection("s", "a", "b", "c")).isNull();
    assertThat(CountRules.approvalRejection("a", "a", "b", null))
        .isEqualTo(CountRules.SELF_APPROVAL);
    assertThat(CountRules.approvalRejection("b", "a", "b", null))
        .isEqualTo(CountRules.SELF_APPROVAL);
    assertThat(CountRules.approvalRejection("c", "a", "b", "c"))
        .isEqualTo(CountRules.SELF_APPROVAL);
  }
}
