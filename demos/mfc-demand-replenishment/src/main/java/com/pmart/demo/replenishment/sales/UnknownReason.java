package com.pmart.demo.replenishment.sales;

/** {@link AvailabilityStatus#UNKNOWN} 의 사유(명세 4장). */
public enum UnknownReason {
  /** 센터 전체에 유효 판매가 없는 날. */
  NO_CENTER_SALES,
  /** 그 SKU 의 데이터상 첫 판매 전. 출시 전이라고 단정하지 않는다. */
  BEFORE_FIRST_OBSERVED,
  /** 직전 판매일로부터 stale_gap_days 를 넘긴 날(판매가 끊긴 상품). */
  AFTER_LAST_OBSERVED,
  /** 출처가 상태를 주지 않음. */
  SOURCE_UNAVAILABLE
}
