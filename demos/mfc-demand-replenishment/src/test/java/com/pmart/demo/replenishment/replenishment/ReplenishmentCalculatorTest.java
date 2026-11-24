package com.pmart.demo.replenishment.replenishment;

import static com.pmart.demo.replenishment.replenishment.Inputs.AS_OF;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pmart.demo.replenishment.replenishment.Model.Granularity;
import com.pmart.demo.replenishment.replenishment.Model.Supply;
import com.pmart.demo.replenishment.replenishment.Model.SupplyStage;
import com.pmart.demo.replenishment.replenishment.Model.SupplyStatus;
import com.pmart.demo.replenishment.replenishment.Model.TargetMethod;
import com.pmart.demo.replenishment.replenishment.Model.Timed;
import org.junit.jupiter.api.Test;

/** 명세 3장 예시 표(SCM 8.1·9장 수치)와 7장 회귀 사례. */
class ReplenishmentCalculatorTest {

  /** H 18, C 6, I 24, F 72, SS 18 → S 90, IP 36, N 54. m 12, B 48 → W 60, K 48, Q 48, 미충족 6. */
  @Test
  void scmExampleWithCap48() {
    Recommendation r =
        new Inputs()
            .h(18)
            .commitment("L1", 6, AS_OF.plusHours(10))
            .departed("TRF-1", 24, AS_OF.plusDays(1))
            .newDemand(72)
            .caps(48L, 1000L, 1000L)
            .withM(12)
            .withSs(18)
            .calc();
    assertThat(r.target()).isEqualTo(90.0);
    assertThat(r.inventoryPosition()).isEqualTo(36.0);
    assertThat(r.shortage()).isEqualTo(54.0);
    assertThat(r.wanted()).isEqualTo(60L);
    assertThat(r.capByMultiple()).isEqualTo(48L);
    assertThat(r.quantity()).isEqualTo(48L);
    assertThat(r.unmetQty()).isEqualTo(6.0);
    assertThat(r.excessQty()).isEqualTo(0.0);
    assertThat(r.status()).isEqualTo(CalcStatus.CALCULABLE);
  }

  /** 여러 날에 걸친 예측 구간은 경로에서 하루씩 나눠 넣는다(첫날에 몰지 않는다). */
  @Test
  void multiDayBucketIsSpreadInShortagePath() {
    Recommendation r =
        new Inputs()
            .h(18)
            .commitment("L1", 6, AS_OF.plusHours(10))
            .departed("TRF-1", 24, AS_OF.plusDays(1))
            .newDemand(72)
            .caps(48L, 1000L, 1000L)
            .withM(12)
            .withSs(18)
            .calc();
    // 0일: 18 − 6 − 24 = −12. 1일: −24 → −36, 공급 +24 → −12, 추천 +48 → 36. 2일: −24 → 12.
    assertThat(r.firstShortageAt()).isEqualTo(AS_OF);
    assertThat(r.maxShortage()).isEqualTo(36.0);
    assertThat(r.shortageBeforeProposedReady()).isEqualTo(36.0);
    assertThat(r.residualShortageAfterReplenishment()).isEqualTo(0.0);
  }

  /** 같은 N 54, B 10 → K 0, Q 0, 미충족 54(12 로 올려 강제 공급하지 않음). */
  @Test
  void capBelowMultipleGivesZero() {
    Recommendation r =
        new Inputs()
            .h(18)
            .commitment("L1", 6, AS_OF.plusHours(10))
            .departed("TRF-1", 24, AS_OF.plusDays(1))
            .newDemand(72)
            .caps(10L, 1000L, 1000L)
            .withM(12)
            .withSs(18)
            .calc();
    assertThat(r.capByMultiple()).isEqualTo(0L);
    assertThat(r.quantity()).isEqualTo(0L);
    assertThat(r.unmetQty()).isEqualTo(54.0);
  }

  /** N 5, m 6, B 12 → Q 6, 초과 1. */
  @Test
  void multipleExcessIsReported() {
    Recommendation r = new Inputs().newDemand(5).caps(12L, 12L, 12L).withM(6).calc();
    assertThat(r.shortage()).isEqualTo(5.0);
    assertThat(r.quantity()).isEqualTo(6L);
    assertThat(r.excessQty()).isEqualTo(1.0);
    assertThat(r.reasons()).anyMatch(s -> s.startsWith("EXCESS_BY_MULTIPLE"));
  }

  /** H 2, C 5, I 0 → IP −3(0 으로 자르지 않음). */
  @Test
  void negativeInventoryPositionIsKept() {
    Recommendation r = new Inputs().h(2).commitment("L1", 5, AS_OF.plusHours(5)).newDemand(0).calc();
    assertThat(r.inventoryPosition()).isEqualTo(-3.0);
    assertThat(r.shortage()).isEqualTo(3.0);
  }

  /** 남은 전체 예측 20, 그 안의 C 6 → F 14, C 6. */
  @Test
  void forecastConsumptionSingleBucket() {
    Recommendation r =
        new Inputs()
            .withEnd(AS_OF.plusDays(1))
            .withReady(AS_OF.plusHours(12))
            .commitment("L1", 6, AS_OF.plusHours(9))
            .dailyFull(20)
            .calc();
    assertThat(r.newDemand()).isEqualTo(14.0);
    assertThat(r.commitments()).isEqualTo(6L);
  }

  /** 7장 회귀: 1일차 예측 2·확정 8, 2일차 예측 10·확정 0 → F 10, C 8. */
  @Test
  void forecastConsumptionDoesNotBorrowFromOtherDays() {
    Recommendation r =
        new Inputs()
            .withEnd(AS_OF.plusDays(2))
            .commitment("L1", 8, AS_OF.plusHours(15))
            .dailyFull(2, 10)
            .calc();
    assertThat(r.newDemand()).isEqualTo(10.0);
    assertThat(r.commitments()).isEqualTo(8L);
  }

  /** 7장 조기 부족(일 단위): H 5, 오전 C 6, 오후 I 10, F 0, SS 0 → IP 9, Q 0, 같은 날 부족 1·같은 날 회복. */
  @Test
  void earlyShortageDay() {
    Recommendation r = earlyShortage(Granularity.DAY, AS_OF).calc();
    assertThat(r.inventoryPosition()).isEqualTo(9.0);
    assertThat(r.quantity()).isEqualTo(0L);
    assertThat(r.firstShortageAt()).isEqualTo(AS_OF);
    assertThat(r.maxShortage()).isEqualTo(1.0);
    assertThat(r.recoveryAt()).isEqualTo(AS_OF);
    assertThat(r.reasons()).anyMatch(s -> s.startsWith("EARLY_SHORTAGE"));
    assertThat(r.reasons()).anyMatch(s -> s.startsWith("DAY_GRANULARITY"));
  }

  /** 같은 사례(시각 단위, 기준 06:00): 11:00 부족 1, 15:00 회복. */
  @Test
  void earlyShortageHour() {
    Recommendation r = earlyShortage(Granularity.HOUR, AS_OF.plusHours(6)).calc();
    assertThat(r.inventoryPosition()).isEqualTo(9.0);
    assertThat(r.quantity()).isEqualTo(0L);
    assertThat(r.firstShortageAt()).isEqualTo(AS_OF.withHour(11));
    assertThat(r.maxShortage()).isEqualTo(1.0);
    assertThat(r.recoveryAt()).isEqualTo(AS_OF.withHour(15));
    assertThat(r.shortageBeforeProposedReady()).isEqualTo(1.0);
  }

  private static Inputs earlyShortage(Granularity g, java.time.LocalDateTime asOf) {
    Inputs in = new Inputs();
    in.asOf = asOf;
    in.granularity = g;
    in.protectionEnd = AS_OF.plusDays(1);
    in.proposedReadyAt = AS_OF.plusHours(20);
    in.h(5).commitment("L1", 6, AS_OF.withHour(11)).departed("TRF-1", 10, AS_OF.withHour(15));
    in.forecast.add(
        new Model.ForecastBucket(asOf, in.protectionEnd, 0, ForecastConsumption.NEW_ONLY));
    return in;
  }

  /** 보충분이 부족 뒤에 와도 추천분 도착 전 부족과 보충 뒤 잔여 부족을 나눠 낸다. */
  @Test
  void proposalSplitsShortageBeforeAndAfter() {
    Recommendation r =
        new Inputs()
            .withEnd(AS_OF.plusDays(3))
            .withReady(AS_OF.plusDays(2))
            .h(5)
            .dailyFull(10, 10, 10)
            .calc();
    // 경로: 1일 −5, 2일 −15, 3일(추천분 도착 전 수요 먼저) −25 → 추천 Q = 25.
    assertThat(r.quantity()).isEqualTo(25L);
    assertThat(r.firstShortageAt()).isEqualTo(AS_OF);
    assertThat(r.shortageBeforeProposedReady()).isEqualTo(25.0);
    assertThat(r.residualShortageAfterReplenishment()).isEqualTo(0.0);
  }

  @Test
  void missingCapsOrMultipleOrSafetyStockIsInputPending() {
    assertThat(new Inputs().newDemand(5).caps(null, 10L, 10L).calc().status())
        .isEqualTo(CalcStatus.INPUT_PENDING);
    Recommendation noM = new Inputs().newDemand(5).withM(null).calc();
    assertThat(noM.status()).isEqualTo(CalcStatus.INPUT_PENDING);
    assertThat(noM.quantity()).isNull();
    assertThat(noM.wanted()).isNull();
    Recommendation noSs = new Inputs().newDemand(5).withSs(null).calc();
    assertThat(noSs.status()).isEqualTo(CalcStatus.INPUT_PENDING);
    assertThat(noSs.target()).isNull();
  }

  @Test
  void missingForecastIsInputPendingNotZero() {
    Inputs in = new Inputs().h(3);
    in.forecastStatus = CalcStatus.INPUT_PENDING;
    Recommendation r = in.calc();
    assertThat(r.status()).isEqualTo(CalcStatus.INPUT_PENDING);
    assertThat(r.newDemand()).isNull();
    assertThat(r.quantity()).isNull();
    assertThat(r.inventoryPosition()).isEqualTo(3.0);
  }

  @Test
  void forecastMustCoverProtectionPeriod() {
    Recommendation r = new Inputs().dailyFull(1, 1).calc(); // 보호기간 3일인데 2일만
    assertThat(r.status()).isEqualTo(CalcStatus.INPUT_PENDING);
    assertThat(r.reason()).contains("보호기간 끝");
  }

  @Test
  void sufficientStockGivesZero() {
    Recommendation r = new Inputs().h(100).newDemand(30).calc();
    assertThat(r.quantity()).isEqualTo(0L);
    assertThat(r.shortage()).isEqualTo(0.0);
  }

  @Test
  void sameSupplyLineageCountedOnce() {
    Inputs in = new Inputs().newDemand(0);
    in.supplies.add(new Supply("TRF-9", SupplyStage.APPROVED, 24, null, SupplyStatus.OK));
    in.supplies.add(new Supply("TRF-9", SupplyStage.SECURED, 24, AS_OF.plusDays(1), SupplyStatus.OK));
    in.supplies.add(new Supply("TRF-9", SupplyStage.DEPARTED, 24, AS_OF.plusDays(1), SupplyStatus.OK));
    assertThat(in.calc().incoming()).isEqualTo(24L);
  }

  /** 입고 준비시각 경계: 보호기간 끝 시각에 준비되면 포함, 1분 늦으면 제외. */
  @Test
  void supplyReadyAtProtectionEndBoundary() {
    Inputs at = new Inputs().newDemand(0).departed("A", 7, AS_OF.plusDays(3));
    assertThat(at.calc().incoming()).isEqualTo(7L);
    Inputs late = new Inputs().newDemand(0).departed("A", 7, AS_OF.plusDays(3).plusMinutes(1));
    assertThat(late.calc().incoming()).isEqualTo(0L);
  }

  @Test
  void expiringStockLeavesInventoryPosition() {
    Inputs in = new Inputs().h(10).newDemand(0);
    in.expiries.add(new Timed(4, AS_OF.plusDays(1)));
    Recommendation r = in.calc();
    assertThat(r.expiringQty()).isEqualTo(4L);
    assertThat(r.inventoryPosition()).isEqualTo(6.0);
  }

  @Test
  void quantileWithoutCalibrationFallsBackToPointSum() {
    Inputs in = new Inputs().newDemand(40);
    in.targetMethod = TargetMethod.CUM_QUANTILE;
    Recommendation r = in.calc();
    assertThat(r.targetMethod()).isEqualTo("POINT_SUM_FALLBACK");
    assertThat(r.target()).isEqualTo(40.0);
    assertThat(r.status()).isEqualTo(CalcStatus.REFERENCE_ONLY);
  }

  @Test
  void dayGranularityNeedsDayBoundary() {
    Inputs in = new Inputs().newDemand(0);
    in.asOf = AS_OF.plusHours(6);
    in.forecast.clear();
    in.forecast.add(
        new Model.ForecastBucket(in.asOf, in.protectionEnd, 0, ForecastConsumption.NEW_ONLY));
    assertThat(in.calc().status()).isEqualTo(CalcStatus.INPUT_PENDING);
  }

  @Test
  void malformedInputIsRejected() {
    assertThatThrownBy(() -> new Inputs().newDemand(1).withM(0).calc())
        .isInstanceOf(IllegalArgumentException.class);
    Inputs neg = new Inputs().newDemand(1);
    neg.onHand = -1;
    assertThatThrownBy(neg::calc).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void sameInputSameOutput() {
    Recommendation a = new Inputs().h(3).newDemand(17).withM(5).calc();
    Recommendation b = new Inputs().h(3).newDemand(17).withM(5).calc();
    assertThat(a).isEqualTo(b);
    assertThat(a.quantity()).isEqualTo(15L);
  }
}
