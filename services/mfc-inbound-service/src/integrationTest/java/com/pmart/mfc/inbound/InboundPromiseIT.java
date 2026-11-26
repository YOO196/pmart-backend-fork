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
import com.pmart.common.messaging.EventSchemaValidator;
import com.pmart.common.test.EventEnvelopeFixtures;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
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
 * 입고 예정(0.16.0): replenishment·cdc 사건 → 예정(EXPECTED) → 도착(RECEIVING) → 수령 → mfc-inventory
 * ConfirmReceipt → 품질검사 작업 → 적치 작업(초기 보유 반입과 같은 흐름), 늦은 버전 무시, 출발 전 취소.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.inbound.post-interval=PT1H",
      "pmart.messaging.outbox.delete-after-insert=false",
      "pmart.test.context=inbound-promise"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock(
    @ConfigureWireMock(
        name = "inventory",
        baseUrlProperties = "pmart.inbound.mfc-inventory-base-url"))
class InboundPromiseIT extends PostgresKafkaContainerSupport {

  static final String SKU = "E-00000041";
  static final String SKU2 = "E-00000042";

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired EventSchemaValidator validator;

  @InjectWireMock("inventory")
  WireMockServer inv;

  record Res(int status, JsonNode body) {}

  RestClient http() {
    return RestClient.builder()
        .baseUrl("http://localhost:" + port + "/api/v1")
        .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
        .build();
  }

  static String wrk(String mfc) {
    return TestJwt.operator("wrk-ip", List.of("MFC_WORKER"), List.of(mfc));
  }

  static String sup(String mfc) {
    return TestJwt.operator("sup-ip", List.of("MFC_SUPERVISOR"), List.of(mfc));
  }

  int outboxCount(String type, String aggregateId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM outbox_event WHERE type = ? AND aggregateid = ?",
            Integer.class,
            type,
            aggregateId);
    return n == null ? 0 : n;
  }

  EventEnvelope departed(String trf, String mfc, int qty) {
    return EventEnvelopeFixtures.envelope("TransferDeparted")
        .from("cdc-inventory-service")
        .aggregate("Transit", trf, 1)
        .payload(
            Map.of(
                "trfId",
                trf,
                "departureId",
                "DEP-" + trf,
                "departureLineIds",
                List.of("DL-1"),
                "lines",
                List.of(
                    Map.of("sku", SKU, "lot", "CDC-L9", "qty", qty, "expiryDate", "2026-12-31")),
                "departedQty",
                qty,
                "departedAt",
                "2026-11-20T22:00:00Z",
                "commitId",
                "TOUT-" + trf,
                "fromCdc",
                "CDC-1",
                "toMfc",
                mfc))
        .build();
  }

  Res get(String token, String uri) throws Exception {
    ResponseEntity<String> e =
        http()
            .get()
            .uri(uri)
            .headers(h -> h.setBearerAuth(token))
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  Res post(String token, String uri, String key, Object body, Integer expectedVersion)
      throws Exception {
    ResponseEntity<String> e =
        http()
            .post()
            .uri(uri)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", key);
                  h.set("X-Source-System", "mfc-pda");
                  if (expectedVersion != null) {
                    h.set("X-Expected-Version", expectedVersion.toString());
                  }
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  /** 계약 스키마에 맞는 사건만 보낸다. */
  void send(String topic, String key, EventEnvelope e) {
    assertThat(validator.check(e)).as(e.eventType() + " 계약 적합").isEmpty();
    kafka.send(topic, key, EventEnvelopeFixtures.codec().encode(e)).join();
  }

  EventEnvelope approved(String trf, String mfc, long v, int qty) {
    return EventEnvelopeFixtures.envelope("TransferApproved")
        .from("replenishment-service")
        .aggregate("Transfer", trf, v)
        .payload(
            Map.of(
                "trfId",
                trf,
                "trfVersion",
                v,
                "fromCdc",
                "CDC-1",
                "toMfc",
                mfc,
                "lines",
                List.of(
                    Map.of("sku", SKU, "approvedQty", qty, "unit", "E"),
                    Map.of("sku", SKU2, "approvedQty", 3, "unit", "E")),
                "deadlineMeaning",
                "ARRIVAL",
                "dueAt",
                "2026-11-21T00:00:00Z",
                "approvalRef",
                Map.of("approvalId", "APR-" + trf)))
        .build();
  }

  JsonNode await(String token, String trf, Predicate<JsonNode> ok) throws Exception {
    long until = System.currentTimeMillis() + 30_000;
    JsonNode last = null;
    while (System.currentTimeMillis() < until) {
      Res r = get(token, "/inbound-promises/" + trf);
      last = r.body();
      if (r.status() == 200 && ok.test(r.body())) {
        return r.body();
      }
      Thread.sleep(200);
    }
    throw new AssertionError("입고 예정 " + trf + " 기다림 끝: " + last);
  }

  JsonNode outbox(String type, String aggregateId) throws Exception {
    JsonNode p =
        mapper.readTree(
            jdbc.queryForObject(
                "SELECT payload::text FROM outbox_event WHERE type = ? AND aggregateid = ?",
                String.class,
                type,
                aggregateId));
    return p.has("payload") ? p.path("payload") : p;
  }

  static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(
      int status, String body) {
    return aResponse()
        .withStatus(status)
        .withHeader("Content-Type", "application/json")
        .withBody(body);
  }

  @Test
  void promiseFromEventsThenArrivalReceiptInspectPutaway() throws Exception {
    String mfc = "MFC-IP-1";
    String w = wrk(mfc);
    String trf = "TRF-" + UUID.randomUUID().toString().substring(0, 8);

    // 승인 v2 가 먼저(변경으로 수량 12), 늦게 온 승인 v1(수량 60)은 무시
    send("scm.replenishment.v1", trf, approved(trf, mfc, 1, 60));
    send(
        "scm.replenishment.v1",
        trf,
        EventEnvelopeFixtures.envelope("TransferChanged")
            .from("replenishment-service")
            .aggregate("Transfer", trf, 2)
            .payload(
                Map.of(
                    "trfId",
                    trf,
                    "fromVersion",
                    1,
                    "toVersion",
                    2,
                    "scope",
                    "QTY",
                    "reason",
                    "CDC 재고 부족",
                    "lines",
                    List.of(
                        Map.of("sku", SKU, "approvedQty", 12, "unit", "E"),
                        Map.of("sku", SKU2, "approvedQty", 3, "unit", "E"))))
            .build());
    send("scm.replenishment.v1", trf, approved(trf, mfc, 1, 60));
    JsonNode p = await(w, trf, n -> n.path("trfVersion").asInt() == 2);
    assertThat(p.path("state").asString()).isEqualTo("EXPECTED");
    assertThat(p.path("fromCdc").asString()).isEqualTo("CDC-1");
    assertThat(p.path("deadlineMeaning").asString()).isEqualTo("ARRIVAL");
    assertThat(p.path("lines").path(0).path("expectedQty").asInt()).isEqualTo(12);
    assertThat(p.path("departureId").isNull()).isTrue();
    // 목록(기본 = 끝나지 않은 것) · 다른 센터 403
    assertThat(
            get(w, "/inbound-promises?mfcId=" + mfc)
                .body()
                .path("items")
                .findValuesAsString("trfId"))
        .contains(trf);
    assertThat(
            get(w, "/inbound-promises?mfcId=" + mfc + "&date=2026-11-21")
                .body()
                .path("items")
                .findValuesAsString("trfId"))
        .contains(trf);
    assertThat(
            get(w, "/inbound-promises?mfcId=" + mfc + "&date=2026-11-25")
                .body()
                .path("items")
                .findValuesAsString("trfId"))
        .doesNotContain(trf);
    assertThat(get(wrk("MFC-ELSE"), "/inbound-promises?mfcId=" + mfc).status()).isEqualTo(403);
    assertThat(get(wrk("MFC-ELSE"), "/inbound-promises/" + trf).status()).isEqualTo(403);

    // 매니페스트(LPN 목록) · 실제 출발(TOUT) — SKU2 는 출발하지 않음(실출발 12만)
    send(
        "cdc.outbound.v1",
        trf,
        EventEnvelopeFixtures.envelope("TransferShipmentManifestIssued")
            .from("cdc-outbound-service")
            .aggregate("ShipmentManifest", "MAN-" + trf, 1)
            .payload(
                Map.of(
                    "shipmentManifestId",
                    "MAN-" + trf,
                    "trfId",
                    trf,
                    "departureId",
                    "DEP-" + trf,
                    "toMfc",
                    mfc,
                    "lines",
                    List.of(
                        Map.of("sku", SKU, "lot", "CDC-L1", "lpn", "LPN-1", "qty", 6),
                        Map.of("sku", SKU, "lot", "CDC-L1", "lpn", "LPN-2", "qty", 6))))
            .build());
    send(
        "cdc.inventory.v1",
        trf,
        EventEnvelopeFixtures.envelope("TransferDeparted")
            .from("cdc-inventory-service")
            .aggregate("Transit", trf, 1)
            .payload(
                Map.of(
                    "trfId",
                    trf,
                    "departureId",
                    "DEP-" + trf,
                    "departureLineIds",
                    List.of("DL-1"),
                    "lines",
                    List.of(
                        Map.of("sku", SKU, "lot", "CDC-L1", "qty", 12, "expiryDate", "2026-12-31")),
                    "departedQty",
                    12,
                    "departedAt",
                    "2026-11-20T22:00:00Z",
                    "commitId",
                    "TOUT-1",
                    "toMfc",
                    mfc,
                    "tripId",
                    "TRIP-1"))
            .build());
    p = await(w, trf, n -> n.path("departureId").asString("").equals("DEP-" + trf));
    p = await(w, trf, n -> n.path("packages").size() == 2);
    assertThat(p.path("lines")).hasSize(1);
    assertThat(p.path("lines").path(0).path("lotId").asString()).isEqualTo("CDC-L1");
    assertThat(p.path("lines").path(0).path("expiryDate").asString()).isEqualTo("2026-12-31");
    assertThat(p.path("packages").findValuesAsString("lpn")).containsExactly("LPN-1", "LPN-2");
    assertThat(p.path("tripId").asString()).isEqualTo("TRIP-1");
    int version = p.path("version").asInt();

    Map<String, Object> line = new LinkedHashMap<>();
    line.put("sku", SKU);
    line.put("normalQty", 8);
    line.put("quarantinedQty", 2);
    line.put("pendingQty", 0);
    line.put("toLocationCode", "RCV-DOCK");
    line.put("discrepancyReason", "SHORT");
    Map<String, Object> receipt = Map.of("lines", List.of(line));
    String rpath = "/inbound-promises/" + trf + "/receipts";

    // 도착 전 수령 409, 버전 틀린 도착 409, 도착 202 → RECEIVING, 다시 도착(다른 키) 409
    assertThat(post(w, rpath, UUID.randomUUID().toString(), receipt, null).status()).isEqualTo(409);
    Map<String, Object> arrival = Map.of("arrivedAt", "2026-11-21T00:30:00Z", "tripId", "TRIP-1");
    String apath = "/inbound-promises/" + trf + "/arrivals";
    assertThat(post(w, apath, UUID.randomUUID().toString(), arrival, version + 5).status())
        .isEqualTo(409);
    Res arrived = post(w, apath, UUID.randomUUID().toString(), arrival, version);
    assertThat(arrived.status()).as(arrived.body().toString()).isEqualTo(202);
    assertThat(arrived.body().path("resultId").asString()).isEqualTo(trf);
    assertThat(post(w, apath, UUID.randomUUID().toString(), arrival, null).status()).isEqualTo(409);
    assertThat(get(w, "/inbound-promises/" + trf).body().path("state").asString())
        .isEqualTo("RECEIVING");
    JsonNode ev = outbox("InboundArrivalRecorded", trf);
    assertThat(ev.path("mfcId").asString()).isEqualTo(mfc);
    assertThat(ev.path("departureId").asString()).isEqualTo("DEP-" + trf);

    // 출발 수량보다 많이 400, 예정에 없는 SKU 400
    Map<String, Object> over = new LinkedHashMap<>(line);
    over.put("normalQty", 11);
    assertThat(
            post(w, rpath, UUID.randomUUID().toString(), Map.of("lines", List.of(over)), null)
                .status())
        .isEqualTo(400);
    Map<String, Object> other = new LinkedHashMap<>(line);
    other.put("sku", SKU2);
    assertThat(
            post(w, rpath, UUID.randomUUID().toString(), Map.of("lines", List.of(other)), null)
                .status())
        .isEqualTo(400);

    // 수령(정상 8·격리 2 / 출발 12) → inventory ConfirmReceipt → APPLIED → 품질검사 작업 8
    inv.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/receipts"))
            .willReturn(
                json(201, "{\"status\":\"APPLIED\",\"resultId\":\"CMT-R1\",\"appliedQty\":10}")));
    inv.stubFor(
        WireMock.get(WireMock.urlPathMatching("/api/v1/internal/receipts/RCV-TRF-.*"))
            .willReturn(
                json(
                    200,
                    "{\"receiptId\":\"x\",\"commitId\":\"CMT-R1\",\"lines\":[{\"lineNo\":1,\"sku\":\""
                        + SKU
                        + "\",\"lotId\":\"LOT-INV-1\",\"locationCode\":\"RCV-DOCK\",\"normalQty\":8,"
                        + "\"quarantinedQty\":2,\"pendingQty\":0}]}")));
    String key = UUID.randomUUID().toString();
    Res rec = post(w, rpath, key, receipt, null);
    assertThat(rec.status()).as(rec.body().toString()).isEqualTo(202);
    assertThat(rec.body().path("status").asString()).isEqualTo("APPLIED");
    String rid = rec.body().path("resultId").asString();
    assertThat(rid).startsWith("RCV-TRF-");
    inv.verify(
        postRequestedFor(urlPathEqualTo("/api/v1/internal/receipts"))
            .withHeader("Idempotency-Key", equalTo(rid))
            .withRequestBody(matchingJsonPath("$.trfId", equalTo(trf)))
            .withRequestBody(matchingJsonPath("$.departureId", equalTo("DEP-" + trf)))
            .withRequestBody(matchingJsonPath("$.lines[0].sourceLotRef", equalTo("CDC-L1")))
            .withRequestBody(matchingJsonPath("$.lines[0].expiryDate", equalTo("2026-12-31")))
            .withRequestBody(matchingJsonPath("$.lines[0].quarantinedQty", equalTo("2")))
            // (0.22.0, IV-8) CDC 이관 수령 → inventory 가 MfcReceiptPosted
            .withRequestBody(matchingJsonPath("$.cdcTransfer", equalTo("true")))
            .withRequestBody(matchingJsonPath("$.fromCdc", equalTo("CDC-1"))));
    // 같은 키 다시 → 같은 수령(재고 다시 안 부름)
    Res replay = post(w, rpath, key, receipt, null);
    assertThat(replay.body().path("resultId").asString()).isEqualTo(rid);
    assertThat(inv.findAll(postRequestedFor(urlPathEqualTo("/api/v1/internal/receipts"))))
        .hasSize(1);

    p = get(w, "/inbound-promises/" + trf).body();
    assertThat(p.path("state").asString()).isEqualTo("TIN_POSTED");
    assertThat(p.path("lines").path(0).path("receivedQty").asInt()).isEqualTo(10);
    assertThat(p.path("lines").path(0).path("quarantinedQty").asInt()).isEqualTo(2);
    JsonNode r = get(w, "/inbound-promises/" + trf + "/receipts/" + rid).body();
    assertThat(r.path("inventoryApplyStatus").asString()).isEqualTo("APPLIED");
    assertThat(r.path("inventoryCommitId").asString()).isEqualTo("CMT-R1");
    assertThat(r.path("discrepancies").path(0).path("reason").asString()).isEqualTo("SHORT");
    assertThat(r.path("discrepancies").path(0).path("receivedQty").asInt()).isEqualTo(10);
    JsonNode d = outbox("ReceiptDiscrepancyReported", trf);
    assertThat(d.path("missingQty").asInt()).isEqualTo(2);
    assertThat(d.path("receiptId").asString()).isEqualTo(rid);
    assertThat(d.path("shipmentManifestId").asString()).isEqualTo("MAN-" + trf);
    // 이미 TIN_POSTED → 409
    assertThat(post(w, rpath, UUID.randomUUID().toString(), receipt, null).status()).isEqualTo(409);

    JsonNode tasks =
        get(w, "/inbound-tasks?mfcId=" + mfc + "&receiptId=" + rid).body().path("items");
    assertThat(tasks).hasSize(1);
    JsonNode inspect = tasks.path(0);
    assertThat(inspect.path("taskType").asString()).isEqualTo("INSPECT");
    assertThat(inspect.path("plannedQty").asInt()).isEqualTo(8);
    assertThat(inspect.path("lotId").asString()).isEqualTo("LOT-INV-1");

    // 품질검사 → 정상 LOT → 적치 작업(초기 보유 반입과 같은 경로)
    inv.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/task-executions"))
            .willReturn(
                json(201, "{\"status\":\"APPLIED\",\"requestId\":\"q\",\"resultId\":\"CMT-I1\"}")));
    inv.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/commits/CMT-I1"))
            .willReturn(
                json(
                    200,
                    "{\"commitId\":\"CMT-I1\",\"entries\":["
                        + "{\"sku\":\""
                        + SKU
                        + "\",\"lotId\":\"LOT-INV-1\",\"locationCode\":\"RCV-DOCK\",\"qualityState\":\"PENDING_INSPECTION\",\"workState\":\"UNSTOWED\",\"symbol\":\"H\",\"delta\":-8},"
                        + "{\"sku\":\""
                        + SKU
                        + "\",\"lotId\":\"LOT-OK-1\",\"locationCode\":\"RCV-DOCK\",\"qualityState\":\"NORMAL\",\"workState\":\"UNSTOWED\",\"symbol\":\"H\",\"delta\":8}]}")));
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("qty", 8);
    result.put("resultQuality", "NORMAL");
    result.put("expiryDate", "2026-12-31");
    Res ins =
        post(
            w,
            "/inbound-tasks/" + inspect.path("taskId").asString() + "/inspections",
            UUID.randomUUID().toString(),
            Map.of("results", List.of(result)),
            null);
    assertThat(ins.status()).as(ins.body().toString()).isEqualTo(200);
    assertThat(ins.body().path("createdTaskIds")).hasSize(1);
    JsonNode put =
        get(w, "/inbound-tasks/" + ins.body().path("createdTaskIds").path(0).asString()).body();
    assertThat(put.path("taskType").asString()).isEqualTo("PUTAWAY");
    assertThat(put.path("receiptId").asString()).isEqualTo(rid);
    assertThat(put.path("plannedQty").asInt()).isEqualTo(8);

    // (0.22.0, MI-5) 10/12 만 받아 검수가 끝나도 자동 종결하지 않는다 → 센터 관리자가 나머지를 닫는다(SHORT_CLOSED)
    assertThat(outboxCount("TransferReceivingClosed", trf)).isZero();
    String cpath = "/inbound-promises/" + trf + "/closures";
    Map<String, Object> why = Map.of("reason", "CDC 확인 — 2개 분실 조사");
    assertThat(post(w, cpath, UUID.randomUUID().toString(), why, null).status()).isEqualTo(403);
    assertThat(
            post(sup(mfc), cpath, UUID.randomUUID().toString(), Map.of("reason", " "), null)
                .status())
        .isEqualTo(400);
    String ckey = UUID.randomUUID().toString();
    Res closed = post(sup(mfc), cpath, ckey, why, null);
    assertThat(closed.status()).as(closed.body().toString()).isEqualTo(202);
    assertThat(closed.body().path("status").asString()).isEqualTo("APPLIED");
    assertThat(post(sup(mfc), cpath, ckey, why, null).status()).isEqualTo(202);
    assertThat(post(sup(mfc), cpath, UUID.randomUUID().toString(), why, null).status())
        .isEqualTo(409);
    p = get(w, "/inbound-promises/" + trf).body();
    assertThat(p.path("state").asString()).isEqualTo("TIN_POSTED");
    assertThat(p.path("closeKind").asString()).isEqualTo("SHORT_CLOSED");
    assertThat(p.path("closedBy").asString()).isEqualTo("sup-ip");
    assertThat(p.path("closeReason").asString()).isEqualTo("CDC 확인 — 2개 분실 조사");
    assertThat(p.path("closedAt").isNull()).isFalse();
    assertThat(outboxCount("TransferReceivingClosed", trf)).isEqualTo(1);
    JsonNode c = outbox("TransferReceivingClosed", trf);
    assertThat(c.path("closeKind").asString()).isEqualTo("SHORT_CLOSED");
    assertThat(c.path("departureId").asString()).isEqualTo("DEP-" + trf);
    assertThat(c.path("fromCdc").asString()).isEqualTo("CDC-1");
    assertThat(c.path("departedQty").asInt()).isEqualTo(12);
    assertThat(c.path("receivedQty").asInt()).isEqualTo(10);
    assertThat(c.path("receiptIds").path(0).asString()).isEqualTo(rid);
    assertThat(c.path("closedBy").asString()).isEqualTo("sup-ip");
  }

  @Test
  void receivingClosesItselfWhenAllReceivedAndInspectedOrSupervisorClosesWhatNeverCame()
      throws Exception {
    String mfc = "MFC-IP-3";
    String w = wrk(mfc);

    // ① 출발 12 를 모두 격리로 받음 → 품질검사 작업이 없어 수령 반영과 함께 자동 종결(ALL_RECEIVED)
    String trf = "TRF-" + UUID.randomUUID().toString().substring(0, 8);
    send("cdc.inventory.v1", trf, departed(trf, mfc, 12));
    await(w, trf, n -> n.path("departureId").asString("").equals("DEP-" + trf));
    assertThat(
            post(
                    w,
                    "/inbound-promises/" + trf + "/arrivals",
                    UUID.randomUUID().toString(),
                    Map.of("arrivedAt", "2026-11-21T00:30:00Z"),
                    null)
                .status())
        .isEqualTo(202);
    inv.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/receipts"))
            .withRequestBody(matchingJsonPath("$.trfId", equalTo(trf)))
            .willReturn(
                json(201, "{\"status\":\"APPLIED\",\"resultId\":\"CMT-Q1\",\"appliedQty\":12}")));
    inv.stubFor(
        WireMock.get(WireMock.urlPathMatching("/api/v1/internal/receipts/RCV-TRF-.*"))
            .willReturn(
                json(
                    200,
                    "{\"receiptId\":\"x\",\"commitId\":\"CMT-Q1\",\"lines\":[{\"lineNo\":1,\"sku\":\""
                        + SKU
                        + "\",\"lotId\":\"LOT-INV-Q\",\"locationCode\":\"RCV-DOCK\",\"normalQty\":0,"
                        + "\"quarantinedQty\":12,\"pendingQty\":0}]}")));
    Map<String, Object> line = new LinkedHashMap<>();
    line.put("sku", SKU);
    line.put("normalQty", 0);
    line.put("quarantinedQty", 12);
    line.put("pendingQty", 0);
    line.put("toLocationCode", "RCV-DOCK");
    Res rec =
        post(
            w,
            "/inbound-promises/" + trf + "/receipts",
            UUID.randomUUID().toString(),
            Map.of("lines", List.of(line)),
            null);
    assertThat(rec.body().path("status").asString()).as(rec.body().toString()).isEqualTo("APPLIED");
    JsonNode p = get(w, "/inbound-promises/" + trf).body();
    assertThat(p.path("state").asString()).isEqualTo("TIN_POSTED");
    assertThat(p.path("closeKind").asString()).isEqualTo("ALL_RECEIVED");
    assertThat(p.path("closedBy").isNull()).isTrue();
    JsonNode c = outbox("TransferReceivingClosed", trf);
    assertThat(c.path("closeKind").asString()).isEqualTo("ALL_RECEIVED");
    assertThat(c.path("receivedQty").asInt()).isEqualTo(12);
    assertThat(c.path("lines").path(0).path("departedQty").asInt()).isEqualTo(12);
    assertThat(c.has("closedBy")).isFalse();
    // 이미 종결 → 관리자 종결 409
    assertThat(
            post(
                    sup(mfc),
                    "/inbound-promises/" + trf + "/closures",
                    UUID.randomUUID().toString(),
                    Map.of("reason", "x"),
                    null)
                .status())
        .isEqualTo(409);

    // ② 출발했는데 오지 않은 예정 → 관리자 종결 → CLOSED(목록에서 빠지고 도착·수령을 받지 않는다)
    String gone = "TRF-" + UUID.randomUUID().toString().substring(0, 8);
    send("cdc.inventory.v1", gone, departed(gone, mfc, 5));
    await(w, gone, n -> n.path("departureId").asString("").equals("DEP-" + gone));
    Res closed =
        post(
            sup(mfc),
            "/inbound-promises/" + gone + "/closures",
            UUID.randomUUID().toString(),
            Map.of("reason", "차량 사고 — CDC 와 확인"),
            null);
    assertThat(closed.status()).as(closed.body().toString()).isEqualTo(202);
    p = get(w, "/inbound-promises/" + gone).body();
    assertThat(p.path("state").asString()).isEqualTo("CLOSED");
    assertThat(p.path("closeKind").asString()).isEqualTo("NOT_RECEIVED_CLOSED");
    assertThat(
            get(w, "/inbound-promises?mfcId=" + mfc)
                .body()
                .path("items")
                .findValuesAsString("trfId"))
        .doesNotContain(gone);
    assertThat(
            post(
                    w,
                    "/inbound-promises/" + gone + "/arrivals",
                    UUID.randomUUID().toString(),
                    Map.of("arrivedAt", "2026-11-21T00:30:00Z"),
                    null)
                .status())
        .isEqualTo(409);
    c = outbox("TransferReceivingClosed", gone);
    assertThat(c.path("closeKind").asString()).isEqualTo("NOT_RECEIVED_CLOSED");
    assertThat(c.path("departedQty").asInt()).isEqualTo(5);
    assertThat(c.path("receivedQty").asInt()).isZero();
    assertThat(c.path("receiptIds")).isEmpty();

    // ③ 출발 전(승인만) 예정은 닫지 않는다 — 409
    String early = "TRF-" + UUID.randomUUID().toString().substring(0, 8);
    send("scm.replenishment.v1", early, approved(early, mfc, 1, 4));
    await(w, early, n -> n.path("trfVersion").asInt() == 1);
    assertThat(
            post(
                    sup(mfc),
                    "/inbound-promises/" + early + "/closures",
                    UUID.randomUUID().toString(),
                    Map.of("reason", "x"),
                    null)
                .status())
        .isEqualTo(409);
    assertThat(outboxCount("TransferReceivingClosed", early)).isZero();
  }

  @Test
  void cancelBeforeDepartureAndRejectedReceiptKeepsReceiving() throws Exception {
    String mfc = "MFC-IP-2";
    String w = wrk(mfc);
    String trf = "TRF-C-" + UUID.randomUUID().toString().substring(0, 8);
    send("scm.replenishment.v1", trf, approved(trf, mfc, 1, 10));
    // 일부 취소(v2) → 남은 수량, 늦은 취소(v2 다시)는 무시, 전체 취소(v3) → CANCELLED
    send(
        "scm.replenishment.v1",
        trf,
        EventEnvelopeFixtures.envelope("TransferCancelled")
            .from("replenishment-service")
            .aggregate("Transfer", trf, 2)
            .payload(
                Map.of(
                    "trfId",
                    trf,
                    "fromVersion",
                    1,
                    "toVersion",
                    2,
                    "scope",
                    "LINES",
                    "reason",
                    "일부 취소",
                    "cancelledLines",
                    List.of(Map.of("sku", SKU, "cancelledQty", 4))))
            .build());
    JsonNode p = await(w, trf, n -> n.path("trfVersion").asInt() == 2);
    assertThat(p.path("state").asString()).isEqualTo("EXPECTED");
    assertThat(p.path("lines").path(0).path("expectedQty").asInt()).isEqualTo(6);
    send(
        "scm.replenishment.v1",
        trf,
        EventEnvelopeFixtures.envelope("TransferCancelled")
            .from("replenishment-service")
            .aggregate("Transfer", trf, 3)
            .payload(
                Map.of(
                    "trfId",
                    trf,
                    "fromVersion",
                    2,
                    "toVersion",
                    3,
                    "scope",
                    "ALL",
                    "reason",
                    "공급 취소"))
            .build());
    p = await(w, trf, n -> "CANCELLED".equals(n.path("state").asString()));
    assertThat(p.path("cancelReason").asString()).isEqualTo("공급 취소");
    assertThat(
            get(w, "/inbound-promises?mfcId=" + mfc)
                .body()
                .path("items")
                .findValuesAsString("trfId"))
        .doesNotContain(trf);
    assertThat(
            get(w, "/inbound-promises?mfcId=" + mfc + "&state=CANCELLED")
                .body()
                .path("items")
                .findValuesAsString("trfId"))
        .contains(trf);
    assertThat(
            post(
                    w,
                    "/inbound-promises/" + trf + "/arrivals",
                    UUID.randomUUID().toString(),
                    Map.of("arrivedAt", "2026-11-21T00:30:00Z"),
                    null)
                .status())
        .isEqualTo(409);
    assertThat(get(w, "/inbound-promises/TRF-NONE").status()).isEqualTo(404);

    // 출발 뒤 취소는 반영하지 않는다(출발 후 원TRF 취소 없음) · 재고가 거절한 수령은 REJECTED, 예정은 RECEIVING
    String trf2 = "TRF-D-" + UUID.randomUUID().toString().substring(0, 8);
    send("scm.replenishment.v1", trf2, approved(trf2, mfc, 1, 5));
    send(
        "cdc.inventory.v1",
        trf2,
        EventEnvelopeFixtures.envelope("TransferDeparted")
            .from("cdc-inventory-service")
            .aggregate("Transit", trf2, 1)
            .payload(
                Map.of(
                    "trfId",
                    trf2,
                    "departureId",
                    "DEP-" + trf2,
                    "departureLineIds",
                    List.of("DL-1"),
                    "lines",
                    List.of(Map.of("sku", SKU, "qty", 5)),
                    "departedQty",
                    5,
                    "departedAt",
                    "2026-11-20T22:00:00Z",
                    "commitId",
                    "TOUT-2"))
            .build());
    send(
        "scm.replenishment.v1",
        trf2,
        EventEnvelopeFixtures.envelope("TransferCancelled")
            .from("replenishment-service")
            .aggregate("Transfer", trf2, 2)
            .payload(
                Map.of(
                    "trfId",
                    trf2,
                    "fromVersion",
                    1,
                    "toVersion",
                    2,
                    "scope",
                    "ALL",
                    "reason",
                    "늦은 취소"))
            .build());
    p = await(w, trf2, n -> n.path("trfVersion").asInt() == 2);
    assertThat(p.path("state").asString()).isEqualTo("EXPECTED");
    assertThat(p.path("departureId").asString()).isEqualTo("DEP-" + trf2);
    assertThat(
            post(
                    w,
                    "/inbound-promises/" + trf2 + "/arrivals",
                    UUID.randomUUID().toString(),
                    Map.of("arrivedAt", "2026-11-21T00:30:00Z"),
                    null)
                .status())
        .isEqualTo(202);
    inv.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/receipts"))
            .withRequestBody(matchingJsonPath("$.trfId", equalTo(trf2)))
            .willReturn(
                json(409, "{\"code\":\"LOCATION_ZONE_MISMATCH\",\"detail\":\"수령 구역 아님\"}")));
    Map<String, Object> line = new LinkedHashMap<>();
    line.put("sku", SKU);
    line.put("normalQty", 5);
    line.put("quarantinedQty", 0);
    line.put("pendingQty", 0);
    line.put("toLocationCode", "SHELF-1");
    Res rec =
        post(
            w,
            "/inbound-promises/" + trf2 + "/receipts",
            UUID.randomUUID().toString(),
            Map.of("lines", List.of(line)),
            null);
    assertThat(rec.status()).as(rec.body().toString()).isEqualTo(202);
    assertThat(rec.body().path("status").asString()).isEqualTo("REJECTED");
    String rid = rec.body().path("resultId").asString();
    JsonNode r = get(w, "/inbound-promises/" + trf2 + "/receipts/" + rid).body();
    assertThat(r.path("inventoryApplyStatus").asString()).isEqualTo("REJECTED");
    assertThat(r.path("rejectReason").asString()).contains("LOCATION_ZONE_MISMATCH");
    assertThat(get(w, "/inbound-promises/" + trf2).body().path("state").asString())
        .isEqualTo("RECEIVING");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM inbound_task WHERE receipt_id = ?", Integer.class, rid))
        .isZero();
  }
}
