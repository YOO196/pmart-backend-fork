package com.pmart.mfc.inventory.application;

import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;

/**
 * 센터 취급 변경 호출자(운영자). 운영자 토큰이고 {@code centers} 클레임에 대상 센터가 있어야 한다(자기 센터 범위). 세부 역할 검증은 아직 정한 규칙이 없어
 * 하지 않는다.
 */
public record Operator(String id, String sourceSystem) {

  public static Operator forCenter(String centerId, String sourceSystem) {
    return new Operator(requireCenter(centerId), sourceSystem);
  }

  /**
   * 그 센터 권한 + 역할 중 하나(MB-3, 2026-11-18 PC 3 결정 — 재고 수치·취급을 바꾸는 관리 동작). 위치·LOT 정정은 MFC_SUPERVISOR,
   * 상품 취급은 MFC_SUPERVISOR 또는 MD. 센터 권한은 그대로 필요하다(넓히지 않는다 — 센터 없는 본사 MD 를 모든 센터에 열지는 결정 대기).
   */
  public static Operator forCenterRole(String centerId, String sourceSystem, String... roles) {
    String id = requireCenter(centerId);
    PmartPrincipal p = PmartPrincipal.current().orElseThrow();
    for (String r : roles) {
      if (p.hasRole(r)) {
        return new Operator(id, sourceSystem);
      }
    }
    throw new PmartException(
        ErrorCode.FORBIDDEN, "이 센터의 " + String.join("·", roles) + " 만 할 수 있습니다.");
  }

  /**
   * 현재 주체가 이 센터의 운영자인지 확인한다(조회에도 쓴다).
   *
   * @return 운영자 ID
   */
  public static String requireCenter(String centerId) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "운영자만 할 수 있습니다."));
    if (!p.canAccessCenter(centerId)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "이 센터에 대한 권한이 없습니다.");
    }
    return p.subject();
  }

  RequestKey key(String requestId, String operation) {
    return new RequestKey(sourceSystem, requestId, operation, id);
  }
}
