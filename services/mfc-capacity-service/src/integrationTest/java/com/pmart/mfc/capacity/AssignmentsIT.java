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
  @Autowired org.springframework.kafka.core.KafkaTemplate<String, String> kafka;

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

  void packReleased(String pack, String picker) {
    kafka
        .send(
            "mfc.fulfillment.v1",
            pack,
            com.pmart.common.test.EventEnvelopeFixtures.envelope("TaskProgressed")
                .from("mfc-fulfillment-service")
                .aggregate("Task", pack, 2)
                .payload(
                    Map.of(
                        "taskId",
                        pack,
                        "mfcId",
                        MFC,
                        "type",
                        "PACK",
                        "state",
                        "READY",
                        "previousState",
                        "WAITING",
                        "pickedBy",
                        picker))
                .buildJson())
        .join();
  }

  int activeCount(String task, String worker) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM task_assignment WHERE task_id = ? AND worker_id = ? AND state = 'ACTIVE'",
        Integer.class,
        task,
        worker);
  }

  void awaitActive(String task, String worker) {
    long until = System.currentTimeMillis() + 20_000;
    while (System.currentTimeMillis() < until && activeCount(task, worker) == 0) {
      try {
        Thread.sleep(200);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  /** 피킹 끝 PACK → 피커에게 바로 SYSTEM 배정(0.32.0, ADR-0080). 맡을 수 없음·이미 배정이면 그대로. */
  @Test
  void packIsAssignedToPickerWhenPickingEnds() throws Exception {
    String picker = "pk-" + UUID.randomUUID().toString().substring(0, 6);
    register(picker, List.of("PICK", "PACK"), List.of("AMBIENT"));
    // 지금 근무 중이어야 바로 배정한다 — 근무를 등록해 둔다(미등록이면 center-master 직원 시간표를 묻는데 이 시험엔 없다)
    java.time.Instant now = java.time.Instant.now();
    send(
        "POST",
        SUPERVISOR,
        "/mfcs/" + MFC + "/workers/" + picker + "/shifts",
        Map.of(
            "startAt",
            now.minusSeconds(3600).toString(),
            "endAt",
            now.plusSeconds(4 * 3600).toString()),
        null);
    String pack = "S-" + UUID.randomUUID() + "-PACK";
    packReleased(pack, picker);
    awaitActive(pack, picker);
    assertThat(activeCount(pack, picker)).isEqualTo(1);
    var row =
        jdbc.queryForMap(
            "SELECT assigned_by, reason, assignment_version FROM task_assignment WHERE task_id = ?",
            pack);
    assertThat(row.get("assigned_by")).isEqualTo("SYSTEM");
    assertThat(row.get("reason")).isEqualTo("PICKER_PACK");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE type = 'AssignmentPublished' AND aggregateid = ?",
                Integer.class,
                "MANUAL:" + pack))
        .isEqualTo(1);
    // 같은 내용 다시(다른 사건) → 이미 활성 배정 — 늘지 않음
    packReleased(pack, picker);
    // PACK 업무가 없는 피커 → 배정 안 함
    String pickOnly = "po-" + UUID.randomUUID().toString().substring(0, 6);
    register(pickOnly, List.of("PICK"), List.of("AMBIENT"));
    String pack2 = "S-" + UUID.randomUUID() + "-PACK";
    packReleased(pack2, pickOnly);
    // 센터 관리자가 먼저 다른 사람에게 배정 → 덮지 않음
    String pack3 = "S-" + UUID.randomUUID() + "-PACK";
    register("wrk-1", List.of("PICK", "PACK"), List.of("AMBIENT"));
    assertThat(
            assign(
                    SUPERVISOR,
                    Map.of("mfcId", MFC, "taskId", pack3, "taskType", "PACK", "workerId", "wrk-1"),
                    0)
                .status())
        .isEqualTo(201);
    packReleased(pack3, picker);
    // 마지막 사건까지 처리됐는지는 앞 사건과 같은 파티션 순서로 본다 — 표지 사건 하나 더
    String marker = "S-" + UUID.randomUUID() + "-PACK";
    packReleased(marker, picker);
    awaitActive(marker, picker);
    Thread.sleep(500);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_assignment WHERE task_id = ?", Integer.class, pack))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_assignment WHERE task_id = ?", Integer.class, pack2))
        .isZero();
    assertThat(activeCount(pack3, "wrk-1")).isEqualTo(1);
    assertThat(activeCount(pack3, picker)).isZero();
  }

  /** 배정 이력(0.31.0, R-29 ADR-0079): 배정·재배정·해제를 버전 순으로 — 누가·언제·왜. 센터 관리자·본사 관리자만. */
  @Test
  void assignmentHistoryShowsWhoAssignedReassignedAndReleased() {
    register("wrk-1", List.of("PICK", "PACK"), List.of("AMBIENT"));
    register("wrk-2", List.of("PICK"), List.of("AMBIENT"));
    String task = "TSK-H-" + UUID.randomUUID();
    assertThat(assign(SUPERVISOR, with(body(task, "wrk-1"), "reason", "첫 배정"), 0).status())
        .isEqualTo(201);
    assertThat(assign(SUPERVISOR, body(task, "wrk-2"), 1).status()).isEqualTo(201);
    Res released =
        send(
            "POST",
            TestJwt.service("mfc-fulfillment-service"),
            "/internal/assignments/" + task + "/releases",
            Map.of("workerId", "wrk-2", "releasedBy", "sup-1", "reason", "막힘 해제: 퇴근"),
            null);
    assertThat(released.status()).as(released.body().toString()).isEqualTo(200);

    String path = "/assignments/history?mfcId=" + MFC + "&taskId=" + task;
    assertThat(get(WORKER, path).status()).isEqualTo(403);
    assertThat(get(OTHER_SUP, path).status()).isEqualTo(403);
    Res h = get(SUPERVISOR, path);
    assertThat(h.status()).as(h.body().toString()).isEqualTo(200);
    JsonNode items = h.body().path("items");
    assertThat(items).hasSize(2);
    JsonNode v1 = items.get(0);
    assertThat(v1.path("assignmentVersion").asInt()).isEqualTo(1);
    assertThat(v1.path("workerId").asString()).isEqualTo("wrk-1");
    assertThat(v1.path("state").asString()).isEqualTo("SUPERSEDED");
    assertThat(v1.path("assignedBy").asString()).isEqualTo("sup-1");
    assertThat(v1.path("reason").asString()).isEqualTo("첫 배정");
    assertThat(v1.path("source").asString()).isEqualTo("MANUAL");
    assertThat(v1.path("endedBy").asString()).isEqualTo("sup-1");
    assertThat(v1.path("endReason").asString()).isEqualTo("SUPERSEDED_BY_V2");
    assertThat(v1.path("endedAt").isNull()).isFalse();
    JsonNode v2 = items.get(1);
    assertThat(v2.path("state").asString()).isEqualTo("RELEASED");
    assertThat(v2.path("endedBy").asString()).isEqualTo("sup-1");
    assertThat(v2.path("endReason").asString()).isEqualTo("막힘 해제: 퇴근");
    assertThat(v2.path("endedAt").isNull()).isFalse();
    assertThat(
            get(TestJwt.operator("OP-admin", List.of("OPERATOR_ADMIN"), List.of()), path)
                .body()
                .path("items"))
        .hasSize(2);
  }

  /**
   * 0.36.0(2026-11-27 MFC 프론트 요청): 센터 관리자 배정 풀기 — 해제 사건 버전, 다음 배정은 그보다 큰 버전, PICK 은 fulfillment 로.
   */
  @Test
  void supervisorReleasesInboundTaskAssignmentAndNextVersionIsHigher() {
    register("wrk-rel", List.of("PUTAWAY", "PICK"), List.of("AMBIENT"));
    String task = "TSK-PUT-R-" + UUID.randomUUID();
    Map<String, Object> put =
        Map.of("mfcId", MFC, "taskId", task, "taskType", "PUTAWAY", "workerId", "wrk-rel");
    assertThat(assign(SUPERVISOR, put, 0).status()).isEqualTo(201);
    String path = "/assignments/" + task + "/releases";
    Map<String, Object> body = Map.of("mfcId", MFC, "workerId", "wrk-rel", "reason", "PDA 에 남은 배정");
    // 작업자·다른 센터 관리자 403, 사유 없음 400, 담당이 아니면 409
    assertThat(send("POST", WORKER, path, body, null).status()).isEqualTo(403);
    assertThat(
            send(
                    "POST",
                    OTHER_SUP,
                    path,
                    Map.of("mfcId", "MFC-OTHER", "workerId", "wrk-rel", "reason", "x"),
                    null)
                .status())
        .isEqualTo(403); // 자기 센터로 적어도 그 작업은 다른 센터 것
    assertThat(
            send(
                    "POST",
                    SUPERVISOR,
                    path,
                    Map.of("mfcId", MFC, "workerId", "wrk-rel", "reason", " "),
                    null)
                .status())
        .isEqualTo(400);
    Res wrong =
        send(
            "POST",
            SUPERVISOR,
            path,
            Map.of("mfcId", MFC, "workerId", "wrk-9", "reason", "x"),
            null);
    assertThat(wrong.status()).isEqualTo(409);
    assertThat(wrong.body().path("code").asString()).isEqualTo("NOT_ASSIGNEE");

    Res ok = send("POST", SUPERVISOR, path, body, null);
    assertThat(ok.status()).as(ok.body().toString()).isEqualTo(200);
    assertThat(ok.body().path("released").asBoolean()).isTrue();
    assertThat(ok.body().path("assignmentVersion").asInt()).isEqualTo(2);
    JsonNode ev =
        mapper.readTree(
            jdbc.queryForObject(
                "SELECT payload::text FROM outbox_event WHERE type = 'AssignmentReleased'"
                    + " AND aggregateid = ?",
                String.class,
                "MANUAL:" + task));
    JsonNode p = ev.has("payload") ? ev.path("payload") : ev;
    assertThat(p.path("taskId").asString()).isEqualTo(task);
    assertThat(p.path("workerId").asString()).isEqualTo("wrk-rel");
    assertThat(p.path("assignmentVersion").asInt()).isEqualTo(2);
    assertThat(p.path("releasedBy").asString()).isEqualTo("sup-1");
    // 활성 배정 없음 → 변화 없는 200
    Res again = send("POST", SUPERVISOR, path, body, null);
    assertThat(again.body().path("released").asBoolean()).isFalse();
    // 다음 배정은 해제 버전보다 큰 v3(투영이 더 새 버전만 받으므로)
    Res next = assign(SUPERVISOR, put, 0);
    assertThat(next.status()).as(next.body().toString()).isEqualTo(201);
    assertThat(next.body().path("assignmentVersion").asInt()).isEqualTo(3);
    JsonNode h =
        get(SUPERVISOR, "/assignments/history?mfcId=" + MFC + "&taskId=" + task)
            .body()
            .path("items");
    assertThat(h.get(0).path("state").asString()).isEqualTo("RELEASED");
    assertThat(h.get(0).path("endedBy").asString()).isEqualTo("sup-1");
    assertThat(h.get(0).path("endReason").asString()).isEqualTo("PDA 에 남은 배정");

    // 피킹은 fulfillment 로(409 USE_FULFILLMENT_RELEASE), 본사 관리자는 다른 센터도 된다
    String pick = "TSK-PICK-R-" + UUID.randomUUID();
    assertThat(assign(SUPERVISOR, body(pick, "wrk-rel"), 0).status()).isEqualTo(201);
    Res viaFul =
        send(
            "POST",
            TestJwt.operator("OP-admin", List.of("OPERATOR_ADMIN"), List.of()),
            "/assignments/" + pick + "/releases",
            body,
            null);
    assertThat(viaFul.status()).isEqualTo(409);
    assertThat(viaFul.body().path("code").asString()).isEqualTo("USE_FULFILLMENT_RELEASE");
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
    // 본사 운영 관리자는 모든 센터 배정을 읽는다(0.30.0 — fulfillment 관리자 막힘 해제, R-29)
    JsonNode byAdmin =
        get(
                TestJwt.operator("OP-admin", List.of("OPERATOR_ADMIN"), List.of()),
                "/assignments?mfcId=" + MFC + "&taskId=" + task)
            .body();
    assertThat(byAdmin.path("items").path(0).path("workerId").asString()).isEqualTo("wrk-2");

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
    // (2026-11-20) 작업자는 남의 상세·근무 일정을 못 본다 — 본인 것은 본다(PDA)
    assertThat(get(WORKER, path).status()).isEqualTo(403);
    assertThat(get(WORKER, path + "/shifts").status()).isEqualTo(403);
    String self = TestJwt.operator(w, List.of("MFC_WORKER"), List.of(MFC));
    assertThat(get(self, path).status()).isEqualTo(200);
    assertThat(get(self, path + "/shifts").status()).isEqualTo(200);
    JsonNode detail = get(SUPERVISOR, path).body();
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
    assertThat(get(SUPERVISOR, path).body().path("stateHistory").path(0).path("toState").asString())
        .isEqualTo("SUSPENDED");
    assertThat(
            get(SUPERVISOR, "/mfcs/" + MFC + "/workers?state=SUSPENDED")
                .body()
                .path("items")
                .findValuesAsString("workerId"))
        .contains(w);
    // (2026-11-20) 센터 작업자 목록은 작업자 토큰으로 못 본다 — 다른 센터 관리자도 못 본다
    assertThat(get(WORKER, "/mfcs/" + MFC + "/workers").status()).isEqualTo(403);
    assertThat(get(OTHER_SUP, "/mfcs/" + MFC + "/workers").status()).isEqualTo(403);
  }
}
