package com.pmart.mfc.capacity;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import com.pmart.mfc.capacity.application.WorkerService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
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

/**
 * 휴게 번호·자동 휴게(0.20.0, 사용자 결정 2026-11-18 — mfc-app B1~B3): 작업자 본인은 센터 휴게 번호로만 켬(미설정·틀림·5번 → 15분 잠금,
 * 번호 바꾸면 풀림, 거절도 같은 키면 같은 판정), 관리자는 번호 없이, 근무 휴게 구간에 자동 휴게(일찍 끝내기는 관리자만).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.capacity.units-per-hour=3",
      "pmart.capacity.auto-break.sweep-interval=PT1H"
    })
@Import(TestJwtConfiguration.class)
class BreakPinIT extends PostgresKafkaContainerSupport {

  static final String MFC = "MFC-BRK-1";
  static final String SUPERVISOR =
      TestJwt.operator("brk-sup", List.of("MFC_SUPERVISOR"), List.of(MFC));

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired WorkerService workers;

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

  Res send(String method, String token, String path, @Nullable Object body, String key) {
    var spec =
        http.method(org.springframework.http.HttpMethod.valueOf(method))
            .uri(path)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", key);
                  h.set("X-Source-System", "mfc-app");
                })
            .contentType(MediaType.APPLICATION_JSON);
    if (body != null) {
      spec = spec.body(mapper.writeValueAsString(body));
    }
    return res(spec.retrieve().toEntity(String.class));
  }

  Res send(String method, String token, String path, @Nullable Object body) {
    return send(method, token, path, body, UUID.randomUUID().toString());
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

  String newWorker() {
    String w = "brk-" + UUID.randomUUID().toString().substring(0, 8);
    Res r =
        send(
            "POST",
            SUPERVISOR,
            "/mfcs/" + MFC + "/workers",
            Map.of(
                "workerId",
                w,
                "displayName",
                "작업자 " + w,
                "duty",
                Map.of("taskTypes", List.of("PICK", "PACK"), "temperatureZones", List.of())));
    assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
    return w;
  }

  static String token(String worker) {
    return TestJwt.operator(worker, List.of("MFC_WORKER"), List.of(MFC));
  }

  Res startBreak(String token, String worker, @Nullable String pin, String key) {
    Map<String, Object> body = new HashMap<>();
    if (pin != null) {
      body.put("supervisorPin", pin);
    }
    return send("POST", token, "/mfcs/" + MFC + "/workers/" + worker + "/breaks", body, key);
  }

  Res startBreak(String token, String worker, @Nullable String pin) {
    return startBreak(token, worker, pin, UUID.randomUUID().toString());
  }

  Res setPin(String pin) {
    return send("PUT", SUPERVISOR, "/mfcs/" + MFC + "/break-pin", Map.of("pin", pin));
  }

  @Test
  void workerNeedsCenterPinAndIsLockedAfterFiveMisses() {
    String w = newWorker();
    String wt = token(w);
    // 번호 없이 → 400(세지 않음)
    assertThat(startBreak(wt, w, null).status()).isEqualTo(400);
    // 센터가 번호를 안 정했으면 422 NOT_SET (이 센터는 다른 시험이 정했을 수 있으니 지운다)
    jdbc.update("DELETE FROM mfc_break_pin WHERE mfc_id = ?", MFC);
    Res notSet = startBreak(wt, w, "1234");
    assertThat(notSet.status()).isEqualTo(422);
    assertThat(notSet.body().path("code").asString()).isEqualTo("BREAK_PIN_NOT_SET");

    // 작업자는 번호를 정할 수 없다
    assertThat(send("PUT", wt, "/mfcs/" + MFC + "/break-pin", Map.of("pin", "1111")).status())
        .isEqualTo(403);
    Res set = setPin("2468");
    assertThat(set.status()).as(set.body().toString()).isEqualTo(200);
    assertThat(set.body().path("set").asBoolean()).isTrue();
    assertThat(set.body().has("pin")).isFalse();
    Res status = get(wt, "/mfcs/" + MFC + "/break-pin");
    assertThat(status.body().path("set").asBoolean()).isTrue();
    assertThat(status.body().path("updatedBy").asString()).isEqualTo("brk-sup");
    assertThat(status.body().toString()).doesNotContain("2468");
    assertThat(
            jdbc.queryForObject(
                "SELECT pin_hash FROM mfc_break_pin WHERE mfc_id = ?", String.class, MFC))
        .startsWith("pbkdf2$")
        .doesNotContain("2468");

    // 틀림 4번: 남은 횟수 4 → 1, 거절도 같은 키면 같은 판정(횟수 안 늘어남)
    String key = UUID.randomUUID().toString();
    Res first = startBreak(wt, w, "0000", key);
    assertThat(first.status()).isEqualTo(422);
    assertThat(first.body().path("code").asString()).isEqualTo("BREAK_PIN_INVALID");
    assertThat(first.body().toString()).contains("remainingAttempts").contains("\"4\"");
    Res replay = startBreak(wt, w, "0000", key);
    assertThat(replay.body().path("code").asString()).isEqualTo("BREAK_PIN_INVALID");
    assertThat(failed(w)).isEqualTo(1);
    for (int i = 0; i < 3; i++) {
      assertThat(startBreak(wt, w, "0000").body().path("code").asString())
          .isEqualTo("BREAK_PIN_INVALID");
    }
    // 5번째 → 잠금, 잠금 중에는 맞는 번호도 막힘
    Res locked = startBreak(wt, w, "0000");
    assertThat(locked.body().path("code").asString()).isEqualTo("BREAK_PIN_LOCKED");
    assertThat(locked.body().toString()).contains("lockedUntil");
    assertThat(startBreak(wt, w, "2468").body().path("code").asString())
        .isEqualTo("BREAK_PIN_LOCKED");
    assertThat(onBreak(w)).isFalse();

    // 관리자가 번호를 새로 정하면 풀린다 → 맞는 번호로 휴게
    setPin("1357");
    Res ok = startBreak(wt, w, "1357");
    assertThat(ok.status()).as(ok.body().toString()).isEqualTo(200);
    assertThat(ok.body().path("source").asString()).isEqualTo("MANUAL");
    assertThat(onBreak(w)).isTrue();
    assertThat(failed(w)).isZero();
    // 직접 켠 휴게는 본인이 번호 없이 끝낸다
    assertThat(
            send("POST", wt, "/mfcs/" + MFC + "/workers/" + w + "/breaks/current/end", null)
                .status())
        .isEqualTo(200);
    assertThat(onBreak(w)).isFalse();
  }

  @Test
  void supervisorStartsBreakWithoutPin() {
    String w = newWorker();
    jdbc.update("DELETE FROM mfc_break_pin WHERE mfc_id = ?", MFC);
    Res r = startBreak(SUPERVISOR, w, null);
    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    assertThat(r.body().path("startedBy").asString()).isEqualTo("brk-sup");
    // 다른 작업자는 남의 휴게를 못 켠다
    assertThat(startBreak(token("someone-else"), w, "1234").status()).isEqualTo(403);
  }

  @Test
  void shiftBreakWindowStartsAndEndsAutoBreakAndOnlySupervisorEndsItEarly() {
    String w = newWorker();
    String wt = token(w);
    Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    // 휴게 구간이 근무 밖이면 400
    Res bad =
        send(
            "POST",
            SUPERVISOR,
            "/mfcs/" + MFC + "/workers/" + w + "/shifts",
            Map.of(
                "startAt", now.minusSeconds(3600).toString(),
                "endAt", now.plusSeconds(3600).toString(),
                "breakStartAt", now.plusSeconds(3000).toString(),
                "breakEndAt", now.plusSeconds(4000).toString()));
    assertThat(bad.status()).isEqualTo(400);
    Res shift =
        send(
            "POST",
            SUPERVISOR,
            "/mfcs/" + MFC + "/workers/" + w + "/shifts",
            Map.of(
                "startAt", now.minusSeconds(3600).toString(),
                "endAt", now.plusSeconds(3600 * 6).toString(),
                "breakStartAt", now.minusSeconds(60).toString(),
                "breakEndAt", now.plusSeconds(1800).toString()));
    assertThat(shift.status()).as(shift.body().toString()).isEqualTo(201);
    assertThat(shift.body().path("breakStartAt").isNull()).isFalse();
    String shiftId = shift.body().path("shiftId").asString();

    workers.sweepAutoBreaks();
    assertThat(onBreak(w)).isTrue();
    Map<String, Object> b =
        jdbc.queryForMap(
            "SELECT source, shift_id, started_by FROM mfc_worker_break WHERE worker_id = ? AND ended_at IS NULL",
            w);
    assertThat(b.get("source")).isEqualTo("AUTO");
    assertThat(b.get("shift_id")).isEqualTo(shiftId);
    assertThat(b.get("started_by")).isEqualTo("SYSTEM");
    // 다음 작업 받기는 휴게 중이라 막힘(들고 있는 작업이 없으므로) — 지금 규칙 그대로
    Res pull = send("POST", wt, "/mfcs/" + MFC + "/workers/" + w + "/next-tasks", Map.of());
    assertThat(pull.body().path("code").asString()).isIn("ON_BREAK", "DEPENDENCY_UNAVAILABLE");

    // 자동 휴게는 본인이 일찍 못 끝낸다 → 409, 관리자는 된다
    Res mine = send("POST", wt, "/mfcs/" + MFC + "/workers/" + w + "/breaks/current/end", null);
    assertThat(mine.status()).isEqualTo(409);
    assertThat(mine.body().path("code").asString()).isEqualTo("AUTO_BREAK_SUPERVISOR_ONLY");
    Res sup =
        send("POST", SUPERVISOR, "/mfcs/" + MFC + "/workers/" + w + "/breaks/current/end", null);
    assertThat(sup.status()).isEqualTo(200);
    assertThat(sup.body().path("source").asString()).isEqualTo("AUTO");
    assertThat(onBreak(w)).isFalse();
    // 같은 근무로 다시 켜지 않는다
    workers.sweepAutoBreaks();
    assertThat(onBreak(w)).isFalse();

    // 끝 시각이 지나면 서버가 끝낸다(다른 작업자 — 구간을 지난 것으로 당긴다)
    String w2 = newWorker();
    send(
        "POST",
        SUPERVISOR,
        "/mfcs/" + MFC + "/workers/" + w2 + "/shifts",
        Map.of(
            "startAt", now.minusSeconds(3600).toString(),
            "endAt", now.plusSeconds(3600 * 6).toString(),
            "breakStartAt", now.minusSeconds(60).toString(),
            "breakEndAt", now.plusSeconds(1800).toString()));
    workers.sweepAutoBreaks();
    assertThat(onBreak(w2)).isTrue();
    jdbc.update(
        "UPDATE mfc_worker_break SET planned_end_at = now() - interval '1 second'"
            + " WHERE worker_id = ? AND ended_at IS NULL",
        w2);
    workers.sweepAutoBreaks();
    assertThat(onBreak(w2)).isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT ended_by FROM mfc_worker_break WHERE worker_id = ?", String.class, w2))
        .isEqualTo("SYSTEM");
  }

  boolean onBreak(String worker) {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM mfc_worker_break WHERE mfc_id = ? AND worker_id = ? AND ended_at IS NULL",
            Integer.class,
            MFC,
            worker);
    return n != null && n > 0;
  }

  int failed(String worker) {
    List<Integer> n =
        jdbc.queryForList(
            "SELECT failed_count FROM mfc_break_pin_failure WHERE mfc_id = ? AND worker_id = ?",
            Integer.class,
            MFC,
            worker);
    return n.isEmpty() ? 0 : n.get(0);
  }
}
