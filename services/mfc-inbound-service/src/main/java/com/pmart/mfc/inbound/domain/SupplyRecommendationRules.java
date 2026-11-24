package com.pmart.mfc.inbound.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * 발주 수량 추천 산식(요청 MI-4, 원문 1장 6-3 — 데모 #411 의 계산을 서비스로). 값이 없으면 추천하지 않고 사유를 준다(0·무제한으로 채우지 않는다).
 *
 * <pre>
 * d  = 인계 실적 합 ÷ historyDays           (historyDays &lt; 최소 이력이면 INSUFFICIENT_HISTORY)
 * SS = d × safetyDays
 * S  = d × (R + L) + SS
 * IP = A(판매 가능, 예약 차감) + 입고 예정
 * N  = max(0, S − IP)
 * Q  = m × ceil(N / m), Q &gt; 0 이면 Q ≥ 최소 발주량
 * </pre>
 */
public final class SupplyRecommendationRules {

  public static final String POLICY_MISSING = "POLICY_MISSING";
  public static final String STORAGE_TYPE_UNKNOWN = "STORAGE_TYPE_UNKNOWN";
  public static final String INSUFFICIENT_HISTORY = "INSUFFICIENT_HISTORY";
  public static final String AVAILABILITY_UNKNOWN = "AVAILABILITY_UNKNOWN";
  public static final String SKU_NOT_HANDLED = "SKU_NOT_HANDLED";

  /** 정책 줄 하나(센터 줄 또는 전 센터 기본 '*'). 빈 값 = 정하지 않음. */
  public record Policy(
      String source,
      @Nullable BigDecimal reviewDays,
      @Nullable BigDecimal leadDays,
      @Nullable BigDecimal safetyDays,
      @Nullable Integer orderMultiple,
      @Nullable Integer minOrderQty,
      int averageWindowDays,
      int minHistoryDays) {

    public List<String> missing() {
      List<String> out = new ArrayList<>();
      if (reviewDays == null) {
        out.add("reviewDays");
      }
      if (leadDays == null) {
        out.add("leadDays");
      }
      if (safetyDays == null) {
        out.add("safetyDays");
      }
      if (orderMultiple == null) {
        out.add("orderMultiple");
      }
      if (minOrderQty == null) {
        out.add("minOrderQty");
      }
      return out;
    }
  }

  /**
   * @param storageType SKU 온도대(모르면 null)
   * @param policy 그 센터·온도대 정책(줄이 없으면 null)
   * @param soldQty historyDays 동안의 인계 실적 합
   * @param historyDays d 를 낸 날 수(센터 첫 인계일부터 어제까지, 이동평균 기간 이하)
   * @param minHistoryDays 최소 이력(정책 줄이 없으면 기본값)
   * @param available 판매 가능 A(모르면 null)
   * @param handlingEnabled 센터 취급(모르면 null — 판정하지 않음)
   * @param expected 입고 예정 수량
   */
  public record Input(
      @Nullable String storageType,
      @Nullable Policy policy,
      long soldQty,
      int historyDays,
      int minHistoryDays,
      @Nullable Integer available,
      @Nullable Boolean handlingEnabled,
      long expected) {}

  public record Result(
      @Nullable Integer recommendedQty,
      @Nullable String reason,
      List<String> missingPolicyFields,
      @Nullable Double forecastDailyQty,
      @Nullable Double inventoryPosition,
      @Nullable Double safetyStockQty,
      @Nullable Double targetQty,
      @Nullable Double shortfallQty) {}

  private static final double EPS = 1e-9;

  private SupplyRecommendationRules() {}

  public static Result evaluate(Input in) {
    Double d =
        in.historyDays() >= in.minHistoryDays() && in.historyDays() > 0
            ? (double) in.soldQty() / in.historyDays()
            : null;
    Double ip = in.available() == null ? null : (double) in.available() + in.expected();
    Policy p = in.policy();
    List<String> missing =
        p == null
            ? List.of("reviewDays", "leadDays", "safetyDays", "orderMultiple", "minOrderQty")
            : p.missing();

    String reason = null;
    if (Boolean.FALSE.equals(in.handlingEnabled())) {
      reason = SKU_NOT_HANDLED;
    } else if (in.storageType() == null) {
      reason = STORAGE_TYPE_UNKNOWN;
    } else if (!missing.isEmpty()) {
      reason = POLICY_MISSING;
    } else if (ip == null) {
      reason = AVAILABILITY_UNKNOWN;
    } else if (d == null) {
      reason = INSUFFICIENT_HISTORY;
    }
    if (reason != null || p == null || d == null || ip == null) {
      return new Result(
          null,
          reason,
          in.storageType() == null ? List.of() : missing,
          round(d),
          round(ip),
          null,
          null,
          null);
    }
    double ss = d * p.safetyDays().doubleValue();
    double s = d * (p.reviewDays().doubleValue() + p.leadDays().doubleValue()) + ss;
    double n = Math.max(0, s - ip);
    int q = 0;
    if (n > EPS) {
      int m = p.orderMultiple();
      q = (int) (m * Math.ceil(n / m - EPS));
      q = Math.max(q, p.minOrderQty());
    }
    return new Result(q, null, List.of(), round(d), round(ip), round(ss), round(s), round(n));
  }

  private static @Nullable Double round(@Nullable Double v) {
    return v == null ? null : Math.round(v * 100) / 100.0;
  }
}
