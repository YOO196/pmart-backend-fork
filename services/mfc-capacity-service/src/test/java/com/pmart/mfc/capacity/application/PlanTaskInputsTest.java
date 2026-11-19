package com.pmart.mfc.capacity.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.mfc.capacity.domain.PlanProblem;
import com.pmart.mfc.capacity.infrastructure.AssignmentStore.AssignmentRow;
import com.pmart.mfc.capacity.infrastructure.FulfillmentClient.TaskView;
import com.pmart.mfc.capacity.infrastructure.InboundClient.InboundTaskView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 배정 추천 입력(0.27.0 — 격리 이동). 정본 담당만 계획, capacity 활성 배정(센터 관리자 재배정 포함)이 있으면 그 담당의 앞 점유. OR-Tools 없이
 * 도는 순수 계산(입력 쪽은 엔진과 무관 — 엔진 쪽 제약은 {@code CpSatPlannerTest.pinnedTaskIsPlannedOnlyForItsWorker}).
 */
class PlanTaskInputsTest {

  static final Instant NOW = Instant.parse("2026-11-20T01:00:00Z");
  static final Map<String, Integer> MINUTES =
      Map.of("PICK", 7, "MOVE", 5, AssignmentService.QUARANTINE_MOVE, 6);

  static AssignmentRow active(String taskId, String taskType, String worker, String by) {
    return new AssignmentRow(
        "ASG-" + taskId, "M", taskId, taskType, worker, 2, "ACTIVE", null, by, null, NOW, null);
  }

  @Test
  void quarantineViewsKeepOnlyQuarantineMovesWithTheirAssignee() {
    var views =
        PlanService.quarantineViews(
            List.of(
                new InboundTaskView("TSK-QRT-1", "QUARANTINE_MOVE", "w-a", NOW),
                new InboundTaskView("TSK-PUT-1", "PUTAWAY", null, NOW),
                new InboundTaskView("TSK-QRT-2", "QUARANTINE_MOVE", null, NOW)));
    assertThat(views).extracting(TaskView::taskId).containsExactly("TSK-QRT-1", "TSK-QRT-2");
    assertThat(views.get(0).state()).isEqualTo("READY");
    assertThat(views.get(0).workerId()).isEqualTo("w-a");
    assertThat(views.get(0).taskType()).isEqualTo("QUARANTINE_MOVE");
  }

  @Test
  void preAssignedQuarantineMoveIsPinnedToItsAssigneeWithTopPriority() {
    List<TaskView> all = new ArrayList<>();
    all.add(new TaskView("P1", "PICK", "READY", 1, null, NOW.plusSeconds(1200)));
    all.addAll(
        PlanService.quarantineViews(
            List.of(new InboundTaskView("Q1", "QUARANTINE_MOVE", "w-a", NOW))));
    var in = PlanService.taskInputs(all, Map.of(), MINUTES, Map.of(), null, NOW);
    assertThat(in.left()).isEmpty();
    assertThat(in.busyMinutes()).isEmpty();
    PlanProblem.Task q =
        in.tasks().stream().filter(t -> t.taskId().equals("Q1")).findFirst().orElseThrow();
    assertThat(q.onlyWorkerId()).isEqualTo("w-a");
    assertThat(q.minutes()).isEqualTo(6);
    assertThat(q.priority()).isEqualTo(4);
    PlanProblem.Task p =
        in.tasks().stream().filter(t -> t.taskId().equals("P1")).findFirst().orElseThrow();
    assertThat(p.onlyWorkerId()).isNull();
    assertThat(p.due()).isEqualTo(20);
    // 담당만 — 업무와 상관없이
    var assignee = new PlanProblem.Worker("w-a", 1, Set.of("PICK"), List.of());
    var mover = new PlanProblem.Worker("w-m", 1, Set.of("MOVE"), List.of());
    assertThat(q.allows(assignee)).isTrue();
    assertThat(q.allows(mover)).isFalse();
    assertThat(p.allows(assignee)).isTrue();
    assertThat(p.allows(mover)).isFalse();
  }

  /** 센터 관리자가 다른 사람에게 바꿨으면(capacity ACTIVE 배정) 추천 대상이 아니라 새 담당의 앞 점유 — 정본 담당이 아직 옛 사람이어도. */
  @Test
  void reassignedQuarantineMoveBlocksTheNewHolderInsteadOfBeingPlanned() {
    List<TaskView> all =
        new ArrayList<>(
            PlanService.quarantineViews(
                List.of(new InboundTaskView("Q1", "QUARANTINE_MOVE", "w-old", NOW))));
    var in =
        PlanService.taskInputs(
            all,
            Map.of("Q1", active("Q1", "QUARANTINE_MOVE", "w-new", "sup")),
            MINUTES,
            Map.of(),
            null,
            NOW);
    assertThat(in.tasks()).isEmpty();
    assertThat(in.left()).isEmpty();
    assertThat(in.busyMinutes()).containsExactly(Map.entry("w-new", 6));
  }

  @Test
  void quarantineMoveWithoutAssigneeIsLeftForTheSupervisorAndMissingMinutesIsConfigRequired() {
    List<TaskView> all =
        new ArrayList<>(
            PlanService.quarantineViews(
                List.of(
                    new InboundTaskView("Q-NONE", "QUARANTINE_MOVE", null, NOW),
                    new InboundTaskView("Q-A", "QUARANTINE_MOVE", "w-a", NOW))));
    var in = PlanService.taskInputs(all, Map.of(), Map.of("PICK", 7), Map.of(), null, NOW);
    assertThat(in.tasks()).isEmpty();
    assertThat(in.left())
        .extracting(r -> r.taskId() + ":" + r.reason())
        .containsExactly("Q-NONE:NO_ELIGIBLE_WORKER", "Q-A:CONFIG_REQUIRED");
  }

  /** 기존 입력은 그대로 — 진행 중 작업은 담당의 앞 점유, 활성 배정이 있는 READY 도 앞 점유, 피커가 정해진 PACK 은 빠짐. */
  @Test
  void existingTasksAreUnchanged() {
    List<TaskView> all =
        List.of(
            new TaskView("A", "PICK", "IN_PROGRESS", 1, "w1", null),
            new TaskView("B", "PICK", "READY", 1, null, null),
            new TaskView("C", "PACK", "READY", 1, null, null, null, null, "w2"),
            new TaskView("D", "MOVE", "READY", 0, null, null));
    var in =
        PlanService.taskInputs(
            all,
            Map.of("B", active("B", "PICK", "w3", "SYSTEM")),
            MINUTES,
            Map.of("D", true),
            null,
            NOW);
    assertThat(in.busyMinutes()).containsOnly(Map.entry("w1", 7), Map.entry("w3", 7));
    assertThat(in.tasks()).extracting(PlanProblem.Task::taskId).containsExactly("D");
    assertThat(in.tasks().get(0).priority()).isEqualTo(2); // 긴급 이동 = 보충
    assertThat(in.tasks().get(0).onlyWorkerId()).isNull();
  }
}
