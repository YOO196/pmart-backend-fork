package com.pmart.mfc.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.pmart.common.web.PmartException;
import com.pmart.mfc.inventory.domain.HqApprovalRules.Line;
import com.pmart.mfc.inventory.infrastructure.ApprovalRefClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 승인 번호 관문(AP-1) — 기준 판정·요청 본문·한 번 사용·범위 비교. pricing·approval 은 MockRestServiceServer. */
class ApprovalRefsTest {

  static final ObjectMapper MAPPER = JsonMapper.builder().build();
  static final String P = "http://pricing";
  static final String A = "http://approval";

  MockRestServiceServer server;
  ApprovalRefClient client;

  @BeforeEach
  void setUp() {
    RestClient.Builder b = RestClient.builder();
    server = MockRestServiceServer.bindTo(b).build();
    client = new ApprovalRefClient(b, MAPPER, P, A);
  }

  ApprovalRefs verify() {
    return new ApprovalRefs("VERIFY", 100_000L, 20, client, MAPPER);
  }

  void price(String sku, long base) {
    server
        .expect(requestTo(P + "/api/v1/internal/prices?skus=" + sku))
        .andRespond(
            withSuccess(
                "{\"items\":[{\"sku\":\""
                    + sku
                    + "\",\"basePrice\":"
                    + base
                    + ",\"priceVersion\":7}],\"notSet\":[]}",
                MediaType.APPLICATION_JSON));
  }

  @Test
  void belowThresholdPassesWithoutApprovalCall() {
    price("E-1", 1_000);
    String ref =
        verify()
            .require(
                "MFC-1",
                "COUNT_ADJUSTMENT",
                "CNT-1",
                "sup",
                "s",
                List.of(new Line("E-1", 3, 3, Map.of())),
                null);
    assertThat(ref).isNull();
    server.verify();
  }

  @Test
  void aboveThresholdWithoutRefGivesChangeRequestBody() {
    price("E-1", 5_000);
    assertThatThrownBy(
            () ->
                verify()
                    .require(
                        "MFC-1",
                        "COUNT_ADJUSTMENT",
                        "CNT-1",
                        "sup",
                        "MFC-1 실사 조정 CNT-1",
                        List.of(new Line("E-1", 25, 25, Map.of("delta", -25))),
                        " "))
        .isInstanceOfSatisfying(
            PmartException.class,
            e -> {
              assertThat(e.code()).isEqualTo("APPROVAL_REQUIRED");
              String json =
                  e.details().stream()
                      .filter(d -> d.field().equals("approvalRequest"))
                      .findFirst()
                      .orElseThrow()
                      .reason();
              JsonNode body = MAPPER.readTree(json);
              assertThat(body.path("type").asString()).isEqualTo("MFC_STOCK_ADJUSTMENT");
              assertThat(body.path("targetService").asString()).isEqualTo("mfc-inventory-service");
              assertThat(body.path("targetRef").asString())
                  .isEqualTo("MFC-1:COUNT_ADJUSTMENT:CNT-1");
              JsonNode line = body.path("lines").path(0);
              assertThat(line.path("lineKey").asString()).isEqualTo("E-1");
              assertThat(line.path("requestedQty").asInt()).isEqualTo(25);
              assertThat(line.path("requestedAmount").asLong()).isEqualTo(125_000);
              assertThat(line.path("attributes").path("delta").asInt()).isEqualTo(-25);
              assertThat(line.path("attributes").path("unitPrice").asLong()).isEqualTo(5_000);
            });
  }

  @Test
  void aboveThresholdUsesApprovalOnceAndChecksScope() {
    price("E-1", 5_000);
    server
        .expect(requestTo(A + "/api/v1/internal/approval-ref-uses"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("X-Source-System", "mfc-inventory-service"))
        .andExpect(jsonPath("$.useRef").value("mfc-inventory-service:COUNT_ADJUSTMENT:CNT-1"))
        .andExpect(jsonPath("$.targetRef").value("MFC-1:COUNT_ADJUSTMENT:CNT-1"))
        .andExpect(jsonPath("$.actorId").value("sup"))
        .andRespond(
            withSuccess(
                "{\"approvalId\":\"APV-1\",\"usable\":true,\"lines\":[{\"lineKey\":\"E-1\",\"requestedQty\":25}]}",
                MediaType.APPLICATION_JSON));
    String ref =
        verify()
            .require(
                "MFC-1",
                "COUNT_ADJUSTMENT",
                "CNT-1",
                "sup",
                "s",
                List.of(new Line("E-1", 25, 25, Map.of())),
                "APV-1");
    assertThat(ref).isEqualTo("APV-1");
    server.verify();
  }

  @Test
  void unusableOrOutOfScopeIsRejected() {
    price("E-1", 5_000);
    server
        .expect(requestTo(A + "/api/v1/internal/approval-ref-uses"))
        .andRespond(
            withSuccess(
                "{\"approvalId\":\"APV-1\",\"usable\":false,\"reason\":\"ALREADY_USED\"}",
                MediaType.APPLICATION_JSON));
    assertThatThrownBy(
            () ->
                verify()
                    .require(
                        "MFC-1",
                        "COUNT_ADJUSTMENT",
                        "CNT-1",
                        "sup",
                        "s",
                        List.of(new Line("E-1", 25, 25, Map.of())),
                        "APV-1"))
        .hasMessageContaining("ALREADY_USED");

    server.reset();
    server
        .expect(requestTo(A + "/api/v1/internal/approval-ref-uses"))
        .andRespond(
            withSuccess(
                "{\"approvalId\":\"APV-2\",\"usable\":true,\"lines\":[{\"lineKey\":\"E-1\",\"requestedQty\":25,\"approvedQty\":20}]}",
                MediaType.APPLICATION_JSON));
    // 정정(감소 없음) — 단가 조회 없이 수량 기준만
    assertThatThrownBy(
            () ->
                verify()
                    .require(
                        "MFC-1",
                        "CORRECTION",
                        "REQ-1",
                        "sup",
                        "s",
                        List.of(new Line("E-1", 25, 0, Map.of())),
                        "APV-2"))
        .hasMessageContaining("넘습니다");
  }

  @Test
  void recordOnlyNeedsRefAndMissingModeOrThresholdIsConfigRequired() {
    var record = new ApprovalRefs("RECORD_ONLY", null, null, client, MAPPER);
    assertThat(record.require("M", "CORRECTION", "R", "a", "s", List.of(), "X")).isEqualTo("X");
    assertThatThrownBy(() -> record.require("M", "CORRECTION", "R", "a", "s", List.of(), null))
        .hasFieldOrPropertyWithValue("code", "APPROVAL_REQUIRED");
    assertThatThrownBy(
            () ->
                new ApprovalRefs("", null, null, client, MAPPER)
                    .require("M", "CORRECTION", "R", "a", "s", List.of(), "X"))
        .hasFieldOrPropertyWithValue("code", "CONFIG_REQUIRED");
    assertThatThrownBy(
            () ->
                new ApprovalRefs("VERIFY", 100_000L, null, client, MAPPER)
                    .require("M", "CORRECTION", "R", "a", "s", List.of(), "X"))
        .hasFieldOrPropertyWithValue("code", "CONFIG_REQUIRED");
  }
}
