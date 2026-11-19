package com.pmart.mfc.capacity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** (0.25.0, BR-52 R2·BR-53 R9) 다음 작업 받기의 입고(RECEIVE) 후보 판정 — 출발을 받은 예정만, 진행 중 수령이 있으면 빼기. */
class InboundReceiveWorkTest {

  private final ObjectMapper mapper = new ObjectMapper();

  private JsonNode promise(String trfId, String state, String departureId, String receipts) {
    return mapper.readTree(
        """
        {"trfId":"%s","state":"%s","departureId":%s,"plannedArrivalAt":"2026-11-20T01:00:00Z","receipts":%s}
        """
            .formatted(
                trfId, state, departureId == null ? "null" : "\"" + departureId + "\"", receipts));
  }

  @Test
  void onlyDepartedPromisesWithoutOpenReceiptAreReceivable() {
    // 출발 전(승인만) — 실물 없음
    assertThat(InboundClient.receivable(promise("T1", "EXPECTED", null, "[]"))).isFalse();
    // 출발함 — 도착 등록부터
    assertThat(InboundClient.receivable(promise("T2", "EXPECTED", "TOUT-2", "[]"))).isTrue();
    // 도착 등록됨, 수령 없음
    assertThat(InboundClient.receivable(promise("T3", "RECEIVING", "TOUT-3", "[]"))).isTrue();
    // 재고 거절만 — 고쳐서 다시
    assertThat(
            InboundClient.receivable(
                promise("T4", "RECEIVING", "TOUT-4", "[{\"inventoryApplyStatus\":\"REJECTED\"}]")))
        .isTrue();
    // 재고 반영 전 수령(응답 불명 포함) — 낸 사람이 같은 키로 마저
    for (String open : List.of("CAPTURED", "SUBMITTED", "PROCESSING", "UNKNOWN_RESULT")) {
      assertThat(
              InboundClient.receivable(
                  promise(
                      "T5",
                      "RECEIVING",
                      "TOUT-5",
                      "[{\"inventoryApplyStatus\":\"REJECTED\"},{\"inventoryApplyStatus\":\""
                          + open
                          + "\"}]")))
          .as(open)
          .isFalse();
    }
    // 도착 등록 됐지만 출발 사실 없음 — mfc-inbound 도 수령 409
    assertThat(InboundClient.receivable(promise("T6", "RECEIVING", null, "[]"))).isFalse();
    // 끝난 것
    assertThat(InboundClient.receivable(promise("T7", "TIN_POSTED", "TOUT-7", "[]"))).isFalse();
    assertThat(InboundClient.receivable(promise("T8", "CANCELLED", "TOUT-8", "[]"))).isFalse();
  }

  @Test
  void receiveWorkUsesPlannedArrivalElseDeparture() {
    JsonNode noPlan =
        mapper.readTree(
            """
            {"trfId":"T9","state":"EXPECTED","departureId":"TOUT-9","plannedArrivalAt":null,"departedAt":"2026-11-20T00:30:00Z"}
            """);
    var work =
        InboundClient.receiveWork(
            List.of(
                promise("T1", "EXPECTED", null, "[]"),
                promise("T2", "EXPECTED", "D", "[]"),
                noPlan));
    assertThat(work).extracting(InboundClient.ReturnWorkView::taskId).containsExactly("T2", "T9");
    assertThat(work).allMatch(w -> "RECEIVE".equals(w.taskType()));
    assertThat(work.get(0).at()).isEqualTo(Instant.parse("2026-11-20T01:00:00Z"));
    assertThat(work.get(1).at()).isEqualTo(Instant.parse("2026-11-20T00:30:00Z"));
  }
}
