package com.pmart.mfc.inbound.application;

import com.pmart.mfc.inbound.infrastructure.InventorySkuClient;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 현장 화면 표시용 상품명·바코드(클라이언트 BR-53 R8, 0.8.0). mfc-inventory SKU 사본(상품 사건 투영)에서 읽는다. 표시용이라 조회가 실패하면 빈
 * 값으로 두고 목록은 그대로 돌려준다(작업 조회를 막지 않는다).
 */
@Service
public class SkuLabels {

  /** 상품명(관리용 명칭, 모르면 null)·활성 바코드. */
  /**
   * SKU 표시·판정 사본. (0.11.0, mfc-app BR-52 R18) 품질검사 정상 판정 기준 — storageType(AMBIENT·CHILLED·FROZEN),
   * minRemainingDays(실제 적용 최소 잔여일 — 상품 보장, 없으면 보관 유형 기본값), maxDaysSinceManufacture(제조 후 최대 경과일, 있으면
   * 제조일 필수). 모르면 null.
   */
  public record Label(
      @Nullable String productName,
      List<String> barcodes,
      @Nullable String storageType,
      @Nullable Integer minRemainingDays,
      @Nullable Integer maxDaysSinceManufacture) {
    public static final Label UNKNOWN = new Label(null, List.of(), null, null, null);

    public Label(@Nullable String productName, List<String> barcodes) {
      this(productName, barcodes, null, null, null);
    }
  }

  private static final Logger log = LoggerFactory.getLogger(SkuLabels.class);

  private final InventorySkuClient client;

  public SkuLabels(InventorySkuClient client) {
    this.client = client;
  }

  public Map<String, Label> of(Collection<String> skus) {
    List<String> distinct = skus.stream().distinct().toList();
    if (distinct.isEmpty()) {
      return Map.of();
    }
    try {
      return client.labels(distinct);
    } catch (RuntimeException ex) {
      log.warn("SKU 상품명·바코드 조회 실패 — 빈 값으로 응답: {}", ex.getMessage());
      return Map.of();
    }
  }

  public static Label get(Map<String, Label> labels, String sku) {
    return labels.getOrDefault(sku, Label.UNKNOWN);
  }
}
