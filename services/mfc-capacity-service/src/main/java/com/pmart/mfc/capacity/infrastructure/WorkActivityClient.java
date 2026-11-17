package com.pmart.mfc.capacity.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.mfc.capacity.application.PlanProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 작업자 처리 내역(F-7 R3) — 네 정본의 {@code GET /internal/work-activities}(내부 호출, 토큰 없음 — ADR-0026). 주소:
 * fulfillment·inbound 는 배정 추천과 같은 {@code pmart.capacity.plan.*}, inventory·quality 는 {@code
 * pmart.capacity.activities.*}. 주소가 없거나 조회가 실패한 정본은 결과에서 빠지고 이름만 알린다(일부라도 보인다).
 */
@Component
public class WorkActivityClient {

  /** 한 건(정본이 준 모양 그대로 + 정본 이름). */
  public record Item(
      String source,
      String activityType,
      String refId,
      @Nullable String taskId,
      @Nullable String sku,
      @Nullable Integer qty,
      @Nullable String result,
      Instant at) {}

  /** 모은 결과. unavailable = 주소가 없거나 조회 실패한 정본. */
  public record Collected(List<Item> items, List<String> unavailable) {}

  private final Map<String, @Nullable RestClient> sources = new LinkedHashMap<>();
  private final ObjectMapper mapper;

  public WorkActivityClient(
      RestClient.Builder builder,
      PlanProperties plan,
      ObjectMapper mapper,
      @Value("${pmart.capacity.activities.mfc-inventory-base-url:}") String inventoryUrl,
      @Value("${pmart.capacity.activities.mfc-quality-base-url:}") String qualityUrl) {
    this.mapper = mapper;
    sources.put("mfc-fulfillment-service", client(builder, plan.mfcFulfillmentBaseUrl()));
    sources.put("mfc-inbound-service", client(builder, plan.mfcInboundBaseUrl()));
    sources.put("mfc-inventory-service", client(builder, inventoryUrl));
    sources.put("mfc-quality-service", client(builder, qualityUrl));
  }

  private static @Nullable RestClient client(RestClient.Builder b, @Nullable String url) {
    return url == null || url.isBlank() ? null : b.clone().baseUrl(url).build();
  }

  public Collected collect(String mfcId, String operatorId, Instant from, Instant to) {
    List<Item> items = new ArrayList<>();
    List<String> unavailable = new ArrayList<>();
    sources.forEach(
        (name, http) -> {
          if (http == null) {
            unavailable.add(name);
            return;
          }
          try {
            String raw =
                Downstream.call(
                    name + " work-activities",
                    () ->
                        http.get()
                            .uri(
                                "/api/v1/internal/work-activities?mfcId={m}&operatorId={o}&from={f}&to={t}",
                                mfcId,
                                operatorId,
                                from.toString(),
                                to.toString())
                            .retrieve()
                            .body(String.class));
            for (JsonNode n : mapper.readTree(raw == null ? "{}" : raw).path("items")) {
              items.add(
                  new Item(
                      name,
                      n.path("activityType").asString(),
                      n.path("refId").asString(),
                      text(n, "taskId"),
                      text(n, "sku"),
                      n.hasNonNull("qty") ? n.get("qty").asInt() : null,
                      text(n, "result"),
                      Instant.parse(n.path("at").asString())));
            }
          } catch (RuntimeException e) {
            unavailable.add(name); // 일부 정본 실패 — 나머지는 보인다(빈 내역으로 오해하지 않게 이름을 알린다)
          }
        });
    return new Collected(items, unavailable);
  }

  private static @Nullable String text(JsonNode n, String f) {
    return n.hasNonNull(f) ? n.get(f).asString() : null;
  }
}
