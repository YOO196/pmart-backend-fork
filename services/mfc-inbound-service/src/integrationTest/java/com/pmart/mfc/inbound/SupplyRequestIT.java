package com.pmart.mfc.inbound;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 로컬 공급 요청(WK-14): 센터 관리자만, 제출 = SUBMITTED + SupplyRequestSubmitted, 같은 SKU 미해결 요청 중복 거절, 변경은 새 버전,
 * 철회.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.messaging.outbox.delete-after-insert=false"
    })
@Import(TestJwtConfiguration.class)
class SupplyRequestIT extends PostgresKafkaContainerSupport {

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;

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
    mfc = "MFC-SR-" + UUID.randomUUID().toString().substring(0, 6);
    sup = TestJwt.operator("sup-1", List.of("MFC_SUPERVISOR"), List.of(mfc));
  }

  Res call(
      String method,
      String token,
      String path,
      @Nullable Object body,
      @Nullable Integer expectedVersion) {
    var spec =
        http.method(org.springframework.http.HttpMethod.valueOf(method))
            .uri(path)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", "backoffice-web");
                  if (expectedVersion != null) {
                    h.set("X-Expected-Version", expectedVersion.toString());
                  }
                });
    var e =
        (body == null ? spec : spec.contentType(MediaType.APPLICATION_JSON).body(body))
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  Map<String, Object> body(String sku, int qty) {
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("mfcId", mfc);
    b.put("requestedReadyAt", "2026-11-10T00:00:00Z");
    b.put("urgency", "NORMAL");
    b.put("reason", "주말 수요");
    b.put("lines", List.of(Map.of("sku", sku, "qty", qty)));
    return b;
  }

  @Test
  void supervisorSubmitsUpdatesAndWithdrawsWithoutDuplicates() {
    String worker = TestJwt.operator("w-1", List.of("MFC_WORKER"), List.of(mfc));
    assertThat(call("POST", worker, "/supply-requests", body("E-00000001", 10), null).status())
        .isEqualTo(403);
    Res r = call("POST", sup, "/supply-requests", body("E-00000001", 10), null);
    assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
    String id = r.body().path("supplyRequestId").asString();
    assertThat(r.body().path("state").asString()).isEqualTo("SUBMITTED");
    assertThat(r.body().path("version").asInt()).isEqualTo(1);
    assertThat(outbox(id)).isEqualTo(1);
    // MB-1(0.12.0): 공급 요청 목록 — 기본(끝나지 않은 것)에 있다, 다른 센터 403
    assertThat(
            call("GET", sup, "/supply-requests?mfcId=" + mfc, null, null)
                .body()
                .path("items")
                .findValuesAsString("supplyRequestId"))
        .contains(id);
    assertThat(
            call(
                    "GET",
                    TestJwt.operator("x", List.of("MFC_SUPERVISOR"), List.of("MFC-OTHER")),
                    "/supply-requests?mfcId=" + mfc,
                    null,
                    null)
                .status())
        .isEqualTo(403);

    // 같은 SKU 미해결 요청이 있으면 새로 받지 않는다
    Res dup = call("POST", sup, "/supply-requests", body("E-00000001", 5), null);
    assertThat(dup.status()).isEqualTo(409);
    assertThat(dup.body().path("code").asString()).isEqualTo("DUPLICATE_OPEN_REQUEST");

    // 변경 → 새 버전·다시 알림, 기대 버전이 다르면 충돌
    assertThat(
            call("PATCH", sup, "/supply-requests/" + id, body("E-00000001", 12), 9)
                .body()
                .path("code")
                .asString())
        .isEqualTo("VERSION_CONFLICT");
    Res up = call("PATCH", sup, "/supply-requests/" + id, body("E-00000001", 12), 1);
    assertThat(up.status()).as(up.body().toString()).isEqualTo(200);
    assertThat(up.body().path("version").asInt()).isEqualTo(2);
    assertThat(up.body().path("lines").path(0).path("qty").asInt()).isEqualTo(12);
    assertThat(outbox(id)).isEqualTo(2);

    // 철회 → 같은 SKU 새 요청 가능
    assertThat(
            call(
                    "POST",
                    sup,
                    "/supply-requests/" + id + "/withdrawals",
                    Map.of("reason", "취소"),
                    null)
                .status())
        .isEqualTo(202);
    assertThat(
            call("GET", sup, "/supply-requests/" + id, null, null).body().path("state").asString())
        .isEqualTo("WITHDRAWN");
    assertThat(call("PATCH", sup, "/supply-requests/" + id, body("E-00000001", 1), null).status())
        .isEqualTo(409);
    assertThat(call("POST", sup, "/supply-requests", body("E-00000001", 5), null).status())
        .isEqualTo(201);
  }

  int outbox(String id) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM outbox_event WHERE type = 'SupplyRequestSubmitted' AND aggregateid = ?",
        Integer.class,
        id);
  }

  @Test
  void concurrentRequestsForTheSameSkuCreateOnlyOne() throws Exception {
    String sku = "E-0000" + (1000 + new java.util.Random().nextInt(8999));
    var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
    try {
      List<java.util.concurrent.Future<Integer>> fs = new java.util.ArrayList<>();
      for (int i = 0; i < 4; i++) {
        fs.add(
            pool.submit(() -> call("POST", sup, "/supply-requests", body(sku, 3), null).status()));
      }
      List<Integer> codes = new java.util.ArrayList<>();
      for (var f : fs) {
        codes.add(f.get());
      }
      assertThat(codes).containsOnly(201, 409);
      assertThat(codes.stream().filter(c -> c == 201).count()).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }
}
