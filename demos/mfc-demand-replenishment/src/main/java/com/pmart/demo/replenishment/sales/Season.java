package com.pmart.demo.replenishment.sales;

import java.time.LocalDate;

/** 계절: 봄 3~5, 여름 6~8, 가을 9~11, 겨울 12~2. 12월은 다음 해 1~2월과 같은 겨울(명세 F01). */
public enum Season {
  SPRING,
  SUMMER,
  AUTUMN,
  WINTER;

  public static Season of(LocalDate d) {
    return switch (d.getMonthValue()) {
      case 3, 4, 5 -> SPRING;
      case 6, 7, 8 -> SUMMER;
      case 9, 10, 11 -> AUTUMN;
      default -> WINTER;
    };
  }

  /** 예: 2009-12-15 → "2010-WINTER", 2010-02-01 → "2010-WINTER", 2010-07-01 → "2010-SUMMER". */
  public static String key(LocalDate d) {
    int year = d.getMonthValue() == 12 ? d.getYear() + 1 : d.getYear();
    return year + "-" + of(d);
  }
}
