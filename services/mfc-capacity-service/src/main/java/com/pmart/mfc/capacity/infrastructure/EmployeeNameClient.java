package com.pmart.mfc.capacity.infrastructure;

import com.pmart.common.web.Downstream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * hr-service {@code GET /internal/employee-names}(토큰 없는 내부 호출, 200개씩) — 직원 이름(ADR-0070 화면 표기). 주소
 * {@code pmart.capacity.hr-base-url} 이 없거나 조회가 안 되면 빈 결과(화면은 사번·"직원"으로 — 막지 않는다).
 */
@Component
public class EmployeeNameClient {

  private static final Logger log = LoggerFactory.getLogger(EmployeeNameClient.class);
  static final int CHUNK = 200;

  private final RestClient client;
  private final ObjectMapper mapper;
  private final boolean configured;

  public EmployeeNameClient(
      RestClient.Builder builder,
      ObjectMapper mapper,
      @Value("${pmart.capacity.hr-base-url:}") String baseUrl) {
    this.configured = baseUrl != null && !baseUrl.isBlank();
    this.client = builder.clone().baseUrl(configured ? baseUrl : "http://unconfigured").build();
    this.mapper = mapper;
  }

  public Map<String, String> names(Collection<String> operatorIds) {
    Map<String, String> out = new HashMap<>();
    if (!configured || operatorIds.isEmpty()) {
      return out;
    }
    List<String> ids = new ArrayList<>(new java.util.LinkedHashSet<>(operatorIds));
    for (int i = 0; i < ids.size(); i += CHUNK) {
      List<String> part = ids.subList(i, Math.min(ids.size(), i + CHUNK));
      try {
        String raw =
            Downstream.call(
                "hr-service",
                () ->
                    client
                        .get()
                        .uri(
                            (UriBuilder u) -> {
                              u.path("/api/v1/internal/employee-names");
                              part.forEach(id -> u.queryParam("operatorIds", id));
                              return u.build();
                            })
                        .header("X-Source-System", "mfc-capacity-service")
                        .retrieve()
                        .body(String.class));
        JsonNode n = raw == null ? mapper.createObjectNode() : mapper.readTree(raw);
        for (JsonNode it : n.path("items")) {
          String id = it.path("operatorId").asString(null);
          String name = it.path("name").asString(null);
          if (id != null && name != null) {
            out.put(id, name);
          }
        }
      } catch (RuntimeException e) {
        log.warn("직원 이름 조회 실패 — 이름 없이: {}", e.getMessage());
        return out;
      }
    }
    return out;
  }
}
