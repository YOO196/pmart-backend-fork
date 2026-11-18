package com.pmart.mfc.capacity;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
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
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 수동 배정(WK-02·WK-05, 슬라이스 6b): 센터 관리자만, 활성 1명·버전 증가, 재지정 SUPERSEDED, 낡은 버전 409, 사건 발행, 조회. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.capacity.units-per-hour=3",
      "pmart.messaging.outbox.delete-after-insert=false"
    })
@Import(TestJwtConfiguration.class)
class AssignmentsIT extends PostgresKafkaContainerSupport {

  static final String MFC = "MFC-ASG-1";
  static final String SUPERVISOR =
      TestJwt.operator("sup-1", List.of("MFC_SUPERVISOR"), List.of(MFC));
  static final String WORKER = TestJwt.operator("wrk-1", List.of("MFC_WORKER"), List.of(MFC));
  static final String OTHER_SUP =
      TestJwt.operator("sup-9", List.of("MFC_SUPERVISOR"), List.of("MFC-OTHER"));

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;

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

  Res assign(String token, Map<String, Object> body, @Nullable Integer expected) {
    var e =
        http.post()
            .uri("/assignments")
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", "backoffice-web");
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

  Res send(String method, String token, String path, Object body, @Nullable Integer expected) {
    var e =
        http.method(org.springframework.http.HttpMethod.valueOf(method))
            .uri(path)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", "backoffice-web");
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

  /** 센터 관리자가 작업자 등록(이미 있으면 409 — 무시해도 된다). */
  Res register(String worker, List<String> types, List<String> zones) {
    return send(
        "POST",
        SUPERVISOR,
        "/mfcs/" + MFC + "/workers",
        Map.of(
            "workerId",
            worker,
            "displayName",
            "작업자 " + worker,
            "duty",
            Map.of("taskTypes", types, "temperatureZones", zones)),
        null);
  }

  static Map<String, Object> with(Map<String, Object> base, String k, String v) {
    Map<String, Object> m = new java.util.HashMap<>(base);
    m.put(k, v);
    return m;
  }

  Res get(String token, String path) {
    var e =
        http.get().uri(path).headers(h -> h.setBearerAuth(token)).retrieve().toEntity(String.class);
    return res(e);
  }

  Res res(ResponseEntity<String> e) {
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  static Map<String, Object> body(String taskId, String worker) {
    return Map.of("mfcId", MFC, "taskId", taskId, "taskType", "PICK", "workerId", worker);
  }

  /** 0.11.0 작업자 속도 배율: 센터 관리자만, 0.5~2.0, 작업자 조회에 보인다(기본 1.0). */
  /** MB-1(0.18.0): 지난 추천 목록 — 최근 순·상태 거르기·쪽·다른 센터 403(추천 계산은 OR-Tools 라 행을 직접 넣는다). */
  @Test
  void planListIsNewestFirstAndFilters() {
    String a = "PLN-LIST-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    String b = "PLN-LIST-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    for (Object[] r : new Object[][] {{a, "COMMITTED", "2 hours"}, {b, "PROPOSED", "1 hour"}}) {
      jdbc.update(
          "INSERT INTO assignment_plan (plan_id, mfc_id, state, solver_status, horizon_start,"
              + " horizon_minutes, unassigned_count, late_minutes, max_load_minutes, objective_value,"
              + " wall_time_ms, created_by, created_at) VALUES (?,?,?,'OPTIMAL',now(),60,0,0,0,0,1,"
              + "'sup-1', now() - (?)::interval)",
          r[0],
          MFC,
          r[1],
          r[2]);
    }
    JsonNode all = get(SUPERVISOR, "/assignment-plans?mfcId=" + MFC + "&size=200").body();
    List<String> ids = all.path("items").findValuesAsString("planId");
    assertThat(ids).contains(a, b);
    assertThat(ids.indexOf(b)).as("최근 것이 먼저").isLessThan(ids.indexOf(a));
    assertThat(
            get(SUPERVISOR, "/assignment-plans?mfcId=" + MFC + "&state=COMMITTED&size=200")
                .body()
                .path("items")
                .findValuesAsString("planId"))
        .contains(a)
        .doesNotContain(b);
    JsonNode first = get(SUPERVISOR, "/assignment-plans?mfcId=" + MFC + "&size=1").body();
    assertThat(first.path("items")).hasSize(1);
    assertThat(first.path("nextCursor").isNull()).isFalse();
    assertThat(get(OTHER_SUP, "/assignment-plans?mfcId=" + MFC).status()).isEqualTo(403);
    assertThat(get(SUPERVISOR, "/assignment-plans?mfcId=" + MFC + "&state=NOPE").status())
        .isEqualTo(400);
  }

  @Test
  void supervisorSetsWorkerSpeedFactor() {
    String w = "spd-" + UUID.randomUUID().toString().substring(0, 6);
    assertThat(register(w, List.of("PICK"), List.of("AMBIENT")).status()).isEqualTo(201);
    String path = "/mfcs/" + MFC + "/workers/" + w;
    assertThat(get(SUPERVISOR, path).body().path("speedFactor").asDouble()).isEqualTo(1.0);
    Res ok =
        send(
            "POST",
            SUPERVISOR,
            path + "/speed-changes",
            Map.of("speedFactor", 1.2, "reason", "숙련"),
            null);
    assertThat(ok.status()).as(ok.body().toString()).isEqualTo(200);
    assertThat(ok.body().path("speedFactor").asDouble()).isEqualTo(1.2);
    assertThat(get(SUPERVISOR, path).body().path("speedFactor").asDouble()).isEqualTo(1.2);
    assertThat(
            send(
                    "POST",
                    SUPERVISOR,
                    path + "/speed-changes",
                    Map.of("speedFactor", 2.5, "reason", "x"),
                    null)
                .status())
        .isEqualTo(400);
    String worker = TestJwt.operator(w, List.of("MFC_WORKER"), List.of(MFC));
    assertThat(
            send(
                    "POST",
                    worker,
                    path + "/speed-changes",
                    Map.of("speedFactor", 1.5, "reason", "x"),
                    null)
                .status())
        .isEqualTo(403);
  }

  @Test
  void supervisorAssignsAndReassignsWithVersions() {
    register("wrk-1", List.of("PICK", "PACK"), List.of("AMBIENT"));
    register("wrk-2", List.of("PICK"), List.of("AMBIENT"));
    String task = "TSK-" + UUID.randomUUID();
    // 작업자·다른 센터의 센터 관리자는 배정 못 한다
    assertThat(assign(WORKER, body(task, "wrk-1"), null).status()).isEqualTo(403);
    assertThat(assign(OTHER_SUP, body(task, "wrk-1"), null).status()).isEqualTo(403);

    Res first = assign(SUPERVISOR, body(task, "wrk-1"), 0);
    assertThat(first.status()).isEqualTo(201);
    assertThat(first.body().path("assignmentVersion").asInt()).isEqualTo(1);
    assertThat(first.body().path("state").asString()).isEqualTo("ACTIVE");
    assertThat(first.body().path("assignedBy").asString()).isEqualTo("sup-1");

    // 같은 작업자 다시 → 변화 없음 200, 낡은 버전 → 409
    Res same = assign(SUPERVISOR, body(task, "wrk-1"), null);
    assertThat(same.status()).isEqualTo(200);
    assertThat(same.body().path("assignmentVersion").asInt()).isEqualTo(1);
    assertThat(assign(SUPERVISOR, body(task, "wrk-2"), 0).status()).isEqualTo(409);

    // 재배정(WK-05) → v2, 앞 배정 SUPERSEDED, 활성 1건
    Res second = assign(SUPERVISOR, body(task, "wrk-2"), 1);
    assertThat(second.status()).isEqualTo(201);
    assertThat(second.body().path("assignmentVersion").asInt()).isEqualTo(2);
    JsonNode active = get(WORKER, "/assignments?mfcId=" + MFC + "&taskId=" + task).body();
    assertThat(active.path("items")).hasSize(1);
    assertThat(active.path("items").path(0).path("workerId").asString()).isEqualTo("wrk-2");
    JsonNode old =
        get(
                TestJwt.service("mfc-fulfillment-service"),
                "/assignments?mfcId=" + MFC + "&taskId=" + task + "&state=SUPERSEDED")
            .body();
    assertThat(old.path("items").path(0).path("workerId").asString()).isEqualTo("wrk-1");
    assertThat(get(OTHER_SUP, "/assignments?mfcId=" + MFC).status()).isEqualTo(403);

    // 사건: 바뀐 두 번만(같은 작업자 재지정은 없음)
    List<String> payloads =
        jdbc.queryForList(
            "SELECT payload::text FROM outbox_event WHERE type = 'AssignmentPublished'"
                + " AND aggregateid = ? ORDER BY created_at",
            String.class,
            "MANUAL:" + task);
    assertThat(payloads).hasSize(2);
    assertThat(payloads.get(1)).contains("\"wrk-2\"").doesNotContain("wrk-1");
  }

  @Test
  void workersAreRegisteredByASupervisorAndDutiesChangeWithoutTouchingTheAccount() {
    String w = "wrk-" + UUID.randomUUID().toString().substring(0, 8);
    Map<String, Object> duty =
        Map.of("taskTypes", List.of("PICK"), "temperatureZones", List.of("AMBIENT"));
    // 작업자 본인은 등록 못 한다
    assertThat(
            send(
                    "POST",
                    WORKER,
                    "/mfcs/" + MFC + "/workers",
                    Map.of("workerId", w, "displayName", "x", "duty", duty),
                    null)
                .status())
        .isEqualTo(403);

    // 등록 전 배정 → 422 WORKER_NOT_REGISTERED
    String task = "TSK-" + UUID.randomUUID();
    Res unknown = assign(SUPERVISOR, body(task, w), null);
    assertThat(unknown.status()).isEqualTo(422);
    assertThat(unknown.body().path("code").asString()).isEqualTo("WORKER_NOT_REGISTERED");

    Res reg = register(w, List.of("PICK"), List.of("AMBIENT"));
    assertThat(reg.status()).as(reg.body().toString()).isEqualTo(201);
    assertThat(reg.body().path("duty").path("dutyVersion").asInt()).isEqualTo(1);
    assertThat(register(w, List.of("PICK"), List.of("AMBIENT")).body().path("code").asString())
        .isEqualTo("WORKER_ALREADY_REGISTERED");

    // 업무에 없는 작업 유형 → 거절. PICK 은 배정
    assertThat(
            assign(SUPERVISOR, with(body(task, w), "taskType", "PACK"), null)
                .body()
                .path("code")
                .asString())
        .isEqualTo("DUTY_NOT_ALLOWED");
    String pickTask = "TSK-" + UUID.randomUUID();
    // MFC 는 온도대 출입 자격이 없다(사용자 2026-11-08) — 업무에 상온만 있어도 냉동 작업 배정
    Res ok = assign(SUPERVISOR, with(body(pickTask, w), "temperatureZone", "FROZEN"), 0);
    assertThat(ok.status()).as(ok.body().toString()).isEqualTo(201);
    assertThat(ok.body().path("warnings")).isEmpty();

    // 업무 변경 → 새 버전, 같은 workerId·등록 정보 그대로. PICK 을 빼면 기존 배정은 지우지 않고 알린다
    String path = "/mfcs/" + MFC + "/workers/" + w;
    Map<String, Object> toPack =
        Map.of(
            "taskTypes",
            List.of("PACK"),
            "temperatureZones",
            List.of("AMBIENT", "FROZEN"),
            "reason",
            "포장 담당으로");
    // 업무는 자격 안이어야 — 자격이 PICK 뿐이면 PACK 업무는 422, 자격을 넓힌 뒤 변경(작업자 쪽 2단계)
    assertThat(
            send("POST", SUPERVISOR, path + "/duty-changes", toPack, 1)
                .body()
                .path("code")
                .asString())
        .isEqualTo("DUTY_OUTSIDE_QUALIFICATION");
    assertThat(
            send(
                    "POST",
                    SUPERVISOR,
                    path + "/qualification-changes",
                    Map.of("taskTypes", List.of("PICK", "PACK"), "reason", "포장 교육"),
                    1)
                .status())
        .isEqualTo(200);
    assertThat(send("POST", SUPERVISOR, path + "/duty-changes", toPack, 9).status()).isEqualTo(409);
    Res changed = send("POST", SUPERVISOR, path + "/duty-changes", toPack, 1);
    assertThat(changed.status()).as(changed.body().toString()).isEqualTo(200);
    assertThat(changed.body().path("changed").asBoolean()).isTrue();
    assertThat(changed.body().path("worker").path("workerId").asString()).isEqualTo(w);
    assertThat(changed.body().path("worker").path("duty").path("dutyVersion").asInt()).isEqualTo(2);
    assertThat(changed.body().path("affectedAssignments")).hasSize(1);
    assertThat(changed.body().path("affectedAssignments").path(0).path("taskId").asString())
        .isEqualTo(pickTask);
    JsonNode detail = get(WORKER, path).body();
    assertThat(detail.path("registeredBy").asString()).isEqualTo("sup-1");
    assertThat(detail.path("dutyHistory")).hasSize(2);
    assertThat(detail.path("dutyHistory").path(1).path("taskTypes").path(0).asString())
        .isEqualTo("PICK");
    // 이제 냉동 PACK 가능
    Map<String, Object> frozenPack =
        with(
            with(body("TSK-" + UUID.randomUUID(), w), "taskType", "PACK"),
            "temperatureZone",
            "FROZEN");
    assertThat(assign(SUPERVISOR, frozenPack, 0).status()).isEqualTo(201);
    // RETURN(집품 뒤 취소 복귀) 작업도 배정 요청 유형으로 받는다 — 업무에 없으면 400 이 아니라 422 DUTY_NOT_ALLOWED(0.5.2)
    Res ret =
        assign(SUPERVISOR, with(body("TSK-" + UUID.randomUUID(), w), "taskType", "RETURN"), 0);
    assertThat(ret.status()).as(ret.body().toString()).isEqualTo(422);
    assertThat(ret.body().path("code").asString()).isEqualTo("DUTY_NOT_ALLOWED");

    // 근무: 겹치면 409, 등록 근무 밖 배정은 막지 않고 OFF_SHIFT 경고
    java.time.Instant later = java.time.Instant.now().plusSeconds(3 * 3600);
    Map<String, Object> shift =
        Map.of("startAt", later.toString(), "endAt", later.plusSeconds(8 * 3600).toString());
    assertThat(send("POST", SUPERVISOR, path + "/shifts", shift, null).status()).isEqualTo(201);
    Res overlap = send("POST", SUPERVISOR, path + "/shifts", shift, null);
    assertThat(overlap.status()).isEqualTo(409);
    assertThat(overlap.body().path("code").asString()).isEqualTo("SHIFT_OVERLAP");
    Res warned =
        assign(SUPERVISOR, with(body("TSK-" + UUID.randomUUID(), w), "taskType", "PACK"), 0);
    assertThat(warned.status()).isEqualTo(201);
    assertThat(warned.body().path("warnings").path(0).asString()).isEqualTo("OFF_SHIFT");

    // 정지 → 새 배정 거절(로그인·계정은 그대로), 상태 이력
    Res suspended =
        send(
            "POST",
            SUPERVISOR,
            path + "/state-changes",
            Map.of("state", "SUSPENDED", "reason", "휴직"),
            null);
    assertThat(suspended.body().path("worker").path("state").asString()).isEqualTo("SUSPENDED");
    assertThat(suspended.body().path("affectedAssignments")).hasSize(3);
    assertThat(
            assign(SUPERVISOR, with(body("TSK-" + UUID.randomUUID(), w), "taskType", "PACK"), 0)
                .body()
                .path("code")
                .asString())
        .isEqualTo("WORKER_NOT_ACTIVE");
    // 기존 배정은 남지만 조회에 "지금은 못 맡음" 이 붙는다 — fulfillment 가 시작을 거절한다
    JsonNode mine =
        get(WORKER, "/assignments?mfcId=" + MFC + "&workerId=" + w).body().path("items");
    assertThat(mine).isNotEmpty();
    assertThat(mine.path(0).path("workerEligibility").asString()).isEqualTo("WORKER_NOT_ACTIVE");
    assertThat(get(WORKER, path).body().path("stateHistory").path(0).path("toState").asString())
        .isEqualTo("SUSPENDED");
    assertThat(
            get(WORKER, "/mfcs/" + MFC + "/workers?state=SUSPENDED")
                .body()
                .path("items")
                .findValuesAsString("workerId"))
        .contains(w);
  }
}
