package com.pmart.mfc.inbound.application;

import com.pmart.mfc.inbound.domain.SupplyRecommendationRules.Policy;
import com.pmart.mfc.inbound.infrastructure.InventoryLowStockClient;
import com.pmart.mfc.inbound.infrastructure.InventorySkuClient;
import com.pmart.mfc.inbound.infrastructure.SupplyRecommendationStore;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 곧 품절 기준 보내기(inventory 0.47.0 — 통합 백오피스 LS-1, 사용자 결정 2026-11-25 "하루 판매량 기준"). 발주 수량 추천({@link
 * SupplyRecommendations})과 **같은 d·같은 공급 정책 표**로 센터마다 온도대별 정책(safetyDays·historyDays·최소 이력·이동평균 기간)과
 * 판 SKU 의 d 를 mfc-inventory 에 통째로 넣는다. inventory 가 A 와 비교해 센다(A 는 거기가 정본).
 *
 * <p>SKU 온도대를 못 읽으면 그 센터는 보내지 않는다 — 판 SKU 를 빼고 보내면 inventory 가 d = 0 으로 보아 "곧 품절 아님"이 되기 때문(0 으로
 * 채우지 않는다).
 */
@Component
@ConditionalOnProperty(
    name = "pmart.inbound.low-stock-basis-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class LowStockBasisPublisher {

  private static final Logger log = LoggerFactory.getLogger(LowStockBasisPublisher.class);
  static final List<String> STORAGE_TYPES = List.of("AMBIENT", "CHILLED", "FROZEN");

  private final SupplyRecommendationStore store;
  private final InventorySkuClient skus;
  private final InventoryLowStockClient inventory;
  private final Clock clock;

  public LowStockBasisPublisher(
      SupplyRecommendationStore store,
      InventorySkuClient skus,
      InventoryLowStockClient inventory,
      Clock clock) {
    this.store = store;
    this.skus = skus;
    this.inventory = inventory;
    this.clock = clock;
  }

  @Scheduled(
      fixedDelayString = "${pmart.inbound.low-stock-basis-interval:PT1H}",
      initialDelayString = "${pmart.inbound.low-stock-basis-initial-delay:PT1M}")
  void publishAll() {
    for (String mfcId : store.salesCenters()) {
      try {
        publish(mfcId);
      } catch (RuntimeException e) {
        log.warn("곧 품절 기준 보내기 실패(센터 {}) — 다음 주기에 다시: {}", mfcId, e.toString());
      }
    }
  }

  /** 한 센터 계산해서 보내기. 반환 = inventory 가 반영했는가. */
  public boolean publish(String mfcId) {
    Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
    LocalDate through = now.atZone(HandoverSales.SEOUL).toLocalDate().minusDays(1);
    Map<String, Policy> policies = store.policies(mfcId);
    Optional<LocalDate> first = store.firstSaleDate(mfcId);

    List<Map<String, Object>> policyOut = new ArrayList<>();
    Map<String, Integer> historyByType = new HashMap<>();
    int maxWindow = SupplyRecommendations.DEFAULT_WINDOW;
    for (String type : STORAGE_TYPES) {
      Policy p = policies.get(type);
      if (p == null) {
        continue;
      }
      int history =
          SupplyRecommendations.historyDays(first.orElse(null), through, p.averageWindowDays());
      historyByType.put(type, history);
      maxWindow = Math.max(maxWindow, p.averageWindowDays());
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("storageType", type);
      m.put("safetyDays", p.safetyDays());
      m.put("historyDays", history);
      m.put("minHistoryDays", p.minHistoryDays());
      m.put("averageWindowDays", p.averageWindowDays());
      policyOut.add(m);
    }

    Map<String, Map<LocalDate, Long>> sales =
        store.salesAll(mfcId, through.minusDays(maxWindow - 1L), through);
    // 판 SKU 의 온도대 — 실패하면 예외로 이 센터를 건너뛴다(SkuLabels 처럼 빈 값으로 두지 않음)
    Map<String, SkuLabels.Label> labels =
        sales.isEmpty() ? Map.of() : skus.labels(List.copyOf(sales.keySet()));

    List<Map<String, Object>> daily = new ArrayList<>();
    sales.forEach(
        (sku, byDay) -> {
          String type = SkuLabels.get(labels, sku).storageType();
          Policy p = type == null ? null : policies.get(type);
          Integer history = type == null ? null : historyByType.get(type);
          if (p == null || history == null || history == 0) {
            return; // inventory 가 온도대·정책·이력으로 모름 처리
          }
          LocalDate from = through.minusDays(history - 1L);
          long sold =
              byDay.entrySet().stream()
                  .filter(e -> !e.getKey().isBefore(from))
                  .mapToLong(Map.Entry::getValue)
                  .sum();
          if (sold <= 0) {
            return;
          }
          Map<String, Object> m = new LinkedHashMap<>();
          m.put("sku", sku);
          m.put(
              "qty",
              BigDecimal.valueOf(sold)
                  .divide(BigDecimal.valueOf(history), 4, RoundingMode.HALF_UP));
          daily.add(m);
        });

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("calculatedAt", now.toString());
    body.put("salesThrough", through.toString());
    body.put("policies", policyOut);
    body.put("dailyQty", daily);
    boolean applied = inventory.put(mfcId, body);
    log.info(
        "곧 품절 기준 보냄 — 센터 {}, 정책 {}개, 판 SKU {}개, 반영 {}",
        mfcId,
        policyOut.size(),
        daily.size(),
        applied);
    return applied;
  }
}
