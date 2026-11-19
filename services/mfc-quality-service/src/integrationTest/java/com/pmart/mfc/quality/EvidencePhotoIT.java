package com.pmart.mfc.quality;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import com.pmart.mfc.quality.application.EvidencePhotoStorage;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 품질 증빙 사진(0.9.0, PDA BR-52 R18-2): 올리기 → evidenceRef, 같은 키·같은 사진 = 같은 ref, 같은 키·다른 사진 409, 형식 422,
 * 다른 센터·역할 없음 403, 보기는 그 센터 운영자만(그 밖 404). 저장소는 메모리(실제 S3 는 LAN SeaweedFS).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.quality.sweep-interval=PT1H"
    })
@Import({TestJwtConfiguration.class, EvidencePhotoIT.MemoryStorage.class})
class EvidencePhotoIT extends PostgresKafkaContainerSupport {

  static final String MFC = "MFC-EV1";
  static final String W1 = TestJwt.operator("w-ev", List.of("MFC_WORKER"), List.of(MFC));
  static final String VIEWER = TestJwt.operator("v-ev", List.of(), List.of(MFC));
  static final String OTHER = TestJwt.operator("x-ev", List.of("MFC_WORKER"), List.of("MFC-ZZ"));
  static final Map<String, byte[]> STORED = new ConcurrentHashMap<>();

  static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};
  static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x10, 0x20};

  @TestConfiguration
  static class MemoryStorage {
    @Bean
    EvidencePhotoStorage memoryEvidencePhotoStorage() {
      return new EvidencePhotoStorage() {
        @Override
        public void put(String key, byte[] bytes, String contentType) {
          STORED.put(key, bytes);
        }

        @Override
        public Optional<byte[]> get(String key) {
          return Optional.ofNullable(STORED.get(key));
        }
      };
    }
  }

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;

  RestClient http() {
    return RestClient.builder()
        .baseUrl("http://localhost:" + port + "/api/v1")
        .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
        .build();
  }

  record Res(int status, JsonNode body) {}

  Res upload(String token, String mfcId, String key, byte[] bytes) {
    var form = new LinkedMultiValueMap<String, Object>();
    form.add(
        "file",
        new ByteArrayResource(bytes) {
          @Override
          public String getFilename() {
            return "p.bin";
          }
        });
    ResponseEntity<String> e =
        http()
            .post()
            .uri("/quality-evidence-photos?mfcId=" + mfcId)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", key);
                  h.set("X-Source-System", "mfc-pda");
                })
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(form)
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  ResponseEntity<byte[]> view(String token, String ref) {
    return http()
        .get()
        .uri("/quality-evidence-photos/" + ref)
        .headers(h -> h.setBearerAuth(token))
        .retrieve()
        .toEntity(byte[].class);
  }

  @Test
  void workerUploadsPhotoAndCenterOperatorsViewIt() {
    String key = UUID.randomUUID().toString();
    Res up = upload(W1, MFC, key, PNG);
    assertThat(up.status()).as(up.body().toString()).isEqualTo(201);
    String ref = up.body().path("evidenceRef").asString();
    assertThat(ref).startsWith("EVQ-");
    assertThat(up.body().path("contentType").asString()).isEqualTo("image/png");
    assertThat(up.body().path("sizeBytes").asInt()).isEqualTo(PNG.length);
    assertThat(up.body().path("uploadedBy").asString()).isEqualTo("w-ev");
    assertThat(STORED).containsKey("mfc/" + MFC + "/" + ref);

    // 같은 키·같은 사진 = 같은 ref, 같은 키·다른 사진 409
    assertThat(upload(W1, MFC, key, PNG).body().path("evidenceRef").asString()).isEqualTo(ref);
    assertThat(upload(W1, MFC, key, JPEG).status()).isEqualTo(409);
    // 형식·크기 422, 다른 센터·역할 없음 403
    Res bad = upload(W1, MFC, UUID.randomUUID().toString(), new byte[] {1, 2, 3, 4});
    assertThat(bad.status()).isEqualTo(422);
    assertThat(bad.body().path("code").asString()).isEqualTo("PHOTO_INVALID");
    assertThat(upload(OTHER, MFC, UUID.randomUUID().toString(), PNG).status()).isEqualTo(403);
    assertThat(upload(VIEWER, MFC, UUID.randomUUID().toString(), PNG).status()).isEqualTo(403);

    // 보기 — 그 센터 운영자(역할 무관), 다른 센터·없는 ref 404, no-store
    ResponseEntity<byte[]> v = view(VIEWER, ref);
    assertThat(v.getStatusCode().value()).isEqualTo(200);
    assertThat(v.getBody()).isEqualTo(PNG);
    assertThat(v.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
    assertThat(v.getHeaders().getCacheControl()).contains("no-store");
    assertThat(view(OTHER, ref).getStatusCode().value()).isEqualTo(404);
    assertThat(view(W1, "EVQ-none").getStatusCode().value()).isEqualTo(404);
  }
}
