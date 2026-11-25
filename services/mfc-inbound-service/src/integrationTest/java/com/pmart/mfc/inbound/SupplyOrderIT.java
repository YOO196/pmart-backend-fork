package com.pmart.mfc.inbound;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
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
import java.util.stream.StreamSupport;
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
 * 센터 발주 승인·발주 수량 추천(0.21.0, 요청 MI-4): 센터 관리자 승인(부분 승인 사유·거절 사유) → 입고 예정 EXPECTED, 인계 실적 이동평균으로 S =
 * d(R+L)+SS·IP = A + 입고 예정·Q, 값이 없으면 사유.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.inbound.post-interval=PT1H",
      "pmart.inbound.low-stock-basis-initial-delay=PT1H",
      "pmart.test.context=supply-order"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock(
    @ConfigureWireMock(
        name = "inventory",
        baseUrlProperties = "pmart.inbound.mfc-inventory-base-url"))
class SupplyOrderIT extends PostgresKafkaContainerSupport {

  static final String A = "E-00000071"; // 상온, 하루 10
  static final String B = "E-00000072"; // 냉장, 하루 2
  static final String C = "E-00000073"; // 온도대 모름
  static final String D = "E-00000074"; // 센터 취급 OFF

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired HandoverSales sales;
  @Autowired EventEnvelopeCodec codec;
  @Autowired com.pmart.mfc.inbound.application.LowStockBasisPublisher lowStock;

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
    mfc = "MFC-SO-" + UUID.randomUUID().toString().substring(0, 6);
    sup = TestJwt.operator("sup-1", List.of("MFC_SUPERVISOR"), List.of(mfc));
    inv.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/skus"))
            .willReturn(
                json(
                    "{\"items\":[{\"sku\":\""
                        + A
                        + "\",\"productName\":\"생수\",\"barcodes\":[],\"storageType\":\"AMBIENT\"},"
                        + "{\"sku\":\""
                        + B
                        + "\",\"productName\":\"우유\",\"barcodes\":[],\"storageType\":\"CHILLED\"},"
                        + "{\"sku\":\""
                        + D
                        + "\",\"productName\":\"단종\",\"barcodes\":[],\"storageType\":\"AMBIENT\"}]}")));
    inv.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/availability"))
            .willReturn(
                json(
                    "{\"mfcId\":\""
                        + mfc
                        + "\",\"asOf\":{\"asOf\":\"2026-11-24T00:00:00Z\",\"sourceVersion\":1,\"freshness\":\"CURRENT\"},\"items\":["
                        + "{\"sku\":\""
                        + A
                        + "\",\"available\":8,\"handlingEnabled\":true},"
                        + "{\"sku\":\""
                        + B
                        + "\",\"available\":100,\"handlingEnabled\":true},"
                        + "{\"sku\":\""
                        + C
                        + "\",\"available\":1,\"handlingEnabled\":true},"
                        + "{\"sku\":\""
                        + D
                        + "\",\"available\":5,\"handlingEnabled\":false}]}")));
  }

  static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String body) {
    return aResponse()
        .withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withBody(body);
  }

  Res call(String method, String token, String path, @Nullable Object body, String key) {
    var spec =
        http.method(org.springframework.http.HttpMethod.valueOf(method))
            .uri(path)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", key);
                  h.set("X-Source-System", "mfc-backoffice");
                });
    var e =
        (body == null ? spec : spec.contentType(MediaType.APPLICATION_JSON).body(body))
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  Res call(String method, String token, String path, @Nullable Object body) {
    return call(method, token, path, body, UUID.randomUUID().toString());
  }

  String submit(Map<String, Integer> lines) {
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("mfcId", mfc);
    b.put("requestedReadyAt", "2026-11-25T00:00:00Z");
    b.put("urgency", "NORMAL");
    b.put("reason", "추천값으로 채움");
    b.put(
        "lines",
        lines.entrySet().stream()
            .map(e -> Map.of("sku", e.getKey(), "qty", e.getValue()))
            .toList());
    Res r = call("POST", sup, "/supply-requests", b);
    assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
    return r.body().path("supplyRequestId").asString();
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

  JsonNode item(JsonNode rec, String sku) {
    return StreamSupport.stream(rec.path("items").spliterator(), false)
        .filter(n -> sku.equals(n.path("sku").asString()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("없는 SKU " + sku + ": " + rec));
  }

  @Test
  void supervisorApprovesIntoExpectedLoadAndRecommendationUsesIt() {
    // 인계 실적: 어제까지 28일, A 하루 10·B 하루 2(같은 commitId 는 한 번만)
    LocalDate yesterday = LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1);
    for (int i = 0; i < 28; i++) {
      handover(mfc + "-c" + i, yesterday.minusDays(i), Map.of(A, 10, B, 2));
    }
    handover(mfc + "-c0", yesterday, Map.of(A, 10, B, 2));
    // 오늘 인계는 아직 세지 않는다(어제까지)
    handover(mfc + "-today", yesterday.plusDays(1), Map.of(A, 500));

    // 발주 A 20 → 작업자는 승인 못 함, 줄이면 사유 필수, 요청 초과·전부 0 은 400
    String id = submit(Map.of(A, 20));
    String worker = TestJwt.operator("w-1", List.of("MFC_WORKER"), List.of(mfc));
    String path = "/supply-requests/" + id + "/approvals";
    assertThat(call("POST", worker, path, Map.of()).status()).isEqualTo(403);
    assertThat(
            call("POST", sup, path, Map.of("lines", List.of(Map.of("sku", A, "approvedQty", 15))))
                .status())
        .isEqualTo(400);
    assertThat(
            call(
                    "POST",
                    sup,
                    path,
                    Map.of("lines", List.of(Map.of("sku", A, "approvedQty", 21)), "note", "x"))
                .status())
        .isEqualTo(400);
    assertThat(
            call(
                    "POST",
                    sup,
                    path,
                    Map.of("lines", List.of(Map.of("sku", A, "approvedQty", 0)), "note", "x"))
                .status())
        .isEqualTo(400);

    // 부분 승인 15(사유) → APPROVED + 입고 예정 하나, 같은 키 다시 = 같은 결과
    String key = UUID.randomUUID().toString();
    Map<String, Object> approval =
        Map.of("lines", List.of(Map.of("sku", A, "approvedQty", 15)), "note", "적재 한도");
    Res ok = call("POST", sup, path, approval, key);
    assertThat(ok.status()).as(ok.body().toString()).isEqualTo(200);
    assertThat(ok.body().path("state").asString()).isEqualTo("APPROVED");
    assertThat(ok.body().path("version").asInt()).isEqualTo(2);
    assertThat(ok.body().path("decidedBy").asString()).isEqualTo("sup-1");
    assertThat(ok.body().path("decisionNote").asString()).isEqualTo("적재 한도");
    assertThat(ok.body().path("approvedLines").path(0).path("requestedQty").asInt()).isEqualTo(20);
    assertThat(ok.body().path("approvedLines").path(0).path("approvedQty").asInt()).isEqualTo(15);
    String receiptId = ok.body().path("linkedReceiptId").asString();
    assertThat(receiptId).startsWith("RCV-SR-");
    Res again = call("POST", sup, path, approval, key);
    assertThat(again.body().path("linkedReceiptId").asString()).isEqualTo(receiptId);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM initial_stock_load WHERE approval_id = ?", Integer.class, id))
        .isEqualTo(1);
    // 다시 승인(다른 키) 409, 수령 전 수량 조회 = 지금 입고 예정 목록 그대로
    assertThat(call("POST", sup, path, approval).status()).isEqualTo(409);
    Res list = call("GET", sup, "/initial-stock-loads?approvalId=" + id, null);
    assertThat(list.status()).as(list.body().toString()).isEqualTo(200);
    JsonNode load = new Res(200, list.body().path("items").path(0)).body();
    assertThat(load.path("receiptId").asString()).isEqualTo(receiptId);
    assertThat(load.path("state").asString()).isEqualTo("EXPECTED");
    assertThat(load.path("approvalId").asString()).isEqualTo(id);
    assertThat(load.path("lines").path(0).path("qty").asInt()).isEqualTo(15);
    assertThat(
            call("GET", sup, "/initial-stock-loads?mfcId=" + mfc + "&state=EXPECTED", null)
                .body()
                .path("items")
                .findValuesAsString("receiptId"))
        .contains(receiptId);
    assertThat(
            call("GET", sup, "/supply-requests?mfcId=" + mfc + "&state=APPROVED", null)
                .body()
                .path("items")
                .findValuesAsString("supplyRequestId"))
        .containsExactly(id);

    // 거절은 사유 필수, 거절 뒤 승인 409
    String rid = submit(Map.of(B, 4));
    String rpath = "/supply-requests/" + rid + "/rejections";
    assertThat(call("POST", sup, rpath, Map.of("reason", " ")).status()).isEqualTo(400);

    // 추천: A d=10, S = 10×(1+1) + 10 = 30, IP = 8 + 15 = 23 → 7
    Res rec =
        call(
            "GET",
            sup,
            "/supply-recommendations?mfcId="
                + mfc
                + "&sku="
                + A
                + "&sku="
                + B
                + "&sku="
                + C
                + "&sku="
                + D,
            null);
    assertThat(rec.status()).as(rec.body().toString()).isEqualTo(200);
    assertThat(rec.body().path("salesThrough").asString()).isEqualTo(yesterday.toString());
    // 0.21.1 — mfc-inventory asOf 는 AsOf 객체({asOf, sourceVersion, freshness})
    assertThat(rec.body().path("availabilityAsOf").asString()).startsWith("2026-11-24T00:00");
    JsonNode a = item(rec.body(), A);
    assertThat(a.path("productName").asString()).isEqualTo("생수");
    assertThat(a.path("historyDays").asInt()).isEqualTo(28);
    assertThat(a.path("forecastDailyQty").asDouble()).isEqualTo(10.0);
    assertThat(a.path("availableQty").asInt()).isEqualTo(8);
    assertThat(a.path("expectedQty").asInt()).isEqualTo(15);
    assertThat(a.path("inventoryPosition").asDouble()).isEqualTo(23.0);
    assertThat(a.path("targetQty").asDouble()).isEqualTo(30.0);
    assertThat(a.path("recommendedQty").asInt()).isEqualTo(7);
    assertThat(a.path("reason").isNull()).isTrue();
    assertThat(a.path("policySource").asString()).isEqualTo("*");
    // B: 승인 전 요청 4 는 IP 에 넣지 않고 보이기만, 충분하면 0
    JsonNode b = item(rec.body(), B);
    assertThat(b.path("pendingRequestedQty").asInt()).isEqualTo(4);
    assertThat(b.path("forecastDailyQty").asDouble()).isEqualTo(2.0);
    assertThat(b.path("recommendedQty").asInt()).isZero();
    assertThat(item(rec.body(), C).path("reason").asString()).isEqualTo("STORAGE_TYPE_UNKNOWN");
    assertThat(item(rec.body(), C).path("recommendedQty").isNull()).isTrue();
    assertThat(item(rec.body(), D).path("reason").asString()).isEqualTo("SKU_NOT_HANDLED");

    // 온도대 거르기: 냉장만(모르는 C 는 남긴다)
    assertThat(
            call(
                    "GET",
                    sup,
                    "/supply-recommendations?mfcId="
                        + mfc
                        + "&storageType=CHILLED&sku="
                        + A
                        + "&sku="
                        + B
                        + "&sku="
                        + C,
                    null)
                .body()
                .path("items")
                .findValuesAsString("sku"))
        .containsExactly(B, C);

    // 센터 정책 줄이 기본보다 우선 — 값이 비면 추천하지 않고 사유
    jdbc.update(
        "INSERT INTO supply_policy (mfc_id, storage_type, review_days, lead_days, safety_days,"
            + " order_multiple, min_order_qty, updated_at) VALUES (?, 'AMBIENT', 1, NULL, 1, 1, 1, now())",
        mfc);
    JsonNode missing =
        item(
            call("GET", sup, "/supply-recommendations?mfcId=" + mfc + "&sku=" + A, null).body(), A);
    assertThat(missing.path("reason").asString()).isEqualTo("POLICY_MISSING");
    assertThat(missing.path("missingPolicyFields").get(0).asString()).isEqualTo("leadDays");
    assertThat(missing.path("policySource").asString()).isEqualTo(mfc);
    assertThat(missing.path("recommendedQty").isNull()).isTrue();

    // 거절(사유) → REJECTED, 그 뒤 승인 409
    Res rej = call("POST", sup, rpath, Map.of("reason", "냉장 칸 부족"));
    assertThat(rej.status()).as(rej.body().toString()).isEqualTo(200);
    assertThat(rej.body().path("state").asString()).isEqualTo("REJECTED");
    assertThat(rej.body().path("rejectReason").asString()).isEqualTo("냉장 칸 부족");
    assertThat(call("POST", sup, "/supply-requests/" + rid + "/approvals", Map.of()).status())
        .isEqualTo(409);

    // 다른 센터 403
    assertThat(
            call(
                    "GET",
                    TestJwt.operator("x", List.of("MFC_SUPERVISOR"), List.of("MFC-OTHER")),
                    "/supply-recommendations?mfcId=" + mfc + "&sku=" + A,
                    null)
                .status())
        .isEqualTo(403);
  }

  @Test
  void newCenterWithoutHistoryGetsReasonNotZero() {
    Res rec = call("GET", sup, "/supply-recommendations?mfcId=" + mfc + "&sku=" + A, null);
    assertThat(rec.status()).as(rec.body().toString()).isEqualTo(200);
    JsonNode a = item(rec.body(), A);
    assertThat(a.path("historyDays").asInt()).isZero();
    assertThat(a.path("reason").asString()).isEqualTo("INSUFFICIENT_HISTORY");
    assertThat(a.path("recommendedQty").isNull()).isTrue();
    assertThat(a.path("forecastDailyQty").isNull()).isTrue();
  }

  /**
   * 곧 품절 기준(inventory 0.47.0): 발주 추천과 같은 d·정책을 inventory PUT 본문(계약 putLowStockBasis 모양)으로. 온도대를 모르는
   * SKU 는 빼고(inventory 가 모름 처리), SKU 사본 조회가 실패하면 보내지 않는다(판 SKU 를 d = 0 으로 만들지 않게).
   */
  @Test
  void lowStockBasisIsPushedWithSameDailyQtyAndPolicies() {
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
    for (int i = 1; i <= 10; i++) {
      handover("LS-" + mfc + "-" + i, today.minusDays(i), Map.of(A, 10, B, 2, C, 1));
    }
    String path = "/api/v1/internal/mfcs/" + mfc + "/low-stock-basis";
    inv.stubFor(WireMock.put(urlPathEqualTo(path)).willReturn(json("{\"applied\":true}")));

    assertThat(lowStock.publish(mfc)).isTrue();
    var reqs = inv.findAll(WireMock.putRequestedFor(urlPathEqualTo(path)));
    assertThat(reqs).hasSize(1);
    assertThat(reqs.get(0).getHeader("Idempotency-Key")).startsWith("LSB-" + mfc + "-");
    JsonNode body = mapper.readTree(reqs.get(0).getBodyAsString());
    assertThat(body.path("salesThrough").asString()).isEqualTo(today.minusDays(1).toString());
    assertThat(body.path("calculatedAt").asString()).isNotBlank();
    assertThat(body.path("policies").findValuesAsString("storageType"))
        .containsExactlyInAnyOrder("AMBIENT", "CHILLED", "FROZEN");
    for (JsonNode p : body.path("policies")) {
      assertThat(p.path("safetyDays").asDouble()).isEqualTo(1.0);
      assertThat(p.path("historyDays").asInt()).isEqualTo(10);
      assertThat(p.path("minHistoryDays").asInt()).isEqualTo(7);
      assertThat(p.path("averageWindowDays").asInt()).isEqualTo(28);
    }
    Map<String, Double> daily = new LinkedHashMap<>();
    body.path("dailyQty")
        .forEach(n -> daily.put(n.path("sku").asString(), n.path("qty").asDouble()));
    assertThat(daily).containsEntry(A, 10.0).containsEntry(B, 2.0).doesNotContainKey(C);

    // SKU 사본 조회 실패 → 이 센터는 보내지 않는다
    inv.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/skus"))
            .willReturn(aResponse().withStatus(503)));
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> lowStock.publish(mfc))
        .isInstanceOf(RuntimeException.class);
    assertThat(inv.findAll(WireMock.putRequestedFor(urlPathEqualTo(path)))).hasSize(1);
  }
}
