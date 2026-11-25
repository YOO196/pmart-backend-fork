package com.pmart.mfc.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.EventEnvelopeFixtures;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 재고 실시간 스트림(0.46.0, SSE): 접속 → ready, 수령·검수·적치·예약·해제마다 커밋 뒤 stock-changed(바뀐 뒤 값·원인), 가용이 0 경계를
 * 넘으면 summary-changed, heartbeat 주석 줄, 다른 센터 거르기, 권한 403, Last-Event-ID 이어 받기. 실제
 * PostgreSQL(LISTEN/NOTIFY)·Kafka.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.inventory.dummy-sku-size=true",
      "pmart.inventory.stock-stream.heartbeat=PT1S",
      // LOT 검색 사건(0.50.0)을 outbox 행으로 읽는다 — 운영은 INSERT 직후 DELETE
      "pmart.messaging.outbox.delete-after-insert=false",
      "pmart.test.context=stock-stream"
    })
@Import(TestJwtConfiguration.class)
class StockStreamIT extends PostgresKafkaContainerSupport {

  static final String INBOUND = TestJwt.service("mfc-inbound-service");
  static final String ORCH = TestJwt.service("order-orchestration-service");

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired KafkaTemplate<String, String> kafka;

  RestClient http;
  String mfc;
  final List<Sse> opened = new ArrayList<>();

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    mfc = "MFC-SS-" + UUID.randomUUID().toString().substring(0, 8);
    location(mfc, "RCV-DOCK", "RECEIVING");
    location(mfc, "A-01-01", "STORAGE");
    awaitSql("SELECT count(*) FROM location_projection WHERE mfc_id = '" + mfc + "'", 2);
  }

  @AfterEach
  void closeStreams() {
    opened.forEach(Sse::close);
  }

  // ---------- SSE 읽기 ----------

  /** 한 이벤트(주석 줄 heartbeat 는 event "comment"). */
  record Frame(String id, String event, JsonNode data) {}

  final class Sse {
    final int status;
    final BlockingQueue<Frame> frames = new LinkedBlockingQueue<>();
    final Thread reader;
    final Stream<String> lines;

    Sse(HttpResponse<Stream<String>> res) {
      this.status = res.statusCode();
      this.lines = res.body();
      this.reader =
          new Thread(
              () -> {
                String id = null;
                String event = null;
                StringBuilder data = new StringBuilder();
                try {
                  for (String line : (Iterable<String>) lines::iterator) {
                    if (line.startsWith(":")) {
                      frames.add(new Frame(null, "comment", null));
                    } else if (line.startsWith("id: ")) {
                      id = line.substring(4);
                    } else if (line.startsWith("event: ")) {
                      event = line.substring(7);
                    } else if (line.startsWith("data: ")) {
                      data.append(line.substring(6));
                    } else if (line.isEmpty() && event != null) {
                      frames.add(new Frame(id, event, mapper.readTree(data.toString())));
                      id = null;
                      event = null;
                      data.setLength(0);
                    }
                  }
                } catch (RuntimeException ignored) {
                  // 닫힘
                }
              });
      reader.setDaemon(true);
      reader.start();
    }

    /** 조건에 맞는 다음 이벤트(heartbeat 는 건너뜀 — 따로 셀 때는 next 로). */
    Frame await(Predicate<Frame> p) {
      Instant until = Instant.now().plusSeconds(20);
      while (Instant.now().isBefore(until)) {
        Frame f = next(Duration.ofMillis(500));
        if (f != null && p.test(f)) {
          return f;
        }
      }
      throw new AssertionError("이벤트가 오지 않음");
    }

    Frame next(Duration wait) {
      try {
        return frames.poll(wait.toMillis(), TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return null;
      }
    }

    void close() {
      lines.close();
      reader.interrupt();
    }
  }

  Sse open(String token, String query, String lastEventId) throws Exception {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/v1/stock-stream" + query))
            .header("Authorization", "Bearer " + token)
            .header("Accept", "text/event-stream")
            .GET();
    if (lastEventId != null) {
      b.header("Last-Event-ID", lastEventId);
    }
    HttpResponse<Stream<String>> res =
        HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofLines());
    Sse s = new Sse(res);
    opened.add(s);
    return s;
  }

  static Predicate<Frame> changed(String cause) {
    return f ->
        "stock-changed".equals(f.event()) && cause.equals(f.data().path("cause").asString());
  }

  // ---------- 준비 ----------

  void location(String center, String code, String zone) {
    String id = "LOC-" + center + "-" + code;
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("locationId", id);
    payload.put("mfcId", center);
    payload.put("locationCode", code);
    payload.put("zone", zone);
    payload.put("temperatureZone", "AMBIENT");
    payload.put("state", "ACTIVE");
    payload.put("version", 1);
    String json =
        EventEnvelopeFixtures.envelope("MfcLocationStateChanged")
            .from("mfc-location-service")
            .aggregate("Location", id, 1)
            .payload(payload)
            .buildJson();
    kafka.send("mfc.location.v1", id, json).join();
  }

  void awaitSql(String sql, int expected) {
    Instant until = Instant.now().plus(Duration.ofSeconds(30));
    while (Instant.now().isBefore(until)) {
      Integer n = jdbc.queryForObject(sql, Integer.class);
      if (n != null && n == expected) {
        return;
      }
      try {
        Thread.sleep(200);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
    assertThat(jdbc.queryForObject(sql, Integer.class)).isEqualTo(expected);
  }

  record Res(int status, JsonNode body) {}

  Res post(String token, String source, String path, Object body) {
    var e =
        http.post()
            .uri(path)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", source);
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toEntity(String.class);
    String b = e.getBody();
    return new Res(e.getStatusCode().value(), mapper.readTree(b == null || b.isBlank() ? "{}" : b));
  }

  Res get(String token, String path) {
    var e =
        http.get().uri(path).headers(h -> h.setBearerAuth(token)).retrieve().toEntity(String.class);
    String b = e.getBody();
    return new Res(e.getStatusCode().value(), mapper.readTree(b == null || b.isBlank() ? "{}" : b));
  }

  static String newSku() {
    return "E-" + String.format("%08d", ThreadLocalRandom.current().nextInt(1, 99_999_999));
  }

  Res execute(String center, String type, String from, String to, Map<String, Object> line) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("executionType", type);
    body.put("mfcId", center);
    body.put("taskId", "TSK-" + UUID.randomUUID());
    body.put("executionId", "EXE-" + UUID.randomUUID());
    body.put("performedBy", "w-it"); // 재고 이력 처리한 사람(0.49.0)
    body.put("fromLocation", from);
    if (to != null) {
      body.put("toLocation", to);
    }
    body.put("lines", List.of(line));
    return post(INBOUND, "mfc-inbound-service", "/internal/task-executions", body);
  }

  /** 수령 → 검수 → 선반 적치(E+qty). */
  void stocked(String center, String sku, int qty) {
    jdbc.update(
        "INSERT INTO sku_projection (sku, unit_type, storage_type, state, product_version, updated_at)"
            + " VALUES (?, 'E', 'AMBIENT', 'ACTIVE', 1, now()) ON CONFLICT (sku) DO NOTHING",
        sku);
    String receiptId = "RCV-" + UUID.randomUUID();
    Map<String, Object> line = new LinkedHashMap<>();
    line.put("sku", sku);
    line.put("normalQty", qty);
    line.put("quarantinedQty", 0);
    line.put("pendingQty", 0);
    line.put("toLocationCode", "RCV-DOCK");
    Res r =
        post(
            INBOUND,
            "mfc-inbound-service",
            "/internal/receipts",
            Map.of(
                "receiptId",
                receiptId,
                "trfId",
                "INITIAL-AP-1",
                "departureId",
                "INITIAL",
                "mfcId",
                center,
                "lines",
                List.of(line)));
    assertThat(r.status()).isEqualTo(201);
    String lotId =
        get(INBOUND, "/internal/receipts/" + receiptId)
            .body()
            .path("lines")
            .path(0)
            .path("lotId")
            .asString();
    assertThat(
            execute(
                    center,
                    "INSPECT",
                    "RCV-DOCK",
                    null,
                    Map.of("sku", sku, "lotId", lotId, "qty", qty, "resultQuality", "NORMAL"))
                .status())
        .isEqualTo(201);
    assertThat(
            execute(
                    center,
                    "PUTAWAY",
                    "RCV-DOCK",
                    "A-01-01",
                    Map.of("sku", sku, "lotId", lotId, "qty", qty))
                .status())
        .isEqualTo(201);
  }

  // ---------- 시험 ----------

  @Test
  void commitsArePushedAfterCommitWithAfterValuesAndSummaryCrossings() throws Exception {
    String admin = TestJwt.operator("hq", List.of("OPERATOR_ADMIN"), List.of());
    String sku = newSku();
    Sse s = open(admin, "?mfcId=" + mfc, null);
    assertThat(s.status).isEqualTo(200);
    Frame ready = s.await(f -> "ready".equals(f.event()));
    assertThat(ready.data().path("resumed").asBoolean()).isFalse();
    assertThat(ready.data().path("mfcIds").path(0).asString()).isEqualTo(mfc);

    // 다른 센터의 재고 변화는 오지 않는다
    String other = "MFC-SS-" + UUID.randomUUID().toString().substring(0, 8);
    location(other, "RCV-DOCK", "RECEIVING");
    location(other, "A-01-01", "STORAGE");
    awaitSql("SELECT count(*) FROM location_projection WHERE mfc_id = '" + other + "'", 2);
    stocked(other, newSku(), 2);

    stocked(mfc, sku, 5);
    Frame tin = s.await(changed("TIN"));
    assertThat(tin.data().path("mfcId").asString()).isEqualTo(mfc);
    assertThat(tin.id()).isEqualTo(tin.data().path("eventId").asString());
    JsonNode tinItem = tin.data().path("items").path(0);
    assertThat(tinItem.path("sku").asString()).isEqualTo(sku);
    assertThat(tinItem.path("onHandQty").asInt()).isEqualTo(5);
    assertThat(tinItem.path("sellableQty").asInt()).isZero();
    assertThat(tin.data().path("units").path(0).path("locationCode").asString())
        .isEqualTo("RCV-DOCK");
    assertThat(tin.data().path("commitId").asString()).startsWith("CMT-");

    // 검수(E 그대로) → 적치(E+5, A 0 → 5 — 품절 경계를 넘음)
    s.await(changed("TASK_EXECUTION"));
    Frame put =
        s.await(
            f ->
                changed("TASK_EXECUTION").test(f)
                    && f.data().path("items").path(0).path("sellableQty").asInt() == 5);
    assertThat(put.data().path("items").path(0).path("availableQty").asInt()).isEqualTo(5);
    assertThat(put.data().path("items").path(0).path("availableDelta").asInt()).isEqualTo(5);
    Frame sum = s.await(f -> "summary-changed".equals(f.event()));
    assertThat(sum.id()).isEqualTo(put.id());
    assertThat(sum.data().path("skus").path(0).asString()).isEqualTo(sku);
    long putVersion = put.data().path("items").path(0).path("version").asLong();

    // 예약 2 → R2 A3, 해제(주문 취소) → A5
    jdbc.update(
        "INSERT INTO center_sku_handling (center_id, sku, handling_enabled, changed_by, updated_at,"
            + " row_version) VALUES (?,?,true,'it',now(),1)",
        mfc,
        sku);
    Res rv =
        post(
            ORCH,
            "order-orchestration-service",
            "/internal/reservations",
            Map.of(
                "orderId",
                "ORD-" + UUID.randomUUID(),
                "mfcId",
                mfc,
                "lines",
                List.of(Map.of("sku", sku, "qty", 2))));
    assertThat(rv.status()).isEqualTo(201);
    Frame reserved = s.await(changed("RESERVE"));
    JsonNode ri = reserved.data().path("items").path(0);
    assertThat(ri.path("reservedQty").asInt()).isEqualTo(2);
    assertThat(ri.path("availableQty").asInt()).isEqualTo(3);
    assertThat(ri.path("availableDelta").asInt()).isEqualTo(-2);
    assertThat(ri.path("version").asLong()).isGreaterThan(putVersion);
    Res rel =
        post(
            ORCH,
            "order-orchestration-service",
            "/internal/reservations/" + rv.body().path("resultId").asString() + "/releases",
            Map.of("reason", "CANCEL_BEFORE_PICK"));
    assertThat(rel.status()).isLessThan(300);
    Frame released = s.await(changed("RELEASE"));
    assertThat(released.data().path("items").path(0).path("availableQty").asInt()).isEqualTo(5);
    assertThat(released.data().path("items").path(0).path("reservedQty").asInt()).isZero();

    // 이 연결에는 다른 센터 이벤트가 하나도 없었다
    Frame f;
    while ((f = s.next(Duration.ofMillis(200))) != null) {
      if (f.data() != null && f.data().has("mfcId")) {
        assertThat(f.data().path("mfcId").asString()).isEqualTo(mfc);
      }
    }

    // 이어 받기: 수령 이벤트 id 로 다시 접속 → ready(resumed) 뒤 그 뒤 이벤트가 다시 온다(겹침 허용 — version 으로 거름)
    Sse again = open(admin, "?mfcId=" + mfc, tin.id());
    Frame r2 = again.await(fr -> "ready".equals(fr.event()));
    assertThat(r2.data().path("resumed").asBoolean()).isTrue();
    again.await(changed("RESERVE"));
    again.await(changed("RELEASE"));

    // 모르는 id → ready(resumed false) 뒤 resync
    Sse unknown = open(admin, "?mfcId=" + mfc, "999999999999");
    assertThat(unknown.await(fr -> "ready".equals(fr.event())).data().path("resumed").asBoolean())
        .isFalse();
    assertThat(unknown.await(fr -> "resync".equals(fr.event())).data().path("reason").asString())
        .isEqualTo("RESUME_UNAVAILABLE");
  }

  @Test
  void heartbeatAndPermissionFollowStockSummaries() throws Exception {
    // 센터 운영자: mfcId 없이 = 토큰 센터, 다른 센터 = 403
    String op = TestJwt.operator("op", List.of("MFC_WORKER"), List.of(mfc));
    Sse own = open(op, "", null);
    assertThat(own.status).isEqualTo(200);
    Frame ready = own.await(f -> "ready".equals(f.event()));
    assertThat(ready.data().path("mfcIds").path(0).asString()).isEqualTo(mfc);
    // heartbeat 주석 줄(시험 설정 1초)
    Frame hb = null;
    Instant until = Instant.now().plusSeconds(5);
    while (hb == null && Instant.now().isBefore(until)) {
      Frame f = own.next(Duration.ofMillis(500));
      if (f != null && "comment".equals(f.event())) {
        hb = f;
      }
    }
    assertThat(hb).isNotNull();

    assertThat(open(op, "?mfcId=MFC-OTHER-01", null).status).isEqualTo(403);
    assertThat(open(TestJwt.customer("C-1"), "", null).status).isEqualTo(403);
    // 여러 센터 중 하나라도 권한 밖이면 403
    assertThat(open(op, "?mfcId=" + mfc + "&mfcId=MFC-OTHER-01", null).status).isEqualTo(403);
  }

  Res put(String path, Object body) {
    var e =
        http.put()
            .uri(path)
            .headers(
                h -> {
                  h.setBearerAuth(INBOUND);
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", "mfc-inbound-service");
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toEntity(String.class);
    String b = e.getBody();
    return new Res(e.getStatusCode().value(), mapper.readTree(b == null || b.isBlank() ? "{}" : b));
  }

  void handled(String center, String sku) {
    jdbc.update(
        "INSERT INTO center_sku_handling (center_id, sku, handling_enabled, changed_by, updated_at,"
            + " row_version) VALUES (?,?,true,'it',now(),1) ON CONFLICT DO NOTHING",
        center,
        sku);
  }

  JsonNode summaryOf(String token, String center) {
    for (JsonNode n : get(token, "/stock-summaries?mfcId=" + center).body().path("items")) {
      if (center.equals(n.path("mfcId").asString())) {
        return n;
      }
    }
    return null;
  }

  Map<String, Object> basis(String calculatedAt, Map<String, Object> daily) {
    Map<String, Object> pol = new LinkedHashMap<>();
    pol.put("storageType", "AMBIENT");
    pol.put("safetyDays", 1);
    pol.put("historyDays", 28);
    pol.put("minHistoryDays", 7);
    pol.put("averageWindowDays", 28);
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("calculatedAt", calculatedAt);
    b.put("salesThrough", "2026-11-24");
    b.put("policies", List.of(pol));
    b.put("dailyQty", List.of(daily));
    return b;
  }

  /** 곧 품절(0.47.0, LS-1~3): 0 < A ≤ SS(d × safetyDays), 기준 없음·정책 없음은 모름, SS 경계도 summary-changed. */
  @Test
  void lowStockCountsListsAndStreamFollowDailySalesBasis() throws Exception {
    String admin = TestJwt.operator("hq", List.of("OPERATOR_ADMIN"), List.of());
    String sku = newSku();
    String soldOut = newSku();
    String chilled = newSku();
    stocked(mfc, sku, 5);
    stocked(mfc, chilled, 2);
    jdbc.update("UPDATE sku_projection SET storage_type = 'CHILLED' WHERE sku = ?", chilled);
    for (String x : List.of(sku, soldOut, chilled)) {
      handled(mfc, x);
    }

    // 기준을 받기 전: A > 0 은 모두 모름(0 으로 채우지 않음), 기준 null
    JsonNode before = summaryOf(admin, mfc);
    assertThat(before.path("soldOutSkuCount").asInt()).isEqualTo(1);
    assertThat(before.path("lowStockSkuCount").asInt()).isZero();
    assertThat(before.path("lowStockUnknownSkuCount").asInt()).isEqualTo(2);
    assertThat(before.path("lowStockBasis").isNull()).isTrue();
    JsonNode unknown =
        get(admin, "/stock-summaries/" + mfc + "/skus?kind=LOW_STOCK_UNKNOWN").body();
    assertThat(unknown.path("items").findValuesAsString("unknownReason"))
        .containsOnly("BASIS_MISSING");

    // 기준: 상온 SS 1일치, d(sku) = 4 → SS 4, A 5 → 아직 아님. 냉장은 정책 없음 → 모름
    Res ok =
        put(
            "/internal/mfcs/" + mfc + "/low-stock-basis",
            basis("2026-11-25T00:10:00Z", Map.of("sku", sku, "qty", 4)));
    assertThat(ok.status()).as(ok.body().toString()).isEqualTo(200);
    assertThat(ok.body().path("applied").asBoolean()).isTrue();
    JsonNode mid = summaryOf(admin, mfc);
    assertThat(mid.path("lowStockSkuCount").asInt()).isZero();
    assertThat(mid.path("lowStockUnknownSkuCount").asInt()).isEqualTo(1);
    assertThat(mid.path("lowStockBasis").path("uniform").asBoolean()).isTrue();
    assertThat(mid.path("lowStockBasis").path("safetyDays").asDouble()).isEqualTo(1.0);
    assertThat(mid.path("lowStockBasis").path("averageWindowDays").asInt()).isEqualTo(28);
    JsonNode unk2 = get(admin, "/stock-summaries/" + mfc + "/skus?kind=LOW_STOCK_UNKNOWN").body();
    assertThat(unk2.path("items").path(0).path("sku").asString()).isEqualTo(chilled);
    assertThat(unk2.path("items").path(0).path("unknownReason").asString())
        .isEqualTo("POLICY_MISSING");
    // 앞선 계산은 반영하지 않는다
    assertThat(
            put(
                    "/internal/mfcs/" + mfc + "/low-stock-basis",
                    basis("2026-11-24T00:10:00Z", Map.of("sku", sku, "qty", 1)))
                .body()
                .path("applied")
                .asBoolean())
        .isFalse();

    // 예약 2 → A 3 ≤ SS 4 → 곧 품절, 스트림 summary-changed
    Sse s = open(admin, "?mfcId=" + mfc, null);
    s.await(f -> "ready".equals(f.event()));
    Res rv =
        post(
            ORCH,
            "order-orchestration-service",
            "/internal/reservations",
            Map.of(
                "orderId",
                "ORD-" + UUID.randomUUID(),
                "mfcId",
                mfc,
                "lines",
                List.of(Map.of("sku", sku, "qty", 2))));
    assertThat(rv.status()).isEqualTo(201);
    Frame reserved = s.await(changed("RESERVE"));
    Frame sum = s.await(f -> "summary-changed".equals(f.event()));
    assertThat(sum.id()).isEqualTo(reserved.id());
    assertThat(sum.data().path("skus").path(0).asString()).isEqualTo(sku);

    JsonNode after = summaryOf(admin, mfc);
    assertThat(after.path("lowStockSkuCount").asInt()).isEqualTo(1);
    assertThat(after.path("soldOutSkuCount").asInt()).isEqualTo(1);
    JsonNode low = get(admin, "/stock-summaries/" + mfc + "/skus?kind=LOW_STOCK").body();
    assertThat(low.path("totalCount").asInt()).isEqualTo(1);
    JsonNode li = low.path("items").path(0);
    assertThat(li.path("sku").asString()).isEqualTo(sku);
    assertThat(li.path("availableQty").asInt()).isEqualTo(3);
    assertThat(li.path("dailyQty").asDouble()).isEqualTo(4.0);
    assertThat(li.path("safetyQty").asDouble()).isEqualTo(4.0);
    assertThat(li.path("coverDays").asDouble()).isEqualTo(0.75);
    assertThat(low.path("nextCursor").isNull()).isTrue();
    JsonNode so = get(admin, "/stock-summaries/" + mfc + "/skus?kind=SOLD_OUT&size=1").body();
    assertThat(so.path("items").findValuesAsString("sku")).containsExactly(soldOut);
    assertThat(so.path("items").path(0).path("availableQty").asInt()).isZero();

    String outsider = TestJwt.operator("op9", List.of("MFC_WORKER"), List.of("MFC-OTHER-01"));
    assertThat(get(outsider, "/stock-summaries/" + mfc + "/skus?kind=LOW_STOCK").status())
        .isEqualTo(403);
    assertThat(get(admin, "/stock-summaries/" + mfc + "/skus?kind=NOPE").status()).isEqualTo(400);
    assertThat(get(admin, "/stock-summaries/" + mfc + "/skus?kind=LOW_STOCK&cursor=x").status())
        .isEqualTo(400);
  }

  /** 재고 이력(0.48.0): 확정 단위 최신순, 단위 줄, 그 뒤 센터 합계, 거르기·커서·권한. */
  @Test
  void stockLedgerListsCommitsNewestFirstWithTotalsAfterEach() {
    String sku = newSku();
    stocked(mfc, sku, 5); // TIN → 검수 → 적치
    handled(mfc, sku);
    String orderId = "ORD-" + UUID.randomUUID();
    Res rv =
        post(
            ORCH,
            "order-orchestration-service",
            "/internal/reservations",
            Map.of(
                "orderId", orderId, "mfcId", mfc, "lines", List.of(Map.of("sku", sku, "qty", 2))));
    assertThat(rv.status()).isEqualTo(201);

    String op = TestJwt.operator("op", List.of("MFC_WORKER"), List.of(mfc));
    String base = "/mfcs/" + mfc + "/stock-ledger?sku=" + sku;
    Res all = get(op, base);
    assertThat(all.status()).as(all.body().toString()).isEqualTo(200);
    JsonNode items = all.body().path("items");
    assertThat(items.size()).as(items.toString()).isGreaterThanOrEqualTo(3);
    assertThat(all.body().path("current").path("availableQty").asInt()).isEqualTo(3);
    // 최신 = 예약: R+2, 그 뒤 A 3
    JsonNode newest = items.path(0);
    assertThat(newest.path("cause").asString()).isEqualTo("RESERVE");
    assertThat(newest.path("delta").path("R").asInt()).isEqualTo(2);
    assertThat(newest.path("after").path("reservedQty").asInt()).isEqualTo(2);
    assertThat(newest.path("after").path("availableQty").asInt()).isEqualTo(3);
    assertThat(newest.path("entries").path(0).path("symbol").asString()).isEqualTo("R");
    // (0.49.0) 예약 확정 = 그 주문, 서비스 호출이라 처리한 사람 없음
    assertThat(newest.path("orderId").asString()).isEqualTo(orderId);
    assertThat(newest.path("actorOperatorId").isNull()).isTrue();
    // 그 앞 = 적치: E+5, 그 뒤 E 5 R 0, 선반 칸 줄
    JsonNode put = items.path(1);
    assertThat(put.path("cause").asString()).isEqualTo("TASK_EXECUTION");
    assertThat(put.path("delta").path("E").asInt()).isEqualTo(5);
    assertThat(put.path("after").path("sellableQty").asInt()).isEqualTo(5);
    assertThat(put.path("after").path("reservedQty").asInt()).isZero();
    assertThat(put.path("entries").findValuesAsString("locationCode"))
        .contains("A-01-01", "RCV-DOCK");
    // (0.49.0) 작업 실행 = 실적의 작업자, 주문 없음
    assertThat(put.path("actorOperatorId").asString()).isEqualTo("w-it");
    assertThat(put.path("orderId").isNull()).isTrue();
    // 가장 오래된 = 수령: H+5, 그 뒤 H 5 E 0
    JsonNode oldest = items.path(items.size() - 1);
    assertThat(oldest.path("cause").asString()).isEqualTo("TIN");
    assertThat(oldest.path("sourceSystem").asString()).isEqualTo("mfc-inbound-service");
    assertThat(oldest.path("sourceRef").asString()).startsWith("RCV-");
    assertThat(oldest.path("after").path("onHandQty").asInt()).isEqualTo(5);
    assertThat(oldest.path("after").path("sellableQty").asInt()).isZero();
    assertThat(oldest.path("entries").path(0).path("lotId").asString()).startsWith("LOT-");

    // 커서: 2개씩 → 합치면 전체와 같다
    Res p1 = get(op, base + "&size=2");
    assertThat(p1.body().path("items")).hasSize(2);
    String next = p1.body().path("nextCursor").asString();
    assertThat(next).isNotBlank();
    Res p2 = get(op, base + "&size=50&cursor=" + next);
    assertThat(p2.body().path("items").size() + 2).isEqualTo(items.size());
    assertThat(p2.body().path("nextCursor").isNull()).isTrue();

    // 거르기: 원인·칸
    assertThat(get(op, base + "&cause=TIN").body().path("items").findValuesAsString("cause"))
        .containsExactly("TIN");
    JsonNode atShelf = get(op, base + "&locationCode=A-01-01").body().path("items");
    assertThat(atShelf).hasSize(1);
    assertThat(atShelf.path(0).path("delta").path("E").asInt()).isEqualTo(5);
    assertThat(get(op, base + "&from=2099-01-01T00:00:00Z").body().path("items")).isEmpty();
    // (0.49.0) 주문 하나로 거르기
    assertThat(
            get(op, base + "&orderId=" + orderId).body().path("items").findValuesAsString("cause"))
        .containsExactly("RESERVE");

    // 권한·검증
    String outsider = TestJwt.operator("op9", List.of("MFC_WORKER"), List.of("MFC-OTHER-01"));
    assertThat(get(outsider, base).status()).isEqualTo(403);
    assertThat(get(op, base + "&cursor=bad").status()).isEqualTo(400);
    assertThat(get(op, "/mfcs/" + mfc + "/stock-ledger").status()).isEqualTo(400);
  }

  List<JsonNode> lotEvents(String center, String lotId) {
    return jdbc
        .queryForList(
            "SELECT payload::text FROM outbox_event WHERE type = 'MfcLotStockChanged'"
                + " AND aggregateid = ? AND topic = 'mfc.lot-stock.v1' ORDER BY created_at, id",
            String.class,
            center + ":" + lotId)
        .stream()
        .map(t -> mapper.readTree(t).path("payload"))
        .toList();
  }

  /** LOT 검색 사건(0.50.0): H·Q·E·칸이 바뀔 때마다 지금 모습, 예약만은 안 냄, 재발행. */
  @Test
  void lotStockEventsCarryCurrentLotForSearch() {
    String sku = newSku();
    stocked(mfc, sku, 5);
    handled(mfc, sku);
    String lotId =
        jdbc.queryForObject(
            "SELECT lot_id FROM stock_lot_balance WHERE mfc_id = ? AND sku = ?",
            String.class,
            mfc,
            sku);
    List<JsonNode> ev = lotEvents(mfc, lotId);
    assertThat(ev.size()).as(ev.toString()).isGreaterThanOrEqualTo(2); // 수령·적치(검수 포함)
    JsonNode last = ev.get(ev.size() - 1);
    assertThat(last.path("sku").asString()).isEqualTo(sku);
    assertThat(last.path("onHandQty").asInt()).isEqualTo(5);
    assertThat(last.path("sellableQty").asInt()).isEqualTo(5);
    assertThat(last.path("depleted").asBoolean()).isFalse();
    assertThat(last.path("retainUntil").isNull()).isTrue();
    assertThat(last.path("storageType").asString()).isEqualTo("AMBIENT");
    assertThat(last.path("locations")).hasSize(1);
    assertThat(last.path("locations").path(0).path("locationCode").asString()).isEqualTo("A-01-01");
    assertThat(last.path("locations").path(0).path("temperatureZone").asString())
        .isEqualTo("AMBIENT");
    assertThat(last.path("version").asLong()).isGreaterThan(ev.get(0).path("version").asLong());

    // 예약(R 만)은 LOT 사건을 내지 않는다
    Res rv =
        post(
            ORCH,
            "order-orchestration-service",
            "/internal/reservations",
            Map.of(
                "orderId",
                "ORD-" + UUID.randomUUID(),
                "mfcId",
                mfc,
                "lines",
                List.of(Map.of("sku", sku, "qty", 2))));
    assertThat(rv.status()).isEqualTo(201);
    assertThat(lotEvents(mfc, lotId)).hasSize(ev.size());

    // 재발행: 지금 모습(예약 2 반영)을 republished 로
    var re =
        http.post()
            .uri("/internal/lot-stock-republications?mfcId=" + mfc)
            .headers(
                h -> {
                  h.setBearerAuth(INBOUND);
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", "ops-tool");
                })
            .retrieve()
            .toEntity(String.class);
    assertThat(re.getStatusCode().value()).isEqualTo(200);
    JsonNode body = mapper.readTree(re.getBody());
    assertThat(body.path("publishedCount").asInt()).isGreaterThanOrEqualTo(1);
    assertThat(body.path("retentionDays").asInt()).isEqualTo(365);
    List<JsonNode> after = lotEvents(mfc, lotId);
    assertThat(after).hasSize(ev.size() + 1);
    JsonNode rep = after.get(after.size() - 1);
    assertThat(rep.path("republished").asBoolean()).isTrue();
    assertThat(rep.path("reservedQty").asInt()).isEqualTo(2);
    assertThat(rep.path("lastMovedAt").asString()).isNotBlank();
  }
}
