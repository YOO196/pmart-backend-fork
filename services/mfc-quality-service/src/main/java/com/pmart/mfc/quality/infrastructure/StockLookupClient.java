package com.pmart.mfc.quality.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.mfc.quality.application.QualityProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 품질 사고 직접 등록의 LOT 확인(0.6.0) — mfc-inventory {@code GET /mfcs/{mfcId}/stock-units?lotId=}(센터 관리자 토큰
 * 전달 — 그 센터 운영자 권한 그대로). 남은 재고가 있으면 그 SKU.
 */
@Component
public class StockLookupClient {

  private final RestClient inventory;
  private final ObjectMapper mapper;

  public StockLookupClient(
      RestClient.Builder builder, QualityProperties props, ObjectMapper mapper) {
    this.inventory = builder.clone().baseUrl(props.inventoryUrl()).build();
    this.mapper = mapper;
  }

  /** 이 센터에 남은 재고가 있는 LOT 이면 SKU, 없으면 empty. 조회 실패는 DEPENDENCY_UNAVAILABLE·UNKNOWN_RESULT. */
  public Optional<String> skuOfLotInCenter(String mfcId, String lotId) {
    String auth = currentAuthorization();
    String raw =
        Downstream.call(
            InventoryClient.INVENTORY,
            () ->
                inventory
                    .get()
                    .uri("/api/v1/mfcs/{m}/stock-units?lotId={l}&size=1", mfcId, lotId)
                    .headers(
                        h -> {
                          if (auth != null) {
                            h.set(HttpHeaders.AUTHORIZATION, auth);
                          }
                        })
                    .retrieve()
                    .body(String.class));
    JsonNode n = raw == null ? mapper.createObjectNode() : mapper.readTree(raw);
    for (JsonNode u : n.path("items")) {
      if (lotId.equals(u.path("lotId").asString(""))) {
        return Optional.of(u.path("sku").asString(""));
      }
    }
    return Optional.empty();
  }

  private static @Nullable String currentAuthorization() {
    if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a) {
      HttpServletRequest r = a.getRequest();
      return r.getHeader(HttpHeaders.AUTHORIZATION);
    }
    return null;
  }
}
