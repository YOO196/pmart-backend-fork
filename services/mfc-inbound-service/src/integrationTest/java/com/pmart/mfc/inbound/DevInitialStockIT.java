package com.pmart.mfc.inbound;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
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
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** [LAN 시험 전용] 시험 입고 예정(0.14.0, MI-3): 켜진 곳에서만, INITIAL_STOCK 승인과 같은 EXPECTED 하나 → 지금 현장 수령 그대로. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.inbound.post-interval=PT1H",
      "pmart.inbound.dev-arrivals.enabled=true"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock(
    @ConfigureWireMock(
        name = "inventory",
        baseUrlProperties = "pmart.inbound.mfc-inventory-base-url"))
class DevInitialStockIT extends PostgresKafkaContainerSupport {

  static final String MFC = "MFC-DEV-1";
  static final String WRK = TestJwt.operator("mfcw-pp-01", List.of("MFC_WORKER"), List.of(MFC));

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;

  @InjectWireMock("inventory")
  WireMockServer inv;

  ResponseEntity<String> create(String token, String key, Map<String, Object> body) {
    return RestClient.builder()
        .baseUrl("http://localhost:" + port + "/api/v1")
        .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
        .build()
        .post()
        .uri("/dev-initial-stock-loads")
        .headers(
            h -> {
              h.setBearerAuth(token);
              h.set("Idempotency-Key", key);
              h.set("X-Source-System", "mfc-simulator");
            })
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .retrieve()
        .toEntity(String.class);
  }

  @Test
  void createsExpectedLoadThatIsReceivedTheUsualWay() throws Exception {
    inv.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/skus"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"items\":[{\"sku\":\"E-00000051\",\"productName\":\"하루견과\",\"barcodes\":[]},"
                            + "{\"sku\":\"E-00000052\",\"productName\":\"생수\",\"barcodes\":[]}]}")));
    String key = UUID.randomUUID().toString();
    Map<String, Object> body =
        Map.of(
            "mfcId",
            MFC,
            "lines",
            List.of(
                Map.of("sku", "E-00000052", "qty", 500), Map.of("sku", "E-00000051", "qty", 12)));
    var e = create(WRK, key, body);
    assertThat(e.getStatusCode().value()).as(e.getBody()).isEqualTo(201);
    JsonNode load = mapper.readTree(e.getBody());
    assertThat(load.path("state").asString()).isEqualTo("EXPECTED");
    assertThat(load.path("approvalId").asString()).startsWith("DEV-");
    assertThat(load.path("mfcId").asString()).isEqualTo(MFC);
    assertThat(load.path("lines").findValuesAsString("sku"))
        .containsExactly("E-00000051", "E-00000052");
    assertThat(load.path("lines").path(0).path("productName").asString()).isEqualTo("하루견과");
    // 같은 키 = 같은 입고 예정
    var again = create(WRK, key, body);
    assertThat(mapper.readTree(again.getBody()).path("receiptId").asString())
        .isEqualTo(load.path("receiptId").asString());
    // 센터 목록(초기 보유 수령 대기)에 그대로 — 현장 수령은 지금 흐름
    JsonNode list =
        mapper.readTree(
            RestClient.create("http://localhost:" + port + "/api/v1")
                .get()
                .uri("/initial-stock-loads?mfcId=" + MFC)
                .headers(h -> h.setBearerAuth(WRK))
                .retrieve()
                .body(String.class));
    assertThat(list.path("items").findValuesAsString("receiptId"))
        .contains(load.path("receiptId").asString());

    // 모르는 SKU 400, 한도 넘는 수량 400, 다른 센터 403
    var unknown =
        create(
            WRK,
            UUID.randomUUID().toString(),
            Map.of("mfcId", MFC, "lines", List.of(Map.of("sku", "E-09999999", "qty", 1))));
    assertThat(unknown.getStatusCode().value()).isEqualTo(400);
    assertThat(unknown.getBody()).contains("UNKNOWN_SKU");
    assertThat(
            create(
                    WRK,
                    UUID.randomUUID().toString(),
                    Map.of("mfcId", MFC, "lines", List.of(Map.of("sku", "E-00000051", "qty", 501))))
                .getStatusCode()
                .value())
        .isEqualTo(400);
    String other = TestJwt.operator("x", List.of("MFC_WORKER"), List.of("MFC-ELSE"));
    assertThat(create(other, UUID.randomUUID().toString(), body).getStatusCode().value())
        .isEqualTo(403);
  }
}
