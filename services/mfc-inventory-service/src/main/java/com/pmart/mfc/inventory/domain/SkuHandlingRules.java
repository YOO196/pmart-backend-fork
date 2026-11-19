package com.pmart.mfc.inventory.domain;

import com.pmart.mfc.inventory.domain.SkuProjection.SkuState;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * 센터 SKU 취급 ON/OFF 규칙(product-registration-design.md §5.3, 사용자 결정 2026-10-30).
 *
 * <ul>
 *   <li>취급 레코드가 없으면 OFF 다. 이벤트로 레코드를 만들지 않고, 운영자가 바꿀 때 생긴다.
 *   <li>SKU 투영이 없으면(상품 승인 이벤트 수신 전) 바꿀 수 없다 — SKU_NOT_FOUND.
 *   <li>켜기는 ACTIVE SKU 만 — INACTIVE 면 SKU_INACTIVE. 끄기는 항상 허용.
 *   <li>X-Expected-Version 은 레코드 version(레코드 없음 = 0)과 비교한다.
 * </ul>
 */
public final class SkuHandlingRules {

  public static final String SKU_NOT_FOUND = "SKU_NOT_FOUND";
  public static final String SKU_INACTIVE = "SKU_INACTIVE";
  public static final String SKU_DISCONTINUED = "SKU_DISCONTINUED";
  public static final String DISCONTINUED = "DISCONTINUED";
  public static final String VERSION_CONFLICT = "VERSION_CONFLICT";

  private SkuHandlingRules() {}

  /** 현재 취급 상태. 레코드가 없으면 OFF·version 0. */
  public record Current(boolean enabled, int version) {
    public static final Current ABSENT = new Current(false, 0);
  }

  /**
   * @param skuState 투영의 SKU 상태. 투영이 없으면 null
   * @param expectedVersion 호출자가 본 version. 없으면 검사하지 않음
   * @return 거절 사유 코드. 허용이면 empty
   */
  public static Optional<String> rejection(
      @Nullable SkuState skuState,
      Current current,
      boolean enable,
      @Nullable Integer expectedVersion) {
    if (skuState == null) {
      return Optional.of(SKU_NOT_FOUND);
    }
    if (expectedVersion != null && expectedVersion != current.version()) {
      return Optional.of(VERSION_CONFLICT);
    }
    if (enable && skuState != SkuState.ACTIVE) {
      return Optional.of(SKU_INACTIVE);
    }
    return Optional.empty();
  }

  /** (0.27.0) 위 규칙 + 본사 단종인 동안 켜기 금지(SKU_DISCONTINUED). */
  public static Optional<String> rejection(
      @Nullable SkuState skuState,
      @Nullable String saleState,
      Current current,
      boolean enable,
      @Nullable Integer expectedVersion) {
    Optional<String> r = rejection(skuState, current, enable, expectedVersion);
    if (r.isPresent()) {
      return r;
    }
    if (enable && DISCONTINUED.equals(saleState)) {
      return Optional.of(SKU_DISCONTINUED);
    }
    return Optional.empty();
  }

  /** OFF 인 까닭(0.27.0): 단종이면 DISCONTINUED, 센터가 정한 OFF 면 CENTER, 정한 적 없으면 null. */
  public static @Nullable String offReason(
      boolean enabled, boolean decided, @Nullable String saleState) {
    if (enabled) {
      return null;
    }
    if (DISCONTINUED.equals(saleState)) {
      return DISCONTINUED;
    }
    return decided ? "CENTER" : null;
  }

  /** 값이 같으면 변경·이력 없이 현재 상태를 돌려준다. */
  public static boolean changes(Current current, boolean enable) {
    return current.enabled() != enable;
  }
}
