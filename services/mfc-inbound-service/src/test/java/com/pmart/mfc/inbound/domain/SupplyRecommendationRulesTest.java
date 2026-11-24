package com.pmart.mfc.inbound.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.mfc.inbound.domain.SupplyRecommendationRules.Input;
import com.pmart.mfc.inbound.domain.SupplyRecommendationRules.Policy;
import com.pmart.mfc.inbound.domain.SupplyRecommendationRules.Result;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** 발주 수량 추천 산식(MI-4): S = d(R+L)+SS, IP = A + 입고 예정, Q = 배수 올림 ≥ 최소. 값이 없으면 사유. */
class SupplyRecommendationRulesTest {

  static Policy policy(Integer multiple, Integer min) {
    return new Policy("*", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, multiple, min, 28, 7);
  }

  static Input input(Policy p, long sold, int days, Integer available, long expected) {
    return new Input("AMBIENT", p, sold, days, 7, available, true, expected);
  }

  @Test
  void startValuesGiveThreeDaysOfDemandMinusPosition() {
    // d = 280/28 = 10, SS = 10, S = 10×2 + 10 = 30, IP = 8 + 5 = 13 → N 17 → Q 17
    Result r = SupplyRecommendationRules.evaluate(input(policy(1, 1), 280, 28, 8, 5));
    assertThat(r.reason()).isNull();
    assertThat(r.forecastDailyQty()).isEqualTo(10.0);
    assertThat(r.safetyStockQty()).isEqualTo(10.0);
    assertThat(r.targetQty()).isEqualTo(30.0);
    assertThat(r.inventoryPosition()).isEqualTo(13.0);
    assertThat(r.shortfallQty()).isEqualTo(17.0);
    assertThat(r.recommendedQty()).isEqualTo(17);
  }

  @Test
  void roundsUpToMultipleAndMinimumButNotWhenNothingIsNeeded() {
    // d = 1.5, S = 4.5, IP = 0 → N 4.5 → 배수 6 → 6, 최소 10 → 10
    assertThat(
            SupplyRecommendationRules.evaluate(input(policy(6, 1), 15, 10, 0, 0)).recommendedQty())
        .isEqualTo(6);
    assertThat(
            SupplyRecommendationRules.evaluate(input(policy(6, 10), 15, 10, 0, 0)).recommendedQty())
        .isEqualTo(10);
    // 이미 충분하면 0(최소 발주량을 강요하지 않는다)
    Result enough = SupplyRecommendationRules.evaluate(input(policy(6, 10), 15, 10, 100, 0));
    assertThat(enough.recommendedQty()).isZero();
    assertThat(enough.shortfallQty()).isZero();
  }

  @Test
  void missingValuesAreReasonsNotZeros() {
    Policy noLead = new Policy("*", BigDecimal.ONE, null, BigDecimal.ONE, 1, null, 28, 7);
    Result r = SupplyRecommendationRules.evaluate(input(noLead, 70, 7, 3, 0));
    assertThat(r.recommendedQty()).isNull();
    assertThat(r.reason()).isEqualTo(SupplyRecommendationRules.POLICY_MISSING);
    assertThat(r.missingPolicyFields()).containsExactly("leadDays", "minOrderQty");
    assertThat(r.forecastDailyQty()).isEqualTo(10.0); // 근거는 보인다

    assertThat(
            SupplyRecommendationRules.evaluate(new Input("AMBIENT", null, 70, 7, 7, 3, true, 0))
                .reason())
        .isEqualTo(SupplyRecommendationRules.POLICY_MISSING);
    assertThat(SupplyRecommendationRules.evaluate(input(policy(1, 1), 70, 7, null, 0)).reason())
        .isEqualTo(SupplyRecommendationRules.AVAILABILITY_UNKNOWN);
    Result shortHistory = SupplyRecommendationRules.evaluate(input(policy(1, 1), 30, 6, 3, 0));
    assertThat(shortHistory.reason()).isEqualTo(SupplyRecommendationRules.INSUFFICIENT_HISTORY);
    assertThat(shortHistory.forecastDailyQty()).isNull();
    assertThat(
            SupplyRecommendationRules.evaluate(new Input(null, policy(1, 1), 70, 7, 7, 3, true, 0))
                .reason())
        .isEqualTo(SupplyRecommendationRules.STORAGE_TYPE_UNKNOWN);
    assertThat(
            SupplyRecommendationRules.evaluate(
                    new Input("AMBIENT", policy(1, 1), 70, 7, 7, 3, false, 0))
                .reason())
        .isEqualTo(SupplyRecommendationRules.SKU_NOT_HANDLED);
  }

  @Test
  void noSalesWithEnoughHistoryRecommendsNothing() {
    Result r = SupplyRecommendationRules.evaluate(input(policy(1, 1), 0, 28, 0, 0));
    assertThat(r.reason()).isNull();
    assertThat(r.recommendedQty()).isZero();
  }
}
