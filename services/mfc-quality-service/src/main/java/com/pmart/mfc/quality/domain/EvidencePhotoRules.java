package com.pmart.mfc.quality.domain;

import java.util.Optional;

/** 품질 증빙 사진 규칙(0.9.0) — delivery-execution 증빙 사진과 같은 기준: JPEG·PNG·WEBP, 10MB 이하. */
public final class EvidencePhotoRules {

  public static final int MAX_PHOTO_BYTES = 10 * 1024 * 1024;

  private EvidencePhotoRules() {}

  /** 파일 앞부분으로 형식 판정(이름·보낸 Content-Type 은 믿지 않는다). JPEG·PNG·WEBP 만. */
  public static Optional<String> photoType(byte[] b) {
    if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
      return Optional.of("image/jpeg");
    }
    if (b.length >= 8
        && (b[0] & 0xFF) == 0x89
        && b[1] == 'P'
        && b[2] == 'N'
        && b[3] == 'G'
        && b[4] == 0x0D
        && b[5] == 0x0A
        && b[6] == 0x1A
        && b[7] == 0x0A) {
      return Optional.of("image/png");
    }
    if (b.length >= 12
        && b[0] == 'R'
        && b[1] == 'I'
        && b[2] == 'F'
        && b[3] == 'F'
        && b[8] == 'W'
        && b[9] == 'E'
        && b[10] == 'B'
        && b[11] == 'P') {
      return Optional.of("image/webp");
    }
    return Optional.empty();
  }
}
