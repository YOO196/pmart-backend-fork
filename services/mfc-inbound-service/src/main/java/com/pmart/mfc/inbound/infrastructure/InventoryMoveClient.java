package com.pmart.mfc.inbound.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.inbound.application.InboundProperties;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * mfc-inventory 이동 배정(IV-03) 호출. 수량 정본은 inventory — 4xx 는 inventory 의 코드·설명을 그대로 돌려주고(센터 관리자·작업자가
 * 이유를 본다), 미전달·불명은 Downstream 의 503/UNKNOWN_RESULT(같은 키로 다시 하면 inventory 가 한 번만 반영).
 */
@Component
public class InventoryMoveClient {

  public static final String INVENTORY = "mfc-inventory-service";
  static final String SOURCE = "mfc-inbound-service";

  private final RestClient http;
  private final ObjectMapper mapper;

  public InventoryMoveClient(
      RestClient.Builder builder, InboundProperties props, ObjectMapper mapper) {
    this.http = builder.clone().baseUrl(props.mfcInventoryBaseUrl()).build();
    this.mapper = mapper;
  }

  public JsonNode create(String key, Map<String, Object> body) {
    return post("/api/v1/internal/move-allocations", key, body);
  }

  public JsonNode pickUp(String moveId, String key, Map<String, Object> body) {
    return post("/api/v1/internal/move-allocations/" + moveId + "/pickups", key, body);
  }

  public JsonNode drop(String moveId, String key, Map<String, Object> body) {
    return post("/api/v1/internal/move-allocations/" + moveId + "/drops", key, body);
  }

  /** 자동 선반 보충 점검(IV-04) — 진행 중 보충 이동 전부를 돌려받는다. */
  public JsonNode evaluateReplenishment(String key) {
    return post("/api/v1/internal/replenishment-evaluations", key, Map.of());
  }

  public JsonNode cancel(String moveId, String key) {
    return post("/api/v1/internal/move-allocations/" + moveId + "/cancellations", key, null);
  }

  /** (0.15.0, PDA BR-52 R19-4) 적치 추천 칸 — mfc-inventory placement-suggestions(#59, 앞이 우선). */
  public List<String> suggestPlacement(String mfcId, String sku, String lotId, int qty) {
    JsonNode n =
        call(
            () ->
                http.get()
                    .uri(
                        u ->
                            u.path("/api/v1/internal/placement-suggestions")
                                .queryParam("mfcId", mfcId)
                                .queryParam("sku", sku)
                                .queryParam("lotId", lotId)
                                .queryParam("qty", qty)
                                .build())
                    .retrieve()
                    .body(String.class));
    java.util.List<String> out = new java.util.ArrayList<>();
    for (JsonNode c : n.path("locationCodes")) {
      out.add(c.asString(""));
    }
    return out;
  }

  private JsonNode post(String uri, String key, Object body) {
    return call(
        () -> {
          var spec =
              http.post().uri(uri).header("Idempotency-Key", key).header("X-Source-System", SOURCE);
          var withBody =
              body == null ? spec : spec.contentType(MediaType.APPLICATION_JSON).body(body);
          return withBody.retrieve().body(String.class);
        });
  }

  private JsonNode call(Supplier<String> s) {
    try {
      String raw = Downstream.call(INVENTORY, s::get);
      return raw == null ? mapper.createObjectNode() : mapper.readTree(raw);
    } catch (HttpClientErrorException e) {
      JsonNode p;
      try {
        p = mapper.readTree(e.getResponseBodyAsString());
      } catch (RuntimeException ex) {
        p = mapper.createObjectNode();
      }
      throw new PmartException(
          p.path("code").asString("HTTP_" + e.getStatusCode().value()),
          HttpStatus.valueOf(e.getStatusCode().value()),
          p.path("detail").asString(e.getStatusText()),
          false,
          List.of());
    }
  }
}
