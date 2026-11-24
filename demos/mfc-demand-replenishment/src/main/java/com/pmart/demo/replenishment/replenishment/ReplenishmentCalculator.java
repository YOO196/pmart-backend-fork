package com.pmart.demo.replenishment.replenishment;

import com.pmart.demo.replenishment.replenishment.Model.Commitment;
import com.pmart.demo.replenishment.replenishment.Model.ForecastBucket;
import com.pmart.demo.replenishment.replenishment.Model.Granularity;
import com.pmart.demo.replenishment.replenishment.Model.Input;
import com.pmart.demo.replenishment.replenishment.Model.Policy;
import com.pmart.demo.replenishment.replenishment.Model.ProtectionMethod;
import com.pmart.demo.replenishment.replenishment.Model.Supply;
import com.pmart.demo.replenishment.replenishment.Model.TargetMethod;
import com.pmart.demo.replenishment.replenishment.Model.Timed;
import com.pmart.demo.replenishment.replenishment.ShortagePath.Event;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * CDC → MFC 보충 추천 계산(SCM 4~9장, 명세 3장).
 *
 * <pre>
 * F  = Σ max(0, 구간 예측 − 구간 약속)       (FULL_DEMAND)   / Σ 예측 (NEW_ONLY)
 * S  = F + SS                               (분위수 방식이면 보정된 누적 분위수)
 * IP = H − 적격상실 − C + I + P              (음수 보존)
 * N  = max(0, S − IP)
 * W  = m × ceil(N / m)
 * B  = min(CDC 공급가능량, MFC 추가 수용량, 운송 상한)
 * K  = m × floor(B / m)
 * Q  = min(W, K)
 * </pre>
 *
 * 형식이 틀린 입력(m ≤ 0, 음수 수량, 시각 순서 틀림)은 예외 — 배치는 그 SKU 만 실패로 남긴다. 값이 없는 입력은 INPUT_PENDING 으로
 * 해당 수량을 비운다.
 */
public final class ReplenishmentCalculator {

  private static final double EPS = 1e-9;

  private ReplenishmentCalculator() {}

  public static Recommendation calculate(Input in) {
    Policy p = in.policy();
    validate(in);
    List<String> reasons = new ArrayList<>();
    CalcStatus status = CalcStatus.CALCULABLE;
    LocalDateTime end = p.protectionEnd();

    if (p.protectionMethod() == ProtectionMethod.APPROX) {
      reasons.add("PROTECTION_APPROX: 리드타임+검토주기 근사");
    }
    if (p.granularity() == Granularity.DAY
        && (!p.protectionEnd().toLocalTime().equals(LocalTime.MIDNIGHT)
            || !in.asOf().toLocalTime().equals(LocalTime.MIDNIGHT))) {
      status = CalcStatus.INPUT_PENDING;
      reasons.add("INPUT_PENDING: 일 단위는 기준시각·보호기간 끝이 날짜 경계여야 함");
    }

    // H·적격 상실·P
    long onHand = in.inventory().onHand();
    long expiring = sumWithin(in.inventory().expiries(), in.asOf(), end, false);
    long pending = sumWithin(in.inventory().pending(), in.asOf(), end, true);

    // C: 보호기간 안(기한 지난 것 포함)의 유효 미이행 약속.
    List<Commitment> counted =
        in.commitments().stream()
            .filter(c -> c.status().counts() && c.openQty() > 0 && c.dueAt().isBefore(end))
            .toList();
    long commitments = counted.stream().mapToLong(Commitment::openQty).sum();
    if (counted.stream().anyMatch(c -> c.dueAt().isBefore(in.asOf()))) {
      reasons.add("OVERDUE_COMMITMENTS: 기한 지난 약속을 C 에 유지(처리는 SCM 정본)");
    }

    // I
    SupplyEligibility.Result supply =
        SupplyEligibility.evaluate(in.supplies(), end, in.acceptSecuredSupply());
    if (supply.openIntent()) {
      reasons.add("SUPPLY_INTENT_OPEN: 미승인·승인 미이행 요청 있음 — 중복 요청 확인");
    }

    double ip = onHand - expiring - commitments + supply.incoming() + pending;

    // F: 예측 구간이 [asOf, end) 를 빈틈없이 덮어야 한다.
    Double newDemand = null;
    List<ForecastConsumption.BucketDemand> buckets = List.of();
    String coverage = coverageProblem(in.forecast(), in.asOf(), end);
    if (in.forecastStatus() == CalcStatus.INPUT_PENDING || in.forecast().isEmpty()) {
      status = CalcStatus.INPUT_PENDING;
      reasons.add("INPUT_PENDING: 예측 없음(0 으로 채우지 않음)");
    } else if (coverage != null) {
      status = CalcStatus.INPUT_PENDING;
      reasons.add("INPUT_PENDING: " + coverage);
    } else {
      ForecastConsumption.Result fc =
          ForecastConsumption.consume(in.forecast(), in.commitments(), in.asOf());
      newDemand = fc.newDemand();
      buckets = fc.buckets();
      status = status.weaker(in.forecastStatus());
      if (in.forecastStatus() == CalcStatus.REFERENCE_ONLY) {
        reasons.add("REFERENCE_ONLY: 대체 예측 모델 사용");
      }
    }

    // S
    Double target = null;
    String targetMethod = p.targetMethod().name();
    if (newDemand != null) {
      if (p.targetMethod() == TargetMethod.POINT_PLUS_SS) {
        if (p.safetyStock() == null) {
          status = CalcStatus.INPUT_PENDING;
          reasons.add("INPUT_PENDING: 안전량 SS 미설정(0 으로 두지 않음)");
        } else {
          target = newDemand + p.safetyStock();
        }
      } else if (p.calibratedCumulativeQuantile() != null) {
        target = p.calibratedCumulativeQuantile().doubleValue();
        targetMethod = "CUM_QUANTILE_CALIBRATED";
      } else {
        target = newDemand;
        targetMethod = "POINT_SUM_FALLBACK";
        reasons.add("QUANTILE_FALLBACK: 누적 분위수 보정값 없음 — 점예측 합계 사용");
        status = status.weaker(CalcStatus.REFERENCE_ONLY);
      }
    }

    // N, W, B, K, Q
    Double shortage = target == null ? null : Math.max(0, target - ip);
    Integer m = p.transferMultiple();
    Long wanted = null;
    Long bottleneck = null;
    Long capByMultiple = null;
    Long quantity = null;
    Double unmet = null;
    Double excess = null;
    if (m == null) {
      status = CalcStatus.INPUT_PENDING;
      reasons.add("INPUT_PENDING: 운송 배수 m 미설정");
    }
    if (!in.caps().complete()) {
      status = CalcStatus.INPUT_PENDING;
      reasons.add("INPUT_PENDING: 실행 상한 B 의 값 없음(무제한으로 두지 않음)");
    } else {
      bottleneck = in.caps().bottleneck();
    }
    if (shortage != null && m != null) {
      wanted = shortage <= EPS ? 0L : (long) m * (long) Math.ceil(shortage / m - EPS);
    }
    if (bottleneck != null && m != null) {
      capByMultiple = (long) m * Math.floorDiv(Math.max(0, bottleneck), m);
    }
    if (wanted != null && capByMultiple != null && status != CalcStatus.INPUT_PENDING) {
      quantity = Math.min(wanted, capByMultiple);
      unmet = Math.max(0, shortage - quantity);
      excess = Math.max(0, quantity - shortage);
      if (unmet > EPS) {
        reasons.add("UNMET_DEMAND: 실행 상한으로 원수요 미충족");
      }
      if (excess > EPS) {
        reasons.add("EXCESS_BY_MULTIPLE: 배수로 원수요 초과 — 기한·보관 정책 재검토");
      }
    }
    if (status == CalcStatus.INPUT_PENDING) {
      quantity = null;
      unmet = null;
      excess = null;
    }

    // 시간대별 부족 경로
    List<Event> events = new ArrayList<>();
    for (Timed t : in.inventory().expiries()) {
      if (t.at().isBefore(end)) {
        events.add(new Event(max(t.at(), in.asOf()), -t.qty(), "EXPIRY", false));
      }
    }
    for (Commitment c : counted) {
      events.add(new Event(max(c.dueAt(), in.asOf()), -c.openQty(), "COMMITMENT", false));
    }
    for (ForecastConsumption.BucketDemand b : buckets) {
      if (b.newDemand() > 0) {
        events.addAll(spread(b, p.granularity()));
      }
    }
    for (Supply s : supply.counted()) {
      events.add(new Event(s.readyEta(), s.remainingQty(), "SUPPLY", false));
    }
    for (Timed t : in.inventory().pending()) {
      if (!t.at().isAfter(end)) {
        events.add(new Event(max(t.at(), in.asOf()), t.qty(), "PENDING_READY", false));
      }
    }
    events.add(
        new Event(p.proposedReadyAt(), quantity == null ? 0 : quantity, "PROPOSAL", true));
    ShortagePath.Result path =
        ShortagePath.compute(in.asOf(), onHand, events, p.granularity());
    if (path.firstShortageAt() != null) {
      reasons.add("EARLY_SHORTAGE: " + path.firstShortageAt() + " 부족 " + fmt(path.maxShortage()));
      if (p.granularity() == Granularity.DAY) {
        reasons.add("DAY_GRANULARITY: 하루 안의 부족 시간은 알 수 없음");
      }
    }
    if (newDemand == null) {
      reasons.add("PATH_WITHOUT_FORECAST: 부족 경로는 확정 약속·공급만 반영");
    }
    reasons.add("MULTI_ITEM_LOAD_NOT_VERIFIED: 합계 중량·부피·온도 구획은 데모 범위 밖");

    return new Recommendation(
        in.asOf(),
        in.mfcId(),
        in.skuId(),
        p.proposedReadyAt(),
        end,
        p.protectionMethod().name(),
        p.granularity().name(),
        p.policyVersion(),
        targetMethod,
        in.forecastModel(),
        onHand,
        expiring,
        commitments,
        supply.incoming(),
        pending,
        newDemand,
        target,
        ip,
        shortage,
        m,
        wanted,
        bottleneck,
        capByMultiple,
        quantity,
        unmet,
        excess,
        path.firstShortageAt(),
        path.maxShortage(),
        path.recoveryAt(),
        path.shortageBeforeProposedReady(),
        path.residualShortageAfterReplenishment(),
        p.sharedCapGroup(),
        status,
        List.copyOf(reasons),
        in.dataMode());
  }

  private static void validate(Input in) {
    Policy p = in.policy();
    if (p.protectionEnd() == null || !p.protectionEnd().isAfter(in.asOf())) {
      throw new IllegalArgumentException("보호기간 끝은 기준시각 뒤여야 함");
    }
    if (p.proposedReadyAt() == null
        || p.proposedReadyAt().isBefore(in.asOf())
        || p.proposedReadyAt().isAfter(p.protectionEnd())) {
      throw new IllegalArgumentException("proposed_ready_at 은 기준시각 ~ 보호기간 끝 사이여야 함");
    }
    if (p.transferMultiple() != null && p.transferMultiple() <= 0) {
      throw new IllegalArgumentException("운송 배수 m 은 양의 정수: " + p.transferMultiple());
    }
    if (p.safetyStock() != null && p.safetyStock() < 0) {
      throw new IllegalArgumentException("안전량은 음수일 수 없음");
    }
    if (in.inventory().onHand() < 0) {
      throw new IllegalArgumentException("H 는 음수일 수 없음");
    }
    for (Timed t : in.inventory().expiries()) {
      if (t.qty() < 0) {
        throw new IllegalArgumentException("적격 상실 수량은 음수일 수 없음");
      }
    }
    for (Timed t : in.inventory().pending()) {
      if (t.qty() < 0) {
        throw new IllegalArgumentException("준비 대기 수량은 음수일 수 없음");
      }
    }
    for (Commitment c : in.commitments()) {
      if (c.openQty() < 0) {
        throw new IllegalArgumentException("약속 수량은 음수일 수 없음: " + c.orderLineId());
      }
    }
    Model.Caps caps = in.caps();
    for (Long v : new Long[] {caps.cdcAvailable(), caps.mfcReceivable(), caps.transportCap()}) {
      if (v != null && v < 0) {
        throw new IllegalArgumentException("실행 상한은 음수일 수 없음");
      }
    }
  }

  /** 구간이 [asOf, end) 를 빈틈·겹침 없이 덮는지. 문제가 없으면 null. */
  static String coverageProblem(List<ForecastBucket> buckets, LocalDateTime asOf, LocalDateTime end) {
    if (buckets.isEmpty()) {
      return "예측 구간 없음";
    }
    List<ForecastBucket> sorted =
        buckets.stream().sorted(Comparator.comparing(ForecastBucket::start)).toList();
    LocalDateTime cursor = asOf;
    for (ForecastBucket b : sorted) {
      if (!b.start().equals(cursor) || !b.end().isAfter(b.start())) {
        return "예측 구간이 보호기간을 빈틈없이 덮지 않음(" + cursor + " 에서 " + b.start() + ")";
      }
      cursor = b.end();
    }
    if (!cursor.equals(end)) {
      return "예측 기간이 보호기간 끝(" + end + ")과 맞지 않음(" + cursor + ")";
    }
    return null;
  }

  private static long sumWithin(List<Timed> list, LocalDateTime asOf, LocalDateTime end, boolean endInclusive) {
    long s = 0;
    for (Timed t : list) {
      boolean inside = endInclusive ? !t.at().isAfter(end) : t.at().isBefore(end);
      if (inside) {
        s += t.qty();
      }
    }
    return s;
  }

  /**
   * 여러 날(HOUR 면 여러 시간)에 걸친 예측 구간의 신규 수요를 경로용으로 하루(한 시간) 조각에 시간 비례로 나눈다. 각 조각의 수요는 조각
   * 시작 시각에 둔다(같은 조각 안에서는 수요 먼저).
   */
  static List<Event> spread(ForecastConsumption.BucketDemand b, Granularity g) {
    List<Event> out = new ArrayList<>();
    long total = java.time.Duration.between(b.start(), b.end()).toMinutes();
    LocalDateTime cursor = b.start();
    while (cursor.isBefore(b.end())) {
      LocalDateTime next =
          g == Granularity.DAY ? cursor.toLocalDate().plusDays(1).atStartOfDay() : cursor.withMinute(0).plusHours(1);
      if (next.isAfter(b.end())) {
        next = b.end();
      }
      double share = (double) java.time.Duration.between(cursor, next).toMinutes() / total;
      out.add(new Event(cursor, -b.newDemand() * share, "FORECAST", false));
      cursor = next;
    }
    return out;
  }

  private static LocalDateTime max(LocalDateTime a, LocalDateTime b) {
    return a.isAfter(b) ? a : b;
  }

  static String fmt(double v) {
    return v == Math.rint(v) ? Long.toString((long) v) : String.format("%.3f", v);
  }
}
