package com.pmart.mfc.inventory.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * 예약 LOT 배정(ADR-0040 결정 4, ADR-0041 상품별 소비기한 보장). 순수 계산.
 *
 * <ul>
 *   <li>적격: LOT 의 가용(E_lot − R_lot) &gt; 0 이고 SKU 의 {@link ShelfRule} 을 지킨다. 기준일은 배송 약속일이다(상품 고시는
 *       "주문일 기준"이고 약속일 ≥ 주문일이라, 약속일로 판정하면 고시 약속을 넘겨 지킨다).
 *       <ul>
 *         <li>소비기한 있는 LOT: {@code 소비기한 − 약속일 ≥ minRemainingDays}. 값이 없으면 판정할 수 없다(호출자가
 *             CONFIG_REQUIRED).
 *         <li>소비기한 없는 LOT(UNTRACKED 포함): 상품이 잔여일을 보장하면 확인할 수 없으므로 배정하지 않는다. 보장이 없으면 소비기한 관리 대상이 아닌
 *             것으로 보고 통과.
 *         <li>제조 후 경과일 보장(maxDaysSinceManufacture)이 있으면 {@code 약속일 − 제조일 ≤ N}. 제조일 모르는 LOT 은 배정하지
 *             않는다.
 *       </ul>
 *   <li>순서: 소비기한 빠른 순(없음은 맨 뒤) → 센터 최초 정상 인수 시각 → lot_id.
 *   <li>줄당 LOT 수 제한 없음. 줄은 요청 순서대로 배정하고, 앞 줄이 쓴 수량은 뒤 줄에서 빠진다.
 *   <li>LOT 지정 줄(IV-9 마감세일, ADR-0090 B2): 그 LOT 의 적격 가용에서만 잡는다(다른 LOT 을 섞지 않음). 지정 줄을 먼저 배정하고 일반 줄은
 *       남은 것에서 FEFO — 일반 줄은 마감 LOT 을 피하지 않는다(PC 3 결정 2026-11-26, 사용자 "추천대로").
 * </ul>
 */
public final class LotAllocator {

  private LotAllocator() {}

  /** 배정 후보 LOT. available = E_lot − R_lot. */
  public record LotCandidate(
      String sku,
      String lotId,
      @Nullable LocalDate expiryDate,
      @Nullable LocalDate manufactureDate,
      @Nullable Instant firstNormalAt,
      int available) {}

  /** 요청 줄. */
  public record LineRequest(
      @Nullable String orderLineId, String sku, int qty, @Nullable String lotId) {

    public LineRequest(@Nullable String orderLineId, String sku, int qty) {
      this(orderLineId, sku, qty, null);
    }
  }

  /** 배정 결과 조각. */
  public record Allocation(@Nullable String orderLineId, String sku, String lotId, int qty) {}

  /** 줄 부족: 요청량과 그 줄에 담을 수 있던 적격 수량. */
  public record LineShortage(
      @Nullable String orderLineId,
      String sku,
      int requestedQty,
      int eligibleQty,
      @Nullable String lotId) {}

  /** 결과. shortages 가 비어 있지 않으면 allocations 는 쓰지 않는다(전체 거절). */
  public record Result(List<Allocation> allocations, List<LineShortage> shortages) {}

  /**
   * SKU 의 소비기한 기준.
   *
   * @param minRemainingDays 소비기한 있는 LOT 의 최소 잔여일. 상품 보장값, 없으면 보관 유형 기본값. null 이면 미설정
   * @param remainingGuaranteed 상품이 잔여일을 보장하는가(소비기한 없는 LOT 을 거른다)
   * @param maxDaysSinceManufacture 상품의 제조 후 경과일 보장. null 이면 없음
   */
  public record ShelfRule(
      @Nullable Integer minRemainingDays,
      boolean remainingGuaranteed,
      @Nullable Integer maxDaysSinceManufacture) {

    /** 상품 보장 없이 보관 유형 기본값만. */
    public static ShelfRule storageDefault(@Nullable Integer minRemainingDays) {
      return new ShelfRule(minRemainingDays, false, null);
    }
  }

  @FunctionalInterface
  public interface Rules {
    ShelfRule of(String sku);
  }

  /** 소비기한이 있는 LOT 이 있는데 최소 잔여일이 미설정인 SKU — 호출자가 CONFIG_REQUIRED 로 거절한다. */
  public static List<String> missingPolicy(List<LotCandidate> lots, Rules rules) {
    return lots.stream()
        .filter(l -> l.expiryDate() != null && l.available() > 0)
        .map(LotCandidate::sku)
        .distinct()
        .filter(s -> rules.of(s).minRemainingDays() == null)
        .sorted()
        .toList();
  }

  public static boolean eligible(LotCandidate l, LocalDate promisedDate, Rules rules) {
    if (l.available() <= 0) {
      return false;
    }
    ShelfRule rule = rules.of(l.sku());
    if (rule.maxDaysSinceManufacture() != null
        && (l.manufactureDate() == null
            || l.manufactureDate()
                .isBefore(promisedDate.minusDays(rule.maxDaysSinceManufacture())))) {
      return false;
    }
    if (l.expiryDate() == null) {
      return !rule.remainingGuaranteed();
    }
    Integer min = rule.minRemainingDays();
    if (min == null) {
      return false;
    }
    return !l.expiryDate().isBefore(promisedDate.plusDays(min));
  }

  static final Comparator<LotCandidate> FEFO =
      Comparator.comparing(
              LotCandidate::expiryDate, Comparator.nullsLast(Comparator.<LocalDate>naturalOrder()))
          .thenComparing(
              LotCandidate::firstNormalAt, Comparator.nullsLast(Comparator.<Instant>naturalOrder()))
          .thenComparing(LotCandidate::lotId);

  public static Result allocate(
      List<LineRequest> lines, List<LotCandidate> lots, LocalDate promisedDate, Rules rules) {
    Map<String, List<LotCandidate>> bySku = new LinkedHashMap<>();
    for (LotCandidate l : lots) {
      if (eligible(l, promisedDate, rules)) {
        bySku.computeIfAbsent(l.sku(), k -> new ArrayList<>()).add(l);
      }
    }
    bySku.values().forEach(v -> v.sort(FEFO));
    Map<String, Integer> used = new LinkedHashMap<>();
    List<Allocation> out = new ArrayList<>();
    List<LineShortage> shortages = new ArrayList<>();
    List<LineRequest> ordered = new ArrayList<>();
    lines.stream().filter(l -> l.lotId() != null).forEach(ordered::add);
    lines.stream().filter(l -> l.lotId() == null).forEach(ordered::add);
    for (LineRequest line : ordered) {
      List<LotCandidate> cands = bySku.getOrDefault(line.sku(), List.of());
      if (line.lotId() != null) {
        cands = cands.stream().filter(c -> c.lotId().equals(line.lotId())).toList();
      }
      int need = line.qty();
      int eligibleQty = 0;
      List<Allocation> mine = new ArrayList<>();
      for (LotCandidate c : cands) {
        int left = c.available() - used.getOrDefault(c.lotId(), 0);
        if (left <= 0) {
          continue;
        }
        eligibleQty += left;
        if (need > 0) {
          int take = Math.min(need, left);
          mine.add(new Allocation(line.orderLineId(), line.sku(), c.lotId(), take));
          need -= take;
        }
      }
      if (need > 0) {
        shortages.add(
            new LineShortage(
                line.orderLineId(), line.sku(), line.qty(), eligibleQty, line.lotId()));
        continue;
      }
      for (Allocation a : mine) {
        used.merge(a.lotId(), a.qty(), Integer::sum);
      }
      out.addAll(mine);
    }
    return new Result(shortages.isEmpty() ? out : List.of(), shortages);
  }
}
