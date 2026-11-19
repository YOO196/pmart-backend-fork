package com.pmart.mfc.capacity.application;

import com.pmart.mfc.capacity.domain.PlanProblem;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/** 엔진에 넘긴 문제 → JSON·지문(0.12.0, A4). 순수 변환 — 같은 문제면 같은 JSON·같은 지문. */
public final class PlanInputs {

  private PlanInputs() {}

  public record Stored(String json, String hash, int bytes) {}

  public static Stored of(
      ObjectMapper mapper, String mfcId, Instant horizonStart, PlanProblem p, Duration limit) {
    Map<String, Object> in = new LinkedHashMap<>();
    in.put("schema", "pmart.capacity.plan-input.v1");
    in.put("mfcId", mfcId);
    in.put("horizonStart", horizonStart.toString());
    in.put("horizonMinutes", p.horizon());
    in.put("packingStations", p.packingStations());
    in.put(
        "weights",
        Map.of(
            "unassigned", p.weights().unassigned(),
            "late", p.weights().late(),
            "maxLoad", p.weights().maxLoad()));
    in.put("timeLimitMs", limit.toMillis());
    in.put("randomSeed", 1);
    List<Map<String, Object>> tasks = new ArrayList<>();
    for (PlanProblem.Task t : p.tasks()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("taskId", t.taskId());
      m.put("taskType", t.taskType());
      m.put("version", t.version());
      m.put("minutes", t.minutes());
      m.put("dueInMinutes", t.due());
      m.put("priority", t.priority());
      if (t.onlyWorkerId() != null) {
        // (0.27.0) 미리 정한 담당(격리 이동) — 없을 때는 넣지 않는다(예전 문제의 입력 지문 그대로)
        m.put("onlyWorkerId", t.onlyWorkerId());
      }
      tasks.add(m);
    }
    in.put("tasks", tasks);
    List<Map<String, Object>> workers = new ArrayList<>();
    for (PlanProblem.Worker w : p.workers()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("workerId", w.workerId());
      m.put("dutyVersion", w.dutyVersion());
      m.put("taskTypes", w.taskTypes().stream().sorted().toList());
      m.put("speed", w.speed());
      m.put("busy", w.busy().stream().map(b -> List.of(b[0], b[1])).toList());
      workers.add(m);
    }
    in.put("workers", workers);
    String json = mapper.writeValueAsString(in);
    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
    try {
      String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
      return new Stored(json, hash, bytes.length);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
