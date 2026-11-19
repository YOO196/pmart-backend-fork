package com.pmart.mfc.quality.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.quality.domain.EvidencePhotoRules;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 품질 증빙 사진(PDA BR-52 R18-2, 사용자 결정 2026-11-20 "사진은 mfc-quality"): 올리기 → evidenceRef(EVQ-…), 보기. 저장
 * 규칙은 delivery-execution 증빙 사진과 같다 — 파일 앞부분으로 형식 판정, 10MB, 참조는 출처·요청 키·올린 사람·센터·내용 해시로 정해 같은 요청
 * 재전송은 같은 사진. 저장소에 먼저 올리고(트랜잭션 밖) 참조를 저장한다 — 참조 저장이 실패해도 같은 키로 다시 보내면 같은 객체 키로 덮어써서 끝난다(참조 없는 객체는
 * 다시 보내지 않은 요청에서만 남는다 — 정리는 남은 것).
 */
@Service
public class EvidencePhotos {

  public static final String NOT_FOUND = "EVIDENCE_PHOTO_NOT_FOUND";

  private static final Logger log = LoggerFactory.getLogger(EvidencePhotos.class);

  /** 사진 참조. */
  public record Photo(
      String evidenceRef,
      String mfcId,
      String contentType,
      int sizeBytes,
      String uploadedBy,
      Instant uploadedAt) {}

  /** 원본. */
  public record PhotoFile(String contentType, byte[] bytes) {}

  private final JdbcTemplate jdbc;
  private final ObjectProvider<EvidencePhotoStorage> storage;
  private final RequestIdempotency idempotency;
  private final Clock clock;

  public EvidencePhotos(
      JdbcTemplate jdbc,
      ObjectProvider<EvidencePhotoStorage> storage,
      RequestIdempotency idempotency,
      Clock clock) {
    this.jdbc = jdbc;
    this.storage = storage;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  public Photo upload(String mfcId, String requestId, String sourceSystem, byte[] bytes) {
    String me = Actors.worker(mfcId, null);
    if (bytes.length == 0 || bytes.length > EvidencePhotoRules.MAX_PHOTO_BYTES) {
      throw invalid("사진은 10MB 이하여야 합니다.");
    }
    String contentType =
        EvidencePhotoRules.photoType(bytes)
            .orElseThrow(() -> invalid("JPEG·PNG·WEBP 사진만 올릴 수 있습니다."));
    EvidencePhotoStorage s = storage.getIfAvailable();
    if (s == null) {
      throw new PmartException(ErrorCode.DEPENDENCY_UNAVAILABLE, "증빙 사진 저장소가 설정되지 않았습니다.");
    }
    String hash = sha256(bytes);
    String ref =
        "EVQ-"
            + UUID.nameUUIDFromBytes(
                String.join("\n", sourceSystem, requestId, me, mfcId, hash)
                    .getBytes(StandardCharsets.UTF_8));
    String key = "mfc/" + mfcId + "/" + ref;
    if (find(ref).isEmpty()) {
      s.put(key, bytes, contentType); // 트랜잭션 밖 — 같은 요청이면 같은 키로 덮어쓴다
    }
    return idempotency
        .execute(
            Actors.key(sourceSystem, requestId, "uploadQualityEvidencePhoto"),
            Map.of("mfcId", mfcId, "sha256", hash),
            Photo.class,
            () -> {
              Instant now = clock.instant();
              jdbc.update(
                  "INSERT INTO quality_evidence_photo (evidence_ref, mfc_id, object_key, content_type,"
                      + " size_bytes, sha256, uploaded_by, uploaded_at) VALUES (?,?,?,?,?,?,?,?)"
                      + " ON CONFLICT (evidence_ref) DO NOTHING",
                  ref,
                  mfcId,
                  key,
                  contentType,
                  bytes.length,
                  hash,
                  me,
                  Timestamp.from(now));
              log.info("품질 증빙 사진 {} {} {}B by {}", ref, mfcId, bytes.length, me);
              return find(ref).orElseThrow();
            })
        .value();
  }

  /** 보기 — 그 센터 운영자(역할 무관)·서비스. 없거나 볼 수 없으면 404(있는지 알리지 않음). */
  public PhotoFile file(String evidenceRef) {
    Map<String, Object> row =
        jdbc
            .queryForList(
                "SELECT mfc_id, object_key, content_type FROM quality_evidence_photo"
                    + " WHERE evidence_ref = ?",
                evidenceRef)
            .stream()
            .findFirst()
            .orElseThrow(EvidencePhotos::notFound);
    PmartPrincipal p = PmartPrincipal.current().orElseThrow(EvidencePhotos::notFound);
    if (!p.isService()
        && (p.type() != PmartPrincipal.PrincipalType.OPERATOR
            || !p.canAccessCenter((String) row.get("mfc_id")))) {
      throw notFound();
    }
    EvidencePhotoStorage s = storage.getIfAvailable();
    if (s == null) {
      throw new PmartException(ErrorCode.DEPENDENCY_UNAVAILABLE, "증빙 사진 저장소가 설정되지 않았습니다.");
    }
    byte[] bytes = s.get((String) row.get("object_key")).orElseThrow(EvidencePhotos::notFound);
    return new PhotoFile((String) row.get("content_type"), bytes);
  }

  private Optional<Photo> find(String ref) {
    return jdbc
        .query(
            "SELECT * FROM quality_evidence_photo WHERE evidence_ref = ?",
            (rs, i) ->
                new Photo(
                    rs.getString("evidence_ref"),
                    rs.getString("mfc_id"),
                    rs.getString("content_type"),
                    rs.getInt("size_bytes"),
                    rs.getString("uploaded_by"),
                    rs.getTimestamp("uploaded_at").toInstant()),
            ref)
        .stream()
        .findFirst();
  }

  private static PmartException invalid(String message) {
    return new PmartException(
        "PHOTO_INVALID", HttpStatus.UNPROCESSABLE_CONTENT, message, false, List.of());
  }

  private static PmartException notFound() {
    return PmartException.notFound(NOT_FOUND, "증빙 사진이 없습니다.");
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
