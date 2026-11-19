package com.pmart.mfc.fulfillment.application;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.fulfillment.infrastructure.MetricsStore;
import com.pmart.mfc.fulfillment.infrastructure.MetricsStore.PickingLines;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결품률 지표(F-10, 2026-11-19 — 백오피스 대시보드 SHORTAGE_RATE). 줄 단위·사유별(사용자 결정 2026-11-18).
 * OPERATOR_ADMIN·서비스는 모든 센터, 그 밖 운영자는 토큰 센터만.
 */
@Service
public class MetricsService {

  static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
  static final int MAX_DAYS = 92;

  private final MetricsStore store;

  public MetricsService(MetricsStore store) {
    this.store = store;
  }

  @Transactional(readOnly = true)
  public List<PickingLines> pickingLines(LocalDate from, LocalDate to, @Nullable String mfcId) {
    if (to.isBefore(from)) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "to 는 from 보다 앞일 수 없습니다.");
    }
    if (ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "기간은 최대 " + MAX_DAYS + "일입니다.");
    }
    PmartPrincipal p =
        PmartPrincipal.current()
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "토큰이 필요합니다."));
    List<String> centers;
    if (p.isService()
        || (p.type() == PmartPrincipal.PrincipalType.OPERATOR && p.hasRole("OPERATOR_ADMIN"))) {
      centers = mfcId == null ? null : List.of(mfcId);
    } else if (p.type() == PmartPrincipal.PrincipalType.OPERATOR) {
      if (mfcId != null && !p.canAccessCenter(mfcId)) {
        throw new PmartException(ErrorCode.FORBIDDEN, "이 센터에 대한 권한이 없습니다.");
      }
      centers = mfcId == null ? p.centerScope() : List.of(mfcId);
    } else {
      throw new PmartException(ErrorCode.FORBIDDEN, "직원만 볼 수 있습니다.");
    }
    return store.pickingLines(
        from.atStartOfDay(SEOUL).toInstant(),
        to.plusDays(1).atStartOfDay(SEOUL).toInstant(),
        centers);
  }
}
