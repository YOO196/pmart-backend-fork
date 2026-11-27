package com.pmart.mfc.inbound;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import com.pmart.mfc.inbound.application.HandoverSales;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
 * 자동 보충(ADR-0091, 2026-11-26 사용자 "자동보충 만들어" → "추천 자동 발주 + 승인"): 인계 실적 → 자동 발주 초안(SUBMITTED, SYSTEM,
 * 하루 한 번) → 센터 관리자 승인 → 보급 DC 에 이관(TRF) 요청 + 입고 예정 EXPECTED.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.inbound.post-interval=PT1H",
      "pmart.inbound.low-stock-basis-initial-delay=PT1H",
      "pmart.inbound.supply-transfer-initial-delay=PT1H",
      "pmart.inbound.supplying-cdc=CDC01",
      "pmart.test.context=auto-supply"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock(
    @ConfigureWireMock(
        name = "inventory",
        baseUrlProperties = {
          "pmart.inbound.mfc-inventory-base-url",
          "pmart.inbound.cdc-outbound-base-url"
        }))
class AutoSupplyIT extends PostgresKafkaContainerSupport {

  static final String A = "E-00000081"; // 상온, 하루 10 — 가용 0 이라 추천 있음
  static final String C = "E-00000082"; // 냉장
  static final String F = "E-00000083"; // 냉동
  static final String X = "E-00000084"; // SKU 사본에 온도대 없음

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired HandoverSales sales;
  @Autowired EventEnvelopeCodec codec;

  @InjectWireMock("inventory")
  WireMockServer inv;

  RestClient http;
  String mfc;
  String sup;

  record Res(int status, JsonNode body) {}

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    mfc = "MFC-AS-" + UUID.randomUUID().toString().substring(0, 6);
    sup = TestJwt.operator("sup-as", List.of("MFC_SUPERVISOR"), List.of(mfc));
    inv.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/skus"))
            .willReturn(
                json(
                    "{\"items\":["
                        + sku(A, "AMBIENT")
                        + ","
                        + sku(C, "CHILLED")
                        + ","
                        + sku(F, "FROZEN")
                        + ",{\"sku\":\""
                        + X
                        + "\",\"productName\":\"모름\",\"barcodes\":[]}]}")));
    inv.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/availability"))
            .willReturn(
                json(
                    "{\"mfcId\":\""
                        + mfc
                        + "\",\"asOf\":{\"asOf\":\"2026-11-24T00:00:00Z\",\"sourceVersion\":1,\"freshness\":\"CURRENT\"},\"items\":["
                        + "{\"sku\":\""
                        + A
                        + "\",\"available\":0,\"handlingEnabled\":true}]}")));
    inv.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/transfer-intakes"))
            .willReturn(
                aResponse()
                    .withStatus(202)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"status\":\"ACCEPTED\",\"requestId\":\"x\",\"resultId\":\"x\"}")));
  }

  static String sku(String sku, String zone) {
    return "{\"sku\":\""
        + sku
        + "\",\"productName\":\"상품\",\"barcodes\":[],\"storageType\":\""
        + zone
        + "\"}";
  }

  String submit(Instant readyAt, Map<String, Integer> lines) {
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("mfcId", mfc);
    b.put("requestedReadyAt", readyAt.toString());
    b.put("urgency", "NORMAL");
    b.put("reason", "R-27 시험");
    b.put(
        "lines",
        lines.entrySet().stream()
            .map(e -> Map.of("sku", e.getKey(), "qty", e.getValue()))
            .toList());
    Res r = call("POST", sup, "/supply-requests", b);
    assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
    return r.body().path("supplyRequestId").asString();
  }

  static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String body) {
    return aResponse()
        .withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withBody(body);
  }

  Res call(String method, @Nullable String token, String path, @Nullable Object body) {
    var spec =
        http.method(org.springframework.http.HttpMethod.valueOf(method))
            .uri(path)
            .headers(
                h -> {
                  if (token != null) {
                    h.setBearerAuth(token);
                  }
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", "mfc-backoffice");
                });
    var e =
        (body == null ? spec : spec.contentType(MediaType.APPLICATION_JSON).body(body))
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  void handover(String commitId, LocalDate day, Map<String, Integer> lines) {
    Instant at = day.atTime(15, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant();
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("commitId", commitId);
    p.put("packageId", "PKG-" + commitId);
    p.put("mfcId", mfc);
    p.put(
        "lines",
        lines.entrySet().stream()
            .map(e -> Map.of("sku", e.getKey(), "qty", e.getValue()))
            .toList());
    p.put("postedAt", at.toString());
    EventEnvelope e =
        codec.create(
            "mfc-inventory-service",
            "PackageHandedOverPosted",
            1,
            "Package",
            "PKG-" + commitId,
            1,
            null,
            EventEnvelope.EventSource.REAL,
            p);
    sales.onInventoryEvent(e);
  }

  @Test
  void autoDraftThenApprovalSendsTransferToCdcAndExpectsInbound() {
    LocalDate yesterday = LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1);
    for (int i = 0; i < 10; i++) {
      handover(mfc + "-c" + i, yesterday.minusDays(i), Map.of(A, 10));
    }

    // 자동 발주 초안 — 하루 한 번
    Res run = call("POST", null, "/internal/auto-supply-runs", Map.of("mfcId", mfc));
    assertThat(run.status()).as(run.body().toString()).isEqualTo(200);
    assertThat(run.body().path("supplyRequestIds")).hasSize(1);
    String id = run.body().path("supplyRequestIds").path(0).asString();
    Res again = call("POST", null, "/internal/auto-supply-runs", Map.of("mfcId", mfc));
    assertThat(again.body().path("supplyRequestIds")).isEmpty();

    Res sr = call("GET", sup, "/supply-requests/" + id, null);
    assertThat(sr.status()).as(sr.body().toString()).isEqualTo(200);
    assertThat(sr.body().path("state").asString()).isEqualTo("SUBMITTED");
    assertThat(sr.body().path("submittedBy").asString()).isEqualTo("SYSTEM");
    assertThat(sr.body().path("recommendationRef").asString()).startsWith("AUTO-" + mfc + "-");
    assertThat(sr.body().path("lines").path(0).path("sku").asString()).isEqualTo(A);
    int qty = sr.body().path("lines").path(0).path("qty").asInt();
    assertThat(qty).isPositive();

    // 센터 관리자 승인 → CDC01 이관 요청(바로) + 입고 예정
    Res ok = call("POST", sup, "/supply-requests/" + id + "/approvals", Map.of("lines", List.of()));
    assertThat(ok.status()).as(ok.body().toString()).isEqualTo(200);
    assertThat(ok.body().path("state").asString()).isEqualTo("APPROVED");
    assertThat(ok.body().path("transferCdcId").asString()).isEqualTo("CDC01");
    String trf = ok.body().path("linkedTrfId").asString();
    assertThat(trf).isEqualTo("TRF-" + id);
    assertThat(ok.body().path("transferSentAt").isNull()).isFalse();
    assertThat(ok.body().path("linkedReceiptId").isNull()).isTrue();
    inv.verify(
        1,
        postRequestedFor(urlPathEqualTo("/api/v1/internal/transfer-intakes"))
            .withHeader("Idempotency-Key", equalTo("mfc-inbound-supply-trf:" + trf))
            .withRequestBody(matchingJsonPath("$.trfId", equalTo(trf)))
            .withRequestBody(matchingJsonPath("$.cdcId", equalTo("CDC01")))
            .withRequestBody(matchingJsonPath("$.mfcId", equalTo(mfc)))
            .withRequestBody(matchingJsonPath("$.approvalRef", equalTo(id)))
            .withRequestBody(matchingJsonPath("$.trfVersion", equalTo("1")))
            .withRequestBody(matchingJsonPath("$.deadlineMeaning", equalTo("READY")))
            .withRequestBody(matchingJsonPath("$.conditions[0].kind", equalTo("TEMPERATURE_ZONE")))
            .withRequestBody(matchingJsonPath("$.conditions[0].value", equalTo("AMBIENT")))
            .withRequestBody(matchingJsonPath("$.lines[0].sku", equalTo(A)))
            .withRequestBody(
                matchingJsonPath("$.lines[0].approvedQty", equalTo(String.valueOf(qty)))));

    Res p = call("GET", sup, "/inbound-promises/" + trf, null);
    assertThat(p.status()).as(p.body().toString()).isEqualTo(200);
    assertThat(p.body().path("state").asString()).isEqualTo("EXPECTED");
    assertThat(p.body().path("fromCdc").asString()).isEqualTo("CDC01");
    assertThat(p.body().path("lines").path(0).path("expectedQty").asInt()).isEqualTo(qty);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM initial_stock_load WHERE approval_id = ?", Integer.class, id))
        .isZero();
  }

  /** R-27: 온도대가 섞이면 TRF 를 온도대마다 나눈다 — 기한 = 희망 사용준비 시각(READY). */
  @Test
  void mixedTemperatureZonesSplitIntoOneTrfPerZone() {
    Instant ready =
        Instant.now()
            .plus(java.time.Duration.ofDays(2))
            .truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    Map<String, Integer> lines = new LinkedHashMap<>();
    lines.put(F, 3);
    lines.put(A, 5);
    lines.put(C, 2);
    String id = submit(ready, lines);

    Res ok = call("POST", sup, "/supply-requests/" + id + "/approvals", Map.of("lines", List.of()));
    assertThat(ok.status()).as(ok.body().toString()).isEqualTo(200);
    JsonNode ts = ok.body().path("transfers");
    assertThat(ts).hasSize(3);
    assertThat(ts.path(0).path("trfId").asString()).isEqualTo("TRF-" + id + "-AMBIENT");
    assertThat(ts.path(1).path("trfId").asString()).isEqualTo("TRF-" + id + "-CHILLED");
    assertThat(ts.path(2).path("trfId").asString()).isEqualTo("TRF-" + id + "-FROZEN");
    assertThat(ts.path(2).path("lines").path(0).path("sku").asString()).isEqualTo(F);
    assertThat(ok.body().path("linkedTrfId").asString()).isEqualTo("TRF-" + id + "-AMBIENT");
    assertThat(ok.body().path("transferSentAt").isNull()).isFalse();
    assertThat(Instant.parse(ok.body().path("transferDueAt").asString())).isEqualTo(ready);
    for (String z : List.of("AMBIENT", "CHILLED", "FROZEN")) {
      String trf = "TRF-" + id + "-" + z;
      inv.verify(
          1,
          postRequestedFor(urlPathEqualTo("/api/v1/internal/transfer-intakes"))
              .withRequestBody(matchingJsonPath("$.trfId", equalTo(trf)))
              .withRequestBody(matchingJsonPath("$.conditions[0].value", equalTo(z)))
              .withRequestBody(matchingJsonPath("$.lines.length()", equalTo("1"))));
      Res p = call("GET", sup, "/inbound-promises/" + trf, null);
      assertThat(p.status()).as(p.body().toString()).isEqualTo(200);
      assertThat(p.body().path("state").asString()).isEqualTo("EXPECTED");
    }
  }

  /** R-27: 온도대를 모르는 SKU 가 있으면 승인하지 않는다(422) — 요청은 SUBMITTED 그대로. */
  @Test
  void unknownStorageTypeRejectsApproval() {
    String id = submit(Instant.now().plus(java.time.Duration.ofDays(1)), Map.of(A, 1, X, 1));
    Res no = call("POST", sup, "/supply-requests/" + id + "/approvals", Map.of("lines", List.of()));
    assertThat(no.status()).as(no.body().toString()).isEqualTo(422);
    assertThat(no.body().path("code").asString()).isEqualTo("STORAGE_TYPE_UNKNOWN");
    assertThat(call("GET", sup, "/supply-requests/" + id, null).body().path("state").asString())
        .isEqualTo("SUBMITTED");
  }

  /** 0.24.1: cdc-outbound 가 인수·할당에 5초 넘게 걸려도(기본 시간 제한) 이 호출은 15초까지 기다려 한 번에 보낸다. */
  @Test
  void slowCdcIntakeWithinFifteenSecondsIsSentAtOnce() {
    String id = submit(Instant.now().plus(java.time.Duration.ofDays(1)), Map.of(C, 1));
    inv.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/transfer-intakes"))
            .withRequestBody(matchingJsonPath("$.trfId", equalTo("TRF-" + id)))
            .willReturn(
                aResponse()
                    .withStatus(202)
                    .withFixedDelay(6500)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"status\":\"ACCEPTED\",\"requestId\":\"x\",\"resultId\":\"x\"}")));
    Res ok = call("POST", sup, "/supply-requests/" + id + "/approvals", Map.of("lines", List.of()));
    assertThat(ok.status()).as(ok.body().toString()).isEqualTo(200);
    assertThat(ok.body().path("transferSentAt").isNull()).as(ok.body().toString()).isFalse();
  }

  /** CDC 가 내용으로 거절하면 멈추고 사유를 남긴다, 응답이 안 되면 다시 보낼 줄로 남는다. */
  @Test
  void cdcFailureIsKeptForRetryOrStops() {
    String id = submit(Instant.now().plus(java.time.Duration.ofDays(1)), Map.of(C, 2));
    inv.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/transfer-intakes"))
            .withRequestBody(matchingJsonPath("$.trfId", equalTo("TRF-" + id)))
            .willReturn(aResponse().withStatus(503)));
    Res ok = call("POST", sup, "/supply-requests/" + id + "/approvals", Map.of("lines", List.of()));
    assertThat(ok.status()).as(ok.body().toString()).isEqualTo(200);
    assertThat(ok.body().path("transferSentAt").isNull()).isTrue();
    assertThat(ok.body().path("transferError").asString()).startsWith("TRF-" + id + ": ");
    assertThat(
            jdbc.queryForObject(
                "SELECT next_try_at IS NOT NULL FROM supply_request_transfer WHERE trf_id = ?",
                Boolean.class,
                "TRF-" + id))
        .isTrue();
  }

  /** CO-3 ①: 출발 전 공급 요청 TRF 의 관리자 종결은 DC 미출발 취소가 먼저 — 피킹 시작이면 409 그대로, 취소되면 CLOSED. */
  @Test
  void supervisorClosureCancelsCdcTransferFirst() {
    String id = submit(Instant.now().plus(java.time.Duration.ofDays(1)), Map.of(A, 3));
    Res ok = call("POST", sup, "/supply-requests/" + id + "/approvals", Map.of("lines", List.of()));
    assertThat(ok.status()).as(ok.body().toString()).isEqualTo(200);
    String trf = ok.body().path("linkedTrfId").asString();
    String cancelPath = "/api/v1/internal/transfer-intakes/" + trf + "/cancellations";
    inv.stubFor(
        WireMock.post(urlPathEqualTo(cancelPath))
            .willReturn(
                aResponse()
                    .withStatus(409)
                    .withHeader("Content-Type", "application/problem+json")
                    .withBody(
                        "{\"code\":\"PICKING_STARTED\",\"detail\":\"피킹이 시작된 TRF\",\"status\":409}")));
    Res refused =
        call("POST", sup, "/inbound-promises/" + trf + "/closures", Map.of("reason", "필요 없어짐"));
    assertThat(refused.status()).as(refused.body().toString()).isEqualTo(409);
    assertThat(refused.body().path("code").asString()).isEqualTo("PICKING_STARTED");
    assertThat(call("GET", sup, "/inbound-promises/" + trf, null).body().path("state").asString())
        .isEqualTo("EXPECTED");

    inv.stubFor(
        WireMock.post(urlPathEqualTo(cancelPath))
            .willReturn(
                aResponse()
                    .withStatus(202)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"status\":\"APPLIED\",\"requestId\":\"x\",\"resultId\":\"x\"}")));
    Res closed =
        call("POST", sup, "/inbound-promises/" + trf + "/closures", Map.of("reason", "필요 없어짐"));
    assertThat(closed.status()).as(closed.body().toString()).isEqualTo(202);
    JsonNode p = call("GET", sup, "/inbound-promises/" + trf, null).body();
    assertThat(p.path("state").asString()).isEqualTo("CLOSED");
    assertThat(p.path("closure").path("cdcNotifyRequired").asBoolean()).isFalse();
    assertThat(p.path("closure").path("cdcCancelRequestId").asString())
        .startsWith("mfc-inbound-close:" + trf + ":");
    inv.verify(
        postRequestedFor(urlPathEqualTo(cancelPath))
            .withHeader("X-Source-System", equalTo("mfc-inbound-service"))
            .withRequestBody(matchingJsonPath("$.mfcId", equalTo(mfc)))
            .withRequestBody(matchingJsonPath("$.reason", equalTo("TRANSFER_CANCELLED")))
            .withRequestBody(matchingJsonPath("$.sourceRef", equalTo(id)))
            .withRequestBody(matchingJsonPath("$.requestedBy", equalTo("sup-as"))));
  }
}
