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
}
