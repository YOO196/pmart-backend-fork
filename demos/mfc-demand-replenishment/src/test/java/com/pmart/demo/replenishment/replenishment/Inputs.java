package com.pmart.demo.replenishment.replenishment;

import com.pmart.demo.replenishment.replenishment.Model.Caps;
import com.pmart.demo.replenishment.replenishment.Model.Commitment;
import com.pmart.demo.replenishment.replenishment.Model.CommitmentStatus;
import com.pmart.demo.replenishment.replenishment.Model.ForecastBucket;
import com.pmart.demo.replenishment.replenishment.Model.Granularity;
import com.pmart.demo.replenishment.replenishment.Model.Input;
import com.pmart.demo.replenishment.replenishment.Model.Inventory;
import com.pmart.demo.replenishment.replenishment.Model.Policy;
import com.pmart.demo.replenishment.replenishment.Model.ProtectionMethod;
import com.pmart.demo.replenishment.replenishment.Model.Supply;
import com.pmart.demo.replenishment.replenishment.Model.SupplyStage;
import com.pmart.demo.replenishment.replenishment.Model.SupplyStatus;
import com.pmart.demo.replenishment.replenishment.Model.TargetMethod;
import com.pmart.demo.replenishment.replenishment.Model.Timed;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 시험용 입력 만들기. 기본: 기준 2011-06-01 00:00, 보호기간 3일, 일 단위, 상한 넉넉, m 1, SS 0(명시). */
final class Inputs {

  static final LocalDateTime AS_OF = LocalDateTime.of(2011, 6, 1, 0, 0);

  LocalDateTime asOf = AS_OF;
  Granularity granularity = Granularity.DAY;
  LocalDateTime proposedReadyAt = AS_OF.plusDays(1);
  LocalDateTime protectionEnd = AS_OF.plusDays(3);
  Integer m = 1;
  TargetMethod targetMethod = TargetMethod.POINT_PLUS_SS;
  Long ss = 0L;
  Long calibrated;
  Caps caps = new Caps(1000L, 1000L, 1000L);
  long onHand;
  final List<Timed> pending = new ArrayList<>();
  final List<Timed> expiries = new ArrayList<>();
  final List<Commitment> commitments = new ArrayList<>();
  final List<Supply> supplies = new ArrayList<>();
  final List<ForecastBucket> forecast = new ArrayList<>();
  CalcStatus forecastStatus = CalcStatus.CALCULABLE;

  Inputs h(long qty) {
    onHand = qty;
    return this;
  }

  Inputs commitment(String id, long qty, LocalDateTime due) {
    commitments.add(new Commitment(id, qty, due, CommitmentStatus.OPEN));
    return this;
  }

  Inputs departed(String id, long qty, LocalDateTime ready) {
    supplies.add(new Supply(id, SupplyStage.DEPARTED, qty, ready, SupplyStatus.OK));
    return this;
  }

  /** 보호기간 전체를 한 구간으로, 신규 수요만(NEW_ONLY). */
  Inputs newDemand(double qty) {
    forecast.add(new ForecastBucket(asOf, protectionEnd, qty, ForecastConsumption.NEW_ONLY));
    return this;
  }

  Inputs dailyFull(double... perDay) {
    for (int i = 0; i < perDay.length; i++) {
      forecast.add(
          new ForecastBucket(
              asOf.plusDays(i), asOf.plusDays(i + 1), perDay[i], ForecastConsumption.FULL_DEMAND));
    }
    return this;
  }

  Inputs withM(Integer value) {
    m = value;
    return this;
  }

  Inputs withSs(Integer value) {
    ss = value == null ? null : value.longValue();
    return this;
  }

  Inputs withEnd(LocalDateTime value) {
    protectionEnd = value;
    if (proposedReadyAt.isAfter(value)) {
      proposedReadyAt = value;
    }
    return this;
  }

  Inputs withReady(LocalDateTime value) {
    proposedReadyAt = value;
    return this;
  }

  Inputs caps(Long cdc, Long mfc, Long transport) {
    caps = new Caps(cdc, mfc, transport);
    return this;
  }

  Input build() {
    Policy p =
        new Policy(
            granularity,
            ProtectionMethod.EXACT,
            7,
            m,
            targetMethod,
            targetMethod == TargetMethod.CUM_QUANTILE ? 0.9 : null,
            ss,
            calibrated,
            proposedReadyAt,
            protectionEnd,
            "test-1",
            null);
    return new Input(
        "MFC-T",
        "SKU-T",
        asOf,
        p,
        caps,
        new Inventory(onHand, pending, expiries),
        commitments,
        supplies,
        forecast,
        forecastStatus,
        "TEST",
        true,
        "simulated_inventory");
  }

  Recommendation calc() {
    return ReplenishmentCalculator.calculate(build());
  }
}
