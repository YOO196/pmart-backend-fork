package com.pmart.mfc.capacity.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** 작업자 배정 적격(WK-02 강제 제약 중 이 서비스가 아는 것). 순수 계산. 계정(workerId)과 업무(작업 유형·온도대)는 따로 — 업무는 버전으로 바뀐다. */
public final class WorkerRules {

  private WorkerRules() {}

  public static final String WORKER_NOT_REGISTERED = "WORKER_NOT_REGISTERED";
  public static final String WORKER_NOT_ACTIVE = "WORKER_NOT_ACTIVE";
  public static final String DUTY_NOT_ALLOWED = "DUTY_NOT_ALLOWED";
  public static final String ZONE_NOT_QUALIFIED = "ZONE_NOT_QUALIFIED";
  public static final String OFF_SHIFT = "OFF_SHIFT";
  public static final String DAILY_NOT_ENROLLED = "DAILY_NOT_ENROLLED";
  public static final String DUTY_OUTSIDE_QUALIFICATION = "DUTY_OUTSIDE_QUALIFICATION";
  public static final String NOT_DAILY_CREW = "NOT_DAILY_CREW";
  public static final Set<String> EMPLOYMENT_TYPES = Set.of("REGULAR", "PART_TIME", "DAILY");

  /** 일일크루 기본 자격(사용자 결정 2026-11-08 — MFC 는 온도대 자격 없음). */
  public static final Set<String> DAILY_DEFAULT_QUALIFICATION = Set.of("PICK", "PACK");

  public static final String SHIFT_OVERLAP = "SHIFT_OVERLAP";

  public static final Set<String> TASK_TYPES =
      Set.of(
          "PICK",
          "PACK",
          "RETURN",
          "DISPATCH",
          "RECEIVE",
          "INSPECT",
          "PUTAWAY",
          "REPLENISH",
          "MOVE",
          // (0.24.0, BR-52 R2) 회수품 반입·회수품 검수·재고 실사 — 다음 작업 받기가 mfc-inbound·mfc-inventory 에서 가져온다
          "RETURN_RECEIPT",
          "RETURN_INSPECT",
          "CYCLE_COUNT");
  public static final Set<String> ZONES = Set.of("AMBIENT", "CHILLED", "FROZEN");
  public static final Duration MAX_SHIFT = Duration.ofHours(16);

  /** 지금 업무. */
  public record Duty(int version, Set<String> taskTypes, Set<String> zones) {}

  /** 배정 판정에 필요한 작업자 사본. null = 등록 안 됨. */
  public record Profile(String state, Duty duty) {}

  /**
   * @return 거절 코드, 적격이면 null
   */
  public static @Nullable String rejection(
      @Nullable Profile p, String taskType, @Nullable String temperatureZone) {
    if (p == null) {
      return WORKER_NOT_REGISTERED;
    }
    if (!"ACTIVE".equals(p.state())) {
      return WORKER_NOT_ACTIVE;
    }
    if (!p.duty().taskTypes().contains(taskType)) {
      return DUTY_NOT_ALLOWED;
    }
    // MFC 는 온도대 출입 자격이 없다(사용자 2026-11-08 — 피킹 때 냉동·냉장·상온 자유, 구분은 CDC 만). temperatureZone 은 받기만 한다
    return null;
  }

  /** 근무가 하나라도 등록돼 있는데 지금이 어느 근무에도 없으면 OFF_SHIFT(막지 않는 경고 — Q-09 미결). 근무 등록이 없으면 판단하지 않는다. */
  public static boolean offShift(List<Instant[]> plannedShifts, Instant now) {
    if (plannedShifts.isEmpty()) {
      return false;
    }
    return plannedShifts.stream().noneMatch(s -> !now.isBefore(s[0]) && now.isBefore(s[1]));
  }

  /** 업무 본문 검사. @return 위반 설명, 없으면 null */
  public static @Nullable String invalidDuty(Set<String> taskTypes, Set<String> zones) {
    if (taskTypes.isEmpty() || !TASK_TYPES.containsAll(taskTypes)) {
      return "작업 유형은 " + TASK_TYPES + " 중 하나 이상입니다.";
    }
    if (!ZONES.containsAll(zones)) {
      return "온도대는 " + ZONES + " 중에서입니다(MFC 배정 조건은 아님 — 기록만).";
    }
    return null;
  }

  /** 근무 범위 검사. @return 위반 설명, 없으면 null */
  public static @Nullable String invalidShift(Instant start, Instant end) {
    if (!end.isAfter(start)) {
      return "근무 끝은 시작보다 뒤여야 합니다.";
    }
    if (Duration.between(start, end).compareTo(MAX_SHIFT) > 0) {
      return "근무 하나는 최대 " + MAX_SHIFT.toHours() + "시간입니다.";
    }
    return null;
  }

  /** 근무 휴게 구간(0.20.0): 둘 다 있거나 둘 다 없음, 시작 < 끝, 근무 안. */
  public static @Nullable String invalidShiftBreak(
      Instant start, Instant end, @Nullable Instant breakStart, @Nullable Instant breakEnd) {
    if (breakStart == null && breakEnd == null) {
      return null;
    }
    if (breakStart == null || breakEnd == null) {
      return "휴게 시작과 끝을 같이 주세요.";
    }
    if (!breakEnd.isAfter(breakStart)) {
      return "휴게 끝은 시작보다 뒤여야 합니다.";
    }
    if (breakStart.isBefore(start) || breakEnd.isAfter(end)) {
      return "휴게 구간은 근무 안이어야 합니다.";
    }
    return null;
  }
}
