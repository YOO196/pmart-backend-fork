package com.pmart.mfc.capacity;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.havingExactly;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.time.Instant;
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

/** 배정 추천(WK-09, 실제 CP-SAT)·확정(WK-10 재검증·STALE). 작업 정본(fulfillment)은 WireMock. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.capacity.units-per-hour=3",
      "pmart.messaging.outbox.delete-after-insert=false",
      "pmart.capacity.plan.mfc-fulfillment-base-url=${ful.url}",
      "pmart.capacity.plan.mfc-inbound-base-url=${ful.url}",
      "pmart.capacity.activities.mfc-inventory-base-url=${ful.url}",
      "pmart.capacity.plan.center-master-base-url=${ful.url}",
      "pmart.capacity.plan.mfc-location-base-url=${ful.url}",
      "pmart.capacity.plan.pick-route-start=CRT-A-01",
      "pmart.capacity.plan.task-minutes.PICK=7",
      "pmart.capacity.plan.task-minutes.PACK=4",
      "pmart.capacity.plan.task-minutes.MOVE=5",
      "pmart.capacity.plan.task-minutes.REPLENISH=5",
      "pmart.capacity.plan.time-limit=PT5S",
      "pmart.capacity.plan.packing-stations=4"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock({@ConfigureWireMock(name = "ful", baseUrlProperties = "ful.url")})
class AssignmentPlanIT extends PostgresKafkaContainerSupport {

  static final String MFC = "MFC-PLAN-1";
  static final String SUP = TestJwt.operator("sup-p", List.of("MFC_SUPERVISOR"), List.of(MFC));
  static final String WORKER = TestJwt.operator("pk1", List.of("MFC_WORKER"), List.of(MFC));
  static final String FUL_SVC = TestJwt.service("mfc-fulfillment-service");

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;

  @InjectWireMock("ful")
  WireMockServer ful;

  RestClient http;

  record Res(int status, JsonNode body) {}

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    staffHours(MFC, 200, "{\"open\":true,\"ranges\":[{\"start\":\"00:00\",\"end\":\"24:00\"}]}");
  }

  /**
   * 피킹 방문 순서(0.10.0, ADR-0050 B2): 냉장 → 냉동 → 상온 C~H, 선반 아닌 칸은 맨 뒤. 거리표는 mfc-location(WireMock). 이
   * PC 처럼 OR-Tools 네이티브를 못 올리면 NEAREST_FALLBACK, 리눅스면 ROUTING — 순서 규칙은 같다.
   */
  @Test
  void pickRouteFollowsTemperatureGroupOrder() {
    String body =
        """
        {"codes":["CRT-A-01","A-C1-01-1-01","F-A1-01-1-01","C-B1-01-1-01","PAK-A-01"],
         "meters":[[0,4,20,12,1],[4,0,16,8,5],[20,16,0,8,21],[12,8,8,0,13],[1,5,21,13,0]],
         "missing":[],"method":"MANHATTAN_V1","limitations":["WALLS_NOT_CONSIDERED"],
         "attributes":[{"locationCode":"CRT-A-01","zone":"CART","temperatureZone":"AMBIENT","areaCode":null},
                       {"locationCode":"A-C1-01-1-01","zone":"STORAGE","temperatureZone":"AMBIENT","areaCode":"C1"},
                       {"locationCode":"F-A1-01-1-01","zone":"STORAGE","temperatureZone":"FROZEN","areaCode":"A1"},
                       {"locationCode":"C-B1-01-1-01","zone":"STORAGE","temperatureZone":"CHILLED","areaCode":"B1"},
                       {"locationCode":"PAK-A-01","zone":"PACKING","temperatureZone":"AMBIENT","areaCode":null}]}
        """;
    ful.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/location-distances"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body)));
    Res r =
        send(
            "POST",
            TestJwt.service("mfc-fulfillment-service"),
            "/internal/pick-routes",
            Map.of(
                "mfcId",
                MFC,
                "locationCodes",
                List.of("PAK-A-01", "A-C1-01-1-01", "F-A1-01-1-01", "C-B1-01-1-01")));
    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    assertThat(r.body().path("ordered").findValuesAsString("ordered")).isEmpty();
    assertThat(r.body().path("ordered").toString())
        .isEqualTo("[\"C-B1-01-1-01\",\"F-A1-01-1-01\",\"A-C1-01-1-01\",\"PAK-A-01\"]");
    assertThat(r.body().path("method").asString()).isIn("ROUTING", "NEAREST_FALLBACK");
    assertThat(r.body().path("startLocation").asString()).isEqualTo("CRT-A-01");
    assertThat(r.body().path("totalMeters").asDouble()).isEqualTo(12 + 8 + 16); // 출발→냉장→냉동→상온
  }

  /** center-master 직원 근무 시간표(STAFF) 응답 — 근무 미등록 작업자의 근무(2026-11-09). */
  void staffHours(String mfc, int status, String body) {
    ful.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/centers/" + mfc + "/calendar"))
            .withQueryParam("kind", WireMock.equalTo("STAFF"))
            .willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body)));
  }

  /**
   * 근무를 등록하지 않은 작업자 = 센터 직원 근무 시간표 안에서만(사용자 결정 2026-11-09 — 풀타임은 센터 운영시간): 시간표 밖(오늘 영업 안 함)이면 다음 작업
   * 받기 409 OFF_SHIFT, 시간표가 없으면 422 CONFIG_REQUIRED(상시 근무로 바꾸지 않음). 수동 배정은 경고만.
   */
  @Test
  void workersWithoutShiftFollowCenterStaffHours() {
    String s = UUID.randomUUID().toString().substring(0, 6);
    String closed = "MFC-SH-" + s;
    String unset = "MFC-SU-" + s;
    staffHours(closed, 200, "{\"open\":false,\"ranges\":[]}");
    staffHours(unset, 422, "{\"code\":\"CONFIG_REQUIRED\"}");
    for (String m : List.of(closed, unset)) {
      String sup = TestJwt.operator("sup-" + s, List.of("MFC_SUPERVISOR"), List.of(m));
      String w = "sh-" + m;
      Res reg =
          send(
              "POST",
              sup,
              "/mfcs/" + m + "/workers",
              Map.of(
                  "workerId",
                  w,
                  "displayName",
                  w,
                  "duty",
                  Map.of("taskTypes", List.of("PICK"), "temperatureZones", List.of("AMBIENT"))));
      assertThat(reg.status()).as(reg.body().toString()).isEqualTo(201);
      String tok = TestJwt.operator(w, List.of("MFC_WORKER"), List.of(m));
      Res r = send("POST", tok, "/mfcs/" + m + "/workers/" + w + "/next-tasks", null);
      if (m.equals(closed)) {
        assertThat(r.status()).as(r.body().toString()).isEqualTo(409);
        assertThat(r.body().path("code").asString()).isEqualTo("OFF_SHIFT");
      } else {
        assertThat(r.status()).as(r.body().toString()).isEqualTo(422);
        assertThat(r.body().path("code").asString()).isEqualTo("CONFIG_REQUIRED");
      }
      // 수동 배정은 막지 않는다 — 시간표 밖이면 경고, 시간표를 못 읽으면 경고 없이
      Res a =
          send(
              "POST",
              sup,
              "/assignments",
              Map.of("mfcId", m, "taskId", "T-SH-" + m, "taskType", "PICK", "workerId", w));
      assertThat(a.status()).as(a.body().toString()).isEqualTo(201);
      assertThat(a.body().path("warnings").toString())
          .isEqualTo(m.equals(closed) ? "[\"OFF_SHIFT\"]" : "[]");
    }
  }

  Res send(String method, String token, String path, @Nullable Object body) {
    var spec =
        http.method(org.springframework.http.HttpMethod.valueOf(method))
            .uri(path)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", "backoffice-web");
                });
    var e =
        (body == null
                ? spec
                : spec.contentType(MediaType.APPLICATION_JSON)
                    .body(mapper.writeValueAsString(body)))
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  void register(String w, List<String> types) {
    send(
        "POST",
        SUP,
        "/mfcs/" + MFC + "/workers",
        Map.of(
            "workerId",
            w,
            "displayName",
            w,
            "duty",
            Map.of("taskTypes", types, "temperatureZones", List.of("AMBIENT"))));
  }

  static Map<String, Object> task(
      String id, String type, String state, int version, @Nullable String worker, int dueIn) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("taskId", id);
    m.put("taskType", type);
    m.put("state", state);
    m.put("version", version);
    m.put("mfcId", MFC);
    if (worker != null) {
      m.put("assignment", Map.of("workerId", worker));
    }
    if (dueIn > 0) {
      m.put("promisedReadyAt", Instant.now().plusSeconds(dueIn * 60L).toString());
    }
    return m;
  }

  void moveOrders(List<Map<String, Object>> moves) {
    ful.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/move-orders"))
            .withQueryParam("mfcId", WireMock.equalTo(MFC))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(mapper.writeValueAsString(Map.of("items", moves)))));
  }

  void tasks(List<Map<String, Object>> items, String... states) {
    ful.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/tasks"))
            .withQueryParam("mfcId", WireMock.equalTo(MFC))
            .withQueryParam("state", havingExactly(states))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        mapper.writeValueAsString(
                            Map.of("items", items, "page", Map.of("size", items.size()))))));
  }

  /**
   * 여러 작업자가 동시에 하나 남은 작업을 받으려 하면 한 명만 받고 나머지는 409 NO_TASK_AVAILABLE — 500 이 나면 안 된다(2026-11-09 결함:
   * 활성 배정이 없을 때 FOR UPDATE 가 잠글 행이 없어 동시 삽입이 유일 인덱스에 걸리면 진 쪽이 500).
   */
  @Test
  void concurrentPullsOnTheLastTaskGiveItToExactlyOneWorker() throws Exception {
    for (int round = 0; round < 5; round++) {
      String s = UUID.randomUUID().toString().substring(0, 6);
      String only = "T-RC-" + s;
      tasks(List.of(task(only, "PICK", "READY", 1, null, 30)), "READY", "ASSIGNED", "IN_PROGRESS");
      int n = 6;
      List<String> ws = new java.util.ArrayList<>();
      for (int i = 0; i < n; i++) {
        String w = "rc" + i + "-" + s;
        register(w, List.of("PICK"));
        ws.add(w);
      }
      var start = new java.util.concurrent.CountDownLatch(1);
      var pool = java.util.concurrent.Executors.newFixedThreadPool(n);
      try {
        List<java.util.concurrent.Future<Res>> fs = new java.util.ArrayList<>();
        for (String w : ws) {
          String tok = TestJwt.operator(w, List.of("MFC_WORKER"), List.of(MFC));
          fs.add(
              pool.submit(
                  () -> {
                    start.await();
                    return send(
                        "POST", tok, "/mfcs/" + MFC + "/workers/" + w + "/next-tasks", null);
                  }));
        }
        start.countDown();
        List<Res> rs = new java.util.ArrayList<>();
        for (var f : fs) {
          rs.add(f.get(30, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertThat(rs)
            .allSatisfy(r -> assertThat(r.status()).as(r.body().toString()).isIn(201, 409));
        assertThat(rs).filteredOn(r -> r.status() == 201).hasSize(1);
        assertThat(rs)
            .filteredOn(r -> r.status() == 409)
            .allSatisfy(
                r -> assertThat(r.body().path("code").asString()).isEqualTo("NO_TASK_AVAILABLE"));
        assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM task_assignment WHERE task_id = ? AND state = 'ACTIVE'",
                    Integer.class,
                    only))
            .isEqualTo(1);
      } finally {
        pool.shutdownNow();
      }
    }
  }

  /**
   * 다음 작업 받기(0.6.0): 업무 안의 담당 없는 READY 작업 중 약속 이른 PICK → (그다음) 복귀 순, 한 번에 하나(다시 부르면 같은 배정), 본인만, 줄
   * 작업 없음 409, 등록 근무 밖 409.
   */
  @Test
  void nextTaskIsAssignedOneAtATimeBySystem() {
    String s = UUID.randomUUID().toString().substring(0, 6);
    String a = "nx1-" + s;
    String b = "nx2-" + s;
    String c = "nx3-" + s;
    register(a, List.of("PICK", "RETURN"));
    register(b, List.of("PICK"));
    register(c, List.of("RETURN"));
    String early = "T-NE-" + s;
    String late = "T-NL-" + s;
    String ret = "T-NR-" + s;
    tasks(
        List.of(
            task(late, "PICK", "READY", 1, null, 90),
            task(ret, "RETURN", "READY", 1, null, 0),
            task(early, "PICK", "READY", 1, null, 30),
            task("T-NO-" + s, "PACK", "READY", 1, null, 10)),
        "READY",
        "ASSIGNED",
        "IN_PROGRESS");
    String ta = TestJwt.operator(a, List.of("MFC_WORKER"), List.of(MFC));
    String tb = TestJwt.operator(b, List.of("MFC_WORKER"), List.of(MFC));
    String tc = TestJwt.operator(c, List.of("MFC_WORKER"), List.of(MFC));
    String path = "/mfcs/" + MFC + "/workers/";
    // 본인만
    assertThat(send("POST", ta, path + b + "/next-tasks", null).status()).isEqualTo(403);
    Res first = send("POST", ta, path + a + "/next-tasks", null);
    assertThat(first.status()).as(first.body().toString()).isEqualTo(201);
    assertThat(first.body().path("taskId").asString()).isEqualTo(early);
    assertThat(first.body().path("source").asString()).isEqualTo("SYSTEM");
    // 한 번에 하나 — 다시 부르면 같은 배정
    Res again = send("POST", ta, path + a + "/next-tasks", null);
    assertThat(again.status()).isEqualTo(200);
    assertThat(again.body().path("taskId").asString()).isEqualTo(early);
    // 휴게 중: 들고 있는 작업은 그대로 돌려줌, 휴게 끝까지 새 작업은 없음(작업자 쪽 3단계)
    // (0.20.0) 작업자 본인 휴게는 센터 휴게 번호로
    assertThat(send("PUT", SUP, "/mfcs/" + MFC + "/break-pin", Map.of("pin", "4321")).status())
        .isEqualTo(200);
    assertThat(send("POST", ta, path + a + "/breaks", Map.of("supervisorPin", "4321")).status())
        .isEqualTo(200);
    assertThat(send("POST", ta, path + a + "/next-tasks", null).status()).isEqualTo(200);
    // 작업 내놓기(fulfillment 가 부르는 내부 API): 담당만, 풀리면 새 작업 받기에서 그 작업이 다시 후보
    assertThat(
            send(
                    "POST",
                    FUL_SVC,
                    "/internal/assignments/" + early + "/releases",
                    Map.of("workerId", "someone"))
                .status())
        .isEqualTo(409);
    Res rel =
        send(
            "POST", FUL_SVC, "/internal/assignments/" + early + "/releases", Map.of("workerId", a));
    assertThat(rel.body().path("released").asBoolean()).isTrue();
    Res onBreak = send("POST", ta, path + a + "/next-tasks", null);
    assertThat(onBreak.body().path("code").asString()).isEqualTo("ON_BREAK");
    assertThat(
            send("POST", ta, path + a + "/breaks/current/end", null)
                .body()
                .path("endedAt")
                .isNull())
        .isFalse();
    assertThat(send("POST", ta, path + a + "/next-tasks", null).body().path("taskId").asString())
        .isEqualTo(early);
    // 다른 작업자는 남은 것 중 가장 급한 PICK, 복귀만 하는 작업자는 복귀
    assertThat(send("POST", tb, path + b + "/next-tasks", null).body().path("taskId").asString())
        .isEqualTo(late);
    assertThat(send("POST", tc, path + c + "/next-tasks", null).body().path("taskId").asString())
        .isEqualTo(ret);
    // 더 받을 작업 없음
    String d = "nx4-" + s;
    register(d, List.of("PICK"));
    String td = TestJwt.operator(d, List.of("MFC_WORKER"), List.of(MFC));
    Res none = send("POST", td, path + d + "/next-tasks", null);
    assertThat(none.status()).isEqualTo(409);
    assertThat(none.body().path("code").asString()).isEqualTo("NO_TASK_AVAILABLE");
    // 등록 근무 밖이면 시스템이 작업을 주지 않음
    Instant later = Instant.now().plusSeconds(3 * 3600);
    send(
        "POST",
        SUP,
        path + d + "/shifts",
        Map.of("startAt", later.toString(), "endAt", later.plusSeconds(4 * 3600).toString()));
    Res off = send("POST", td, path + d + "/next-tasks", null);
    assertThat(off.status()).isEqualTo(409);
    assertThat(off.body().path("code").asString()).isEqualTo("OFF_SHIFT");
  }

  /**
   * BR-52 R1·R3·R4(0.13.0): 본인이 자기 상세를 읽어 고를 수 있는 업무(workAreas)를 보고, 고른 업무 안에서만 새 작업을 받는다. 겹치는 유형이
   * 없는 업무는 422 WORK_AREA_NOT_ALLOWED, 들고 있는 작업은 업무와 상관없이 그대로. 상세에 자격·고용 형태·유효 업무가 같이 온다(0.12.0 버그).
   */
  @Test
  void nextTaskStaysWithinChosenWorkArea() {
    moveOrders(List.of());
    String s = UUID.randomUUID().toString().substring(0, 6);
    String w = "wa-" + s;
    register(w, List.of("PICK", "RETURN"));
    String pick = "T-WP-" + s;
    String ret = "T-WR-" + s;
    tasks(
        List.of(task(pick, "PICK", "READY", 1, null, 10), task(ret, "RETURN", "READY", 1, null, 0)),
        "READY",
        "ASSIGNED",
        "IN_PROGRESS");
    String tw = TestJwt.operator(w, List.of("MFC_WORKER"), List.of(MFC));
    String path = "/mfcs/" + MFC + "/workers/" + w;

    Res me = send("GET", tw, path, null);
    assertThat(me.status()).as(me.body().toString()).isEqualTo(200);
    assertThat(me.body().path("workAreas").toString()).isEqualTo("[\"STOCK_MOVE\",\"PICK_PACK\"]");
    assertThat(me.body().path("effectiveTaskTypes").toString()).contains("PICK").contains("RETURN");
    assertThat(me.body().path("employmentType").asString()).isEqualTo("REGULAR");
    assertThat(me.body().path("qualification").path("taskTypes").toString()).contains("PICK");
    assertThat(me.body().path("onBreak").asBoolean()).isFalse();

    Res denied = send("POST", tw, path + "/next-tasks", Map.of("workArea", "INBOUND"));
    assertThat(denied.status()).isEqualTo(422);
    assertThat(denied.body().path("code").asString()).isEqualTo("WORK_AREA_NOT_ALLOWED");

    // 재고 이동을 고르면 더 급한 PICK 이 있어도 복귀
    Res moved = send("POST", tw, path + "/next-tasks", Map.of("workArea", "STOCK_MOVE"));
    assertThat(moved.status()).as(moved.body().toString()).isEqualTo(201);
    assertThat(moved.body().path("taskId").asString()).isEqualTo(ret);
    // 들고 있는 작업은 다른 업무를 골라도 그대로
    Res held = send("POST", tw, path + "/next-tasks", Map.of("workArea", "PICK_PACK"));
    assertThat(held.status()).isEqualTo(200);
    assertThat(held.body().path("taskId").asString()).isEqualTo(ret);
  }

  /**
   * 작업자 쪽 2단계: 일일크루는 그날 근무 등록한 날만(그날 업무 = 유효 자격, 기본 PICK·PACK), 업무는 자격 안, 자격에서 빠진 유형·만료 자격은 배정 조건에서
   * 빠짐, 정규직은 근무 등록 대상 아님.
   */
  @Test
  void dailyCrewWorksOnlyOnEnrolledDaysAndDutyStaysWithinQualification() {
    String s = UUID.randomUUID().toString().substring(0, 6);
    String dc = "dc-" + s;
    String rg = "rg-" + s;
    String path = "/mfcs/" + MFC + "/workers";
    Res reg =
        send(
            "POST",
            SUP,
            path,
            Map.of(
                "workerId",
                dc,
                "displayName",
                dc,
                "employmentType",
                "DAILY",
                "duty",
                Map.of("taskTypes", List.of("PICK"))));
    assertThat(reg.status()).as(reg.body().toString()).isEqualTo(201);
    assertThat(reg.body().path("employmentType").asString()).isEqualTo("DAILY");
    assertThat(reg.body().path("qualification").path("taskTypes").toString())
        .contains("PICK")
        .contains("PACK");
    assertThat(reg.body().path("effectiveTaskTypes")).isEmpty(); // 아직 근무 등록 전
    String pack = "T-DP-" + s;
    tasks(List.of(task(pack, "PACK", "READY", 1, null, 15)), "READY", "ASSIGNED", "IN_PROGRESS");
    String tdc = TestJwt.operator(dc, List.of("MFC_WORKER"), List.of(MFC));
    Res before = send("POST", tdc, path + "/" + dc + "/next-tasks", null);
    assertThat(before.status()).isEqualTo(422);
    assertThat(before.body().path("code").asString()).isEqualTo("DAILY_NOT_ENROLLED");
    Res day = send("POST", tdc, path + "/" + dc + "/day-enrollments", Map.of());
    assertThat(day.status()).as(day.body().toString()).isEqualTo(200);
    assertThat(day.body().path("dutyTaskTypes").toString()).contains("PICK").contains("PACK");
    Res got = send("POST", tdc, path + "/" + dc + "/next-tasks", null);
    assertThat(got.status()).as(got.body().toString()).isEqualTo(201);
    assertThat(got.body().path("taskId").asString()).isEqualTo(pack);

    // 정규직: 업무는 자격 안, 근무 등록 대상 아님
    Res outside =
        send(
            "POST",
            SUP,
            path,
            Map.of(
                "workerId",
                rg,
                "displayName",
                rg,
                "qualification",
                Map.of("taskTypes", List.of("PICK")),
                "duty",
                Map.of("taskTypes", List.of("PICK", "PACK"))));
    assertThat(outside.body().path("code").asString()).isEqualTo("DUTY_OUTSIDE_QUALIFICATION");
    register(rg, List.of("PICK", "PACK"));
    String trg = TestJwt.operator(rg, List.of("MFC_WORKER"), List.of(MFC));
    assertThat(
            send("POST", trg, path + "/" + rg + "/day-enrollments", Map.of())
                .body()
                .path("code")
                .asString())
        .isEqualTo("NOT_DAILY_CREW");
    // 자격에서 PICK 을 빼면 유효 업무 = PACK 만, 만료 자격이면 아무것도
    Res q1 =
        sendV(
            SUP,
            path + "/" + rg + "/qualification-changes",
            Map.of("taskTypes", List.of("PACK"), "reason", "피킹 교육 전"),
            1);
    assertThat(q1.status()).as(q1.body().toString()).isEqualTo(200);
    assertThat(q1.body().path("effectiveTaskTypes").toString()).isEqualTo("[\"PACK\"]");
    Res q2 =
        sendV(
            SUP,
            path + "/" + rg + "/qualification-changes",
            Map.of(
                "taskTypes", List.of("PACK"),
                "expiresAt", Instant.now().minusSeconds(60).toString(),
                "reason", "만료 시험"),
            2);
    assertThat(q2.body().path("qualification").path("expired").asBoolean()).isTrue();
    assertThat(q2.body().path("effectiveTaskTypes")).isEmpty();
  }

  Res sendV(String token, String path, Object body, int version) {
    var e =
        http.post()
            .uri(path)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", "backoffice-web");
                  h.set("X-Expected-Version", String.valueOf(version));
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapper.writeValueAsString(body))
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  /** 작업자 쪽 4단계: 다음 작업 받기 순서 — 자동 보충·긴급 이동(만든 순) → 센터 관리자 이동 → 복귀, 담당 있거나 이미 집은 이동은 제외. */
  @Test
  void nextTaskIncludesMovesAndReplenishmentsInDecidedOrder() {
    String s = UUID.randomUUID().toString().substring(0, 6);
    String ret = "T-MR-" + s;
    tasks(List.of(task(ret, "RETURN", "READY", 1, null, 0)), "READY", "ASSIGNED", "IN_PROGRESS");
    Instant base = Instant.now().minusSeconds(3600);
    List<Map<String, Object>> moves =
        List.of(
            move("MV-N-" + s, "MOVE", false, 0, null, base.plusSeconds(10)),
            move("MV-U-" + s, "MOVE", true, 0, null, base.plusSeconds(30)),
            move("MV-R-" + s, "REPLENISH", false, 0, null, base.plusSeconds(20)),
            move("MV-P-" + s, "REPLENISH", false, 2, null, base),
            move("MV-A-" + s, "REPLENISH", false, 0, "someone", base));
    ful.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/move-orders"))
            .withQueryParam("mfcId", WireMock.equalTo(MFC))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(mapper.writeValueAsString(Map.of("items", moves)))));
    // 0.9.1 상세: 보충 지시·복귀 작업은 상세를 옮겨 적고, 상세가 없는(404) 이동은 available=false
    Map<String, Object> mvDetail =
        new LinkedHashMap<>(move("MV-R-" + s, "REPLENISH", false, 1, null, base));
    mvDetail.putAll(
        Map.of(
            "sku",
            "E-1",
            "lotId",
            "LOT-1",
            "fromLocation",
            "A-S1-01-1-01",
            "toLocation",
            "A-S9-01-1-01",
            "qty",
            4));
    ful.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/move-orders/MV-R-" + s))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(mapper.writeValueAsString(mvDetail))));
    Map<String, Object> retDetail =
        Map.of(
            "taskId",
            ret,
            "taskType",
            "RETURN",
            "lines",
            List.of(
                Map.of(
                    "sku",
                    "E-2",
                    "productName",
                    "우유",
                    "remainingQty",
                    2,
                    "picks",
                    List.of(
                        Map.of(
                            "lotId",
                            "LOT-2",
                            "remainingQty",
                            2,
                            "locations",
                            List.of(Map.of("locationCode", "PAK-A-01", "qty", 2)),
                            "suggestedToLocations",
                            List.of("A-S1-02-1-01"))))));
    ful.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/tasks/" + ret))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(mapper.writeValueAsString(retDetail))));
    List<String> order = new java.util.ArrayList<>();
    List<JsonNode> details = new java.util.ArrayList<>();
    for (int i = 0; i < 4; i++) {
      String w = "mv" + i + "-" + s;
      register(w, List.of("MOVE", "REPLENISH", "RETURN"));
      String tok = TestJwt.operator(w, List.of("MFC_WORKER"), List.of(MFC));
      Res r = send("POST", tok, "/mfcs/" + MFC + "/workers/" + w + "/next-tasks", null);
      assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
      order.add(r.body().path("taskId").asString());
      details.add(r.body().path("detail"));
    }
    assertThat(order).containsExactly("MV-R-" + s, "MV-U-" + s, "MV-N-" + s, ret);
    JsonNode d0 = details.get(0);
    assertThat(d0.path("available").asBoolean()).isTrue();
    assertThat(d0.path("lines").path(0).path("qty").asInt()).isEqualTo(3);
    assertThat(d0.path("lines").path(0).path("fromLocation").asString()).isEqualTo("A-S1-01-1-01");
    assertThat(d0.path("lines").path(0).path("toLocation").asString()).isEqualTo("A-S9-01-1-01");
    assertThat(details.get(1).path("available").asBoolean()).isFalse();
    JsonNode d3 = details.get(3);
    assertThat(d3.path("available").asBoolean()).isTrue();
    assertThat(d3.path("lines").path(0).path("productName").asString()).isEqualTo("우유");
    assertThat(d3.path("lines").path(0).path("fromLocation").asString()).isEqualTo("PAK-A-01");
    assertThat(d3.path("lines").path(0).path("toLocation").asString()).isEqualTo("A-S1-02-1-01");
  }

  /**
   * (0.14.0, BR-52 R2) 품질검사·적치(mfc-inbound 입고 작업)도 다음 작업 받기 후보 — 순서 자동 보충 → 품질검사 → 적치 → 센터 관리자
   * 이동(사용자 2026-11-14), 같은 유형은 먼저 만든 것부터, 담당 있는 작업 제외, 들고 있는 입고 작업은 그대로, 업무 INSPECTION 은 품질검사만.
   */
  @Test
  void nextTaskIncludesInspectionsAndPutawaysAfterReplenishment() {
    String s = UUID.randomUUID().toString().substring(0, 6);
    tasks(List.of(), "READY", "ASSIGNED", "IN_PROGRESS");
    Instant base = Instant.now().minusSeconds(3600);
    ful.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/move-orders"))
            .withQueryParam("mfcId", WireMock.equalTo(MFC))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        mapper.writeValueAsString(
                            Map.of(
                                "items",
                                List.of(
                                    move("MV-IM-" + s, "MOVE", false, 0, null, base),
                                    move(
                                        "MV-IR-" + s,
                                        "REPLENISH",
                                        false,
                                        0,
                                        null,
                                        base.plusSeconds(50))))))));
    List<Map<String, Object>> inbound =
        List.of(
            inboundTask("TSK-PUT-A-" + s, "PUTAWAY", null, base.plusSeconds(10)),
            inboundTask("TSK-INS-B-" + s, "INSPECT", null, base.plusSeconds(30)),
            inboundTask("TSK-INS-A-" + s, "INSPECT", null, base.plusSeconds(20)),
            inboundTask("TSK-INS-X-" + s, "INSPECT", "someone", base));
    ful.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/inbound-tasks"))
            .withQueryParam("mfcId", WireMock.equalTo(MFC))
            .withQueryParam("state", WireMock.equalTo("READY"))
            .withQueryParam("withLabels", WireMock.equalTo("false"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(mapper.writeValueAsString(Map.of("items", inbound)))));
    Map<String, Object> insDetail =
        new LinkedHashMap<>(inboundTask("TSK-INS-A-" + s, "INSPECT", null, base));
    insDetail.putAll(
        Map.of("sku", "E-7", "lotId", "LOT-R", "locationCode", "RCV-DOCK", "plannedQty", 6));
    ful.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/inbound-tasks/TSK-INS-A-" + s))
            .withQueryParam("withLabels", WireMock.equalTo("false"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(mapper.writeValueAsString(insDetail))));
    // 업무 INSPECTION 만 고르면 보충이 앞에 있어도 품질검사
    String insp = "in-" + s;
    register(insp, List.of("INSPECT", "PUTAWAY", "REPLENISH", "MOVE"));
    String inspTok = TestJwt.operator(insp, List.of("MFC_WORKER"), List.of(MFC));
    Res first =
        send(
            "POST",
            inspTok,
            "/mfcs/" + MFC + "/workers/" + insp + "/next-tasks",
            Map.of("workArea", "INSPECTION"));
    assertThat(first.status()).as(first.body().toString()).isEqualTo(201);
    assertThat(first.body().path("taskId").asString()).isEqualTo("TSK-INS-A-" + s);
    assertThat(first.body().path("taskType").asString()).isEqualTo("INSPECT");
    JsonNode d = first.body().path("detail");
    assertThat(d.path("available").asBoolean()).isTrue();
    assertThat(d.path("lines").path(0).path("qty").asInt()).isEqualTo(6);
    assertThat(d.path("lines").path(0).path("fromLocation").asString()).isEqualTo("RCV-DOCK");
    // 아직 READY(끝나지 않음)라 다시 부르면 들고 있는 작업 그대로
    Res again = send("POST", inspTok, "/mfcs/" + MFC + "/workers/" + insp + "/next-tasks", null);
    assertThat(again.status()).isEqualTo(200);
    assertThat(again.body().path("taskId").asString()).isEqualTo("TSK-INS-A-" + s);
    // 업무를 안 고르면 순서대로: 보충 → 품질검사(남은 B) → 적치 → 센터 관리자 이동
    List<String> order = new java.util.ArrayList<>();
    for (int i = 0; i < 4; i++) {
      String w = "ip" + i + "-" + s;
      register(w, List.of("INSPECT", "PUTAWAY", "REPLENISH", "MOVE"));
      String tok = TestJwt.operator(w, List.of("MFC_WORKER"), List.of(MFC));
      Res r = send("POST", tok, "/mfcs/" + MFC + "/workers/" + w + "/next-tasks", null);
      assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
      order.add(r.body().path("taskId").asString());
    }
    assertThat(order)
        .containsExactly("MV-IR-" + s, "TSK-INS-B-" + s, "TSK-PUT-A-" + s, "MV-IM-" + s);
  }

  /**
   * (0.24.0, MC-1 — BR-52 R2·R12~R14) 회수품 반입(반납 도착 대기)·회수품 검수(검수 대기 반입)·재고 실사(계획 실사) — 정본에 담당이 없어 이
   * 서비스 배정이 한 사람에게만 준다. 실사는 셀 수 없는 사람(첫 계수자)에게 주지 않고, 첫 계수를 낸 사람의 배정은 닫고 다른 사람에게.
   */
  @Test
  void nextTaskGivesReturnWorkAndCycleCountsToOnePersonEach() {
    String s = UUID.randomUUID().toString().substring(0, 6);
    tasks(List.of(), "READY", "ASSIGNED", "IN_PROGRESS");
    moveOrders(List.of());
    Instant base = Instant.now().minusSeconds(3600);
    String arr = "RTP-" + s;
    stubJson(
        WireMock.get(urlPathEqualTo("/api/v1/return-arrivals"))
            .withQueryParam("mfcId", WireMock.equalTo(MFC))
            .withQueryParam("state", WireMock.equalTo("WAITING")),
        Map.of(
            "items",
            List.of(
                Map.of(
                    "arrivalId",
                    arr,
                    "mfcId",
                    MFC,
                    "purpose",
                    "CLAIM_RETURN",
                    "state",
                    "WAITING",
                    "arrivedAt",
                    base.toString(),
                    "lines",
                    List.of(Map.of("sku", "E-9", "productName", "두부", "qty", 2))))));
    String rr = "RR-" + s;
    stubJson(
        WireMock.get(urlPathEqualTo("/api/v1/return-receipts"))
            .withQueryParam("mfcId", WireMock.equalTo(MFC))
            .withQueryParam("inspectionState", WireMock.equalTo("PENDING")),
        Map.of(
            "items",
            List.of(
                receipt("RR-DONE-" + s, "INSPECTED", base.minusSeconds(60)),
                receipt(rr, "PENDING", base))));
    Map<String, Object> rrDetail = new LinkedHashMap<>(receipt(rr, "PENDING", base));
    rrDetail.put("toLocationCode", "RTN-A-01");
    rrDetail.put(
        "lines",
        List.of(Map.of("sku", "E-9", "qty", 2, "lotId", "LOT-RET", "conditionNote", "미개봉")));
    stubJson(WireMock.get(urlPathEqualTo("/api/v1/return-receipts/" + rr)), rrDetail);
    String cs1 = "CS1-" + s;
    String cs2 = "CS2-" + s;
    String c1 = "c1-" + s;
    countWork(
        List.of(
            count(cs2, "RECOUNT_REQUIRED", List.of(c1), base.minusSeconds(600)),
            count(cs1, "COUNTING", List.of(), base)));

    // 회수품 반입: 재고 이동 업무 — 한 사람만
    String r1 = "r1-" + s;
    register(r1, List.of("PUTAWAY", "RETURN_RECEIPT"));
    Res got = pull(r1, "STOCK_MOVE");
    assertThat(got.status()).as(got.body().toString()).isEqualTo(201);
    assertThat(got.body().path("taskId").asString()).isEqualTo(arr);
    assertThat(got.body().path("taskType").asString()).isEqualTo("RETURN_RECEIPT");
    assertThat(got.body().path("detail").path("lines").path(0).path("productName").asString())
        .isEqualTo("두부");
    assertThat(pull(r1, null).status()).isEqualTo(200); // 아직 대기 — 들고 있는 작업
    String r2 = "r2-" + s;
    register(r2, List.of("RETURN_RECEIPT"));
    Res none = pull(r2, "STOCK_MOVE");
    assertThat(none.status()).as(none.body().toString()).isEqualTo(409);
    assertThat(none.body().path("code").asString()).isEqualTo("NO_TASK_AVAILABLE");

    // 회수품 검수: 품질검사 업무 — 검수 대기(PENDING)만, 상세 = 반입 줄(출발 칸 = 반품 칸)
    String i1 = "i1-" + s;
    register(i1, List.of("INSPECT", "RETURN_INSPECT"));
    Res insp = pull(i1, "INSPECTION");
    assertThat(insp.status()).as(insp.body().toString()).isEqualTo(201);
    assertThat(insp.body().path("taskId").asString()).isEqualTo(rr);
    JsonNode line = insp.body().path("detail").path("lines").path(0);
    assertThat(line.path("fromLocation").asString()).isEqualTo("RTN-A-01");
    assertThat(line.path("lotId").asString()).isEqualTo("LOT-RET");

    // 재고 실사: c1 은 CS2 의 첫 계수자라 CS1(기준 시각이 늦어도)
    register(c1, List.of("CYCLE_COUNT"));
    Res c1First = pull(c1, "STOCK_COUNT");
    assertThat(c1First.status()).as(c1First.body().toString()).isEqualTo(201);
    assertThat(c1First.body().path("taskId").asString()).isEqualTo(cs1);
    assertThat(c1First.body().path("taskType").asString()).isEqualTo("CYCLE_COUNT");
    assertThat(c1First.body().path("detail").path("locationCodes").path(0).asString())
        .isEqualTo("A-C1-01-1-01");
    assertThat(pull(c1, "STOCK_COUNT").status()).isEqualTo(200);
    // c1 이 첫 계수를 냄 → CS1 재계수 대기(c1 제외) — c1 은 들고 있는 것도, 받을 것도 없다
    countWork(
        List.of(
            count(cs2, "RECOUNT_REQUIRED", List.of(c1), base.minusSeconds(600)),
            count(cs1, "RECOUNT_REQUIRED", List.of(c1), base)));
    Res c1Again = pull(c1, "STOCK_COUNT");
    assertThat(c1Again.status()).as(c1Again.body().toString()).isEqualTo(409);
    // 다른 사람: 오래된 CS2 → 다음 사람은 CS1(c1 배정은 닫힘)
    String c2 = "c2-" + s;
    register(c2, List.of("CYCLE_COUNT"));
    assertThat(pull(c2, "STOCK_COUNT").body().path("taskId").asString()).isEqualTo(cs2);
    String c3 = "c3-" + s;
    register(c3, List.of("CYCLE_COUNT"));
    Res c3got = pull(c3, "STOCK_COUNT");
    assertThat(c3got.status()).as(c3got.body().toString()).isEqualTo(201);
    assertThat(c3got.body().path("taskId").asString()).isEqualTo(cs1);
    assertThat(
            jdbc.queryForList(
                "SELECT worker_id || ':' || state FROM task_assignment WHERE task_id = ?"
                    + " ORDER BY assignment_version",
                String.class,
                cs1))
        .containsExactly(c1 + ":RELEASED", c3 + ":ACTIVE");
  }

  /**
   * (0.25.0, BR-52 R2·BR-53 R9) 입고(RECEIVE, taskId = trfId) — mfc-inbound 입고 예정 중 출발을 받았고 진행 중 수령이
   * 없는 것만, 예정 도착 이른 순, 한 사람에게만. 업무를 안 고르면 품질검사보다 앞. 예정이 끝나면(목록에서 빠짐) 다음 작업.
   */
  @Test
  void nextTaskGivesDepartedInboundPromisesAsReceiveBeforeInspection() {
    String s = UUID.randomUUID().toString().substring(0, 6);
    tasks(List.of(), "READY", "ASSIGNED", "IN_PROGRESS");
    moveOrders(List.of());
    Instant base = Instant.now().minusSeconds(3600);
    String ins = "TSK-RI-" + s;
    stubJson(
        WireMock.get(urlPathEqualTo("/api/v1/inbound-tasks"))
            .withQueryParam("mfcId", WireMock.equalTo(MFC))
            .withQueryParam("state", WireMock.equalTo("READY")),
        Map.of("items", List.of(inboundTask(ins, "INSPECT", null, base.minusSeconds(7200)))));
    String notDeparted = "TRF-N-" + s;
    String openReceipt = "TRF-O-" + s;
    String expected = "TRF-E-" + s;
    String receiving = "TRF-R-" + s;
    List<Map<String, Object>> all =
        List.of(
            promise(notDeparted, "EXPECTED", null, base.minusSeconds(600), List.of()),
            promise(
                openReceipt,
                "RECEIVING",
                "TOUT-O",
                base.minusSeconds(300),
                List.of(Map.of("receiptId", "RCV-1", "inventoryApplyStatus", "UNKNOWN_RESULT"))),
            promise(receiving, "RECEIVING", "TOUT-R", base, List.of()),
            promise(expected, "EXPECTED", "TOUT-E", base.plusSeconds(600), List.of()));
    stubJson(
        WireMock.get(urlPathEqualTo("/api/v1/inbound-promises"))
            .withQueryParam("mfcId", WireMock.equalTo(MFC)),
        Map.of("items", all));
    Map<String, Object> detail =
        new LinkedHashMap<>(promise(receiving, "RECEIVING", "TOUT-R", base, List.of()));
    detail.put(
        "lines",
        List.of(
            Map.of(
                "sku",
                "S-R",
                "productName",
                "우유",
                "lotId",
                "CDC-L1",
                "expectedQty",
                12,
                "receivedQty",
                0)));
    stubJson(WireMock.get(urlPathEqualTo("/api/v1/inbound-promises/" + receiving)), detail);

    // 업무를 안 골라도 입고가 품질검사보다 먼저, 출발 전·진행 중 수령은 건너뛰고 예정 도착 이른 것
    String w0 = "rc0-" + s;
    register(w0, List.of("RECEIVE", "INSPECT"));
    Res got = pull(w0, null);
    assertThat(got.status()).as(got.body().toString()).isEqualTo(201);
    assertThat(got.body().path("taskId").asString()).isEqualTo(receiving);
    assertThat(got.body().path("taskType").asString()).isEqualTo("RECEIVE");
    JsonNode line = got.body().path("detail").path("lines").path(0);
    assertThat(line.path("productName").asString()).isEqualTo("우유");
    assertThat(line.path("qty").asInt()).isEqualTo(12);
    assertThat(line.path("lotId").asString()).isEqualTo("CDC-L1");
    // 아직 RECEIVING — 들고 있는 작업
    Res again = pull(w0, "INBOUND");
    assertThat(again.status()).isEqualTo(200);
    assertThat(again.body().path("taskId").asString()).isEqualTo(receiving);
    // 한 사람만: 다음 사람은 출발한 EXPECTED, 그 다음은 없음
    String w1 = "rc1-" + s;
    register(w1, List.of("RECEIVE"));
    assertThat(pull(w1, "INBOUND").body().path("taskId").asString()).isEqualTo(expected);
    String w2 = "rc2-" + s;
    register(w2, List.of("RECEIVE"));
    Res none = pull(w2, "INBOUND");
    assertThat(none.status()).as(none.body().toString()).isEqualTo(409);
    assertThat(none.body().path("code").asString()).isEqualTo("NO_TASK_AVAILABLE");
    // 수령이 재고에 반영되면(TIN_POSTED — 끝나지 않은 목록에서 빠짐) 끝난 것 → 다음 작업(품질검사)
    stubJson(
        WireMock.get(urlPathEqualTo("/api/v1/inbound-promises"))
            .withQueryParam("mfcId", WireMock.equalTo(MFC)),
        Map.of("items", List.of(all.get(0), all.get(1), all.get(3))));
    Res next = pull(w0, null);
    assertThat(next.status()).as(next.body().toString()).isEqualTo(201);
    assertThat(next.body().path("taskId").asString()).isEqualTo(ins);
  }

  static Map<String, Object> promise(
      String trfId,
      String state,
      @Nullable String departureId,
      Instant planned,
      List<Map<String, Object>> receipts) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("trfId", trfId);
    m.put("mfcId", MFC);
    m.put("state", state);
    m.put("departureId", departureId);
    m.put("plannedArrivalAt", planned.toString());
    m.put("receipts", receipts);
    m.put("lines", List.of());
    return m;
  }

  Res pull(String worker, @Nullable String workArea) {
    String tok = TestJwt.operator(worker, List.of("MFC_WORKER"), List.of(MFC));
    return send(
        "POST",
        tok,
        "/mfcs/" + MFC + "/workers/" + worker + "/next-tasks",
        workArea == null ? null : Map.of("workArea", workArea));
  }

  void stubJson(com.github.tomakehurst.wiremock.client.MappingBuilder m, Object body) {
    ful.stubFor(
        m.willReturn(
            aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(mapper.writeValueAsString(body))));
  }

  void countWork(List<Map<String, Object>> items) {
    stubJson(
        WireMock.get(urlPathEqualTo("/api/v1/internal/count-work"))
            .withQueryParam("mfcId", WireMock.equalTo(MFC)),
        Map.of("items", items));
  }

  static Map<String, Object> count(
      String id, String state, List<String> excluded, Instant baseline) {
    return Map.of(
        "sessionId",
        id,
        "planId",
        "CPL-1",
        "state",
        state,
        "locationCodes",
        List.of("A-C1-01-1-01"),
        "excludedOperatorIds",
        excluded,
        "baselineAt",
        baseline.toString());
  }

  static Map<String, Object> receipt(String id, String inspection, Instant at) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("returnReceiptId", id);
    m.put("mfcId", MFC);
    m.put("inventoryApplyStatus", "APPLIED");
    m.put("inspectionState", inspection);
    m.put("receivedAt", at.toString());
    return m;
  }

  static Map<String, Object> inboundTask(
      String id, String type, @Nullable String assignee, Instant created) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("taskId", id);
    m.put("taskType", type);
    m.put("state", "READY");
    m.put("plannedQty", 1);
    m.put("doneQty", 0);
    if (assignee != null) {
      m.put("assigneeId", assignee);
    }
    m.put("createdAt", created.toString());
    return m;
  }

  static Map<String, Object> move(
      String id,
      String kind,
      boolean urgent,
      int picked,
      @Nullable String assignee,
      Instant created) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("moveId", id);
    m.put("kind", kind);
    m.put("state", "ACTIVE");
    m.put("urgent", urgent);
    m.put("pickedQty", picked);
    if (assignee != null) {
      m.put("assigneeId", assignee);
    }
    m.put("createdAt", created.toString());
    return m;
  }

  /** "작업 시작"(0.22.0, 사용자 결정 2026-11-19): commit=true 면 계산한 추천을 바로 배정한다. */
  @Test
  void startWorkCommitsPlanRightAway() {
    moveOrders(List.of());
    register("sw1", List.of("PICK", "PACK"));
    String s = UUID.randomUUID().toString().substring(0, 6);
    String p1 = "T-SW1-" + s;
    String k1 = "T-SW2-" + s;
    List<Map<String, Object>> ready =
        List.of(task(p1, "PICK", "READY", 1, null, 10), task(k1, "PACK", "READY", 1, null, 0));
    tasks(ready, "READY", "ASSIGNED", "IN_PROGRESS"); // 계산 입력
    tasks(ready, "READY"); // 확정 직전 다시 보기(작업이 그대로 — READY·같은 version)
    Res started =
        send(
            "POST",
            SUP,
            "/assignment-plans",
            Map.of("mfcId", MFC, "horizonMinutes", 60, "commit", true));
    assertThat(started.status()).as(started.body().toString()).isEqualTo(201);
    assertThat(started.body().path("state").asString()).isEqualTo("COMMITTED");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_assignment WHERE task_id IN (?,?) AND state = 'ACTIVE'",
                Integer.class,
                p1,
                k1))
        .isEqualTo(2);
    // 작업자도 commit=true 는 못 한다
    assertThat(
            send(
                    "POST",
                    WORKER,
                    "/assignment-plans",
                    Map.of("mfcId", MFC, "horizonMinutes", 60, "commit", true))
                .status())
        .isEqualTo(403);
  }

  @Test
  void proposesByDutyAndBusyTimeThenCommitsAfterRecheck() {
    moveOrders(List.of());
    register("pk1", List.of("PICK"));
    register("pk2", List.of("PICK", "PACK"));
    String s = UUID.randomUUID().toString().substring(0, 6);
    String p1 = "T-P1-" + s;
    String p2 = "T-P2-" + s;
    String k1 = "T-K1-" + s;
    String rx = "T-RX-" + s;
    List<Map<String, Object>> all =
        List.of(
            task(p1, "PICK", "READY", 1, null, 20),
            task(p2, "PICK", "READY", 1, null, 0),
            task(k1, "PACK", "READY", 1, null, 0),
            task(rx, "RECEIVE", "READY", 1, null, 0),
            task("T-BUSY-" + s, "PICK", "IN_PROGRESS", 3, "pk1", 0));
    tasks(all, "READY", "ASSIGNED", "IN_PROGRESS");

    assertThat(
            send("POST", WORKER, "/assignment-plans", Map.of("mfcId", MFC, "horizonMinutes", 60))
                .status())
        .isEqualTo(403);
    Res created =
        send("POST", SUP, "/assignment-plans", Map.of("mfcId", MFC, "horizonMinutes", 60));
    assertThat(created.status()).as(created.body().toString()).isEqualTo(201);
    JsonNode plan = created.body();
    assertThat(plan.path("state").asString()).isEqualTo("PROPOSED");
    assertThat(plan.path("solverStatus").asString()).isIn("OPTIMAL", "FEASIBLE");
    assertThat(plan.path("items")).hasSize(3);
    assertThat(plan.path("unassigned")).hasSize(1);
    // 쓴 설정(임시 구현값)·근무 미등록 작업자·한계가 결과에 남는다
    assertThat(plan.path("settings").path("taskMinutes").path("PICK").asInt()).isEqualTo(7);
    assertThat(plan.path("settings").path("status").asString())
        .isEqualTo("TEMPORARY_IMPLEMENTATION_VALUES");
    assertThat(plan.path("availabilityUnverifiedWorkers").toString()).contains("pk1", "pk2");
    assertThat(plan.path("limitations").toString()).contains("IN_PROGRESS_FULL_DURATION");
    assertThat(plan.path("unassigned").path(0).path("taskId").asString()).isEqualTo(rx);
    assertThat(plan.path("unassigned").path(0).path("reason").asString())
        .isEqualTo("CONFIG_REQUIRED");
    Instant start = Instant.parse(plan.path("horizonStart").asString());
    for (JsonNode i : plan.path("items")) {
      if (i.path("taskId").asString().equals(k1)) {
        assertThat(i.path("workerId").asString()).isEqualTo("pk2"); // PACK 업무는 pk2 만
      }
      if (i.path("workerId").asString().equals("pk1")) {
        // pk1 은 진행 중 PICK(7분) 뒤에만
        assertThat(Instant.parse(i.path("startAt").asString()))
            .isAfterOrEqualTo(start.plusSeconds(7 * 60));
      }
    }
    // 추천만으로는 배정이 생기지 않는다
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_assignment WHERE task_id IN (?,?,?)",
                Integer.class,
                p1,
                p2,
                k1))
        .isZero();

    // 확정: 작업이 그대로(READY·같은 version) → 3건 배정 + 사건(planId)
    tasks(all.subList(0, 4), "READY");
    String planId = plan.path("planId").asString();
    // MB-1(0.18.0): 지난 추천 목록에 있다
    assertThat(
            send("GET", SUP, "/assignment-plans?mfcId=" + MFC + "&size=200", null)
                .body()
                .path("items")
                .findValuesAsString("planId"))
        .contains(planId);
    Res committed = send("POST", SUP, "/assignment-plans/" + planId + "/commits", null);
    assertThat(committed.status()).as(committed.body().toString()).isEqualTo(200);
    assertThat(committed.body().path("state").asString()).isEqualTo("COMMITTED");
    assertThat(committed.body().path("assignments")).hasSize(3);
    assertThat(committed.body().path("assignments").path(0).path("planId").asString())
        .isEqualTo(planId);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE type = 'AssignmentPublished'"
                    + " AND payload::text LIKE ?",
                Integer.class,
                "%" + planId + "%"))
        .isEqualTo(3);
    // 다시 확정 → 지금 상태
    assertThat(
            send("POST", SUP, "/assignment-plans/" + planId + "/commits", null)
                .body()
                .path("state")
                .asString())
        .isEqualTo("COMMITTED");
  }

  /** 작업자 쪽 4단계 뒤: 배정 추천에 열린 이동·보충이 들어가고, 확정하면 이동 지시도 배정된다(사건 → inbound 담당 투영). */
  @Test
  void planIncludesOpenMovesAndCommitsThem() {
    String s = UUID.randomUUID().toString().substring(0, 6);
    register("pm-" + s, List.of("MOVE", "REPLENISH"));
    tasks(List.of(), "READY", "ASSIGNED", "IN_PROGRESS");
    Instant base = Instant.now().minusSeconds(600);
    String open = "MV-PO-" + s;
    String rep = "MV-PR-" + s;
    moveOrders(
        List.of(
            move(open, "MOVE", false, 0, null, base),
            move(rep, "REPLENISH", false, 0, null, base),
            move("MV-PP-" + s, "MOVE", false, 1, null, base), // 이미 집음 — 추천 대상 아님
            move("MV-PA-" + s, "REPLENISH", false, 0, "someone", base))); // 담당 있음
    Res created =
        send("POST", SUP, "/assignment-plans", Map.of("mfcId", MFC, "horizonMinutes", 60));
    assertThat(created.status()).as(created.body().toString()).isEqualTo(201);
    JsonNode plan = created.body();
    assertThat(plan.path("items").findValuesAsString("taskId")).contains(open, rep);
    assertThat(plan.path("items").findValuesAsString("taskId"))
        .doesNotContain("MV-PP-" + s, "MV-PA-" + s);
    assertThat(plan.path("limitations").toString()).doesNotContain("MOVES_NOT_INCLUDED");

    tasks(List.of(), "READY");
    Res committed =
        send("POST", SUP, "/assignment-plans/" + plan.path("planId").asString() + "/commits", null);
    assertThat(committed.status()).as(committed.body().toString()).isEqualTo(200);
    assertThat(committed.body().path("assignments").findValuesAsString("taskId"))
        .contains(open, rep);
    assertThat(
            jdbc.queryForObject(
                "SELECT task_type FROM task_assignment WHERE task_id = ? AND state = 'ACTIVE'",
                String.class,
                rep))
        .isEqualTo("REPLENISH");
  }

  /** 이동 지시 조회가 안 되면 이동 없이 계산하고 한계로 남긴다. */
  @Test
  void planWithoutMovesWhenInboundFails() {
    ful.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/move-orders"))
            .withQueryParam("mfcId", WireMock.equalTo(MFC))
            .willReturn(aResponse().withStatus(500)));
    tasks(List.of(), "READY", "ASSIGNED", "IN_PROGRESS");
    Res created =
        send("POST", SUP, "/assignment-plans", Map.of("mfcId", MFC, "horizonMinutes", 30));
    assertThat(created.status()).as(created.body().toString()).isEqualTo(201);
    assertThat(created.body().path("limitations").toString()).contains("MOVES_NOT_INCLUDED");
    moveOrders(List.of());
  }

  @Test
  void commitIsRejectedAsStaleWhenATaskChangedAfterPlanning() {
    moveOrders(List.of());
    register("pk3", List.of("PICK"));
    String q = "T-Q1-" + UUID.randomUUID().toString().substring(0, 6);
    tasks(List.of(task(q, "PICK", "READY", 1, null, 0)), "READY", "ASSIGNED", "IN_PROGRESS");
    JsonNode plan =
        send("POST", SUP, "/assignment-plans", Map.of("mfcId", MFC, "horizonMinutes", 30)).body();
    assertThat(plan.path("state").asString()).isEqualTo("PROPOSED");
    String planId = plan.path("planId").asString();

    // 계산 뒤 작업 version 이 바뀜 → 아무것도 배정 안 하고 STALE
    tasks(List.of(task(q, "PICK", "READY", 2, null, 0)), "READY");
    Res stale = send("POST", SUP, "/assignment-plans/" + planId + "/commits", null);
    assertThat(stale.status()).isEqualTo(409);
    assertThat(stale.body().path("code").asString()).isEqualTo("PLAN_STALE");
    JsonNode after = send("GET", SUP, "/assignment-plans/" + planId, null).body();
    assertThat(after.path("state").asString()).isEqualTo("STALE");
    assertThat(after.path("staleReasons").path(0).asString()).startsWith("TASK_CHANGED:");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_assignment WHERE task_id = ?", Integer.class, q))
        .isZero();
  }

  /**
   * 계산 입력 저장(0.12.0, A4 — 원문 WK-09 재현 저장): 계획마다 엔진 입력 JSON·지문을 남기고 조회한다. 총량 상한을 넘으면 오래된 것부터(확정 안 된
   * 계획 먼저) 지우고, 계획 결과는 남는다. 지워진 입력은 404 INPUT_NOT_KEPT.
   */
  @Test
  void planInputIsKeptWithHashAndOldestIsSweptOverCap() {
    // 다른 시험이 남긴 입력(확정 계획 포함)을 비운다 — 확정 안 된 것부터 지우므로, 확정 계획이 남아 있으면 계획 B 가 먼저 지워진다(순서 의존)
    jdbc.update("DELETE FROM assignment_plan_input");
    moveOrders(List.of());
    tasks(List.of(), "READY", "ASSIGNED", "IN_PROGRESS");
    Res a = send("POST", SUP, "/assignment-plans", Map.of("mfcId", MFC, "horizonMinutes", 30));
    assertThat(a.status()).as(a.body().toString()).isEqualTo(201);
    String planA = a.body().path("planId").asString();
    String hashA = a.body().path("inputHash").asString();
    assertThat(hashA).hasSize(64);

    Res in = send("GET", SUP, "/assignment-plans/" + planA + "/input", null);
    assertThat(in.status()).as(in.body().toString()).isEqualTo(200);
    assertThat(in.body().path("inputHash").asString()).isEqualTo(hashA);
    assertThat(in.body().path("input").path("mfcId").asString()).isEqualTo(MFC);
    assertThat(in.body().path("input").path("horizonMinutes").asInt()).isEqualTo(30);
    assertThat(in.body().path("input").path("packingStations").asInt()).isEqualTo(4);
    assertThat(in.body().path("input").path("timeLimitMs").asLong()).isEqualTo(5000);
    assertThat(in.body().path("bytes").asInt()).isPositive();
    // 다른 센터 운영자는 못 본다
    String other = TestJwt.operator("sup-x", List.of("MFC_SUPERVISOR"), List.of("MFC-OTHER"));
    assertThat(send("GET", other, "/assignment-plans/" + planA + "/input", null).status())
        .isEqualTo(403);

    Res b = send("POST", SUP, "/assignment-plans", Map.of("mfcId", MFC, "horizonMinutes", 30));
    String planB = b.body().path("planId").asString();
    // 지금 총량에서 한 건만 넘치게 상한을 두면 가장 오래된 것(계획 A 포함 앞선 것들)부터 지워진다
    long total = plans.totalInputBytes();
    int bytesB =
        jdbc.queryForObject(
            "SELECT bytes FROM assignment_plan_input WHERE plan_id = ?", Integer.class, planB);
    new com.pmart.mfc.capacity.application.PlanInputSweeper(plans, props(bytesB)).sweep();
    assertThat(plans.totalInputBytes()).isLessThanOrEqualTo(bytesB).isLessThan(total);
    assertThat(plans.input(planB)).isPresent();
    Res gone = send("GET", SUP, "/assignment-plans/" + planA + "/input", null);
    assertThat(gone.status()).isEqualTo(404);
    assertThat(gone.body().path("code").asString()).isEqualTo("INPUT_NOT_KEPT");
    // 계획 결과는 그대로, 지문은 null
    Res planAfter = send("GET", SUP, "/assignment-plans/" + planA, null);
    assertThat(planAfter.status()).isEqualTo(200);
    assertThat(planAfter.body().path("inputHash").isNull()).isTrue();
    // 상한이 없으면 지우지 않는다
    new com.pmart.mfc.capacity.application.PlanInputSweeper(plans, props(null)).sweep();
    assertThat(plans.input(planB)).isPresent();
  }

  @Autowired com.pmart.mfc.capacity.infrastructure.PlanStore plans;

  static com.pmart.mfc.capacity.application.PlanProperties props(@Nullable Integer max) {
    return new com.pmart.mfc.capacity.application.PlanProperties(
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        max == null ? null : (long) max);
  }
}
