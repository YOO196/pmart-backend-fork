package com.pmart.mfc.inventory.application;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.inventory.infrastructure.LowStockStore;
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
  private final LowStockStore lowStock;
  private final java.time.Clock clock;

  public StockSummaryService(
      StockSummaryStore store, LowStockStore lowStock, java.time.Clock clock) {
    this.store = store;
    this.lowStock = lowStock;
    this.clock = clock;
  }

  /** 센터 한 줄 — 품절(0.28.0) + 곧 품절·모름·기준(0.47.0, 기준을 못 받았으면 basis null). */
  public record Row(
      Summary summary, int lowStock, int lowStockUnknown, LowStockStore.@Nullable Basis basis) {}

  @Transactional(readOnly = true)
  public List<Row> summaries(@Nullable String mfcId) {
    List<String> centers = scope(mfcId);
    List<Summary> base = store.summaries(centers);
    java.util.Map<String, int[]> counts = lowStock.counts(centers);
    java.util.Map<String, LowStockStore.Basis> bases = lowStock.bases(centers);
    return base.stream()
        .map(
            s -> {
              int[] c = counts.getOrDefault(s.mfcId(), new int[] {0, 0});
              return new Row(s, c[0], c[1], bases.get(s.mfcId()));
            })
        .toList();
  }

  public record SkuList(
      LowStockStore.Page page,
      LowStockStore.@Nullable Basis basis,
      int offset,
      java.time.Instant asOf) {}

  /** 곧 품절·품절·모름 목록(0.47.0, LS-2) — 권한은 {@link #summaries} 와 같다. */
  @Transactional(readOnly = true)
  public SkuList skus(String mfcId, LowStockStore.Kind kind, int size, @Nullable String cursor) {
    scope(mfcId);
    int offset = 0;
    if (cursor != null) {
      try {
        offset = Integer.parseInt(cursor);
      } catch (NumberFormatException e) {
        offset = -1;
      }
      if (offset < 0) {
        throw new PmartException(ErrorCode.VALIDATION_FAILED, "cursor 가 올바르지 않습니다.");
      }
    }
    var page = lowStock.list(mfcId, kind, offset, size);
    return new SkuList(page, lowStock.bases(List.of(mfcId)).get(mfcId), offset, clock.instant());
  }

  /**
   * 곧 품절 기준 넣기(0.47.0, mfc-inbound 내부 호출) — 앞선 계산이면 false. 센터 기준을 통째로 바꾸고 calculatedAt 으로 순서를 가리므로
   * 같은 요청을 다시 보내도 결과가 같다(request_record 를 두지 않음 — 1시간마다 센터 수만큼 쌓이기만 한다).
   */
  @Transactional
  public boolean putBasis(
      LowStockStore.Basis basis, java.util.Map<String, java.math.BigDecimal> daily) {
    return lowStock.replace(basis, daily, clock.instant());
  }

  /** null = 모든 센터(OPERATOR_ADMIN·서비스, mfcId 없음). */
  private static @Nullable List<String> scope(@Nullable String mfcId) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "토큰이 필요합니다."));
    if (p.isService()
        || (p.type() == PmartPrincipal.PrincipalType.OPERATOR && p.hasRole("OPERATOR_ADMIN"))) {
      return mfcId == null ? null : List.of(mfcId);
    }
    if (p.type() != PmartPrincipal.PrincipalType.OPERATOR) {
      throw new PmartException(ErrorCode.FORBIDDEN, "직원만 볼 수 있습니다.");
    }
    if (mfcId != null && !p.canAccessCenter(mfcId)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "이 센터에 대한 권한이 없습니다.");
    }
    return mfcId == null ? p.centerScope() : List.of(mfcId);
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
