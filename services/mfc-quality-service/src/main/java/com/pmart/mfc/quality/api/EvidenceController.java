package com.pmart.mfc.quality.api;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.quality.api.generated.EvidenceApi;
import com.pmart.mfc.quality.api.generated.model.QualityEvidencePhoto;
import com.pmart.mfc.quality.application.EvidencePhotos;
import java.io.IOException;
import java.time.ZoneOffset;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 계약 EvidenceApi — 품질 증빙 사진(0.9.0, PDA BR-52 R18-2). 규칙은 {@link EvidencePhotos}. */
@RestController
public class EvidenceController implements EvidenceApi {

  private final EvidencePhotos photos;

  public EvidenceController(EvidencePhotos photos) {
    this.photos = photos;
  }

  @Override
  public ResponseEntity<QualityEvidencePhoto> uploadQualityEvidencePhoto(
      String mfcId, String idempotencyKey, String xSourceSystem, MultipartFile file) {
    byte[] bytes;
    try {
      bytes = file.getBytes();
    } catch (IOException e) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "사진을 읽을 수 없습니다.");
    }
    var p = photos.upload(mfcId, idempotencyKey, xSourceSystem, bytes);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            new QualityEvidencePhoto()
                .evidenceRef(p.evidenceRef())
                .mfcId(p.mfcId())
                .contentType(QualityEvidencePhoto.ContentTypeEnum.fromValue(p.contentType()))
                .sizeBytes(p.sizeBytes())
                .uploadedBy(p.uploadedBy())
                .uploadedAt(p.uploadedAt().atOffset(ZoneOffset.UTC)));
  }

  /** 보기 — 앱·브라우저가 남기지 않게 no-store·private. */
  @Override
  public ResponseEntity<Resource> getQualityEvidencePhoto(String evidenceRef) {
    var f = photos.file(evidenceRef);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore().cachePrivate())
        .contentType(MediaType.parseMediaType(f.contentType()))
        .body(new ByteArrayResource(f.bytes()));
  }
}
