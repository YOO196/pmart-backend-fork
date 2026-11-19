package com.pmart.mfc.capacity.infrastructure;

import com.google.ortools.Loader;
import com.google.ortools.sat.BoolVar;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.CpSolverStatus;
import com.google.ortools.sat.CumulativeConstraint;
import com.google.ortools.sat.IntVar;
import com.google.ortools.sat.IntervalVar;
import com.google.ortools.sat.LinearExpr;
import com.google.ortools.sat.LinearExprBuilder;
import com.pmart.mfc.capacity.domain.PlanProblem;
import com.pmart.mfc.capacity.domain.PlanProblem.Item;
import com.pmart.mfc.capacity.domain.PlanProblem.Result;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Component;

/**
 * OR-Tools CP-SAT 어댑터(WK-09). 모델: 작업마다 (적격 작업자별 선택 구간) 하나 또는 미배정, 작업자마다 NoOverlap(+ 막힌 구간), PACK 은
 * 패킹대 수만큼 Cumulative. 목적 = {@link PlanProblem} 가중치. 네이티브 라이브러리는 처음 쓸 때 한 번 올린다. 동시 계산은 2개까지.
 */
@Component
public class CpSatPlanner {

  private static volatile boolean loaded;
  private final Semaphore slots = new Semaphore(2);

  private static synchronized void load() {
    if (!loaded) {
      Loader.loadNativeLibraries();
      loaded = true;
    }
  }

  public Result solve(PlanProblem p, Duration timeLimit) {
    if (p.tasks().isEmpty()) {
      return new Result("NOT_RUN", List.of(), List.of(), 0, 0, 0, 0);
    }
    if (!slots.tryAcquire()) {
      return new Result("UNKNOWN", List.of(), List.of(), 0, 0, 0, 0); // 계산 자리 없음 — 수동 배정
    }
    try {
      load();
      return run(p, timeLimit);
    } finally {
      slots.release();
    }
  }

  /**
   * 규칙 기반 첫 해. 작업을 정해진 앞섬(큰 것 먼저) → 약속 이른 순 → ID 로 보고, 할 수 있는 작업자마다 가장 이른 빈 시작 분(근무 밖·앞 점유·이미 넣은
   * 작업과 겹치지 않고, PACK 은 동시 패킹대 수 이하)을 찾아 가장 이른 작업자에 넣는다. 못 넣으면 미배정.
   */
  static Map<String, int[]> greedy(PlanProblem p) {
    int h = p.horizon();
    int nw = p.workers().size();
    boolean[][] busy = new boolean[nw][h];
    for (int w = 0; w < nw; w++) {
      for (int[] b : p.workers().get(w).busy()) {
        for (int t = Math.max(0, b[0]); t < Math.min(h, b[1]); t++) {
          busy[w][t] = true;
        }
      }
    }
    int[] packUse = new int[h];
    int stations = Math.max(1, p.packingStations());
    List<PlanProblem.Task> order = new ArrayList<>(p.tasks());
    order.sort(
        java.util.Comparator.comparingInt((PlanProblem.Task t) -> -t.priority())
            .thenComparingInt(t -> t.due() == null ? Integer.MAX_VALUE : t.due())
            .thenComparing(PlanProblem.Task::taskId));
    Map<String, int[]> out = new HashMap<>();
    for (PlanProblem.Task t : order) {
      boolean pack = "PACK".equals(t.taskType());
      int bestW = -1;
      int bestS = Integer.MAX_VALUE;
      int bestD = 0;
      for (int w = 0; w < nw; w++) {
        if (!t.allows(p.workers().get(w))) {
          continue;
        }
        int d = p.workers().get(w).minutes(t.minutes());
        for (int s = 0; s + d <= h && s < bestS; s++) {
          boolean ok = true;
          for (int x = s; x < s + d && ok; x++) {
            ok = !busy[w][x] && (!pack || packUse[x] < stations);
          }
          if (ok) {
            bestW = w;
            bestS = s;
            bestD = d;
            break;
          }
        }
      }
      if (bestW >= 0) {
        for (int x = bestS; x < bestS + bestD; x++) {
          busy[bestW][x] = true;
          if (pack) {
            packUse[x]++;
          }
        }
        out.put(t.taskId(), new int[] {bestW, bestS});
      }
    }
    return out;
  }

  private static Result ruleBased(
      PlanProblem p, Map<String, int[]> hint, List<String> wids, long wall) {
    List<Item> items = new ArrayList<>();
    List<String> left = new ArrayList<>();
    Map<String, Integer> load = new HashMap<>();
    int lateSum = 0;
    for (PlanProblem.Task t : p.tasks()) {
      int[] g = hint.get(t.taskId());
      if (g == null) {
        left.add(t.taskId());
        continue;
      }
      int dw = p.workers().get(g[0]).minutes(t.minutes());
      int late = t.due() == null ? 0 : Math.max(0, g[1] + dw - t.due());
      lateSum += late;
      load.merge(wids.get(g[0]), dw, Integer::sum);
      items.add(new Item(t.taskId(), wids.get(g[0]), g[1], g[1] + dw, late));
    }
    if (items.isEmpty()) {
      return new Result(
          "UNKNOWN", List.of(), List.of(), 0, 0, 0, wall); // 규칙으로도 못 넣음 — UNDETERMINED
    }
    items.sort(java.util.Comparator.comparingInt(Item::start).thenComparing(Item::taskId));
    left.sort(String::compareTo);
    int maxLoad = load.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    return new Result("UNKNOWN", items, left, lateSum, maxLoad, 0, wall, true);
  }

  private Result run(PlanProblem p, Duration timeLimit) {
    long t0 = System.nanoTime();
    CpModel m = new CpModel();
    int h = p.horizon();
    Map<String, List<IntervalVar>> byWorker = new HashMap<>();
    List<IntervalVar> packs = new ArrayList<>();
    LinearExprBuilder objective = LinearExpr.newBuilder();
    Map<String, LinearExprBuilder> load = new HashMap<>();
    record Choice(
        PlanProblem.Task task, String workerId, BoolVar present, IntVar start, IntVar late) {}
    List<Choice> choices = new ArrayList<>();
    Map<String, BoolVar> unassigned = new HashMap<>();

    for (PlanProblem.Worker w : p.workers()) {
      List<IntervalVar> ivs = byWorker.computeIfAbsent(w.workerId(), k -> new ArrayList<>());
      int i = 0;
      for (int[] b : w.busy()) {
        int s = Math.max(0, b[0]);
        int e = Math.min(h, b[1]);
        if (e > s) {
          ivs.add(m.newFixedInterval(s, e - s, "busy_" + w.workerId() + "_" + i++));
        }
      }
      load.put(w.workerId(), LinearExpr.newBuilder());
    }

    for (PlanProblem.Task t : p.tasks()) {
      BoolVar u = m.newBoolVar("u_" + t.taskId());
      unassigned.put(t.taskId(), u);
      LinearExprBuilder one = LinearExpr.newBuilder().add(u);
      int d = t.minutes();
      // 미배정은 늘 늦게라도 하는 것보다 비싸야 한다 — 급한 작업을 빼서 지연을 피하지 않게, 구간 끝에 끝낸 지연만큼 더한다
      long dropLate = t.due() == null ? 0 : Math.max(0, (long) h + d - t.due());
      objective.addTerm(
          u,
          p.weights().unassigned()
              + p.weights().late() * dropLate
              + p.weights().late() * (long) t.priority());
      {
        for (PlanProblem.Worker w : p.workers()) {
          int dw = w.minutes(d); // 작업자 속도 배율(0.11.0)
          if (!t.allows(w) || dw > h) {
            continue;
          }
          String n = t.taskId() + "_" + w.workerId();
          BoolVar b = m.newBoolVar("x_" + n);
          IntVar s = m.newIntVar(0, h - dw, "s_" + n);
          m.addEquality(s, 0).onlyEnforceIf(b.not());
          IntervalVar iv = m.newOptionalFixedSizeIntervalVar(s, dw, b, "i_" + n);
          byWorker.get(w.workerId()).add(iv);
          if ("PACK".equals(t.taskType())) {
            packs.add(iv);
          }
          IntVar late =
              m.newIntVar(0, (long) h + Math.abs(t.due() == null ? 0 : t.due()), "l_" + n);
          if (t.due() != null) {
            // late ≥ start + d − due (배정됐을 때만)
            m.addGreaterOrEqual(late, LinearExpr.newBuilder().add(s).add(dw - t.due()).build())
                .onlyEnforceIf(b);
          }
          m.addEquality(late, 0).onlyEnforceIf(b.not());
          objective.addTerm(late, p.weights().late());
          objective.add(s);
          one.add(b);
          load.get(w.workerId()).addTerm(b, dw);
          choices.add(new Choice(t, w.workerId(), b, s, late));
        }
      }
      m.addEquality(one.build(), 1);
    }

    for (List<IntervalVar> ivs : byWorker.values()) {
      if (ivs.size() > 1) {
        m.addNoOverlap(ivs);
      }
    }
    if (!packs.isEmpty()) {
      CumulativeConstraint c = m.addCumulative(Math.max(1, p.packingStations()));
      for (IntervalVar iv : packs) {
        c.addDemand(iv, 1);
      }
    }
    IntVar maxLoad = m.newIntVar(0, h, "maxLoad");
    for (LinearExprBuilder l : load.values()) {
      m.addGreaterOrEqual(maxLoad, l.build());
    }
    objective.addTerm(maxLoad, p.weights().maxLoad());
    m.minimize(objective.build());

    // 첫 해(힌트, 2026-11-09): 규칙 순서(정해진 앞섬 → 약속 이른 순)로 근무·겹침·패킹대 수를 지키며 가장 이른 작업자에 넣어 본 해.
    // 큰 문제(150건 이상)에서 엔진이 5초 안에 첫 해도 못 찾던 것(UNKNOWN)을 막는다 — 엔진은 이 해에서 시작해 더 좋은 해를 찾는다.
    Map<String, int[]> hint = greedy(p); // taskId → {workerIndex, start}
    List<String> wids = p.workers().stream().map(PlanProblem.Worker::workerId).toList();
    for (Choice c : choices) {
      int[] g = hint.get(c.task().taskId());
      boolean on = g != null && wids.get(g[0]).equals(c.workerId());
      m.addHint(c.present(), on ? 1 : 0);
      m.addHint(c.start(), on ? g[1] : 0);
      int dwh = on ? p.workers().get(g[0]).minutes(c.task().minutes()) : 0;
      int late = on && c.task().due() != null ? Math.max(0, g[1] + dwh - c.task().due()) : 0;
      m.addHint(c.late(), late);
    }
    unassigned.forEach((id, u) -> m.addHint(u, hint.containsKey(id) ? 0 : 1));

    CpSolver solver = new CpSolver();
    solver.getParameters().setMaxTimeInSeconds(Math.max(0.1, timeLimit.toMillis() / 1000.0));
    solver.getParameters().setNumWorkers(4);
    solver.getParameters().setRandomSeed(1); // 같은 입력이면 같은 추천(재현)
    // 최적과의 차이가 1 % 안이면 멈춘다(임시 구현값, 2026-11-09 — 20건에도 증명 못 해 늘 시간 제한까지 쓰던 것)
    solver.getParameters().setRelativeGapLimit(0.01);
    if (Boolean.getBoolean("pmart.cpsat.debug")) {
      solver.getParameters().setLogSearchProgress(true);
      solver.getParameters().setLogToStdout(false);
      solver.setLogCallback(System.err::println);
    }
    CpSolverStatus st = solver.solve(m);
    long wall = (System.nanoTime() - t0) / 1_000_000;
    if (st != CpSolverStatus.OPTIMAL && st != CpSolverStatus.FEASIBLE) {
      if (Boolean.getBoolean("pmart.cpsat.debug")) {
        System.err.println(
            "CPSAT " + st + " " + solver.getSolutionInfo() + " | " + solver.responseStats());
      }
      if (st == CpSolverStatus.UNKNOWN) {
        // 시간 안에 엔진이 해를 못 냄 — 규칙 기반 첫 해를 추천으로 낸다(개념 문서 8.3 "실행 가능한 결과가 없으면 규칙 기반 배정으로 전환",
        // 2026-11-09 측정: 150건·120분부터 5초 안에 첫 해 없음). 확정은 여전히 센터 관리자
        return ruleBased(p, hint, wids, wall);
      }
      return new Result(st.name(), List.of(), List.of(), 0, 0, 0, wall);
    }
    List<Item> items = new ArrayList<>();
    int lateSum = 0;
    for (Choice c : choices) {
      if (solver.booleanValue(c.present())) {
        int s = (int) solver.value(c.start());
        int late = (int) solver.value(c.late());
        lateSum += late;
        int dw =
            p.workers().stream()
                .filter(w -> w.workerId().equals(c.workerId()))
                .findFirst()
                .map(w -> w.minutes(c.task().minutes()))
                .orElse(c.task().minutes());
        items.add(new Item(c.task().taskId(), c.workerId(), s, s + dw, late));
      }
    }
    items.sort(java.util.Comparator.comparingInt(Item::start).thenComparing(Item::taskId));
    List<String> left = new ArrayList<>();
    unassigned.forEach(
        (id, u) -> {
          if (solver.booleanValue(u)) {
            left.add(id);
          }
        });
    left.sort(String::compareTo);
    return new Result(
        st.name(),
        items,
        left,
        lateSum,
        (int) solver.value(maxLoad),
        (long) solver.objectiveValue(),
        wall);
  }
}
