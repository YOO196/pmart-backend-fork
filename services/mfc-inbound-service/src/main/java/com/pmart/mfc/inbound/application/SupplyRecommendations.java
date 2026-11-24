package com.pmart.mfc.inbound.application;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.inbound.domain.SupplyRecommendationRules;
import com.pmart.mfc.inbound.domain.SupplyRecommendationRules.Policy;
import com.pmart.mfc.inbound.infrastructure.InventoryAvailabilityClient;
import com.pmart.mfc.inbound.infrastructure.SupplyRecommendationStore;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * 발주 수량 추천 조회(요청 MI-4 — 사용자 결정 "B", SCM 전 임시). 저장하지 않고 부를 때마다 계산한다. 예측 d 는 이 서비스가 모은 인계 실적 ({@link
 * HandoverSales})의 이동평균, 판매 가능 A 와 센터 취급은 mfc-inventory, 온도대는 SKU 사본, 입고 예정·승인 전 요청은 이 서비스 표. 산식은
 * {@link SupplyRecommendationRules}.
 */
@Service
public class SupplyRecommendations {

  static final int MAX_SKUS = 100;

  /** 정책 줄이 없을 때 d 를 보여 주는 기본 기간(추천은 하지 않는다). */
  static final int DEFAULT_WINDOW = 28;

  static final int DEFAULT_MIN_HISTORY = 7;

  public record Item(
      String sku,
      @Nullable String productName,
      @Nullable String storageType,
      SupplyRecommendationRules.Result result,
      int historyDays,
      @Nullable Integer availableQty,
      long expectedQty,
      long pendingRequestedQty,
      @Nullable Policy policy) {}

  public record View(
      String mfcId,
      Instant calculatedAt,
      @Nullable Instant availabilityAsOf,
      LocalDate salesThrough,
      List<Item> items) {}

  private final SupplyRecommendationStore store;
  private final InventoryAvailabilityClient availability;
  private final SkuLabels labels;
  private final Clock clock;

  public SupplyRecommendations(
      SupplyRecommendationStore store,
      InventoryAvailabilityClient availability,
      SkuLabels labels,
      Clock clock) {
    this.store = store;
    this.availability = availability;
    this.labels = labels;
    this.clock = clock;
  }

  public View recommend(String mfcId, @Nullable String storageType, List<String> skuList) {
    InboundTaskService.requireOperator(mfcId);
    List<String> skus = List.copyOf(new LinkedHashSet<>(skuList == null ? List.of() : skuList));
    if (skus.isEmpty() || skus.size() > MAX_SKUS) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "SKU 는 1~" + MAX_SKUS + "개입니다.");
    }
    Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
    LocalDate through = now.atZone(HandoverSales.SEOUL).toLocalDate().minusDays(1);

    Map<String, SkuLabels.Label> known = labels.of(skus);
    List<String> picked = new ArrayList<>();
    for (String sku : skus) {
      String type = SkuLabels.get(known, sku).storageType();
      if (storageType == null || type == null || storageType.equals(type)) {
        picked.add(sku);
      }
    }
    if (picked.isEmpty()) {
      return new View(mfcId, now, null, through, List.of());
    }

    Map<String, Policy> policies = store.policies(mfcId);
    int maxWindow =
        policies.values().stream().mapToInt(Policy::averageWindowDays).max().orElse(DEFAULT_WINDOW);
    maxWindow = Math.max(maxWindow, DEFAULT_WINDOW);
    Optional<LocalDate> first = store.firstSaleDate(mfcId);
    Map<String, Map<LocalDate, Long>> sales = new HashMap<>();
    store
        .sales(mfcId, picked, through.minusDays(maxWindow - 1L), through)
        .forEach(
            s ->
                sales
                    .computeIfAbsent(s.sku(), k -> new HashMap<>())
                    .merge(s.businessDate(), s.qty(), Long::sum));
    Map<String, Long> expected = store.expected(mfcId, picked);
    Map<String, Long> pending = store.pendingRequested(mfcId, picked);
    InventoryAvailabilityClient.Result avail = availability.availability(mfcId, picked);

    List<Item> items = new ArrayList<>();
    for (String sku : picked) {
      SkuLabels.Label label = SkuLabels.get(known, sku);
      Policy policy = label.storageType() == null ? null : policies.get(label.storageType());
      int window = policy == null ? DEFAULT_WINDOW : policy.averageWindowDays();
      int minHistory = policy == null ? DEFAULT_MIN_HISTORY : policy.minHistoryDays();
      int historyDays = historyDays(first.orElse(null), through, window);
      LocalDate from = through.minusDays(historyDays - 1L);
      long sold =
          sales.getOrDefault(sku, Map.of()).entrySet().stream()
              .filter(e -> !e.getKey().isBefore(from))
              .mapToLong(Map.Entry::getValue)
              .sum();
      InventoryAvailabilityClient.Item a = avail.items().get(sku);
      Integer available = a == null ? null : a.available();
      long exp = expected.getOrDefault(sku, 0L);
      var result =
          SupplyRecommendationRules.evaluate(
              new SupplyRecommendationRules.Input(
                  label.storageType(),
                  policy,
                  sold,
                  historyDays,
                  minHistory,
                  available,
                  a == null ? null : a.handlingEnabled(),
                  exp));
      items.add(
          new Item(
              sku,
              label.productName(),
              label.storageType(),
              result,
              historyDays,
              available,
              exp,
              pending.getOrDefault(sku, 0L),
              policy));
    }
    return new View(mfcId, now, avail.asOf(), through, items);
  }

  /** min(기간, 센터 첫 인계일부터 어제까지 날 수) — 인계가 한 번도 없으면 0. */
  static int historyDays(@Nullable LocalDate first, LocalDate through, int window) {
    if (first == null || first.isAfter(through)) {
      return 0;
    }
    long span = ChronoUnit.DAYS.between(first, through) + 1;
    return (int) Math.min(window, span);
  }
}
