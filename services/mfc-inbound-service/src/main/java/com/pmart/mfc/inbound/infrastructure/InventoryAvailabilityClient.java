package com.pmart.mfc.inbound.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.mfc.inbound.application.InboundProperties;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * mfc-inventory 가용 수량 조회({@code GET /internal/availability?mfcId&sku}, 100건씩 — A = E − R, 센터 취급).
 * 내부 호출(토큰 없음, ADR-0026). 미전달·503 은 {@code DEPENDENCY_UNAVAILABLE} 로 그대로 올린다(추천을 0 으로 채우지 않는다).
 */
@Component
public class InventoryAvailabilityClient {

  static final int BATCH = 100;

  /** SKU 한 줄 — available 을 모르면 null. */
  public record Item(@Nullable Integer available, @Nullable Boolean handlingEnabled) {}

  public record Result(@Nullable Instant asOf, Map<String, Item> items) {}

  private final RestClient http;
  private final ObjectMapper mapper;

  public InventoryAvailabilityClient(
      RestClient.Builder builder, InboundProperties props, ObjectMapper mapper) {
    this.http = builder.clone().baseUrl(props.mfcInventoryBaseUrl()).build();
    this.mapper = mapper;
  }

  public Result availability(String mfcId, List<String> skus) {
    Map<String, Item> out = new HashMap<>();
    Instant asOf = null;
    for (int i = 0; i < skus.size(); i += BATCH) {
      List<String> part = skus.subList(i, Math.min(skus.size(), i + BATCH));
      String raw =
          Downstream.call(
              "mfc-inventory-service availability",
              () ->
                  http.get()
                      .uri(
                          u -> {
                            u.path("/api/v1/internal/availability").queryParam("mfcId", mfcId);
                            part.forEach(s -> u.queryParam("sku", s));
                            return u.build();
                          })
                      .retrieve()
                      .body(String.class));
      JsonNode root = mapper.readTree(raw == null ? "{}" : raw);
      // asOf = _common AsOf 객체 {asOf, sourceVersion, freshness}(0.21.1 — 문자열로 읽어 LAN 500)
      JsonNode asOfNode = root.path("asOf").path("asOf");
      if (asOfNode.isString()) {
        Instant t = Instant.parse(asOfNode.asString());
        asOf = asOf == null || t.isBefore(asOf) ? t : asOf;
      }
      for (JsonNode n : root.path("items")) {
        out.put(
            n.path("sku").asString(),
            new Item(
                n.hasNonNull("available") ? n.get("available").asInt() : null,
                n.hasNonNull("handlingEnabled") ? n.get("handlingEnabled").asBoolean() : null));
      }
    }
    return new Result(asOf, out);
  }
}
