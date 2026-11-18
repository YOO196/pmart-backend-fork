package com.pmart.mfc.quality;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.pmart.common.test.EventEnvelopeFixtures;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 회수품 검수(IV-10): 반입 사본(ReturnItemReceived) 기준, 반입자 아닌 센터 관리자, 재판매는 상온·미개봉만 → 격리 해제(반품 위치 UNSTOWED),
 * 폐기는 폐기 승인 요청 → ReturnInspected.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.messaging.outbox.delete-after-insert=false",
      "pmart.quality.mfc-inventory-base-url=${inv.url}",
      "pmart.quality.sweep-interval=PT1H",
      "pmart.test.context=return-inspection" // QualityFlowIT 와 같은 문맥을 재사용하지 않게(그 시험의 DB 컨테이너는 끝나면
      // 멈춤)
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock({@ConfigureWireMock(name = "inv", baseUrlProperties = "inv.url")})
class ReturnInspectionIT extends PostgresKafkaContainerSupport {

  static final String MFC = "MFC-RI1";
  static final String S1 = TestJwt.operator("s1", List.of("MFC_SUPERVISOR"), List.of(MFC));
  static final String RECEIVER = TestJwt.operator("rcv", List.of("MFC_SUPERVISOR"), List.of(MFC));

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired KafkaTemplate<String, String> kafka;

  @InjectWireMock("inv")
  WireMockServer inv;

  record Res(int status, JsonNode body) {}

  Res get(String token, String path) {
    var e =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(org.springframework.http.HttpStatusCode::isError, (q, r) -> {})
            .build()
            .get()
            .uri(path)
            .headers(h -> h.setBearerAuth(token))
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  Res inspect(String token, String who, String rrc, List<Map<String, Object>> lines) {
    RestClient http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("mfcId", MFC);
    b.put("claimId", "CLM-RI");
    b.put("returnReceiptId", rrc);
    b.put("inspectedBy", who);
    b.put("lines", lines);
    var e =
        http.post()
            .uri("/return-inspections")
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", "mfc-pda");
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapper.writeValueAsString(b))
            .retrieve()
            .toEntity(String.class);
    String body = e.getBody();
    return new Res(
        e.getStatusCode().value(),
        body == null ? mapper.createObjectNode() : mapper.readTree(body));
  }

  static Map<String, Object> line(int qty, String verdict, String zone, boolean opened) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("sku", "E-00000041");
    m.put("qty", qty);
    m.put("verdict", verdict);
    m.put("temperatureZone", zone);
    m.put("opened", opened);
    if ("DISPOSE".equals(verdict)) {
      m.put("disposeReason", "OPENED");
    }
    return m;
  }

  @Test
  void returnedGoodsAreInspectedAndResaleIsReleasedFromQuarantine() throws Exception {
    String rrc = "RRC-" + UUID.randomUUID().toString().substring(0, 8);
    // 반입 전에는 409
    assertThat(inspect(S1, "s1", rrc, List.of(line(2, "DISPOSE", "AMBIENT", true))).status())
        .isEqualTo(409);
    String json =
        EventEnvelopeFixtures.envelope("ReturnItemReceived")
            .from("mfc-inbound-service")
            .aggregate("ReturnReceipt", rrc, 1)
            .payload(
                Map.of(
                    "returnReceiptId", rrc,
                    "claimId", "CLM-RI",
                    "returnPickupId", "RPU-1",
                    "mfcId", MFC,
                    "lines",
                        List.of(
                            Map.of(
                                "sku",
                                "E-00000041",
                                "lot",
                                "LOT-RET",
                                "qty",
                                2,
                                "locationCode",
                                "RTN-A-01")),
                    "receivedBy", "rcv",
                    "receivedAt", "2026-11-08T09:00:00Z"))
            .buildJson();
    kafka.send("mfc.inbound.v1", rrc, json).join();
    long until = System.currentTimeMillis() + 30_000;
    Integer n = 0;
    while (System.currentTimeMillis() < until) {
      n =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM return_receipt_projection WHERE return_receipt_id = ?",
              Integer.class,
              rrc);
      if (n != null && n > 0) {
        break;
      }
      Thread.sleep(200);
    }
    assertThat(n).isEqualTo(1);

    // 반입자는 검수 못 함, 냉장 재판매·개봉 재판매 불가, 수량은 반입분 전부
    assertThat(
            inspect(RECEIVER, "rcv", rrc, List.of(line(2, "DISPOSE", "AMBIENT", true)))
                .body()
                .path("code")
                .asString())
        .isEqualTo("SELF_APPROVAL");
    assertThat(
            inspect(S1, "s1", rrc, List.of(line(2, "RESALE", "CHILLED", false)))
                .body()
                .path("code")
                .asString())
        .isEqualTo("RESALE_NOT_ALLOWED");
    assertThat(
            inspect(S1, "s1", rrc, List.of(line(1, "RESALE", "AMBIENT", false)))
                .body()
                .path("code")
                .asString())
        .isEqualTo("QTY_EXCEEDS_RECEIVED");

    inv.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/quality-postings"))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"status\":\"APPLIED\",\"resultId\":\"CMT-REL\",\"appliedQty\":1}")));
    Res ok =
        inspect(
            S1,
            "s1",
            rrc,
            List.of(line(1, "RESALE", "AMBIENT", false), line(1, "DISPOSE", "AMBIENT", true)));
    assertThat(ok.status()).as(ok.body().toString()).isEqualTo(202);
    assertThat(ok.body().path("status").asString()).isEqualTo("APPLIED");
    // 재판매 1 → 격리 해제(반품 위치 그대로 UNSTOWED)
    inv.verify(
        postRequestedFor(urlPathEqualTo("/api/v1/internal/quality-postings"))
            .withRequestBody(matchingJsonPath("$.postingType", WireMock.equalTo("RELEASE")))
            .withRequestBody(matchingJsonPath("$.lines[0].workState", WireMock.equalTo("UNSTOWED")))
            .withRequestBody(matchingJsonPath("$.lines[0].lotId", WireMock.equalTo("LOT-RET")))
            .withRequestBody(
                matchingJsonPath("$.lines[0].locationCode", WireMock.equalTo("RTN-A-01"))));
    String id = ok.body().path("resultId").asString();
    // MB-1(0.5.0): 회수품 검수 목록 — 재고 반영 끝(APPLIED)은 state 로 고를 때만
    JsonNode applied =
        get(S1, "/return-inspections?mfcId=" + MFC + "&state=APPLIED&size=200")
            .body()
            .path("items");
    assertThat(applied.findValuesAsString("returnInspectionId")).contains(id);
    assertThat(
            get(S1, "/return-inspections?mfcId=" + MFC + "&size=200")
                .body()
                .path("items")
                .findValuesAsString("returnInspectionId"))
        .doesNotContain(id);
    String evt =
        jdbc.queryForObject(
            "SELECT payload::text FROM outbox_event WHERE type = 'ReturnInspected' AND aggregateid = ?",
            String.class,
            id);
    JsonNode ev = mapper.readTree(evt).path("payload").path("lines");
    assertThat(ev.findValuesAsString("disposition")).containsExactly("RESALE", "DISPOSE");
    // 폐기 1 → 폐기 승인 요청(센터 관리자 승인·실행은 기존 흐름)
    assertThat(
            jdbc.queryForObject(
                "SELECT disposal_approval_id IS NOT NULL FROM return_inspection WHERE return_inspection_id = ?",
                Boolean.class,
                id))
        .isTrue();
    // 같은 반입은 다시 검수 안 함
    assertThat(inspect(S1, "s1", rrc, List.of(line(2, "DISPOSE", "AMBIENT", true))).status())
        .isEqualTo(409);
  }
}
