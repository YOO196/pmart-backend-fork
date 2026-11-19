package com.pmart.mfc.capacity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.mfc.capacity.domain.PlanProblem;
import com.pmart.mfc.capacity.domain.PlanProblem.Item;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 실제 CP-SAT(네이티브 라이브러리 로딩 포함). */
class CpSatPlannerTest {

  final CpSatPlanner planner = new CpSatPlanner();

  static PlanProblem.Worker worker(String id, Set<String> types, List<int[]> busy) {
    return new PlanProblem.Worker(id, 1, types, busy);
  }

  /** (0.27.0) 격리 이동(onlyWorkerId) — 엔진도 그 사람만(업무와 상관없이). 그 사람이 못 하면 미배정. */
  @Test
  void pinnedTaskIsPlannedOnlyForItsWorker() {
    var p =
        new PlanProblem(
            30,
            1,
            List.of(
                new PlanProblem.Task("QM", "QUARANTINE_MOVE", 0, 5, null, 4, "pk"),
                new PlanProblem.Task("QX", "QUARANTINE_MOVE", 0, 5, null, 4, "gone")),
            List.of(
                worker("mover", Set.of("MOVE", "QUARANTINE_MOVE"), List.of()),
                worker("pk", Set.of("PICK"), List.<int[]>of(new int[] {0, 4}))));
    var r = planner.solve(p, Duration.ofSeconds(5));
    assertThat(r.solved()).isTrue();
    Item qm = r.items().stream().filter(i -> i.taskId().equals("QM")).findFirst().orElseThrow();
    assertThat(qm.workerId()).isEqualTo("pk");
    assertThat(qm.start()).isGreaterThanOrEqualTo(4);
    assertThat(r.items()).noneMatch(i -> i.taskId().equals("QX"));
    assertThat(r.unassigned()).containsExactly("QX");
  }

  @Test
  void assignsByDutyWithoutOverlapAndRespectsBusyTime() {
    var p =
        new PlanProblem(
            60,
            4,
            List.of(
                new PlanProblem.Task("T1", "PICK", 1, 7, 20),
                new PlanProblem.Task("T2", "PICK", 1, 7, 20),
                new PlanProblem.Task("T3", "PACK", 1, 4, null)),
            List.of(
                worker("picker", Set.of("PICK"), List.of()),
                worker("packer", Set.of("PACK", "PICK"), List.<int[]>of(new int[] {0, 10}))));
    var r = planner.solve(p, Duration.ofSeconds(5));
    assertThat(r.status()).isEqualTo("OPTIMAL");
    assertThat(r.unassigned()).isEmpty();
    assertThat(r.items()).hasSize(3);
    Item pack = r.items().stream().filter(i -> i.taskId().equals("T3")).findFirst().orElseThrow();
    assertThat(pack.workerId()).isEqualTo("packer");
    assertThat(pack.start()).isGreaterThanOrEqualTo(10); // 앞 점유 뒤
    // 같은 작업자 구간은 겹치지 않는다
    for (Item a : r.items()) {
      for (Item b : r.items()) {
        if (a != b && a.workerId().equals(b.workerId())) {
          assertThat(a.end() <= b.start() || b.end() <= a.start()).isTrue();
        }
      }
    }
    assertThat(r.lateMinutes()).isZero();
  }

  @Test
  void leavesTasksUnassignedWhenTimeRunsOutAndCountsLateness() {
    var p =
        new PlanProblem(
            20,
            4,
            List.of(
                new PlanProblem.Task("A", "PICK", 1, 7, 5),
                new PlanProblem.Task("B", "PICK", 1, 7, null),
                new PlanProblem.Task("C", "PICK", 1, 7, null)),
            List.of(worker("w", Set.of("PICK"), List.of())));
    var r = planner.solve(p, Duration.ofSeconds(5));
    assertThat(r.solved()).isTrue();
    assertThat(r.items()).hasSize(2); // 20분에 7분짜리 둘
    assertThat(r.unassigned()).hasSize(1);
    Item a = r.items().stream().filter(i -> i.taskId().equals("A")).findFirst().orElseThrow();
    assertThat(a.start()).isZero(); // 약속이 급한 작업을 먼저
    assertThat(a.late()).isEqualTo(2);
  }

  @Test
  void packingStationsLimitConcurrentPacks() {
    var p =
        new PlanProblem(
            30,
            1,
            List.of(
                new PlanProblem.Task("P1", "PACK", 1, 4, null),
                new PlanProblem.Task("P2", "PACK", 1, 4, null)),
            List.of(
                worker("a", Set.of("PACK"), List.of()), worker("b", Set.of("PACK"), List.of())));
    var r = planner.solve(p, Duration.ofSeconds(5));
    Item x = r.items().get(0);
    Item y = r.items().get(1);
    assertThat(x.end() <= y.start() || y.end() <= x.start()).isTrue(); // 패킹대 1개
  }

  @Test
  void offShiftTurnsShiftsIntoBusyGaps() {
    assertThat(PlanProblem.offShift(List.of(), 60)).isEmpty();
    var gaps = PlanProblem.offShift(List.<int[]>of(new int[] {10, 30}, new int[] {-5, 5}), 60);
    assertThat(gaps).hasSize(2);
    assertThat(gaps.get(0)).containsExactly(5, 10);
    assertThat(gaps.get(1)).containsExactly(30, 60);
  }

  /** 같은 개수만 넣을 수 있으면 정해진 순서의 앞선 유형을 넣는다(보충 > 이동 > 복귀). */
  @Test
  void priorityDecidesWhichTaskFitsWhenTimeIsShort() {
    var p =
        new PlanProblem(
            10,
            4,
            List.of(
                new PlanProblem.Task("RET", "RETURN", 0, 5, null, 0),
                new PlanProblem.Task("MV", "MOVE", 0, 5, null, 1),
                new PlanProblem.Task("REP", "REPLENISH", 0, 5, null, 2)),
            List.of(worker("w", Set.of("RETURN", "MOVE", "REPLENISH"), List.of())));
    var r = planner.solve(p, Duration.ofSeconds(5));
    assertThat(r.solved()).isTrue();
    assertThat(r.items()).extracting(Item::taskId).containsExactlyInAnyOrder("REP", "MV");
    assertThat(r.unassigned()).containsExactly("RET");
  }
}
