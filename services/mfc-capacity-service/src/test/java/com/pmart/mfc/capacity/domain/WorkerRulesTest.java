package com.pmart.mfc.capacity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorkerRulesTest {

  static final WorkerRules.Profile PICKER =
      new WorkerRules.Profile(
          "ACTIVE", new WorkerRules.Duty(1, Set.of("PICK"), Set.of("AMBIENT", "CHILLED")));

  @Test
  void hardConstraintsInOrder() {
    assertThat(WorkerRules.rejection(null, "PICK", null))
        .isEqualTo(WorkerRules.WORKER_NOT_REGISTERED);
    assertThat(
            WorkerRules.rejection(
                new WorkerRules.Profile("SUSPENDED", PICKER.duty()), "PICK", null))
        .isEqualTo(WorkerRules.WORKER_NOT_ACTIVE);
    assertThat(WorkerRules.rejection(PICKER, "PACK", null)).isEqualTo(WorkerRules.DUTY_NOT_ALLOWED);
    // MFC 는 온도대 출입 자격이 없다(사용자 2026-11-08) — 냉동도 거절하지 않음
    assertThat(WorkerRules.rejection(PICKER, "PICK", "FROZEN")).isNull();
    assertThat(WorkerRules.rejection(PICKER, "PICK", "CHILLED")).isNull();
    assertThat(WorkerRules.rejection(PICKER, "PICK", null)).isNull();
  }

  /** (0.27.0) 격리 이동은 업무에 없어도 배정 가능 — 등록·ACTIVE 만 본다. 업무에는 넣을 수 없다. */
  @Test
  void quarantineMoveSkipsDutyButNotRegistrationOrState() {
    assertThat(WorkerRules.rejection(PICKER, "QUARANTINE_MOVE", null)).isNull();
    assertThat(WorkerRules.rejection(null, "QUARANTINE_MOVE", null))
        .isEqualTo(WorkerRules.WORKER_NOT_REGISTERED);
    assertThat(
            WorkerRules.rejection(
                new WorkerRules.Profile("LEFT", PICKER.duty()), "QUARANTINE_MOVE", null))
        .isEqualTo(WorkerRules.WORKER_NOT_ACTIVE);
    assertThat(WorkerRules.ASSIGNABLE_TASK_TYPES).contains("QUARANTINE_MOVE", "PICK");
    assertThat(WorkerRules.TASK_TYPES).doesNotContain("QUARANTINE_MOVE");
    assertThat(WorkerRules.invalidDuty(Set.of("QUARANTINE_MOVE"), Set.of())).isNotNull();
  }

  @Test
  void offShiftOnlyWhenShiftsAreUsed() {
    Instant now = Instant.parse("2026-11-08T03:00:00Z");
    assertThat(WorkerRules.offShift(List.of(), now)).isFalse();
    Instant[] morning = {
      Instant.parse("2026-11-08T00:00:00Z"), Instant.parse("2026-11-08T03:00:00Z")
    };
    assertThat(WorkerRules.offShift(List.<Instant[]>of(morning), now)).isTrue(); // 끝 시각은 근무 밖
    Instant[] day = {Instant.parse("2026-11-08T01:00:00Z"), Instant.parse("2026-11-08T09:00:00Z")};
    assertThat(WorkerRules.offShift(List.<Instant[]>of(morning, day), now)).isFalse();
  }

  @Test
  void dutyAndShiftValidation() {
    assertThat(WorkerRules.invalidDuty(Set.of(), Set.of("AMBIENT"))).isNotNull();
    assertThat(WorkerRules.invalidDuty(Set.of("PICK"), Set.of("HOT"))).isNotNull();
    assertThat(WorkerRules.invalidDuty(Set.of("PICK", "RETURN"), Set.of("FROZEN"))).isNull();
    Instant s = Instant.parse("2026-11-08T00:00:00Z");
    assertThat(WorkerRules.invalidShift(s, s)).isNotNull();
    assertThat(WorkerRules.invalidShift(s, s.plusSeconds(17 * 3600))).isNotNull();
    assertThat(WorkerRules.invalidShift(s, s.plusSeconds(8 * 3600))).isNull();
  }
}
