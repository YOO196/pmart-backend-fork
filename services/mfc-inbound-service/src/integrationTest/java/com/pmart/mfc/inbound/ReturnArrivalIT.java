package com.pmart.mfc.inbound;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.pmart.common.test.EventEnvelopeFixtures;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 반납 도착 대기(MI-1·MI-2·MQ-1, 0.13.0): 오배송 회수 ReturnDelivered → 대기 → arrivalId 로 반입(claim 없음, 같은 상품 두
 * 주문 줄은 수량대로 나눔) → 대기에서 빠짐, 같은 건 다시 반입 409.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.inbound.post-interval=PT1H",
      "pmart.messaging.outbox.delete-after-insert=false",
      "pmart.test.context=return-arrival"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock(
    @ConfigureWireMock(
        name = "inventory",
        baseUrlProperties = "pmart.inbound.mfc-inventory-base-url"))
class ReturnArrivalIT extends PostgresKafkaContainerSupport {

  static final String MFC = "MFC-RA-1";
  static final String WRK = TestJwt.operator("wrk-ra", List.of("MFC_WORKER"), List.of(MFC));

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired KafkaTemplate<String, String> kafka;

  @InjectWireMock("inventory")
  WireMockServer inv;

  RestClient http() {
    return RestClient.builder()
        .baseUrl("http://localhost:" + port + "/api/v1")
        .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
        .build();
  }

  JsonNode get(String token, String uri) throws Exception {
    return mapper.readTree(
        http().get().uri(uri).headers(h -> h.setBearerAuth(token)).retrieve().body(String.class));
  }

  ResponseEntity<String> receive(Map<String, Object> body) {
    return http()
        .post()
        .uri("/return-receipts")
        .headers(
            h -> {
              h.setBearerAuth(WRK);
              h.set("Idempotency-Key", UUID.randomUUID().toString());
              h.set("X-Source-System", "mfc-pda");
            })
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .retrieve()
        .toEntity(String.class);
  }

  @Test
  void deliveredReturnWaitsThenReceiptFillsFromArrival() throws Exception {
    inv.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/receipts"))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"status\":\"APPLIED\",\"resultId\":\"CMT-RA1\",\"appliedQty\":3}")));
    inv.stubFor(
        WireMock.get(urlPathMatching("/api/v1/internal/receipts/.*"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"lines\":[{\"sku\":\"E-00000032\",\"lotId\":\"LOT-RA1\",\"locationCode\":\"RTN-A-01\",\"normalQty\":0}]}")));
    String dlv = "DLV-RET-" + UUID.randomUUID().toString().substring(0, 8);
    String json =
        EventEnvelopeFixtures.envelope("ReturnDelivered")
            .from("delivery-execution-service")
            .aggregate("Delivery", dlv, 3)
            .payload(
                Map.of(
                    "returnPickupId",
                    dlv,
                    "deliveryId",
                    dlv,
                    "orderId",
                    "ORD-RA1",
                    "mfcId",
                    MFC,
                    "purpose",
                    "MISDELIVERY_RECOVERY",
                    "caseId",
                    "CASE-RA1",
                    "originDeliveryId",
                    "DLV-ORIG-1",
                    "lines",
                    List.of(
                        Map.of("orderLineId", "OL-1", "sku", "E-00000032", "qty", 1),
                        Map.of("orderLineId", "OL-2", "sku", "E-00000032", "qty", 2)),
                    "deliveredAt",
                    "2026-11-19T05:00:00Z"))
            .buildJson();
    kafka.send("delivery.delivery.v1", dlv, json).join();
    JsonNode arrival = null;
    long until = System.currentTimeMillis() + 30_000;
    while (System.currentTimeMillis() < until && arrival == null) {
      for (JsonNode a : get(WRK, "/return-arrivals?mfcId=" + MFC).path("items")) {
        if (dlv.equals(a.path("arrivalId").asString())) {
          arrival = a;
        }
      }
      if (arrival == null) {
        Thread.sleep(200);
      }
    }
    assertThat(arrival).as("대기 목록에 올라옴").isNotNull();
    assertThat(arrival.path("state").asString()).isEqualTo("WAITING");
    assertThat(arrival.path("purpose").asString()).isEqualTo("MISDELIVERY_RECOVERY");
    assertThat(arrival.path("caseId").asString()).isEqualTo("CASE-RA1");
    assertThat(arrival.path("lines")).hasSize(2);

    // 점원이 상품 3개를 스캔(주문 줄 번호 없이) → 대기 줄 수량대로 OL-1 1개·OL-2 2개
    Map<String, Object> body =
        Map.of(
            "mfcId",
            MFC,
            "toLocationCode",
            "RTN-A-01",
            "arrivalId",
            dlv,
            "lines",
            List.of(Map.of("sku", "E-00000032", "qty", 3)));
    var e = receive(body);
    assertThat(e.getStatusCode().value()).as(e.getBody()).isEqualTo(202);
    String id = mapper.readTree(e.getBody()).path("resultId").asString();
    inv.verify(
        postRequestedFor(urlPathEqualTo("/api/v1/internal/receipts"))
            .withRequestBody(matchingJsonPath("$.receiptId", WireMock.equalTo(id)))
            .withRequestBody(matchingJsonPath("$.trfId", WireMock.equalTo("CASE-RA1")))
            .withRequestBody(matchingJsonPath("$.departureId", WireMock.equalTo(dlv))));
    JsonNode p =
        mapper.readTree(
            jdbc.queryForObject(
                "SELECT payload::text FROM outbox_event WHERE type = 'ReturnItemReceived'"
                    + " AND aggregateid = ?",
                String.class,
                id));
    if (p.has("payload")) {
      p = p.path("payload");
    }
    assertThat(p.has("claimId")).isFalse();
    assertThat(p.path("purpose").asString()).isEqualTo("MISDELIVERY_RECOVERY");
    assertThat(p.path("caseId").asString()).isEqualTo("CASE-RA1");
    assertThat(p.path("arrivalId").asString()).isEqualTo(dlv);
    assertThat(p.path("lines").findValuesAsString("orderLineId")).containsExactly("OL-1", "OL-2");
    assertThat(p.path("lines").path(1).path("qty").asInt()).isEqualTo(2);

    JsonNode view = get(WRK, "/return-receipts/" + id);
    assertThat(view.path("purpose").asString()).isEqualTo("MISDELIVERY_RECOVERY");
    assertThat(view.path("claimId").isNull()).isTrue();
    assertThat(view.path("arrivalId").asString()).isEqualTo(dlv);

    // 대기에서 빠지고, 다시 반입은 409
    assertThat(
            get(WRK, "/return-arrivals?mfcId=" + MFC).path("items").findValuesAsString("arrivalId"))
        .doesNotContain(dlv);
    assertThat(
            get(WRK, "/return-arrivals?mfcId=" + MFC + "&state=RECEIVED")
                .path("items")
                .findValuesAsString("returnReceiptId"))
        .contains(id);
    var again = receive(body);
    assertThat(again.getStatusCode().value()).as(again.getBody()).isEqualTo(409);
    assertThat(again.getBody()).contains("ARRIVAL_ALREADY_RECEIVED");

    // 다른 센터 운영자는 목록 403, arrivalId 도 claimId 도 없으면 400
    String other = TestJwt.operator("wrk-x", List.of("MFC_WORKER"), List.of("MFC-ELSE"));
    assertThat(
            http()
                .get()
                .uri("/return-arrivals?mfcId=" + MFC)
                .headers(h -> h.setBearerAuth(other))
                .retrieve()
                .toEntity(String.class)
                .getStatusCode()
                .value())
        .isEqualTo(403);
    var bad =
        receive(
            Map.of(
                "mfcId",
                MFC,
                "toLocationCode",
                "RTN-A-01",
                "lines",
                List.of(Map.of("sku", "E-00000032", "qty", 1))));
    assertThat(bad.getStatusCode().value()).as(bad.getBody()).isEqualTo(400);
  }
}
