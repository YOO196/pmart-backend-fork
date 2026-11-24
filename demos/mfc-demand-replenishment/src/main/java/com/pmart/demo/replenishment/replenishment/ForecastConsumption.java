package com.pmart.demo.replenishment.replenishment;

import com.pmart.demo.replenishment.replenishment.Model.Commitment;
import com.pmart.demo.replenishment.replenishment.Model.ForecastBucket;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 예측 소비(SCM 5.3, 명세 3장 "예측 소비 단위"): F = Σ max(0, 구간 예측 − 그 구간에 due_at 이 있는 약속). 구간별 차감은 그 구간 예측을
 * 넘지 않고, 넘는 확정량은 다른 구간에서 빼지 않는다. 기한이 지난 약속(due_at < 기준시각)은 소비하지 않는다(C 에는 남는다). NEW_ONLY
 * 예측은 빼지 않는다.
 */
public final class ForecastConsumption {

  public static final String FULL_DEMAND = "FULL_DEMAND";
  public static final String NEW_ONLY = "NEW_ONLY";

  /** 약속 한 줄이 어느 구간 예측을 얼마나 소비했는지. */
  public record Consumed(String orderLineId, LocalDateTime bucketStart, double qty) {}

  /** 구간별 신규 수요 F_b. */
  public record BucketDemand(LocalDateTime start, LocalDateTime end, double forecast, double newDemand) {}

  public record Result(double newDemand, List<BucketDemand> buckets, List<Consumed> consumed) {}

  private ForecastConsumption() {}

  public static Result consume(List<ForecastBucket> buckets, List<Commitment> commitments, LocalDateTime asOf) {
    List<Commitment> open =
        commitments.stream()
            .filter(c -> c.status().counts() && c.openQty() > 0 && !c.dueAt().isBefore(asOf))
            .sorted(Comparator.comparing(Commitment::dueAt).thenComparing(Commitment::orderLineId))
            .toList();
    double total = 0;
    List<BucketDemand> out = new ArrayList<>();
    List<Consumed> consumed = new ArrayList<>();
    for (ForecastBucket b : buckets) {
      if (b.qty() < 0) {
        throw new IllegalArgumentException("예측은 음수일 수 없음: " + b.start());
      }
      double remaining = b.qty();
      if (FULL_DEMAND.equals(b.scope())) {
        for (Commitment c : open) {
          if (remaining <= 0) {
            break;
          }
          if (!c.dueAt().isBefore(b.start()) && c.dueAt().isBefore(b.end())) {
            double take = Math.min(remaining, c.openQty());
            remaining -= take;
            consumed.add(new Consumed(c.orderLineId(), b.start(), take));
          }
        }
      } else if (!NEW_ONLY.equals(b.scope())) {
        throw new IllegalArgumentException("forecast_scope 는 FULL_DEMAND 또는 NEW_ONLY: " + b.scope());
      }
      out.add(new BucketDemand(b.start(), b.end(), b.qty(), remaining));
      total += remaining;
    }
    return new Result(total, out, consumed);
  }
}
