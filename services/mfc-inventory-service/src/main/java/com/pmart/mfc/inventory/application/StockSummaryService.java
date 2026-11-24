package com.pmart.mfc.inventory.application;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.inventory.infrastructure.StockSummaryStore;
import com.pmart.mfc.inventory.infrastructure.StockSummaryStore.Summary;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 센터별 품절 상품 수(0.28.0, 요청 IV-5 — 백오피스 대시보드 D5). 안전재고 없이 "품절"(사용자 결정 2026-11-18). OPERATOR_ADMIN·서비스는
 * 모든 센터, 그 밖 운영자는 토큰 센터만.
 */
@Service
public class StockSummaryService {

  private final StockSummaryStore store;

  public StockSummaryService(StockSummaryStore store) {
    this.store = store;
  }

  @Transactional(readOnly = true)
  public List<Summary> summaries(@Nullable String mfcId) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "토큰이 필요합니다."));
    if (p.isService()
        || (p.type() == PmartPrincipal.PrincipalType.OPERATOR && p.hasRole("OPERATOR_ADMIN"))) {
      return store.summaries(mfcId == null ? null : List.of(mfcId));
    }
    if (p.type() != PmartPrincipal.PrincipalType.OPERATOR) {
      throw new PmartException(ErrorCode.FORBIDDEN, "직원만 볼 수 있습니다.");
    }
    if (mfcId != null && !p.canAccessCenter(mfcId)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "이 센터에 대한 권한이 없습니다.");
    }
    return store.summaries(mfcId == null ? p.centerScope() : List.of(mfcId));
  }

  /**
   * 재고 실시간 스트림(0.46.0) 받을 센터 — {@link #summaries} 와 같은 판정. null = 모든 센터(OPERATOR_ADMIN·서비스가 센터를 비웠을
   * 때).
   */
  public java.util.@Nullable Set<String> streamScope(@Nullable List<String> mfcIds) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "토큰이 필요합니다."));
    List<String> asked = mfcIds == null ? List.of() : mfcIds;
    if (p.isService()
        || (p.type() == PmartPrincipal.PrincipalType.OPERATOR && p.hasRole("OPERATOR_ADMIN"))) {
      return asked.isEmpty() ? null : new java.util.TreeSet<>(asked);
    }
    if (p.type() != PmartPrincipal.PrincipalType.OPERATOR) {
      throw new PmartException(ErrorCode.FORBIDDEN, "직원만 볼 수 있습니다.");
    }
    for (String m : asked) {
      if (!p.canAccessCenter(m)) {
        throw new PmartException(ErrorCode.FORBIDDEN, "이 센터에 대한 권한이 없습니다.");
      }
    }
    java.util.Set<String> out = new java.util.TreeSet<>(asked.isEmpty() ? p.centerScope() : asked);
    if (out.isEmpty()) {
      throw new PmartException(ErrorCode.FORBIDDEN, "볼 수 있는 센터가 없습니다.");
    }
    return out;
  }
}
