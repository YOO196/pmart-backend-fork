package com.pmart.mfc.location;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 위치 마스터(ADR-0040 2단계, TC-03 영역/위치 구분의 위치 쪽): 등록(센터 운영자만, 코드 센터 안 유일) → 속성 변경(코드 불변, 버전) → 상태
 * 전이(ACTIVE↔CLOSED·COUNT_LOCKED, RETIRED 는 DELETE) → 적치 후보(온도대·ACTIVE·선반) → MfcLocationStateChanged
 * Outbox.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.messaging.outbox.delete-after-insert=false",
      "pmart.location.mfc-inventory-base-url=${inv.url}"
    })
@Import(TestJwtConfiguration.class)
@org.wiremock.spring.EnableWireMock({
  @org.wiremock.spring.ConfigureWireMock(name = "inv", baseUrlProperties = "inv.url")
})
class LocationFlowIT extends PostgresKafkaContainerSupport {

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;

  @org.wiremock.spring.InjectWireMock("inv")
  com.github.tomakehurst.wiremock.WireMockServer inv;

  RestClient http;
  String mfc;
  String op;

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    mfc = "MFC-IT-" + UUID.randomUUID().toString().substring(0, 8);
    op = TestJwt.operator("op-1", List.of("MFC_SUPERVISOR"), List.of(mfc));
  }

  record Res(int status, JsonNode body) {}

  Res send(HttpMethod m, String token, String path, Object body, Integer expectedVersion) {
    var spec =
        http.method(m)
            .uri(path)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", "backoffice-web");
                  if (expectedVersion != null) {
                    h.set("X-Expected-Version", String.valueOf(expectedVersion));
                  }
                })
            .contentType(MediaType.APPLICATION_JSON);
    var e = (body == null ? spec : spec.body(body)).retrieve().toEntity(String.class);
    return new Res(e.getStatusCode().value(), read(e.getBody()));
  }

  Res get(String token, String path) {
    var e =
        http.get().uri(path).headers(h -> h.setBearerAuth(token)).retrieve().toEntity(String.class);
    return new Res(e.getStatusCode().value(), read(e.getBody()));
  }

  JsonNode read(String body) {
    return mapper.readTree(body == null || body.isBlank() ? "{}" : body);
  }

  Map<String, Object> body(String code, String zone, String temp, Integer seq) {
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("mfcId", mfc);
    b.put("locationCode", code);
    b.put("zone", zone);
    b.put("temperatureZone", temp);
    if (seq != null) {
      b.put("pickSequence", seq);
    }
    return b;
  }

  void inventoryItems(int count) {
    inv.resetAll();
    List<Map<String, Object>> items = new java.util.ArrayList<>();
    for (int i = 0; i < count; i++) {
      items.add(Map.of("locationCode", "A-S9-01-1-01", "qty", 1));
    }
    inv.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.get(
                com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo(
                    "/api/v1/internal/snapshots"))
            .willReturn(
                com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(mapper.writeValueAsString(Map.of("items", items)))));
  }

  /** 폐지: CLOSED 이고 mfc-inventory 에 그 위치 재고가 없을 때만 RETIRED(되돌리지 않음), 재고 조회 실패면 폐지하지 않는다. */
  @Test
  void retireOnlyClosedEmptyLocation() {
    String id =
        send(
                HttpMethod.POST,
                op,
                "/locations",
                body("A-S9-01-1-01", "STORAGE", "AMBIENT", null),
                null)
            .body()
            .path("locationId")
            .asString();
    // ACTIVE 는 폐지 못 한다
    assertThat(send(HttpMethod.DELETE, op, "/locations/" + id, null, null).status()).isEqualTo(409);
    send(
        HttpMethod.POST,
        op,
        "/locations/" + id + "/state-changes",
        Map.of("targetState", "CLOSED", "reason", "철거"),
        null);
    // 재고가 남아 있으면 거절
    inventoryItems(1);
    Res left = send(HttpMethod.DELETE, op, "/locations/" + id, null, null);
    assertThat(left.status()).isEqualTo(409);
    assertThat(left.body().path("detail").asString()).contains("재고");
    // 재고 조회 실패 → 폐지 안 함
    inv.resetAll();
    inv.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.get(
                com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo(
                    "/api/v1/internal/snapshots"))
            .willReturn(
                com.github.tomakehurst.wiremock.client.WireMock.aResponse().withStatus(503)));
    assertThat(send(HttpMethod.DELETE, op, "/locations/" + id, null, null).status()).isEqualTo(503);
    // 비었으면 RETIRED + MfcLocationStateChanged, 다시 해도 같은 상태
    inventoryItems(0);
    Res ok = send(HttpMethod.DELETE, op, "/locations/" + id, null, null);
    assertThat(ok.status()).as(ok.body().toString()).isEqualTo(202);
    assertThat(get(op, "/locations/" + id).body().path("state").asString()).isEqualTo("RETIRED");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE type = 'MfcLocationStateChanged'"
                    + " AND aggregateid = ? AND payload::text LIKE '%RETIRED%'",
                Integer.class, id))
        .isEqualTo(1);
    assertThat(send(HttpMethod.DELETE, op, "/locations/" + id, null, null).status()).isEqualTo(202);
    // RETIRED 는 되돌리지 않는다
    assertThat(
            send(
                    HttpMethod.POST,
                    op,
                    "/locations/" + id + "/state-changes",
                    Map.of("targetState", "ACTIVE", "reason", "x"),
                    null)
                .status())
        .isEqualTo(409);
  }

  /** 0.6.0 도면 위 위치(ADR-0050): 등록·변경에 넣으면 그대로 돌려주고 조회에도 남는다, 범위 밖은 400, PUT 에 빠지면 지워진다. */
  @Test
  void floorPositionIsStoredAndValidated() {
    Map<String, Object> b = body("F-A3-05-2-01", "STORAGE", "FROZEN", 7);
    b.put("position", Map.of("xM", 12.35, "zM", 7.5));
    Res created = send(HttpMethod.POST, op, "/locations", b, null);
    assertThat(created.status()).as(created.body().toString()).isEqualTo(201);
    String id = created.body().path("locationId").asString();
    assertThat(created.body().path("position").path("xM").asDouble()).isEqualTo(12.35);
    assertThat(created.body().path("areaCode").asString()).isEqualTo("A3");
    assertThat(get(op, "/locations/" + id).body().path("position").path("zM").asDouble())
        .isEqualTo(7.5);
    Map<String, Object> bad = body("F-A3-05-2-01", "STORAGE", "FROZEN", 7);
    bad.put("position", Map.of("xM", -1, "zM", 0));
    assertThat(send(HttpMethod.PUT, op, "/locations/" + id, bad, 1).status()).isEqualTo(400);
    Res cleared =
        send(
            HttpMethod.PUT,
            op,
            "/locations/" + id,
            body("F-A3-05-2-01", "STORAGE", "FROZEN", 7),
            1);
    assertThat(cleared.status()).isEqualTo(200);
    assertThat(cleared.body().path("position").isNull()).isTrue();
  }

  /** 0.7.0 칸 사이 거리표(내부): 좌표 있는 칸끼리 직각 거리, 좌표 없거나 없는 코드는 missing, 벽은 아직 안 봄. */
  @Test
  void distanceTableFromFloorPositions() {
    for (Object[] c :
        new Object[][] {
          {"A-C1-01-1-01", 1.0, 2.0}, {"A-C1-01-1-02", 4.0, 6.0}, {"A-C1-01-1-03", null, null}
        }) {
      Map<String, Object> b = body((String) c[0], "STORAGE", "AMBIENT", null);
      if (c[1] != null) {
        b.put("position", Map.of("xM", c[1], "zM", c[2]));
      }
      assertThat(send(HttpMethod.POST, op, "/locations", b, null).status()).isEqualTo(201);
    }
    Res r =
        send(
            HttpMethod.POST,
            TestJwt.service("mfc-capacity-service"),
            "/internal/location-distances",
            Map.of(
                "mfcId",
                mfc,
                "locationCodes",
                List.of("A-C1-01-1-02", "A-C1-01-1-01", "A-C1-01-1-03", "NOPE", "A-C1-01-1-02")),
            null);
    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    assertThat(r.body().path("method").asString()).isEqualTo("MANHATTAN_V1");
    assertThat(r.body().path("limitations").toString()).contains("WALLS_NOT_CONSIDERED");
    assertThat(r.body().path("codes").toString()).isEqualTo("[\"A-C1-01-1-02\",\"A-C1-01-1-01\"]");
    assertThat(r.body().path("meters").path(0).path(1).asDouble()).isEqualTo(7.0);
    assertThat(r.body().path("missing").toString()).contains("A-C1-01-1-03", "NOPE");
    // 0.7.1 칸 속성(피킹 순서의 온도대·그룹 판단용)
    assertThat(r.body().path("attributes").path(0).path("temperatureZone").asString())
        .isEqualTo("AMBIENT");
    assertThat(r.body().path("attributes").path(1).path("areaCode").asString()).isEqualTo("C1");
  }

  @Test
  void registerChangeAndListLocations() {
    // MB-3(2026-11-18): 칸 등록·변경은 그 센터 MFC_SUPERVISOR 만 — 작업자 403
    assertThat(
            send(
                    HttpMethod.POST,
                    TestJwt.operator("wrk", List.of("MFC_WORKER"), List.of(mfc)),
                    "/locations",
                    body("C-S1-09-1-01", "STORAGE", "CHILLED", 2),
                    null)
                .status())
        .isEqualTo(403);
    Res created =
        send(
            HttpMethod.POST, op, "/locations", body("C-S1-01-1-01", "STORAGE", "CHILLED", 2), null);
    assertThat(created.status()).isEqualTo(201);
    String id = created.body().path("locationId").asString();
    assertThat(id).startsWith("LOC-");
    assertThat(created.body().path("state").asString()).isEqualTo("ACTIVE");
    assertThat(created.body().path("version").asInt()).isEqualTo(1);

    // 같은 센터 같은 코드 → 409, 다른 센터 운영자 → 403
    Res dup =
        send(
            HttpMethod.POST,
            op,
            "/locations",
            body("C-S1-01-1-01", "STORAGE", "CHILLED", null),
            null);
    assertThat(dup.status()).isEqualTo(409);
    assertThat(dup.body().path("code").asString()).isEqualTo("LOCATION_CODE_DUPLICATE");
    // 코드 문법(Q-03, ADR-0042): 옛 자유 형식·온도대 불일치 → 400, 구역은 코드에서 채운다
    Res legacy =
        send(HttpMethod.POST, op, "/locations", body("C-01-01", "STORAGE", "CHILLED", null), null);
    assertThat(legacy.status()).isEqualTo(400);
    assertThat(legacy.body().path("code").asString()).isEqualTo("LOCATION_CODE_INVALID");
    assertThat(
            send(
                    HttpMethod.POST,
                    op,
                    "/locations",
                    body("A-S1-01-1-02", "STORAGE", "CHILLED", null),
                    null)
                .status())
        .isEqualTo(400);
    assertThat(created.body().path("areaCode").asString()).isEqualTo("S1");
    String other = TestJwt.operator("op-2", List.of(), List.of("MFC-OTHER"));
    assertThat(
            send(
                    HttpMethod.POST,
                    other,
                    "/locations",
                    body("A-S1-09-1-01", "STORAGE", "AMBIENT", null),
                    null)
                .status())
        .isEqualTo(403);

    // 속성 변경(동선 순서), 코드 변경 → 400, 낡은 버전 → 409
    Res updated =
        send(
            HttpMethod.PUT,
            op,
            "/locations/" + id,
            body("C-S1-01-1-01", "STORAGE", "CHILLED", 1),
            1);
    assertThat(updated.status()).isEqualTo(200);
    assertThat(updated.body().path("version").asInt()).isEqualTo(2);
    assertThat(updated.body().path("pickSequence").asInt()).isEqualTo(1);
    assertThat(
            send(
                    HttpMethod.PUT,
                    op,
                    "/locations/" + id,
                    body("C-S1-01-1-02", "STORAGE", "CHILLED", 1),
                    2)
                .status())
        .isEqualTo(400);
    assertThat(
            send(
                    HttpMethod.PUT,
                    op,
                    "/locations/" + id,
                    body("C-S1-01-1-01", "STORAGE", "CHILLED", 3),
                    1)
                .status())
        .isEqualTo(409);

    // 상태: ACTIVE → CLOSED → (COUNT_LOCKED 직행 불가) → ACTIVE, RETIRED 는 상태 변경으로 불가
    String sc = "/locations/" + id + "/state-changes";
    Res closed =
        send(HttpMethod.POST, op, sc, Map.of("targetState", "CLOSED", "reason", "선반 수리"), null);
    assertThat(closed.status()).isEqualTo(202);
    assertThat(closed.body().path("version").asInt()).isEqualTo(3);
    assertThat(
            send(
                    HttpMethod.POST,
                    op,
                    sc,
                    Map.of("targetState", "COUNT_LOCKED", "reason", "x"),
                    null)
                .status())
        .isEqualTo(409);
    assertThat(
            send(HttpMethod.POST, op, sc, Map.of("targetState", "RETIRED", "reason", "x"), null)
                .status())
        .isEqualTo(409);
    assertThat(
            send(HttpMethod.POST, op, sc, Map.of("targetState", "ACTIVE", "reason", "수리 끝"), null)
                .status())
        .isEqualTo(202);

    // 적치 후보: 냉장·ACTIVE·선반/피킹만, 동선 순서
    send(HttpMethod.POST, op, "/locations", body("A-S1-01-1-01", "STORAGE", "AMBIENT", 1), null);
    send(HttpMethod.POST, op, "/locations", body("C-P1-00-1-09", "PICKING", "CHILLED", 0), null);
    send(HttpMethod.POST, op, "/locations", body("RCV-C-01", "RECEIVING", "CHILLED", null), null);
    Res cand =
        get(op, "/putaway-candidates?mfcId=" + mfc + "&sku=E-00000001&temperatureZone=CHILLED");
    assertThat(cand.status()).isEqualTo(200);
    assertThat(cand.body().path("items").findValuesAsString("locationCode"))
        .containsExactly("C-P1-00-1-09", "C-S1-01-1-01");
    assertThat(get(op, "/putaway-candidates?mfcId=" + mfc + "&sku=E-00000001").status())
        .isEqualTo(400);

    // 목록·커서, 서비스 토큰 읽기 허용, 다른 센터 운영자 읽기 403
    Res page1 = get(op, "/locations?mfcId=" + mfc + "&size=2");
    assertThat(page1.body().path("items")).hasSize(2);
    String cursor = page1.body().path("page").path("nextCursor").asString();
    Res page2 = get(op, "/locations?mfcId=" + mfc + "&size=2&cursor=" + cursor);
    assertThat(page2.body().path("items")).hasSize(2);
    assertThat(page2.body().path("page").path("nextCursor").isNull()).isTrue();
    assertThat(get(TestJwt.service("mfc-inventory-service"), "/locations/" + id).status())
        .isEqualTo(200);
    assertThat(get(other, "/locations/" + id).status()).isEqualTo(403);

    // 사건: 등록 4 + 속성 변경 1 + 상태 2 = 이 위치 4건(v1~v4), 전체 속성
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT payload::text AS p FROM outbox_event WHERE topic = 'mfc.location.v1'"
                + " AND aggregateid = ? ORDER BY (payload->>'aggregateVersion')::int",
            id);
    assertThat(rows).hasSize(4);
    JsonNode last = read(rows.get(3).get("p").toString()).path("payload");
    assertThat(last.path("locationCode").asString()).isEqualTo("C-S1-01-1-01");
    assertThat(last.path("zone").asString()).isEqualTo("STORAGE");
    assertThat(last.path("temperatureZone").asString()).isEqualTo("CHILLED");
    assertThat(last.path("state").asString()).isEqualTo("ACTIVE");
    assertThat(last.path("previousState").asString()).isEqualTo("CLOSED");
    assertThat(last.path("version").asInt()).isEqualTo(4);

    // 다시 알림: 상태 변화 없이 같은 버전
    Res rep =
        send(
            HttpMethod.POST,
            TestJwt.service("ops"),
            "/internal/location-republications?mfcId=" + mfc,
            null,
            null);
    assertThat(rep.status()).isEqualTo(202);
    assertThat(rep.body().path("appliedQty").asInt()).isEqualTo(4);
    assertThat(get(op, "/locations/" + id).body().path("version").asInt()).isEqualTo(4);
  }
}
