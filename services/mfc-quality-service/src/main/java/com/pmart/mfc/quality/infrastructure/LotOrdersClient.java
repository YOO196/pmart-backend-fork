package com.pmart.mfc.quality.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.mfc.quality.application.QualityProperties;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 품질 사고 고객 영향(R6) — mfc-inventory {@code GET /internal/lot-orders}(서비스 간 인증 없음 — ADR-0026). LOT 을
 * 집품·인계한 주문 줄.
 */
@Component
public class LotOrdersClient {

  public record Line(
      String orderId, @Nullable String orderLineId, String lotId, String sku, int handedQty) {}

  public record Result(List<Line> lines, boolean truncated) {}

  private final RestClient inventory;
  private final ObjectMapper mapper;

  public LotOrdersClient(RestClient.Builder builder, QualityProperties props, ObjectMapper mapper) {
    this.inventory = builder.clone().baseUrl(props.inventoryUrl()).build();
    this.mapper = mapper;
  }

  /** 실패는 예외(Downstream — 호출자가 다음 주기에 다시). lotIds 는 50개씩. */
  public Result of(String mfcId, List<String> lotIds) {
    List<Line> out = new ArrayList<>();
    boolean truncated = false;
    for (int i = 0; i < lotIds.size(); i += 50) {
      String joined = String.join(",", lotIds.subList(i, Math.min(lotIds.size(), i + 50)));
      String raw =
          Downstream.call(
              InventoryClient.INVENTORY,
              () ->
                  inventory
                      .get()
                      .uri("/api/v1/internal/lot-orders?mfcId={m}&lotIds={l}", mfcId, joined)
                      .header("X-Source-System", InventoryClient.SOURCE)
                      .retrieve()
                      .body(String.class));
      JsonNode n = raw == null ? mapper.createObjectNode() : mapper.readTree(raw);
      truncated |= n.path("truncated").asBoolean(false);
      for (JsonNode l : n.path("items")) {
        out.add(
            new Line(
                l.path("orderId").asString(),
                l.path("orderLineId").isNull() ? null : l.path("orderLineId").asString(null),
                l.path("lotId").asString(),
                l.path("sku").asString(),
                l.path("handedQty").asInt()));
      }
    }
    return new Result(out, truncated);
  }
}
