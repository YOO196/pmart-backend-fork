package com.pmart.mfc.inbound.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.mfc.inbound.application.InboundProperties;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * mfc-inventory 곧 품절 기준 넣기({@code PUT /internal/mfcs/{mfcId}/low-stock-basis}, inventory 0.47.0).
 * 내부 호출(토큰 없음, ADR-0026). 본문 모양은 계약 {@code putLowStockBasis} 그대로.
 */
@Component
public class InventoryLowStockClient {

  private final RestClient http;
  private final ObjectMapper mapper;

  public InventoryLowStockClient(
      RestClient.Builder builder, InboundProperties props, ObjectMapper mapper) {
    this.http = builder.clone().baseUrl(props.mfcInventoryBaseUrl()).build();
    this.mapper = mapper;
  }

  /** applied — inventory 가 이미 더 새 기준을 가졌으면 false. */
  public boolean put(String mfcId, Map<String, Object> body) {
    String raw =
        Downstream.call(
            "mfc-inventory-service low-stock-basis",
            () ->
                http.put()
                    .uri("/api/v1/internal/mfcs/{m}/low-stock-basis", mfcId)
                    // 통째로 바꾸기 + calculatedAt 순서 판정이라 다시 보내도 결과가 같다 — 키는 계산 하나마다
                    .header("Idempotency-Key", "LSB-" + mfcId + "-" + body.get("calculatedAt"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class));
    JsonNode root = mapper.readTree(raw == null ? "{}" : raw);
    return root.path("applied").asBoolean(false);
  }
}
