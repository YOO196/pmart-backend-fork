package com.pmart.mfc.quality;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import com.pmart.mfc.quality.application.DisposalService;
import com.pmart.mfc.quality.application.QualityDecisionService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 품질 판정·폐기(IV-09·IV-10): 격리 즉시 재고 반영, 해제는 요청 → 다른 센터 관리자 승인 → 재고 반영, 재고 결과 모름은 같은 키로 다시, 폐기는 요청 →
 * 승인(근거·부분 승인) → 승인 잔량 안에서 실행. 재고 정본은 WireMock.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.messaging.outbox.delete-after-insert=false",
      "pmart.quality.mfc-inventory-base-url=${inv.url}",
      "pmart.quality.sweep-interval=PT1H"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock({@ConfigureWireMock(name = "inv", baseUrlProperties = "inv.url")})
class QualityFlowIT extends PostgresKafkaContainerSupport {

  static final String MFC = "MFC-Q1";
  static final String W1 = TestJwt.operator("w1", List.of("MFC_WORKER"), List.of(MFC));
  static final String S1 = TestJwt.operator("s1", List.of("MFC_SUPERVISOR"), List.of(MFC));
  static final String S2 = TestJwt.operator("s2", List.of("MFC_SUPERVISOR"), List.of(MFC));
  static final String OTHER = TestJwt.operator("x1", List.of("MFC_SUPERVISOR"), List.of("MFC-ZZ"));
  static final String POSTINGS = "/api/v1/internal/quality-postings";

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired QualityDecisionService decisions;
  @Autowired DisposalService disposals;

  @InjectWireMock("inv")
  WireMockServer inv;

  RestClient http;

  record Res(int status, JsonNode body) {}

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    inv.resetAll();
  }

  static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(
      int status, Object body) {
    return aResponse()
        .withStatus(status)
        .withHeader("Content-Type", "application/json")
        .withBody(new ObjectMapper().writeValueAsString(body));
  }

  void inventoryApplies(String commitId, int qty) {
    inv.stubFor(
        WireMock.post(urlPathEqualTo(POSTINGS))
            .willReturn(
                json(201, Map.of("status", "APPLIED", "resultId", commitId, "appliedQty", qty))));
  }

  Res call(String method, String token, String path, @Nullable String key, @Nullable Object body) {
    var spec =
        http.method(org.springframework.http.HttpMethod.valueOf(method))
            .uri(path)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  if (key != null) {
                    h.set("Idempotency-Key", key);
                    h.set("X-Source-System", "mfc-pda");
                  }
                });
    var e =
        (body == null ? spec : spec.contentType(MediaType.APPLICATION_JSON).body(body))
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  static Map<String, Object> line(String sku, String lot, int qty) {
    return Map.of("sku", sku, "lotId", lot, "locationCode", "A-01-01", "qty", qty);
  }

  Res decide(String token, String type, String reason, int qty) {
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("mfcId", MFC);
    b.put("decisionType", type);
    b.put("reason", reason);
    b.put("lines", List.of(line("E-00000001", "LOT-1", qty)));
    return call("POST", token, "/quality-decisions", UUID.randomUUID().toString(), b);
  }

  int outbox(String type, String aggregateId) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM outbox_event WHERE type = ? AND aggregateid = ?",
        Integer.class,
        type,
        aggregateId);
  }

  @Test
  void quarantineAppliesAndReleaseNeedsAnotherSupervisor() {
    // 다른 센터·역할 없는 운영자·대리 판정은 막는다
    assertThat(decide(OTHER, "QUARANTINE", "DAMAGE", 2).status()).isEqualTo(403);
    Map<String, Object> spoof = new LinkedHashMap<>();
    spoof.put("mfcId", MFC);
    spoof.put("decisionType", "QUARANTINE");
    spoof.put("reason", "DAMAGE");
    spoof.put("decidedBy", "someone-else");
    spoof.put("lines", List.of(line("E-00000001", "LOT-1", 1)));
    assertThat(call("POST", W1, "/quality-decisions", "k-spoof", spoof).status()).isEqualTo(403);

    // 격리 — 바로 재고 반영(키 = 판정 ID)
    inventoryApplies("CMT-Q1", 2);
    Res q = decide(W1, "QUARANTINE", "DAMAGE", 2);
    assertThat(q.status()).as(q.body().toString()).isEqualTo(202);
    assertThat(q.body().path("status").asString()).isEqualTo("APPLIED");
    String qid = q.body().path("resultId").asString();
    inv.verify(
        postRequestedFor(urlPathEqualTo(POSTINGS))
            .withHeader("Idempotency-Key", equalTo(qid))
            .withRequestBody(matchingJsonPath("$.postingType", equalTo("QUARANTINE")))
            .withRequestBody(matchingJsonPath("$.lines[0].locationCode", equalTo("A-01-01"))));
    JsonNode got = call("GET", W1, "/quality-decisions/" + qid, null, null).body();
    assertThat(got.path("state").asString()).isEqualTo("APPLIED");
    assertThat(got.path("inventoryCommitId").asString()).isEqualTo("CMT-Q1");
    assertThat(outbox("QualityDecisionMade", qid)).isEqualTo(1);
    // F-7(0.4.0): 작업자 처리 내역 — w1 의 격리 판정 2개
    java.time.Instant now = java.time.Instant.now();
    JsonNode acts =
        call(
                "GET",
                com.pmart.common.test.TestJwt.service("mfc-capacity-service"),
                "/internal/work-activities?mfcId="
                    + MFC
                    + "&operatorId=w1&from="
                    + now.minusSeconds(3600)
                    + "&to="
                    + now.plusSeconds(3600),
                null,
                null)
            .body()
            .path("items");
    JsonNode mine = null;
    for (JsonNode a : acts) {
      if (qid.equals(a.path("refId").asString())) {
        mine = a;
      }
    }
    assertThat(mine).as("w1 의 격리 판정").isNotNull();
    assertThat(mine.path("activityType").asString()).isEqualTo("QUARANTINE");
    assertThat(mine.path("qty").asInt()).isEqualTo(2);

    // 해제 — 요청만(재고 안 감), 요청자 본인·작업자는 승인 못 함
    inv.resetRequests();
    Res r = decide(S1, "RELEASE", "INSPECTION_PASS", 1);
    assertThat(r.status()).isEqualTo(202);
    assertThat(r.body().path("status").asString()).isEqualTo("ACCEPTED");
    assertThat(r.body().path("nextAction").asString()).isEqualTo("AWAIT_APPROVAL");
    String rid = r.body().path("resultId").asString();
    inv.verify(0, postRequestedFor(urlPathEqualTo(POSTINGS)));
    Res self =
        call(
            "POST",
            S1,
            "/quality-decisions/" + rid + "/approvals",
            "k-self",
            Map.of("action", "APPROVE"));
    assertThat(self.status()).isEqualTo(422);
    assertThat(self.body().path("code").asString()).isEqualTo("SELF_APPROVAL");
    assertThat(
            call(
                    "POST",
                    W1,
                    "/quality-decisions/" + rid + "/approvals",
                    "k-w",
                    Map.of("action", "APPROVE"))
                .status())
        .isEqualTo(403);

    // 다른 센터 관리자 승인 → 재고 결과 모름 → 같은 키로 다시(sweep) → APPLIED
    inv.stubFor(WireMock.post(urlPathEqualTo(POSTINGS)).willReturn(json(500, Map.of())));
    Res a =
        call(
            "POST",
            S2,
            "/quality-decisions/" + rid + "/approvals",
            "k-ok",
            Map.of("action", "APPROVE"));
    assertThat(a.status()).as(a.body().toString()).isEqualTo(200);
    assertThat(a.body().path("state").asString()).isEqualTo("UNKNOWN_RESULT");
    assertThat(a.body().path("approverId").asString()).isEqualTo("s2");
    inventoryApplies("CMT-R1", 1);
    jdbc.update(
        "UPDATE quality_decision SET updated_at = now() - interval '1 minute' WHERE decision_id = ?",
        rid);
    decisions.sweep();
    JsonNode done = call("GET", S1, "/quality-decisions/" + rid, null, null).body();
    assertThat(done.path("state").asString()).isEqualTo("APPLIED");
    inv.verify(
        2,
        postRequestedFor(urlPathEqualTo(POSTINGS))
            .withHeader("Idempotency-Key", equalTo(rid))
            .withRequestBody(matchingJsonPath("$.postingType", equalTo("RELEASE"))));
    assertThat(outbox("QualityDecisionMade", rid)).isEqualTo(1);

    // 반려는 DECLINED, 재고 안 감
    String rid2 = decide(W1, "RELEASE", "INSPECTION_PASS", 1).body().path("resultId").asString();
    Res declined =
        call(
            "POST",
            S1,
            "/quality-decisions/" + rid2 + "/approvals",
            "k-no",
            Map.of("action", "REJECT", "note", "아직 미검수"));
    assertThat(declined.body().path("state").asString()).isEqualTo("DECLINED");
  }

  @Test
  void disposalNeedsApprovalAndExecutesWithinApprovedRemainder() {
    Map<String, Object> req = new LinkedHashMap<>();
    req.put("action", "REQUEST");
    req.put("mfcId", MFC);
    req.put("reason", "파손");
    req.put("sourceRef", "QD-x");
    req.put("lines", List.of(line("E-00000002", "LOT-2", 3)));
    Res r = call("POST", W1, "/disposal-approvals", UUID.randomUUID().toString(), req);
    assertThat(r.status()).as(r.body().toString()).isEqualTo(202);
    String id = r.body().path("resultId").asString();

    // 승인 전 실행은 안 된다
    Res early =
        call(
            "POST",
            W1,
            "/disposal-approvals/" + id + "/executions",
            UUID.randomUUID().toString(),
            Map.of("lines", List.of(line("E-00000002", "LOT-2", 1))));
    assertThat(early.status()).isEqualTo(409);

    // 근거 없는 승인·작업자 승인은 거절, 센터 관리자가 2개만 부분 승인
    Map<String, Object> approve = new LinkedHashMap<>();
    approve.put("action", "APPROVE");
    approve.put("mfcId", MFC);
    approve.put("disposalApprovalId", id);
    Res noRef = call("POST", S1, "/disposal-approvals", UUID.randomUUID().toString(), approve);
    assertThat(noRef.body().path("code").asString()).isEqualTo("APPROVAL_REQUIRED");
    approve.put("approvalRef", "APR-1");
    approve.put("lines", List.of(line("E-00000002", "LOT-2", 2)));
    assertThat(
            call("POST", W1, "/disposal-approvals", UUID.randomUUID().toString(), approve).status())
        .isEqualTo(403);
    Res ok = call("POST", S1, "/disposal-approvals", UUID.randomUUID().toString(), approve);
    assertThat(ok.status()).as(ok.body().toString()).isEqualTo(202);
    assertThat(ok.body().path("appliedQty").asInt()).isEqualTo(2);
    assertThat(outbox("DisposalApproved", id)).isEqualTo(1);

    // 실행 1 → 재고 DISPOSE(키 = 실행 ID), 미실행 1 유지
    inventoryApplies("CMT-D1", 1);
    String key = UUID.randomUUID().toString();
    Res ex =
        call(
            "POST",
            W1,
            "/disposal-approvals/" + id + "/executions",
            key,
            Map.of("lines", List.of(line("E-00000002", "LOT-2", 1))));
    assertThat(ex.status()).as(ex.body().toString()).isEqualTo(201);
    assertThat(ex.body().path("state").asString()).isEqualTo("APPLIED");
    inv.verify(
        postRequestedFor(urlPathEqualTo(POSTINGS))
            .withHeader("Idempotency-Key", equalTo(ex.body().path("executionId").asString()))
            .withRequestBody(matchingJsonPath("$.postingType", equalTo("DISPOSE")))
            .withRequestBody(matchingJsonPath("$.approvalRef", equalTo(id))));
    // 같은 키 → 같은 실행(재고 다시 안 감)
    assertThat(
            call(
                    "POST",
                    W1,
                    "/disposal-approvals/" + id + "/executions",
                    key,
                    Map.of("lines", List.of(line("E-00000002", "LOT-2", 1))))
                .body()
                .path("executionId")
                .asString())
        .isEqualTo(ex.body().path("executionId").asString());
    inv.verify(1, postRequestedFor(urlPathEqualTo(POSTINGS)));
    JsonNode view = call("GET", W1, "/disposal-approvals/" + id, null, null).body();
    assertThat(view.path("state").asString()).isEqualTo("PARTIALLY_EXECUTED");
    assertThat(view.path("lines").path(0).path("executedQty").asInt()).isEqualTo(1);
    assertThat(view.path("lines").path(0).path("remainingQty").asInt()).isEqualTo(1);

    // 남은 승인보다 많이는 안 된다 → 남은 1 실행 → COMPLETED
    Res over =
        call(
            "POST",
            W1,
            "/disposal-approvals/" + id + "/executions",
            UUID.randomUUID().toString(),
            Map.of("lines", List.of(line("E-00000002", "LOT-2", 2))));
    assertThat(over.status()).isEqualTo(422);
    assertThat(over.body().path("code").asString()).isEqualTo("QTY_EXCEEDS_APPROVED");
    assertThat(
            call(
                    "POST",
                    W1,
                    "/disposal-approvals/" + id + "/executions",
                    UUID.randomUUID().toString(),
                    Map.of("lines", List.of(line("E-00000002", "LOT-2", 1))))
                .status())
        .isEqualTo(201);
    assertThat(
            call("GET", W1, "/disposal-approvals/" + id, null, null)
                .body()
                .path("state")
                .asString())
        .isEqualTo("COMPLETED");
  }
}
