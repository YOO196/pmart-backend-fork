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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 회수품 반입(IV-10): 반품 위치에 격리로 수령(inventory 수령 quarantinedQty) → ReturnItemReceived → ReturnInspected
 * 의 재판매 분은 적치 작업.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.inbound.post-interval=PT1H",
      "pmart.messaging.outbox.delete-after-insert=false"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock(
    @ConfigureWireMock(
        name = "inventory",
        baseUrlProperties = "pmart.inbound.mfc-inventory-base-url"))
class ReturnReceiptIT extends PostgresKafkaContainerSupport {

  static final String MFC = "MFC-RR-1";
  static final String WRK = TestJwt.operator("wrk-r", List.of("MFC_WORKER"), List.of(MFC));

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired KafkaTemplate<String, String> kafka;

  @InjectWireMock("inventory")
  WireMockServer inv;

  @Test
  void returnedGoodsAreQuarantinedThenResaleBecomesPutaway() throws Exception {
    inv.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/receipts"))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"status\":\"APPLIED\",\"resultId\":\"CMT-RR1\",\"appliedQty\":2}")));
    inv.stubFor(
        WireMock.get(urlPathMatching("/api/v1/internal/receipts/.*"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"lines\":[{\"sku\":\"E-00000031\",\"lotId\":\"LOT-R1\",\"locationCode\":\"RTN-A-01\",\"normalQty\":0}]}")));
    RestClient http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    String claim = "CLM-" + UUID.randomUUID().toString().substring(0, 8);
    var e =
        http.post()
            .uri("/return-receipts")
            .headers(
                h -> {
                  h.setBearerAuth(WRK);
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", "mfc-pda");
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                mapper.writeValueAsString(
                    Map.of(
                        "mfcId",
                        MFC,
                        "toLocationCode",
                        "RTN-A-01",
                        "claimId",
                        claim,
                        "returnPickupId",
                        "RPU-1",
                        "lines",
                        List.of(
                            Map.of(
                                "sku", "E-00000031",
                                "qty", 2,
                                "expiryDate", "2027-01-31",
                                "conditionNote", "미개봉")))))
            .retrieve()
            .toEntity(String.class);
    assertThat(e.getStatusCode().value()).as(e.getBody()).isEqualTo(202);
    JsonNode res = mapper.readTree(e.getBody());
    assertThat(res.path("status").asString()).isEqualTo("APPLIED");
    String id = res.path("resultId").asString();
    // 격리로만 수령(정상 0)
    inv.verify(
        postRequestedFor(urlPathEqualTo("/api/v1/internal/receipts"))
            .withRequestBody(matchingJsonPath("$.lines[0].quarantinedQty", WireMock.equalTo("2")))
            .withRequestBody(matchingJsonPath("$.lines[0].normalQty", WireMock.equalTo("0")))
            .withRequestBody(matchingJsonPath("$.trfId", WireMock.equalTo(claim))));
    String received =
        String.join(
            " | ",
            jdbc.queryForList(
                "SELECT type || ':' || aggregateid || ':' || payload::text FROM outbox_event",
                String.class));
    assertThat(received).contains("ReturnItemReceived:" + id).contains("LOT-R1");

    // (0.15.0, BR-52 R12) 검수 대기 큐 — inspectionState=PENDING(반영된 것만, 오래된 순), 줄 온도대·상품명(SKU 사본)
    inv.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/skus"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"items\":[{\"sku\":\"E-00000031\",\"productName\":\"우유\",\"barcodes\":[],"
                            + "\"storageType\":\"CHILLED\",\"state\":\"ACTIVE\",\"productVersion\":1}],"
                            + "\"missing\":[]}")));
    JsonNode pending =
        mapper.readTree(
            http.get()
                .uri("/return-receipts?mfcId=" + MFC + "&inspectionState=PENDING")
                .headers(h -> h.setBearerAuth(WRK))
                .retrieve()
                .body(String.class));
    assertThat(pending.path("items").findValuesAsString("returnReceiptId")).contains(id);
    JsonNode mine = null;
    for (JsonNode i : pending.path("items")) {
      if (id.equals(i.path("returnReceiptId").asString())) {
        mine = i;
      }
    }
    assertThat(mine.path("lines").path(0).path("temperatureZone").asString()).isEqualTo("CHILLED");
    assertThat(mine.path("lines").path(0).path("productName").asString()).isEqualTo("우유");
    assertThat(
            http.get()
                .uri("/return-receipts?mfcId=" + MFC + "&inspectionState=DONE")
                .headers(h -> h.setBearerAuth(WRK))
                .retrieve()
                .toEntity(String.class)
                .getStatusCode()
                .value())
        .isEqualTo(400);

    // 검수: 재판매 1·폐기 1 → 재판매 1 적치 작업
    String json =
        EventEnvelopeFixtures.envelope("ReturnInspected")
            .from("mfc-quality-service")
            .aggregate("ReturnInspection", "RIN-1", 1)
            .payload(
                Map.of(
                    "inspectionId",
                    "RIN-1",
                    "claimId",
                    claim,
                    "returnReceiptId",
                    id,
                    "mfcId",
                    MFC,
                    "lines",
                    List.of(
                        Map.of(
                            "sku",
                            "E-00000031",
                            "lot",
                            "LOT-R1",
                            "qty",
                            1,
                            "disposition",
                            "RESALE"),
                        Map.of(
                            "sku",
                            "E-00000031",
                            "lot",
                            "LOT-R1",
                            "qty",
                            1,
                            "disposition",
                            "DISPOSE")),
                    "inspectedAt",
                    "2026-11-08T10:00:00Z"))
            .buildJson();
    kafka.send("mfc.quality.v1", "RIN-1", json).join();
    long until = System.currentTimeMillis() + 30_000;
    Integer n = 0;
    while (System.currentTimeMillis() < until) {
      n =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM inbound_task WHERE receipt_id = ? AND task_type = 'PUTAWAY'"
                  + " AND lot_id = 'LOT-R1' AND planned_qty = 1 AND location_code = 'RTN-A-01'",
              Integer.class,
              id);
      if (n != null && n > 0) {
        break;
      }
      Thread.sleep(200);
    }
    assertThat(n).isEqualTo(1);
    JsonNode view =
        mapper.readTree(
            http.get()
                .uri("/return-receipts/" + id)
                .headers(h -> h.setBearerAuth(WRK))
                .retrieve()
                .body(String.class));
    assertThat(view.path("inspectionState").asString()).isEqualTo("INSPECTED");
    assertThat(view.path("putawayTaskIds")).hasSize(1);
    assertThat(view.path("lines").path(0).path("lotId").asString()).isEqualTo("LOT-R1");
    assertThat(view.path("lines").path(0).path("temperatureZone").asString()).isEqualTo("CHILLED");
    // 검수 끝 → 대기 큐에서 빠지고 INSPECTED 로 보인다
    assertThat(
            mapper
                .readTree(
                    http.get()
                        .uri("/return-receipts?mfcId=" + MFC + "&inspectionState=PENDING")
                        .headers(h -> h.setBearerAuth(WRK))
                        .retrieve()
                        .body(String.class))
                .path("items")
                .findValuesAsString("returnReceiptId"))
        .doesNotContain(id);
    assertThat(
            mapper
                .readTree(
                    http.get()
                        .uri("/return-receipts?mfcId=" + MFC + "&inspectionState=INSPECTED")
                        .headers(h -> h.setBearerAuth(WRK))
                        .retrieve()
                        .body(String.class))
                .path("items")
                .findValuesAsString("returnReceiptId"))
        .contains(id);
    // MB-1(0.12.0): 회수품 반입 목록 — 최근 순, 기간 거르기
    JsonNode list =
        mapper.readTree(
            http.get()
                .uri("/return-receipts?mfcId=" + MFC)
                .headers(h -> h.setBearerAuth(WRK))
                .retrieve()
                .body(String.class));
    assertThat(list.path("items").findValuesAsString("returnReceiptId")).contains(id);
    assertThat(list.path("items").path(0).path("inspectionState").asString()).isNotBlank();
    JsonNode future =
        mapper.readTree(
            http.get()
                .uri("/return-receipts?mfcId=" + MFC + "&from=2099-01-01T00:00:00Z")
                .headers(h -> h.setBearerAuth(WRK))
                .retrieve()
                .body(String.class));
    assertThat(future.path("items")).isEmpty();
  }
}
