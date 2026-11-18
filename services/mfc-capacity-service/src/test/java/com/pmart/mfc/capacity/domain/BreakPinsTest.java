package com.pmart.mfc.capacity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 휴게 번호 해시·형식, 근무 휴게 구간 검사(0.20.0). */
class BreakPinsTest {

  @Test
  void hashIsSaltedAndVerifiesOnlyTheSamePin() {
    String a = BreakPins.hash("2468");
    String b = BreakPins.hash("2468");
    assertThat(a).startsWith("pbkdf2$").doesNotContain("2468").isNotEqualTo(b);
    assertThat(BreakPins.matches("2468", a)).isTrue();
    assertThat(BreakPins.matches("2469", a)).isFalse();
    assertThat(BreakPins.matches("2468", "garbage")).isFalse();
  }

  @Test
  void onlyFourDigits() {
    assertThat(BreakPins.wellFormed("0123")).isTrue();
    assertThat(BreakPins.wellFormed("123")).isFalse();
    assertThat(BreakPins.wellFormed("12a4")).isFalse();
    assertThat(BreakPins.wellFormed(null)).isFalse();
  }

  @Test
  void shiftBreakMustBeBothWithinShift() {
    Instant s = Instant.parse("2026-11-18T00:00:00Z");
    Instant e = Instant.parse("2026-11-18T09:00:00Z");
    assertThat(WorkerRules.invalidShiftBreak(s, e, null, null)).isNull();
    assertThat(WorkerRules.invalidShiftBreak(s, e, s.plusSeconds(3600), null)).isNotNull();
    assertThat(WorkerRules.invalidShiftBreak(s, e, s.plusSeconds(7200), s.plusSeconds(3600)))
        .isNotNull();
    assertThat(WorkerRules.invalidShiftBreak(s, e, s.minusSeconds(60), s.plusSeconds(3600)))
        .isNotNull();
    assertThat(WorkerRules.invalidShiftBreak(s, e, s.plusSeconds(10800), s.plusSeconds(14400)))
        .isNull();
  }
}
