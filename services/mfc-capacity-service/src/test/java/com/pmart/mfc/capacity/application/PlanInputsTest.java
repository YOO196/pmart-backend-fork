package com.pmart.mfc.capacity.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.mfc.capacity.domain.PlanProblem;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** 계산 입력 JSON·지문(0.12.0, A4) — 같은 문제면 같은 지문, 하나라도 다르면 다른 지문. */
class PlanInputsTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private final Instant at = Instant.parse("2026-11-09T01:00:00Z");

  private PlanProblem problem(double speed) {
    return new PlanProblem(
        60,
        2,
        List.of(new PlanProblem.Task("T1", "PICK", 3, 7, 20, 3)),
        List.of(
            new PlanProblem.Worker(
                "w1", 2, Set.of("PICK", "PACK"), List.of(new int[] {0, 5}), speed)));
  }

  @Test
  void sameProblemSameHashAndContentIsComplete() {
    var a = PlanInputs.of(mapper, "M", at, problem(1.0), Duration.ofSeconds(5));
    var b = PlanInputs.of(mapper, "M", at, problem(1.0), Duration.ofSeconds(5));
    assertThat(a.hash()).isEqualTo(b.hash()).hasSize(64);
    assertThat(a.bytes())
        .isEqualTo(a.json().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    var n = mapper.readTree(a.json());
    assertThat(n.path("tasks").path(0).path("dueInMinutes").asInt()).isEqualTo(20);
    assertThat(n.path("workers").path(0).path("taskTypes").toString())
        .isEqualTo("[\"PACK\",\"PICK\"]");
    assertThat(n.path("workers").path(0).path("busy").toString()).isEqualTo("[[0,5]]");
    assertThat(n.path("weights").path("unassigned").asLong())
        .isEqualTo(PlanProblem.Weights.DEFAULT.unassigned());
    assertThat(n.path("randomSeed").asInt()).isEqualTo(1);
  }

  /** (0.27.0) 미리 정한 담당은 있을 때만 입력에 — 없으면 예전과 같은 JSON(지문 그대로). */
  @Test
  void onlyWorkerIdIsWrittenOnlyWhenPresent() {
    var plain = PlanInputs.of(mapper, "M", at, problem(1.0), Duration.ofSeconds(5));
    assertThat(mapper.readTree(plain.json()).path("tasks").path(0).has("onlyWorkerId")).isFalse();
    var pinned =
        new PlanProblem(
            60,
            2,
            List.of(new PlanProblem.Task("Q1", "QUARANTINE_MOVE", 0, 5, null, 4, "w1")),
            List.of(new PlanProblem.Worker("w1", 2, Set.of("PICK"), List.of())));
    var q = PlanInputs.of(mapper, "M", at, pinned, Duration.ofSeconds(5));
    assertThat(mapper.readTree(q.json()).path("tasks").path(0).path("onlyWorkerId").asString())
        .isEqualTo("w1");
  }

  @Test
  void anyChangeChangesHash() {
    var a = PlanInputs.of(mapper, "M", at, problem(1.0), Duration.ofSeconds(5));
    assertThat(PlanInputs.of(mapper, "M", at, problem(1.5), Duration.ofSeconds(5)).hash())
        .isNotEqualTo(a.hash());
    assertThat(PlanInputs.of(mapper, "M", at, problem(1.0), Duration.ofSeconds(4)).hash())
        .isNotEqualTo(a.hash());
  }
}
