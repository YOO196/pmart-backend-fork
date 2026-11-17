package com.pmart.mfc.capacity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.pmart.mfc.capacity.infrastructure.WorkActivityClient;
import com.pmart.mfc.capacity.infrastructure.WorkActivityClient.Item;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** 작업자 오늘 업무 내역(F-7 R3·R4) — 정본별 모으기·일부 실패·합계. */
class WorkActivityServiceTest {

  static final Instant T = Instant.parse("2026-11-17T01:00:00Z");

  @Test
  void totalsCountAllButSumOnlyNonRejected() {
    var totals =
        WorkActivityService.totals(
            List.of(
                new Item("mfc-inbound-service", "INSPECT", "E1", "T1", "S", 5, "APPLIED", T),
                new Item("mfc-inbound-service", "INSPECT", "E2", "T2", "S", 3, "REJECTED", T),
                new Item(
                    "mfc-inventory-service", "COUNT", "C1", null, null, 7, "PENDING_APPROVAL", T)));
    assertThat(totals)
        .containsExactly(
            new WorkActivityService.Total("mfc-inbound-service", "INSPECT", 2, 5),
            new WorkActivityService.Total("mfc-inventory-service", "COUNT", 1, 7));
  }

  @Test
  void collectsFromConfiguredSourcesAndNamesFailedOnes() {
    RestClient.Builder b = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(b).ignoreExpectOrder(true).build();
    PlanProperties plan =
        new PlanProperties(
            "http://ful", null, null, null, null, "http://inb", null, null, null, null, null);
    var client = new WorkActivityClient(b, plan, JsonMapper.builder().build(), "http://inv", "");
    server
        .expect(
            ExpectedCount.once(),
            requestTo(
                org.hamcrest.Matchers.startsWith(
                    "http://ful/api/v1/internal/work-activities?mfcId=MFC-1&operatorId=w1")))
        .andRespond(
            withSuccess(
                "{\"items\":[{\"activityType\":\"PICK\",\"refId\":\"EXE-1\",\"taskId\":\"S-PICK\",\"qty\":2,\"result\":\"APPLIED\",\"at\":\"2026-11-17T01:00:00Z\"}]}",
                MediaType.APPLICATION_JSON));
    server
        .expect(ExpectedCount.once(), requestTo(org.hamcrest.Matchers.startsWith("http://inb/")))
        .andRespond(withServerError());
    server
        .expect(ExpectedCount.once(), requestTo(org.hamcrest.Matchers.startsWith("http://inv/")))
        .andRespond(withSuccess("{\"items\":[]}", MediaType.APPLICATION_JSON));
    var c = client.collect("MFC-1", "w1", T, T.plusSeconds(86400));
    assertThat(c.items()).hasSize(1);
    assertThat(c.items().get(0).source()).isEqualTo("mfc-fulfillment-service");
    assertThat(c.items().get(0).qty()).isEqualTo(2);
    assertThat(c.unavailable()).containsExactly("mfc-inbound-service", "mfc-quality-service");
  }
}
