package com.pmart.mfc.capacity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.mfc.capacity.domain.PlanProblem;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 규칙 기반 첫 해(엔진 힌트·시간 안에 해가 없을 때 대체, 2026-11-09) — 네이티브 라이브러리 없이 도는 순수 계산. */
class GreedyHintTest {

  @Test
  void respectsPriorityDueBusyTimeAndDuty() {
    var p =
        new PlanProblem(
            30,
            4,
            List.of(
                new PlanProblem.Task("LATE", "PICK", 1, 7, 25, 3),
                new PlanProblem.Task("EARLY", "PICK", 1, 7, 10, 3),
                new PlanProblem.Task("MV", "MOVE", 1, 5, null, 1)),
            List.of(
                new PlanProblem.Worker("a", 1, Set.of("PICK"), List.of(new int[] {0, 5})),
                new PlanProblem.Worker("b", 1, Set.of("PICK", "MOVE"), List.of())));
    var h = CpSatPlanner.greedy(p);
    // 약속 이른 EARLY 가 먼저 — 바로 비어 있는 b 에 0분
    assertThat(h.get("EARLY")).containsExactly(1, 0);
    // LATE 는 a 가 5분부터 빈다(b 는 7분부터) → a 5분
    assertThat(h.get("LATE")).containsExactly(0, 5);
    // MOVE 는 b 만 할 수 있다 — EARLY 뒤 7분
    assertThat(h.get("MV")).containsExactly(1, 7);
  }

  @Test
  void packStationsCapConcurrentPacksAndUnfitTasksStayUnassigned() {
    var p =
        new PlanProblem(
            10,
            1,
            List.of(
                new PlanProblem.Task("P1", "PACK", 1, 4, null, 3),
                new PlanProblem.Task("P2", "PACK", 1, 4, null, 3),
                new PlanProblem.Task("P3", "PACK", 1, 4, null, 3)),
            List.of(
                new PlanProblem.Worker("a", 1, Set.of("PACK"), List.of()),
                new PlanProblem.Worker("b", 1, Set.of("PACK"), List.of())));
    var h = CpSatPlanner.greedy(p);
    // 패킹대 1대 — 두 사람이 있어도 동시에 하나: 0~4, 4~8, 세 번째는 10분 안에 못 넣음
    assertThat(h.get("P1")[1]).isEqualTo(0);
    assertThat(h.get("P2")[1]).isEqualTo(4);
    assertThat(h).doesNotContainKey("P3");
  }

  @Test
  void fasterWorkerFinishesSoonerAndGetsTheEarlierSlot() {
    // 같은 7분 작업 둘, 둘 다 할 수 있는 a(보통)·b(배율 2.0 → 4분)
    var p =
        new PlanProblem(
            20,
            4,
            List.of(
                new PlanProblem.Task("T1", "PICK", 1, 7, 10, 3),
                new PlanProblem.Task("T2", "PICK", 1, 7, 12, 3)),
            List.of(
                new PlanProblem.Worker("a", 1, Set.of("PICK"), List.of(new int[] {0, 1}), 1.0),
                new PlanProblem.Worker("b", 1, Set.of("PICK"), List.of(new int[] {0, 1}), 2.0)));
    assertThat(p.workers().get(1).minutes(7)).isEqualTo(4);
    assertThat(p.workers().get(0).minutes(7)).isEqualTo(7);
    var h = CpSatPlanner.greedy(p);
    // 둘 다 1분부터 비면 앞 작업자(a)가 T1, T2 는 a 가 8분부터·b 가 1분부터 → b
    assertThat(h.get("T1")).containsExactly(0, 1);
    assertThat(h.get("T2")).containsExactly(1, 1);
  }

  /** (0.27.0) 격리 이동(onlyWorkerId) — 업무에 없어도 그 사람만, 그 사람이 바쁘면 다른 사람이 비어 있어도 넣지 않는다. */
  @Test
  void pinnedTaskGoesOnlyToItsWorker() {
    var p =
        new PlanProblem(
            20,
            1,
            List.of(
                new PlanProblem.Task("QM", "QUARANTINE_MOVE", 0, 5, null, 4, "b"),
                new PlanProblem.Task("QX", "QUARANTINE_MOVE", 0, 5, null, 4, "c")),
            List.of(
                new PlanProblem.Worker("a", 1, Set.of("MOVE", "QUARANTINE_MOVE"), List.of()),
                new PlanProblem.Worker("b", 1, Set.of("PICK"), List.of(new int[] {0, 3})),
                new PlanProblem.Worker("c", 1, Set.of("MOVE"), List.of(new int[] {0, 20}))));
    var h = CpSatPlanner.greedy(p);
    assertThat(h.get("QM")).containsExactly(1, 3); // b, 앞 점유 뒤
    assertThat(h).doesNotContainKey("QX"); // c 는 구간 내내 바쁨 — a 가 비어 있어도 안 줌
  }
}
