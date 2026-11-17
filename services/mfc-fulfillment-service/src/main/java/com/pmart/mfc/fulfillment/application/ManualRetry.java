package com.pmart.mfc.fulfillment.application;

import org.jspecify.annotations.Nullable;

/**
 * 운영자 재시도 기록(0.9.0, 처리 대기 BO-12 — 요청 F-4). 백오피스 bff 가 운영자 ID(토큰 주체)·메모를 넘긴다. 둘 다 선택 — 본문 없이 부르던
 * 호출자는 {@link #NONE}.
 */
public record ManualRetry(
    @Nullable String operatorId, @Nullable String reason, String sourceSystem) {

  public static final ManualRetry NONE = new ManualRetry(null, null, "unknown");

  public static ManualRetry of(
      @Nullable String operatorId, @Nullable String reason, @Nullable String sourceSystem) {
    return new ManualRetry(
        blankToNull(operatorId),
        blankToNull(reason),
        sourceSystem == null || sourceSystem.isBlank() ? "unknown" : sourceSystem);
  }

  private static @Nullable String blankToNull(@Nullable String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }
}
