package com.pmart.mfc.capacity;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 직원 화면 표기(0.23.0, ADR-0070): 작업자 응답 이름·사번, 센터 화면용 운영자 ID → 이름·사번(HR 이름 + 인사 사본 사번). */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.capacity.units-per-hour=3",
      "pmart.capacity.hr-base-url=${hr.url}"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock({@ConfigureWireMock(name = "hr", baseUrlProperties = "hr.url")})
class StaffNamesIT extends PostgresKafkaContainerSupport {

  static final String MFC = "MFC-NM-1";
  static final String SUP = TestJwt.operator("nm-sup", List.of("MFC_SUPERVISOR"), List.of(MFC));

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;

  @InjectWireMock("hr")
  WireMockServer hr;

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

  void hrEmployee(String op, String no) {
    jdbc.update(
        "INSERT INTO hr_employee (operator_id, employee_no, employment_type, status, hr_version, updated_at)"
            + " VALUES (?,?,'REGULAR','ACTIVE',1,?) ON CONFLICT DO NOTHING",
        op,
        no,
        Timestamp.from(Instant.now()));
  }

  @Test
  void workersAndStaffShowNameAndEmployeeNo() {
    String w = "OP-nm-" + UUID.randomUUID().toString().substring(0, 6);
    String sup = "OP-sup-" + UUID.randomUUID().toString().substring(0, 6);
    Res reg =
        res(
            http.post()
                .uri("/mfcs/" + MFC + "/workers")
                .headers(
                    h -> {
                      h.setBearerAuth(SUP);
                      h.set("Idempotency-Key", UUID.randomUUID().toString());
                      h.set("X-Source-System", "mfc-backoffice");
                    })
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    mapper.writeValueAsString(
                        Map.of(
                            "workerId",
                            w,
                            "displayName",
                            "P2026-00077",
                            "duty",
                            Map.of("taskTypes", List.of("PICK"), "temperatureZones", List.of()))))
                .retrieve()
                .toEntity(String.class));
    assertThat(reg.status()).as(reg.body().toString()).isEqualTo(201);
    hrEmployee(w, "P2026-00077");
    hrEmployee(sup, "P2026-00001");
    hr.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/employee-names"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"items\":[{\"operatorId\":\""
                            + w
                            + "\",\"name\":\"김작업\"},{\"operatorId\":\""
                            + sup
                            + "\",\"name\":\"홍관리\"}]}")));

    // 작업자 목록·상세에 이름 · 사번
    Res list = get(SUP, "/mfcs/" + MFC + "/workers");
    JsonNode row = null;
    for (JsonNode n : list.body().path("items")) {
      if (w.equals(n.path("workerId").asString())) {
        row = n;
      }
    }
    assertThat(row).as(list.body().toString()).isNotNull();
    assertThat(row.path("name").asString()).isEqualTo("김작업");
    assertThat(row.path("employeeNo").asString()).isEqualTo("P2026-00077");
    assertThat(get(SUP, "/mfcs/" + MFC + "/workers/" + w).body().path("name").asString())
        .isEqualTo("김작업");

    // 처리자 칸용 — 작업자 아닌 직원도, 모르는 ID 는 빠진다
    Res names =
        get(
            SUP,
            "/mfcs/"
                + MFC
                + "/staff-names?operatorIds="
                + sup
                + "&operatorIds="
                + w
                + "&operatorIds=OP-none");
    assertThat(names.status()).as(names.body().toString()).isEqualTo(200);
    assertThat(names.body().path("items").findValuesAsString("operatorId")).containsExactly(sup, w);
    assertThat(names.body().path("items").path(0).path("name").asString()).isEqualTo("홍관리");
    assertThat(names.body().path("items").path(0).path("employeeNo").asString())
        .isEqualTo("P2026-00001");
    // 다른 센터 운영자는 403
    String other = TestJwt.operator("nm-x", List.of("MFC_SUPERVISOR"), List.of("MFC-OTHER"));
    assertThat(get(other, "/mfcs/" + MFC + "/staff-names?operatorIds=" + sup).status())
        .isEqualTo(403);

    // HR 이 안 되면 이름만 비고 사번은 준다(화면을 막지 않는다)
    hr.resetAll();
    hr.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/employee-names"))
            .willReturn(aResponse().withStatus(503)));
    Res degraded = get(SUP, "/mfcs/" + MFC + "/staff-names?operatorIds=" + sup);
    assertThat(degraded.status()).isEqualTo(200);
    assertThat(degraded.body().path("items").path(0).path("name").isNull()).isTrue();
    assertThat(degraded.body().path("items").path(0).path("employeeNo").asString())
        .isEqualTo("P2026-00001");
  }
}
