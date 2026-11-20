package com.pmart.mfc.fulfillment.application;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.fulfillment.infrastructure.MetricsStore;
import com.pmart.mfc.fulfillment.infrastructure.MetricsStore.PickingLines;
import com.pmart.mfc.fulfillment.infrastructure.MetricsStore.SkuLines;
import java.time.Instant;
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
    checkPeriod(from, to);
    return store.pickingLines(start(from), end(to), centers(mfcId));
  }

  /** 상품별 정렬(F-13). */
  public enum SkuSort {
    SHORT_LINES,
    SHORT_RATE
  }

  /** 결품률 정렬 하한 기본값 — 20줄부터 결품 1줄 = 5%p(PC 3·사용자 2026-11-20, F-13). */
  public static final int DEFAULT_MIN_PICKED_LINES = 20;

  public record SkuResult(List<SkuLines> items, SkuSort sort, @Nullable Integer minPickedLines) {}

  /** 상품(SKU)별 피킹·결품 줄(F-13) — 세는 법·권한·기간은 {@link #pickingLines} 와 같다. */
  @Transactional(readOnly = true)
  public SkuResult pickingLinesBySku(
      LocalDate from,
      LocalDate to,
      @Nullable String mfcId,
      @Nullable SkuSort sort,
      @Nullable Integer limit,
      @Nullable Integer minPickedLines) {
    checkPeriod(from, to);
    SkuSort s = sort == null ? SkuSort.SHORT_LINES : sort;
    int n = limit == null ? 10 : limit;
    if (n < 1 || n > 50) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "limit 은 1~50 입니다.");
    }
    Integer min = null;
    if (s == SkuSort.SHORT_RATE) {
      min = minPickedLines == null ? DEFAULT_MIN_PICKED_LINES : minPickedLines;
      if (min < 1 || min > 1000) {
        throw new PmartException(ErrorCode.VALIDATION_FAILED, "minPickedLines 는 1~1000 입니다.");
      }
    }
    var items =
        store.pickingLinesBySku(
            start(from),
            end(to),
            centers(mfcId),
            s == SkuSort.SHORT_RATE,
            min == null ? 0 : min,
            n);
    return new SkuResult(items, s, min);
  }

  private static void checkPeriod(LocalDate from, LocalDate to) {
    if (to.isBefore(from)) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "to 는 from 보다 앞일 수 없습니다.");
    }
    if (ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "기간은 최대 " + MAX_DAYS + "일입니다.");
    }
  }

  private static Instant start(LocalDate from) {
    return from.atStartOfDay(SEOUL).toInstant();
  }

  private static Instant end(LocalDate to) {
    return to.plusDays(1).atStartOfDay(SEOUL).toInstant();
  }

  /** null = 모든 센터(OPERATOR_ADMIN·서비스, mfcId 없음). */
  private static @Nullable List<String> centers(@Nullable String mfcId) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "토큰이 필요합니다."));
    if (p.isService()
        || (p.type() == PmartPrincipal.PrincipalType.OPERATOR && p.hasRole("OPERATOR_ADMIN"))) {
      return mfcId == null ? null : List.of(mfcId);
    }
    if (p.type() == PmartPrincipal.PrincipalType.OPERATOR) {
      if (mfcId != null && !p.canAccessCenter(mfcId)) {
        throw new PmartException(ErrorCode.FORBIDDEN, "이 센터에 대한 권한이 없습니다.");
      }
      return mfcId == null ? p.centerScope() : List.of(mfcId);
    }
    throw new PmartException(ErrorCode.FORBIDDEN, "직원만 볼 수 있습니다.");
  }
}
