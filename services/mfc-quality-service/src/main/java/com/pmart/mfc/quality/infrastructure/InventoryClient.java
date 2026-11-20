package com.pmart.mfc.quality.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.quality.application.QualityProperties;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** mfc-inventory {@code /internal/quality-postings}(서비스 간 인증 없음 — ADR-0026). 수량 정본은 inventory. */
@Component
public class InventoryClient {

  public static final String INVENTORY = "mfc-inventory-service";
  public static final String SOURCE = "mfc-quality-service";

  /** 결과. APPLIED / REJECTED(4xx — code·detail) / UNKNOWN(결과 모름) / NOT_DELIVERED(안 감). */
  public record Posted(
      String outcome,
      @Nullable String commitId,
      int appliedQty,
      @Nullable String code,
      @Nullable String detail) {

    public String reason() {
      return code + (detail == null ? "" : ": " + detail);
    }
  }

  private final RestClient inventory;
  private final ObjectMapper mapper;

  public InventoryClient(RestClient.Builder builder, QualityProperties props, ObjectMapper mapper) {
    this.inventory = builder.clone().baseUrl(props.inventoryUrl()).build();
    this.mapper = mapper;
  }

  /** 키 = 판정 ID·실행 ID(같은 본문 재생). */
  public Posted post(String key, Map<String, Object> body) {
    try {
      String raw =
          Downstream.call(
              INVENTORY,
              () ->
                  inventory
                      .post()
                      .uri("/api/v1/internal/quality-postings")
                      .header("Idempotency-Key", key)
                      .header("X-Source-System", SOURCE)
                      .contentType(MediaType.APPLICATION_JSON)
                      .body(body)
                      .retrieve()
                      .body(String.class));
      JsonNode n = raw == null ? mapper.createObjectNode() : mapper.readTree(raw);
      return new Posted(
          "APPLIED", n.path("resultId").asString(null), n.path("appliedQty").asInt(0), null, null);
    } catch (HttpClientErrorException e) {
      JsonNode p;
      try {
        p = mapper.readTree(e.getResponseBodyAsString());
      } catch (RuntimeException ex) {
        p = mapper.createObjectNode();
      }
      return new Posted(
          "REJECTED",
          null,
          0,
          p.path("code").asString("HTTP_" + e.getStatusCode().value()),
          p.path("detail").asString(null));
    } catch (PmartException e) {
      String outcome =
          ErrorCode.DEPENDENCY_UNAVAILABLE.name().equals(e.code()) ? "NOT_DELIVERED" : "UNKNOWN";
      return new Posted(outcome, null, 0, e.code(), e.getMessage());
    }
  }

  /**
   * 품질 사고 LOT 판매 보류 걸기·풀기(mfc-inventory 0.39.0 {@code /internal/lot-sale-holds}) — 같은 사고·LOT 은 다시
   * 불러도 같은 결과. 실패는 예외(호출자가 다음 주기에 다시). 반환 = 이 사고의 보류 상태(ACTIVE·RELEASED).
   */
  public String lotSaleHold(boolean release, String mfcId, String lotId, String incidentId) {
    String raw =
        Downstream.call(
            INVENTORY,
            () ->
                inventory
                    .post()
                    .uri("/api/v1/internal/lot-sale-holds" + (release ? "/releases" : ""))
                    .header(
                        "Idempotency-Key",
                        incidentId + ":" + lotId + (release ? ":release" : ":place"))
                    .header("X-Source-System", SOURCE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("mfcId", mfcId, "lotId", lotId, "sourceRef", incidentId))
                    .retrieve()
                    .body(String.class));
    JsonNode n = raw == null ? mapper.createObjectNode() : mapper.readTree(raw);
    return n.path("state").asString("");
  }
}
