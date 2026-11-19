package com.pmart.mfc.capacity.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class NextTaskDetailsTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void returnAndCountDetails() {
    var arrival =
        NextTaskDetails.fromReturnArrival(
            mapper.readTree(
                """
                {"arrivalId":"RTP-1","lines":[{"sku":"E-1","productName":"두부","qty":2},{"sku":null,"orderLineId":"OL-2","qty":1}]}
                """));
    assertThat(arrival.lines()).hasSize(2);
    assertThat(arrival.lines().get(0).productName()).isEqualTo("두부");
    assertThat(arrival.lines().get(1).sku()).isNull(); // 배송 사건에 상품 코드가 없던 반납
    assertThat(arrival.lines().get(1).qty()).isEqualTo(1);
    var receipt =
        NextTaskDetails.fromReturnReceipt(
            mapper.readTree(
                """
                {"returnReceiptId":"RR-1","toLocationCode":"RTN-A-01","lines":[{"sku":"E-1","qty":2,"lotId":"L-R","conditionNote":"개봉"}]}
                """));
    assertThat(receipt.lines().get(0).fromLocation()).isEqualTo("RTN-A-01");
    assertThat(receipt.lines().get(0).lotId()).isEqualTo("L-R");
    assertThat(receipt.lines().get(0).notice()).isEqualTo("개봉");
    var count = NextTaskDetails.fromCountWork(java.util.List.of("A-1"));
    assertThat(count.available()).isTrue();
    assertThat(count.locationCodes()).containsExactly("A-1");
    assertThat(count.lines()).isEmpty();
  }

  @Test
  void pickUsesAllocatedLocationAndSkipsDonePieces() {
    var d =
        NextTaskDetails.fromTask(
            mapper.readTree(
                """
                {"taskType":"PICK","promisedReadyAt":"2026-11-08T10:00:00Z","lines":[{"sku":"E-1","productName":"사과","remainingQty":3,
                 "picks":[{"lotId":"L1","remainingQty":0,"locations":[]},
                          {"lotId":"L2","expiresOn":"2026-11-20","remainingQty":3,"pickStatus":"PARTIAL","notice":"1개는 칸을 못 찾음",
                           "locations":[{"locationCode":"A-1","qty":9,"allocatedQty":0},{"locationCode":"A-2","qty":2,"allocatedQty":2}]}]}]}
                """));
    assertThat(d.available()).isTrue();
    assertThat(d.promisedReadyAt()).isEqualTo("2026-11-08T10:00:00Z");
    assertThat(d.lines()).hasSize(1);
    var l = d.lines().get(0);
    assertThat(l.lotId()).isEqualTo("L2");
    assertThat(l.fromLocation()).isEqualTo("A-2");
    assertThat(l.qty()).isEqualTo(3);
    assertThat(l.notice()).contains("칸을 못 찾음");
  }

  @Test
  void packListsLinesAndPackingLocations() {
    var d =
        NextTaskDetails.fromTask(
            mapper.readTree(
                """
                {"taskType":"PACK","packingLocations":["PAK-A-01"],"lines":[{"sku":"E-1","remainingQty":2,"picks":[]},{"sku":"E-2","remainingQty":0}]}
                """));
    assertThat(d.packingLocations()).containsExactly("PAK-A-01");
    assertThat(d.lines()).extracting(NextTaskDetails.Line::sku).containsExactly("E-1");
  }

  @Test
  void moveRemainingIsOrderedMinusPicked() {
    var d =
        NextTaskDetails.fromMove(
            mapper.readTree(
                """
                {"sku":"E-1","lotId":"L1","fromLocation":"A-1","toLocation":"A-9","qty":5,"pickedQty":2,"urgent":true}
                """));
    assertThat(d.urgent()).isTrue();
    assertThat(d.lines().get(0).qty()).isEqualTo(3);
  }

  /** (0.14.0, BR-52 R2) 입고 작업: 한 줄, 출발 칸 = 수령 위치, 남은 양 = 계획 − 완료. */
  @Test
  void inboundTaskRemainingIsPlannedMinusDone() {
    var d =
        NextTaskDetails.fromInboundTask(
            mapper.readTree(
                """
                {"taskId":"TSK-PUT-1","taskType":"PUTAWAY","sku":"E-1","lotId":"L1","locationCode":"RCV-DOCK","plannedQty":5,"doneQty":2}
                """));
    assertThat(d.available()).isTrue();
    assertThat(d.lines()).hasSize(1);
    assertThat(d.lines().get(0).qty()).isEqualTo(3);
    assertThat(d.lines().get(0).fromLocation()).isEqualTo("RCV-DOCK");
    assertThat(d.lines().get(0).toLocation()).isNull();
    assertThat(d.lines().get(0).lotId()).isEqualTo("L1");
  }
}
