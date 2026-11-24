package com.pmart.demo.replenishment.sales;

import java.time.LocalDate;

/** 일별 판매 한 줄(명세 4장 "일별 판매"). unitsSold 는 낱개 관측값. */
public record DailySale(
    String mfcId,
    String skuId,
    LocalDate businessDate,
    long unitsSold,
    AvailabilityStatus status,
    UnknownReason unknownReason,
    String salesBasis,
    String sourceTimezone) {

  public DailySale {
    if (unitsSold < 0) {
      throw new IllegalArgumentException("판매량은 음수일 수 없음: " + skuId + " " + businessDate);
    }
    if ((status == AvailabilityStatus.UNKNOWN) != (unknownReason != null)) {
      throw new IllegalArgumentException("UNKNOWN 에만 사유를 둔다: " + skuId + " " + businessDate);
    }
  }
}
