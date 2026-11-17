package com.pmart.mfc.capacity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.pmart.common.web.PmartException;
import com.pmart.mfc.capacity.infrastructure.ApprovalRefClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 운영 제한 해제 승인 번호(AP-1) — 늘 본사, 한 번 사용. */
class ApprovalRefsTest {

  static final ObjectMapper MAPPER = JsonMapper.builder().build();
  static final String A = "http://approval";

  MockRestServiceServer server;
  ApprovalRefs verify;

  @BeforeEach
  void setUp() {
    RestClient.Builder b = RestClient.builder();
    server = MockRestServiceServer.bindTo(b).build();
    verify = new ApprovalRefs("VERIFY", new ApprovalRefClient(b, MAPPER, A), MAPPER);
  }

  @Test
  void withoutRefGivesRestrictionChangeRequest() {
    assertThatThrownBy(() -> verify.requireLift("MFC-1", "OPR-1", "sup", "해제", null))
        .isInstanceOfSatisfying(
            PmartException.class,
            e -> {
              assertThat(e.code()).isEqualTo("APPROVAL_REQUIRED");
              JsonNode body = MAPPER.readTree(e.details().get(0).reason());
              assertThat(e.details().get(0).field()).isEqualTo("approvalRequest");
              assertThat(body.path("type").asString()).isEqualTo("MFC_OPERATION_RESTRICTION");
              assertThat(body.path("targetService").asString()).isEqualTo("mfc-capacity-service");
              assertThat(body.path("targetRef").asString())
                  .isEqualTo("MFC-1:RESTRICTION_LIFT:OPR-1");
              assertThat(body.has("lines")).isFalse();
            });
  }

  @Test
  void usesApprovalOnceBeforeLift() {
    server
        .expect(requestTo(A + "/api/v1/internal/approval-ref-uses"))
        .andExpect(header("X-Source-System", "mfc-capacity-service"))
        .andExpect(jsonPath("$.type").value("MFC_OPERATION_RESTRICTION"))
        .andExpect(jsonPath("$.useRef").value("mfc-capacity-service:RESTRICTION_LIFT:OPR-1"))
        .andRespond(
            withSuccess("{\"approvalId\":\"APV-9\",\"usable\":true}", MediaType.APPLICATION_JSON));
    assertThat(verify.requireLift("MFC-1", "OPR-1", "sup", "해제", "APV-9")).isEqualTo("APV-9");
    server.verify();
  }

  @Test
  void unusableIsRejectedWithReason() {
    server
        .expect(requestTo(A + "/api/v1/internal/approval-ref-uses"))
        .andRespond(
            withSuccess(
                "{\"approvalId\":\"APV-9\",\"usable\":false,\"reason\":\"NOT_APPROVED\"}",
                MediaType.APPLICATION_JSON));
    assertThatThrownBy(() -> verify.requireLift("MFC-1", "OPR-1", "sup", "해제", "APV-9"))
        .hasMessageContaining("NOT_APPROVED");
  }

  @Test
  void recordOnlyAndMissingMode() {
    var record =
        new ApprovalRefs(
            "RECORD_ONLY", new ApprovalRefClient(RestClient.builder(), MAPPER, ""), MAPPER);
    assertThat(record.requireLift("M", "O", "a", "s", "X")).isEqualTo("X");
    assertThatThrownBy(() -> record.requireLift("M", "O", "a", "s", " "))
        .hasFieldOrPropertyWithValue("code", "APPROVAL_REQUIRED");
    var none =
        new ApprovalRefs("", new ApprovalRefClient(RestClient.builder(), MAPPER, ""), MAPPER);
    assertThatThrownBy(() -> none.requireLift("M", "O", "a", "s", "X"))
        .hasFieldOrPropertyWithValue("code", "CONFIG_REQUIRED");
  }
}
