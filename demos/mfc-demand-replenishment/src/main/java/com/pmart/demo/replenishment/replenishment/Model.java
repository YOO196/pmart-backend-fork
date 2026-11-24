package com.pmart.demo.replenishment.replenishment;

import java.time.LocalDateTime;
import java.util.List;

/** 보충 계산 입력 형식(명세 2·4장). 시각은 센터 현지 시각(LocalDateTime). */
public final class Model {

  private Model() {}

  public enum Granularity {
    DAY,
    HOUR
  }

  public enum ProtectionMethod {
    /** 마감·운송·수령창·검수·적치로 계산한 실제 사용 준비 시각. */
    EXACT,
    /** 리드타임 + 검토주기 근사(리드타임이 다섯 요소를 담을 때만). */
    APPROX
  }

  public enum TargetMethod {
    /** S = F + SS. */
    POINT_PLUS_SS,
    /** S = 신규 수요 누적 분위수(보정값이 있어야 함, 없으면 점예측 합계). */
    CUM_QUANTILE
  }

  /** 공급 단계(명세 2장 "입고 예정 I 인정 조건"). */
  public enum SupplyStage {
    RECOMMENDED,
    REQUESTED,
    APPROVED,
    SECURED,
    DEPARTED,
    RECEIVED_READY,
    RECEIVED_PENDING
  }

  public enum SupplyStatus {
    OK,
    ETA_OR_QUALITY_UNKNOWN,
    CANCELLED
  }

  public enum CommitmentStatus {
    OPEN,
    BLOCKED,
    CANCELLED,
    FULFILLED;

    /** 유효 미이행 약속(BLOCKED 도 의무가 유효하면 포함). */
    public boolean counts() {
      return this == OPEN || this == BLOCKED;
    }
  }

  /** 공급 정책 + 계산 스냅샷(proposedReadyAt·protectionEnd 는 회차 일정으로 계산한 시각). */
  public record Policy(
      Granularity granularity,
      ProtectionMethod protectionMethod,
      Integer reviewIntervalDays,
      Integer transferMultiple,
      TargetMethod targetMethod,
      Double targetQuantile,
      Long safetyStock,
      Long calibratedCumulativeQuantile,
      LocalDateTime proposedReadyAt,
      LocalDateTime protectionEnd,
      String policyVersion,
      String sharedCapGroup) {}

  /** 실행 상한. 하나라도 null 이면 INPUT_PENDING. */
  public record Caps(Long cdcAvailable, Long mfcReceivable, Long transportCap) {
    public boolean complete() {
      return cdcAvailable != null && mfcReceivable != null && transportCap != null;
    }

    public long bottleneck() {
      return Math.min(cdcAvailable, Math.min(mfcReceivable, transportCap));
    }
  }

  /** 수량과 시각. */
  public record Timed(long qty, LocalDateTime at) {}

  /**
   * 재고 스냅샷. H 는 고객 예약 빼기 전 적격 현물. expiries 는 H 안에서 시각 at 에 적격을 잃는 양, pending 은 수령했으나 H 에 아직 없는
   * 양 중 at 까지 정상 준비가 확인된 양(P).
   */
  public record Inventory(long onHand, List<Timed> pending, List<Timed> expiries) {}

  public record Commitment(String orderLineId, long openQty, LocalDateTime dueAt, CommitmentStatus status) {}

  /** 같은 supplyId 는 같은 공급 계보(승인·확보·출발이 같은 물량). */
  public record Supply(
      String supplyId, SupplyStage stage, long remainingQty, LocalDateTime readyEta, SupplyStatus status) {}

  /** 예측 구간 [start, end). scope: FULL_DEMAND(확정 주문 포함) 또는 NEW_ONLY. */
  public record ForecastBucket(LocalDateTime start, LocalDateTime end, double qty, String scope) {}

  /** 한 SKU 계산 입력. */
  public record Input(
      String mfcId,
      String skuId,
      LocalDateTime asOf,
      Policy policy,
      Caps caps,
      Inventory inventory,
      List<Commitment> commitments,
      List<Supply> supplies,
      List<ForecastBucket> forecast,
      CalcStatus forecastStatus,
      String forecastModel,
      boolean acceptSecuredSupply,
      String dataMode) {}
}
