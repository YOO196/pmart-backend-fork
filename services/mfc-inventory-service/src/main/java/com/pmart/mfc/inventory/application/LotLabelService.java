package com.pmart.mfc.inventory.application;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.inventory.domain.SkuProjection;
import com.pmart.mfc.inventory.infrastructure.LotLabelStore;
import com.pmart.mfc.inventory.infrastructure.LotLabelStore.LotRow;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * LOT 라벨(QR) 인쇄·재출력·해석(0.20.0, 클라이언트 mfc-app L1~L6 — 사용자 "추천대로" 2026-11-17). 값은 {@code LOT:{lotId}}
 * — LOT 하나 = 값 하나, 재출력해도 같다. LOT 기준은 바꾸지 않는다(납입일은 표시만). 그 센터 운영자.
 */
@Service
public class LotLabelService {

  public static final String PREFIX = "LOT:";
  static final int MAX_ITEMS = 100;

  public record Label(LotRow lot, @Nullable SkuProjection sku) {
    public String value() {
      return PREFIX + lot.lotId();
    }
  }

  private final LotLabelStore store;
  private final SkuProjector skus;

  public LotLabelService(LotLabelStore store, SkuProjector skus) {
    this.store = store;
    this.skus = skus;
  }

  @Transactional(readOnly = true)
  public Label get(String mfcId, String lotId) {
    Operator.requireCenter(mfcId);
    LotRow lot =
        store
            .find(mfcId, lotId)
            .orElseThrow(() -> PmartException.notFound("LOT_NOT_FOUND", "LOT 이 없습니다: " + lotId));
    return new Label(lot, skus.projections(List.of(lot.sku())).get(lot.sku()));
  }

  @Transactional(readOnly = true)
  public List<Label> find(String mfcId, @Nullable String sku, @Nullable String locationCode) {
    Operator.requireCenter(mfcId);
    String s = blankToNull(sku);
    String loc = blankToNull(locationCode);
    if (s == null && loc == null) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "sku 나 locationCode 중 하나가 필요합니다.");
    }
    List<LotRow> lots = store.held(mfcId, s, loc, MAX_ITEMS);
    Map<String, SkuProjection> projections =
        skus.projections(lots.stream().map(LotRow::sku).distinct().toList());
    return lots.stream().map(l -> new Label(l, projections.get(l.sku()))).toList();
  }

  private static @Nullable String blankToNull(@Nullable String s) {
    return s == null || s.isBlank() ? null : s;
  }
}
