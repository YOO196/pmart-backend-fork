package com.pmart.mfc.capacity;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
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
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 센터 직원 연락처(0.21.0, 사용자 결정 2026-11-18): 본인 등록·변경(버전), 형식 400, 다른 센터 403, 목록은 모든 직원·라이더. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.capacity.units-per-hour=3"
    })
@Import(TestJwtConfiguration.class)
class StaffContactIT extends PostgresKafkaContainerSupport {

  static final String MFC = "MFC-CT-1";

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;

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

  Res put(String token, Map<String, Object> body, @Nullable Integer expected) {
    var e =
        http.put()
            .uri("/mfcs/" + MFC + "/staff-contacts/me")
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

  @Test
  void ownerSetsContactAndOthersSeeIt() {
    String id = "ct-" + UUID.randomUUID().toString().substring(0, 6);
    String me = TestJwt.operator(id, List.of("MFC_SUPERVISOR"), List.of(MFC));
    // 처음: 빈 값·버전 0
    Res empty = get(me, "/mfcs/" + MFC + "/staff-contacts/me");
    assertThat(empty.status()).isEqualTo(200);
    assertThat(empty.body().path("version").asInt()).isZero();
    assertThat(empty.body().path("phone").isNull()).isTrue();

    // 형식 틀림 → 400 + 어느 칸
    Res bad = put(me, Map.of("phone", "abc", "email", "no-at"), 0);
    assertThat(bad.status()).isEqualTo(400);
    assertThat(bad.body().toString()).contains("phone").contains("email");

    Res saved = put(me, Map.of("name", "센터장", "phone", "010-1234-5678", "email", "a@b.co"), 0);
    assertThat(saved.status()).as(saved.body().toString()).isEqualTo(200);
    assertThat(saved.body().path("version").asInt()).isEqualTo(1);
    // 낡은 버전 → 409
    assertThat(put(me, Map.of("phone", "010-0000-0000"), 0).status()).isEqualTo(409);

    // 다른 센터 직원·라이더도 목록에서 본다
    String other = TestJwt.operator("ct-other", List.of("MFC_SUPERVISOR"), List.of("MFC-OTHER"));
    Res list = get(other, "/mfcs/" + MFC + "/staff-contacts");
    assertThat(list.status()).isEqualTo(200);
    assertThat(list.body().path("items").findValuesAsString("operatorId")).contains(id);
    Res byRider = get(TestJwt.rider("RD-ct"), "/mfcs/" + MFC + "/staff-contacts");
    assertThat(byRider.status()).isEqualTo(200);
    assertThat(byRider.body().toString()).contains("010-1234-5678");
    // 다른 센터 직원은 이 센터에 자기 연락처를 못 둔다
    assertThat(put(other, Map.of("phone", "010-1111-2222"), null).status()).isEqualTo(403);

    // 빈 값으로 지우면 목록에서 빠진다
    Map<String, Object> clear = new HashMap<>();
    clear.put("name", null);
    clear.put("phone", "");
    clear.put("email", null);
    Res cleared = put(me, clear, 1);
    assertThat(cleared.status()).isEqualTo(200);
    assertThat(cleared.body().path("version").asInt()).isEqualTo(2);
    assertThat(
            get(other, "/mfcs/" + MFC + "/staff-contacts")
                .body()
                .path("items")
                .findValuesAsString("operatorId"))
        .doesNotContain(id);
  }
}
