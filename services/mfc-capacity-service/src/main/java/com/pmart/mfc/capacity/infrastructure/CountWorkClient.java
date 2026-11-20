package com.pmart.mfc.capacity.infrastructure;

import com.pmart.common.web.Downstream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * (0.24.0, MC-1 — BR-52 R2·R14) mfc-inventory {@code GET /internal/count-work}: 작업자에게 줄 계획 실사(계수
 * 단계, 기준 시각 오래된 순). 내부 호출이라 토큰 없음(ADR-0026). 주소는 작업자 처리 내역과 같은 {@code
 * pmart.capacity.activities.mfc-inventory-base-url} — 없으면 실사는 다음 작업 후보에 없다.
 */
@Component
public class CountWorkClient {

  /** 실사 하나. excludedOperatorIds = 셀 수 없는 사람(재계수면 첫 계수자). */
  public record CountWork(
      String sessionId,
      String state,
      List<String> locationCodes,
      Set<String> excludedOperatorIds,
      @Nullable Instant baselineAt,
      @Nullable String assigneeId) {

    public CountWork(
        String sessionId,
        String state,
        List<String> locationCodes,
        Set<String> excludedOperatorIds,
        @Nullable Instant baselineAt) {
      this(sessionId, state, locationCodes, excludedOperatorIds, baselineAt, null);
    }

    /** (inventory 0.40.0) 계획의 실사 담당이 정해져 있고 그 사람이 셀 수 있으면 그 사람만 — 재계수에서 담당이 첫 계수자면 누구나. */
    public @Nullable String effectiveAssignee() {
      return assigneeId == null || excludedOperatorIds.contains(assigneeId) ? null : assigneeId;
    }
  }

  private final @Nullable RestClient client;
  private final ObjectMapper mapper;

  public CountWorkClient(
      RestClient.Builder builder,
      ObjectMapper mapper,
      @Value("${pmart.capacity.activities.mfc-inventory-base-url:}") String inventoryUrl) {
    this.client =
        inventoryUrl == null || inventoryUrl.isBlank()
            ? null
            : builder.clone().baseUrl(inventoryUrl).build();
    this.mapper = mapper;
  }

  public boolean configured() {
    return client != null;
  }

  public List<CountWork> list(String mfcId) {
    RestClient http = client;
    if (http == null) {
      return List.of();
    }
    String raw =
        Downstream.call(
            "mfc-inventory-service count-work",
            () ->
                http.get()
                    .uri("/api/v1/internal/count-work?mfcId={m}", mfcId)
                    .retrieve()
                    .body(String.class));
    JsonNode n = raw == null ? mapper.createObjectNode() : mapper.readTree(raw);
    List<CountWork> out = new ArrayList<>();
    for (JsonNode c : n.path("items")) {
      List<String> codes = new ArrayList<>();
      c.path("locationCodes").forEach(x -> codes.add(x.asString("")));
      Set<String> excluded = new HashSet<>();
      c.path("excludedOperatorIds").forEach(x -> excluded.add(x.asString("")));
      String at = c.path("baselineAt").asString(null);
      out.add(
          new CountWork(
              c.path("sessionId").asString(""),
              c.path("state").asString(""),
              codes,
              excluded,
              at == null || at.isBlank() ? null : Instant.parse(at),
              c.path("assigneeId").isNull() ? null : c.path("assigneeId").asString(null)));
    }
    return out;
  }
}
