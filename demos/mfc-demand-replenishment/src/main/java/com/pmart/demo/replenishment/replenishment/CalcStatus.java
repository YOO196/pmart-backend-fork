package com.pmart.demo.replenishment.replenishment;

/** SCM 계산 결과 상태(명세 1장 끝). */
public enum CalcStatus {
  /** 계산 가능. */
  CALCULABLE,
  /** 참고만(대체 모델·근사 등). */
  REFERENCE_ONLY,
  /** 입력 대기. 수량을 비운다. */
  INPUT_PENDING;

  /** 둘 중 더 약한 상태. */
  public CalcStatus weaker(CalcStatus other) {
    return this.ordinal() >= other.ordinal() ? this : other;
  }
}
