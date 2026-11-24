package com.pmart.demo.replenishment.sales;

/** 일별 판매 상태(명세 4장). 판매량 0 인 날도 상태로 구분한다. */
public enum AvailabilityStatus {
  /** 판매 관측됨. */
  NORMAL,
  /** 품절. 관측 판매량은 보존하되 실수요는 미확정. */
  STOCKOUT,
  /** 수집 누락. */
  MISSING,
  /** 출처가 준 출시일 전. UCI 에는 없다. */
  PRE_LAUNCH,
  /** 출처로 확인한 영업 달력의 비영업일. */
  CLOSED_DAY,
  /** 상태 미확인. 사유는 {@link UnknownReason}. */
  UNKNOWN,
  /** UCI 데모 전용: 판매 관측 구간 안의 무판매를 0 으로 가정. 재고 보유를 입증하지 않는다. */
  ZERO_ASSUMED;

  /** 모델 입력·일별 평가에 쓰는 상태(명세 6장 2번). */
  public boolean usableAsDemand() {
    return this == NORMAL || this == ZERO_ASSUMED;
  }
}
