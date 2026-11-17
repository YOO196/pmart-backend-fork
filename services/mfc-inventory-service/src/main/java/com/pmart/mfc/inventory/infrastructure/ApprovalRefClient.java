package com.pmart.mfc.inventory.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.inventory.domain.HqApprovalRules.Price;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 본사 승인 확인에 쓰는 두 내부 호출(요청 AP-1, 토큰 없음 — ADR-0026): pricing {@code GET /internal/prices?skus=}(판매
 * 기준가, 200건씩)· approval-service {@code POST /internal/approval-ref-uses}(확인 + 한 번 사용, useRef 멱등).
 * 주소가 없으면 CONFIG_REQUIRED.
 */
@Component
public class ApprovalRefClient {

  static final int PRICE_BATCH = 200;
  public static final String SELF = "mfc-inventory-service";

  /** approval-ref-uses 결과. approved = SKU → 승인 수량(approvedQty, 없으면 requestedQty). */
  public record Use(boolean usable, @Nullable String reason, Map<String, Integer> approved) {}

  private final @Nullable RestClient pricing;
  private final @Nullable RestClient approval;
  private final ObjectMapper mapper;

  public ApprovalRefClient(
      RestClient.Builder builder,
      ObjectMapper mapper,
      @Value("${pmart.approval-ref.pricing-base-url:}") String pricingUrl,
      @Value("${pmart.approval-ref.approval-base-url:}") String approvalUrl) {
    this.pricing = pricingUrl.isBlank() ? null : builder.clone().baseUrl(pricingUrl).build();
    this.approval = approvalUrl.isBlank() ? null : builder.clone().baseUrl(approvalUrl).build();
    this.mapper = mapper;
  }

  /** SKU → 판매 기준가. 단가 없는 SKU 는 결과에 없다. */
  public Map<String, Price> prices(List<String> skus) {
    RestClient http = require(pricing, "pmart.approval-ref.pricing-base-url");
    Map<String, Price> out = new HashMap<>();
    for (int i = 0; i < skus.size(); i += PRICE_BATCH) {
      String joined = String.join(",", skus.subList(i, Math.min(skus.size(), i + PRICE_BATCH)));
      String raw =
          Downstream.call(
              "pricing-service prices",
              () ->
                  http.get()
                      .uri("/api/v1/internal/prices?skus={s}", joined)
                      .retrieve()
                      .body(String.class));
      for (JsonNode n : mapper.readTree(raw == null ? "{}" : raw).path("items")) {
        out.put(
            n.path("sku").asString(),
            new Price(n.path("basePrice").asLong(), n.path("priceVersion").asLong()));
      }
    }
    return out;
  }

  /** 승인 번호 한 번 사용 — 자기 변경을 확정하기 전에. */
  public Use use(String approvalId, String type, String targetRef, String actorId, String useRef) {
    RestClient http = require(approval, "pmart.approval-ref.approval-base-url");
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("approvalId", approvalId);
    body.put("type", type);
    body.put("targetService", SELF);
    body.put("targetRef", targetRef);
    body.put("actorId", actorId);
    body.put("useRef", useRef);
    String raw =
        Downstream.call(
            "approval-service approval-ref-uses",
            () ->
                http.post()
                    .uri("/api/v1/internal/approval-ref-uses")
                    .header("Idempotency-Key", useRef)
                    .header("X-Source-System", SELF)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class));
    JsonNode n = mapper.readTree(raw == null ? "{}" : raw);
    Map<String, Integer> approved = new HashMap<>();
    for (JsonNode l : n.path("lines")) {
      JsonNode q = l.hasNonNull("approvedQty") ? l.get("approvedQty") : l.path("requestedQty");
      approved.merge(l.path("lineKey").asString(), q.asInt(0), Integer::sum);
    }
    return new Use(
        n.path("usable").asBoolean(false),
        n.hasNonNull("reason") ? n.get("reason").asString() : null,
        approved);
  }

  private static RestClient require(@Nullable RestClient c, String key) {
    if (c == null) {
      throw PmartException.configRequired(key);
    }
    return c;
  }
}
