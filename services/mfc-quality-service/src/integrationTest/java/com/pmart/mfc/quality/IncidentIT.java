package com.pmart.mfc.quality;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.pmart.common.test.EventEnvelopeFixtures;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.sql.Timestamp;
import java.time.Instant;
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
 * 품질 사고(0.6.0, MB-2 1차 — 사용자 결정 2026-11-19): 직접 등록(센터 관리자, 이 센터 재고가 있는 LOT 만), 영향 LOT 이 격리 해제·폐기
 * 승인되면 DECIDED, 그 뒤에만 닫기(사유·버전), 결품 보고 DAMAGED 는 자동으로 열고 같은 LOT 은 묶음, SHORT 는 열지 않음.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.messaging.outbox.delete-after-insert=false",
      "pmart.quality.mfc-inventory-base-url=${inv.url}",
      "pmart.quality.sweep-interval=PT1H",
      "pmart.test.context=quality-incident"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock({@ConfigureWireMock(name = "inv", baseUrlProperties = "inv.url")})
class IncidentIT extends PostgresKafkaContainerSupport {

  static final String MFC = "MFC-QI1";
  static final String SUP = TestJwt.operator("qi-sup", List.of("MFC_SUPERVISOR"), List.of(MFC));
  static final String WRK = TestJwt.operator("qi-wrk", List.of("MFC_WORKER"), List.of(MFC));

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired KafkaTemplate<String, String> kafka;

  @InjectWireMock("inv")
  WireMockServer inv;

  @Autowired com.pmart.mfc.quality.application.IncidentService incidents;

  RestClient http;

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
  }

  record Res(int status, JsonNode body) {}

  Res send(String method, String token, String path, Object body, @Nullable Integer expected) {
    var e =
        http.method(org.springframework.http.HttpMethod.valueOf(method))
            .uri(path)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", "mfc-backoffice");
                  if (expected != null) {
                    h.set("X-Expected-Version", expected.toString());
                  }
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapper.writeValueAsString(body))
            .retrieve()
            .toEntity(String.class);
    return res(e);
  }

  Res get(String token, String path) {
    return res(
        http.get()
            .uri(path)
            .headers(h -> h.setBearerAuth(token))
            .retrieve()
            .toEntity(String.class));
  }

  Res res(ResponseEntity<String> e) {
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  void lotInCenter(String lot, boolean present) {
    lotInCenter(lot, present, "E-1");
  }

  void lotInCenter(String lot, boolean present, String sku) {
    inv.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/mfcs/" + MFC + "/stock-units"))
            .withQueryParam("lotId", equalTo(lot))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        present
                            ? "{\"items\":[{\"lotId\":\""
                                + lot
                                + "\",\"sku\":\""
                                + sku
                                + "\",\"qty\":5}],\"nextCursor\":null}"
                            : "{\"items\":[],\"nextCursor\":null}")));
  }

  void releaseApplied(String lot, @Nullable String incidentId) {
    Instant now = Instant.now();
    jdbc.update(
        "INSERT INTO quality_decision (decision_id, mfc_id, decision_type, state, reason, decided_by,"
            + " incident_id, lines, created_at, updated_at) VALUES (?,?,'RELEASE','APPLIED','DAMAGE','qi-sup',?,?::jsonb,?,?)",
        "QD-" + UUID.randomUUID(),
        MFC,
        incidentId,
        "[{\"sku\":\"E-1\",\"lotId\":\"" + lot + "\",\"locationCode\":\"A-1\",\"qty\":1}]",
        Timestamp.from(now),
        Timestamp.from(now));
  }

  @Test
  void manualIncidentClosesOnlyAfterEveryLotIsResolved() {
    String lotA = "LOT-A-" + UUID.randomUUID().toString().substring(0, 6);
    String lotB = "LOT-B-" + UUID.randomUUID().toString().substring(0, 6);
    lotInCenter(lotA, true);
    lotInCenter(lotB, true);
    lotInCenter("LOT-NONE", false);
    Map<String, Object> body =
        Map.of(
            "mfcId",
            MFC,
            "kind",
            "SPOILAGE",
            "description",
            "냉장 진열대에서 변질 발견",
            "affectedLots",
            List.of(Map.of("lotId", lotA, "qty", 2), Map.of("lotId", lotB, "qty", 1)));
    // 작업자는 못 연다, 센터에 없는 LOT 은 422
    assertThat(send("POST", WRK, "/quality-incidents", body, null).status()).isEqualTo(403);
    Res none =
        send(
            "POST",
            SUP,
            "/quality-incidents",
            Map.of(
                "mfcId",
                MFC,
                "kind",
                "DAMAGE",
                "description",
                "x",
                "affectedLots",
                List.of(Map.of("lotId", "LOT-NONE", "qty", 1))),
            null);
    assertThat(none.status()).isEqualTo(422);
    assertThat(none.body().path("code").asString()).isEqualTo("LOT_NOT_IN_CENTER");

    Res made = send("POST", SUP, "/quality-incidents", body, null);
    assertThat(made.status()).as(made.body().toString()).isEqualTo(201);
    String id = made.body().path("incidentId").asString();
    assertThat(made.body().path("origin").asString()).isEqualTo("MANUAL");
    assertThat(made.body().path("openedBy").asString()).isEqualTo("qi-sup");
    assertThat(made.body().path("state").asString()).isEqualTo("OPEN");
    assertThat(made.body().path("remainingLotCount").asInt()).isEqualTo(2);
    assertThat(made.body().path("affectedLots").path(0).path("sku").asString()).isEqualTo("E-1");

    // 기본 목록(끝나지 않은 것)에 보인다, 작업자도 조회는 된다
    Res list = get(WRK, "/quality-incidents?mfcId=" + MFC);
    assertThat(list.status()).isEqualTo(200);
    assertThat(list.body().path("items").findValuesAsString("incidentId")).contains(id);
    assertThat(
            get(SUP, "/quality-incidents?mfcId=" + MFC + "&lotId=" + lotA)
                .body()
                .path("items")
                .findValuesAsString("incidentId"))
        .containsExactly(id);

    // 남은 LOT 이 있으면 닫기 409
    Res early =
        send("POST", SUP, "/quality-incidents/" + id + "/closures", Map.of("reason", "끝"), 1);
    assertThat(early.status()).isEqualTo(409);
    assertThat(early.body().path("code").asString()).isEqualTo("LOTS_NOT_RESOLVED");
    assertThat(early.body().toString()).contains(lotA).contains(lotB);

    // A 는 이 사고로 이은 격리 해제, B 는 폐기 승인 → DECIDED
    releaseApplied(lotA, id);
    Instant now = Instant.now();
    String disposal = "DSP-" + UUID.randomUUID();
    jdbc.update(
        "INSERT INTO disposal_approval (disposal_approval_id, mfc_id, state, requester_id, lines,"
            + " requested_at, updated_at) VALUES (?,?,'APPROVED','qi-wrk',?::jsonb,?,?)",
        disposal,
        MFC,
        "[{\"sku\":\"E-1\",\"lotId\":\""
            + lotB
            + "\",\"locationCode\":\"A-1\",\"requestedQty\":1}]",
        Timestamp.from(now),
        Timestamp.from(now));
    Res decided = get(SUP, "/quality-incidents/" + id);
    assertThat(decided.body().path("state").asString()).isEqualTo("DECIDED");
    assertThat(decided.body().path("remainingLotCount").asInt()).isZero();
    assertThat(decided.body().path("decisionIds").toString()).contains(disposal);

    // 버전이 다르면 409, 맞으면 닫힘, 다시 닫기는 INVALID_STATE
    assertThat(
            send("POST", SUP, "/quality-incidents/" + id + "/closures", Map.of("reason", "끝"), 99)
                .status())
        .isEqualTo(409);
    int v = decided.body().path("version").asInt();
    Res closed =
        send("POST", WRK, "/quality-incidents/" + id + "/closures", Map.of("reason", "처리 끝"), v);
    assertThat(closed.status()).isEqualTo(403);
    closed =
        send("POST", SUP, "/quality-incidents/" + id + "/closures", Map.of("reason", "처리 끝"), v);
    assertThat(closed.status()).as(closed.body().toString()).isEqualTo(200);
    assertThat(closed.body().path("state").asString()).isEqualTo("CLOSED");
    assertThat(closed.body().path("closedBy").asString()).isEqualTo("qi-sup");
    assertThat(closed.body().path("closeReason").asString()).isEqualTo("처리 끝");
    Res again =
        send("POST", SUP, "/quality-incidents/" + id + "/closures", Map.of("reason", "또"), null);
    assertThat(again.body().path("code").asString()).isEqualTo("INVALID_STATE");
    assertThat(
            get(SUP, "/quality-incidents?mfcId=" + MFC)
                .body()
                .path("items")
                .findValuesAsString("incidentId"))
        .doesNotContain(id);
    assertThat(
            get(SUP, "/quality-incidents?mfcId=" + MFC + "&state=CLOSED")
                .body()
                .path("items")
                .findValuesAsString("incidentId"))
        .contains(id);
    // 다른 센터 운영자는 못 본다
    String other = TestJwt.operator("qi-o", List.of("MFC_SUPERVISOR"), List.of("MFC-OTHER"));
    assertThat(get(other, "/quality-incidents/" + id).status()).isEqualTo(403);
  }

  void shortage(String lot, String reason, int qty) {
    String json =
        EventEnvelopeFixtures.envelope("LineShortageReported")
            .payload(
                Map.of(
                    "orderId",
                    "ORD-QI",
                    "orderLineId",
                    "ORD-QI-L1",
                    "sku",
                    "E-2",
                    "lot",
                    lot,
                    "shortQty",
                    qty,
                    "reason",
                    reason,
                    "alreadyHandedOverQty",
                    0,
                    "mfcId",
                    MFC,
                    "reportedAt",
                    "2026-11-19T01:00:00Z"))
            .buildJson();
    kafka.send("mfc.fulfillment.v1", "ORD-QI", json).join();
  }

  int autoIncidents(String lot) throws InterruptedException {
    Integer n = 0;
    long until = System.currentTimeMillis() + 30_000;
    while (System.currentTimeMillis() < until) {
      n =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM quality_incident i JOIN quality_incident_lot l ON l.incident_id = i.incident_id"
                  + " WHERE i.origin = 'AUTO' AND l.lot_id = ?",
              Integer.class,
              lot);
      if (n != null && n > 0) {
        break;
      }
      Thread.sleep(200);
    }
    return n == null ? 0 : n;
  }

  String autoIncidentId(String lot) {
    return jdbc.queryForObject(
        "SELECT i.incident_id FROM quality_incident i JOIN quality_incident_lot l ON l.incident_id = i.incident_id"
            + " WHERE i.origin = 'AUTO' AND l.lot_id = ?",
        String.class,
        lot);
  }

  void caseOpened(String caseId, String type, String incidentId) {
    String json =
        EventEnvelopeFixtures.envelope("CaseOpened")
            .payload(
                Map.of(
                    "caseId",
                    caseId,
                    "type",
                    type,
                    "refs",
                    List.of(
                        Map.of("refType", "orderId", "refId", "ORD-QI"),
                        Map.of("refType", "incidentId", "refId", incidentId))))
            .buildJson();
    // 같은 열쇠 = 같은 파티션 → 보낸 순서대로 처리된다
    kafka.send("platform.case.v1", "CASE-QI", json).join();
  }

  @Nullable String caseIdOf(String incidentId, long waitMs) throws InterruptedException {
    long until = System.currentTimeMillis() + waitMs;
    String c = null;
    while (System.currentTimeMillis() < until) {
      c =
          jdbc.queryForObject(
              "SELECT case_id FROM quality_incident WHERE incident_id = ?",
              String.class,
              incidentId);
      if (c != null) {
        break;
      }
      Thread.sleep(200);
    }
    return c;
  }

  @Test
  void caseOpenedLinksFirstCustomerCaseOnly() throws Exception {
    String lotA = "LOT-C-" + UUID.randomUUID().toString().substring(0, 6);
    String lotB = "LOT-C-" + UUID.randomUUID().toString().substring(0, 6);
    shortage(lotA, "DAMAGED", 1);
    shortage(lotB, "SPOILED", 1);
    assertThat(autoIncidents(lotA)).isEqualTo(1);
    assertThat(autoIncidents(lotB)).isEqualTo(1);
    String a = autoIncidentId(lotA);
    String b = autoIncidentId(lotB);
    int versionBefore = get(SUP, "/quality-incidents/" + a).body().path("version").asInt();

    // 다른 유형·모르는 사고는 무시(처리 실패로 막히지 않음), 그 뒤 첫 사건이 이어진다
    caseOpened("CASE-OTHER", "DELIVERY_COMPLAINT", a);
    caseOpened("CASE-NONE", "MFC_QUALITY_IMPACT", "QIN-NONE");
    caseOpened("CASE-1", "MFC_QUALITY_IMPACT", a);
    assertThat(caseIdOf(a, 30_000)).isEqualTo("CASE-1");

    // 같은 사고의 두 번째 사건(다른 영향 주문)은 덮지 않는다 — 뒤에 보낸 b 가 이어졌으면 앞의 것도 처리된 것
    caseOpened("CASE-2", "MFC_QUALITY_IMPACT", a);
    caseOpened("CASE-B", "MFC_QUALITY_IMPACT", b);
    assertThat(caseIdOf(b, 30_000)).isEqualTo("CASE-B");
    Res view = get(SUP, "/quality-incidents/" + a);
    assertThat(view.body().path("caseId").asString()).isEqualTo("CASE-1");
    // 표시용 참조라 버전은 그대로(관리자 닫기 요청을 409 로 만들지 않음)
    assertThat(view.body().path("version").asInt()).isEqualTo(versionBefore);
  }

  @Test
  void damagedShortageOpensAutoIncidentAndSameLotIsGrouped() throws Exception {
    String lot = "LOT-D-" + UUID.randomUUID().toString().substring(0, 6);
    shortage(lot, "DAMAGED", 1);
    assertThat(autoIncidents(lot)).isEqualTo(1);
    shortage(lot, "DAMAGED", 2);
    long until = System.currentTimeMillis() + 30_000;
    Integer qty = 0;
    while (System.currentTimeMillis() < until) {
      qty =
          jdbc.queryForObject(
              "SELECT qty FROM quality_incident_lot WHERE lot_id = ?", Integer.class, lot);
      if (qty != null && qty == 3) {
        break;
      }
      Thread.sleep(200);
    }
    assertThat(qty).isEqualTo(3);
    assertThat(autoIncidents(lot)).isEqualTo(1);
    String id =
        jdbc.queryForObject(
            "SELECT incident_id FROM quality_incident_lot WHERE lot_id = ?", String.class, lot);
    Res got = get(SUP, "/quality-incidents/" + id);
    assertThat(got.body().path("origin").asString()).isEqualTo("AUTO");
    assertThat(got.body().path("kind").asString()).isEqualTo("DAMAGE");
    assertThat(got.body().path("sourceEvent").asString()).isEqualTo("LineShortageReported");
    assertThat(got.body().path("openedBy").isNull()).isTrue();

    // 변질(SPOILED, fulfillment 0.22.0)은 변질 사고 — 같은 LOT 이라도 파손 사고와 따로
    shortage(lot, "SPOILED", 1);
    long until2 = System.currentTimeMillis() + 30_000;
    Integer spoilage = 0;
    while (System.currentTimeMillis() < until2) {
      spoilage =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM quality_incident i JOIN quality_incident_lot l ON l.incident_id = i.incident_id"
                  + " WHERE i.origin = 'AUTO' AND i.kind = 'SPOILAGE' AND l.lot_id = ?",
              Integer.class,
              lot);
      if (spoilage != null && spoilage > 0) {
        break;
      }
      Thread.sleep(200);
    }
    assertThat(spoilage).isEqualTo(1);
    assertThat(autoIncidents(lot)).isEqualTo(2);

    // 단순 부족은 열지 않는다
    String shortLot = "LOT-S-" + UUID.randomUUID().toString().substring(0, 6);
    shortage(shortLot, "SHORT", 1);
    String marker = "LOT-M-" + UUID.randomUUID().toString().substring(0, 6);
    shortage(marker, "DAMAGED", 1); // 뒤에 보낸 것이 처리됐으면 앞의 SHORT 도 처리된 것
    assertThat(autoIncidents(marker)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM quality_incident_lot WHERE lot_id = ?",
                Integer.class,
                shortLot))
        .isZero();
  }

  void lotOrders(String lot, String body) {
    inv.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/lot-orders"))
            .withQueryParam("lotIds", equalTo(lot))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body)));
  }

  int impactEvents(String incidentId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM outbox_event WHERE type = 'QualityIncidentImpactAssessed'"
                + " AND aggregateid = ?",
            Integer.class,
            incidentId);
    return n == null ? 0 : n;
  }

  /** 고객 영향(R6, 0.8.0): 영향 LOT 이 라이더에게 인계된 주문만 센다(집품만 한 주문은 빠짐), 같으면 사건 없음, 늘면 다음 판단 사건. */
  @Test
  void impactCountsHandedOverOrdersAndPublishesOnChange() throws Exception {
    String lot = "LOT-I-" + UUID.randomUUID().toString().substring(0, 6);
    lotInCenter(lot, true, "E-00000061");
    lotOrders(lot, "{\"truncated\":false,\"items\":[]}");
    Res created =
        send(
            "POST",
            SUP,
            "/quality-incidents",
            Map.of(
                "mfcId",
                MFC,
                "kind",
                "DAMAGE",
                "description",
                "포장 파손 LOT",
                "affectedLots",
                List.of(Map.of("lotId", lot, "qty", 1))),
            null);
    assertThat(created.status()).as(created.body().toString()).isIn(200, 201);
    String id = created.body().path("incidentId").asString();
    assertThat(created.body().path("affectedOrderCount").isNull()).isTrue();

    // 처음 판단 — 0건도 한 번 나간다
    incidents.assessImpact(id);
    assertThat(get(SUP, "/quality-incidents/" + id).body().path("affectedOrderCount").asInt())
        .isZero();
    assertThat(impactEvents(id)).isEqualTo(1);

    // 인계 1건 + 집품만 1건 → 1건
    lotOrders(
        lot,
        "{\"truncated\":false,\"items\":["
            + "{\"orderId\":\"ORD-1\",\"reservationId\":\"RSV-1\",\"orderLineId\":\"OL-1\",\"lotId\":\""
            + lot
            + "\",\"sku\":\"E-00000061\",\"pickedQty\":2,\"packedQty\":2,\"handedQty\":2},"
            + "{\"orderId\":\"ORD-2\",\"reservationId\":\"RSV-2\",\"orderLineId\":\"OL-9\",\"lotId\":\""
            + lot
            + "\",\"sku\":\"E-00000061\",\"pickedQty\":1,\"packedQty\":0,\"handedQty\":0}]}");
    incidents.assessImpact(id);
    assertThat(get(SUP, "/quality-incidents/" + id).body().path("affectedOrderCount").asInt())
        .isEqualTo(1);
    assertThat(impactEvents(id)).isEqualTo(2);
    JsonNode ev =
        mapper
            .readTree(
                jdbc.queryForObject(
                    "SELECT payload::text FROM outbox_event WHERE type = 'QualityIncidentImpactAssessed'"
                        + " AND aggregateid = ? ORDER BY created_at DESC LIMIT 1",
                    String.class,
                    id))
            .path("payload");
    assertThat(ev.path("affectedOrders").findValuesAsString("orderId")).containsExactly("ORD-1");
    assertThat(ev.path("affectedOrders").path(0).path("lines").path(0).path("qty").asInt())
        .isEqualTo(2);
    assertThat(ev.path("mfcId").asString()).isEqualTo(MFC);

    // 같으면 사건 없음
    incidents.assessImpact(id);
    assertThat(impactEvents(id)).isEqualTo(2);
  }

  void saleHoldStub(boolean release, int status) {
    String path = "/api/v1/internal/lot-sale-holds" + (release ? "/releases" : "");
    inv.stubFor(
        WireMock.post(urlPathEqualTo(path))
            .willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        status == 200
                            ? "{\"state\":\"" + (release ? "RELEASED" : "ACTIVE") + "\"}"
                            : "{\"code\":\"UNAVAILABLE\"}")));
  }

  String saleHoldOf(JsonNode incident, String lot) {
    for (JsonNode l : incident.path("affectedLots")) {
      if (lot.equals(l.path("lotId").asString())) {
        return l.path("saleHold").isNull() ? null : l.path("saleHold").asString();
      }
    }
    throw new AssertionError("LOT 없음 " + lot);
  }

  /**
   * 직접 등록 사고는 기본으로 영향 LOT 을 판매 보류(0.10.0 — 재고 0.39.0 lot-sale-holds): 재고 서비스가 안 되면 HOLD_PENDING 으로
   * 남았다가 주기에 걸리고, LOT 이 처리되면 그 LOT 만 풀리고, 닫히면 남은 것도 풀린다. saleHold false 면 걸지 않는다.
   */
  @Test
  void manualIncidentHoldsLotsFromSaleUntilResolvedOrClosed() {
    String lotA = "LOT-HA-" + UUID.randomUUID().toString().substring(0, 6);
    String lotB = "LOT-HB-" + UUID.randomUUID().toString().substring(0, 6);
    lotInCenter(lotA, true);
    lotInCenter(lotB, true);
    // 재고 서비스가 안 되면 등록은 되고 걸기 대기
    saleHoldStub(false, 503);
    saleHoldStub(true, 200);
    Res made =
        send(
            "POST",
            SUP,
            "/quality-incidents",
            Map.of(
                "mfcId",
                MFC,
                "kind",
                "RECALL",
                "description",
                "제조사 리콜 통보",
                "affectedLots",
                List.of(Map.of("lotId", lotA, "qty", 3), Map.of("lotId", lotB, "qty", 2))),
            null);
    assertThat(made.status()).as(made.body().toString()).isEqualTo(201);
    String id = made.body().path("incidentId").asString();
    assertThat(saleHoldOf(made.body(), lotA)).isEqualTo("HOLD_PENDING");
    assertThat(saleHoldOf(made.body(), lotB)).isEqualTo("HOLD_PENDING");

    // 재고 서비스가 돌아오면 주기에 걸린다
    saleHoldStub(false, 200);
    incidents.syncSaleHolds();
    JsonNode held = get(SUP, "/quality-incidents/" + id).body();
    assertThat(saleHoldOf(held, lotA)).isEqualTo("HELD");
    assertThat(saleHoldOf(held, lotB)).isEqualTo("HELD");
    inv.verify(
        WireMock.postRequestedFor(urlPathEqualTo("/api/v1/internal/lot-sale-holds"))
            .withRequestBody(WireMock.matchingJsonPath("$.sourceRef", equalTo(id)))
            .withRequestBody(WireMock.matchingJsonPath("$.lotId", equalTo(lotA))));

    // A 를 해제(이 사고로 이은 판정) → A 만 풀림
    releaseApplied(lotA, id);
    incidents.syncSaleHolds();
    JsonNode partly = get(SUP, "/quality-incidents/" + id).body();
    assertThat(saleHoldOf(partly, lotA)).isEqualTo("RELEASED");
    assertThat(saleHoldOf(partly, lotB)).isEqualTo("HELD");
    inv.verify(
        WireMock.moreThanOrExactly(1),
        WireMock.postRequestedFor(urlPathEqualTo("/api/v1/internal/lot-sale-holds/releases"))
            .withRequestBody(WireMock.matchingJsonPath("$.lotId", equalTo(lotA))));
    inv.verify(
        0,
        WireMock.postRequestedFor(urlPathEqualTo("/api/v1/internal/lot-sale-holds/releases"))
            .withRequestBody(WireMock.matchingJsonPath("$.lotId", equalTo(lotB))));

    // B 폐기 승인 → 닫기 → B 도 풀림(닫기 응답에 바로)
    Instant now = Instant.now();
    jdbc.update(
        "INSERT INTO disposal_approval (disposal_approval_id, mfc_id, state, requester_id, lines,"
            + " requested_at, updated_at) VALUES (?,?,'APPROVED','qi-wrk',?::jsonb,?,?)",
        "DSP-" + UUID.randomUUID(),
        MFC,
        "[{\"sku\":\"E-1\",\"lotId\":\""
            + lotB
            + "\",\"locationCode\":\"A-1\",\"requestedQty\":2}]",
        Timestamp.from(now),
        Timestamp.from(now));
    int v = get(SUP, "/quality-incidents/" + id).body().path("version").asInt();
    Res closed =
        send("POST", SUP, "/quality-incidents/" + id + "/closures", Map.of("reason", "회수 끝"), v);
    assertThat(closed.status()).as(closed.body().toString()).isEqualTo(200);
    assertThat(saleHoldOf(closed.body(), lotB)).isEqualTo("RELEASED");

    // saleHold false 면 걸지 않는다
    String lotC = "LOT-HC-" + UUID.randomUUID().toString().substring(0, 6);
    lotInCenter(lotC, true);
    Res noHold =
        send(
            "POST",
            SUP,
            "/quality-incidents",
            Map.of(
                "mfcId",
                MFC,
                "kind",
                "DAMAGE",
                "description",
                "상자 한 개 찌그러짐",
                "saleHold",
                false,
                "affectedLots",
                List.of(Map.of("lotId", lotC, "qty", 1))),
            null);
    assertThat(noHold.status()).as(noHold.body().toString()).isEqualTo(201);
    assertThat(saleHoldOf(noHold.body(), lotC)).isNull();
    inv.verify(
        0,
        WireMock.postRequestedFor(urlPathEqualTo("/api/v1/internal/lot-sale-holds"))
            .withRequestBody(WireMock.matchingJsonPath("$.lotId", equalTo(lotC))));
  }
}
