package com.pmart.mfc.quality.application;

import java.util.Optional;

/**
 * 품질 증빙 사진 원본 저장소(비공개 — ADR-0016, delivery-execution 증빙 사진과 같은 방식). 장애는 DEPENDENCY_UNAVAILABLE 로
 * 던진다. 버킷 설정이 없으면 빈이 없다(올리기·보기 503).
 */
public interface EvidencePhotoStorage {

  /** 같은 키로 다시 올리면 덮어쓴다(같은 요청의 재시도). */
  void put(String key, byte[] bytes, String contentType);

  /** 원본 읽기. 없으면 empty. */
  Optional<byte[]> get(String key);
}
