package com.pmart.mfc.capacity.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.common.web.PmartException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * approval-service {@code POST /internal/approval-ref-uses}(확인 + 한 번 사용, useRef 멱등 — 요청 AP-1). 내부
 * 호출(토큰 없음, ADR-0026). 주소가 없으면 CONFIG_REQUIRED.
 */
@Component
public class ApprovalRefClient {

  public static final String SELF = "mfc-capacity-service";

  public record Use(boolean usable, @Nullable String reason) {}

  private final @Nullable RestClient approval;
  private final ObjectMapper mapper;

  public ApprovalRefClient(
      RestClient.Builder builder,
      ObjectMapper mapper,
      @Value("${pmart.approval-ref.approval-base-url:}") String approvalUrl) {
    this.approval = approvalUrl.isBlank() ? null : builder.clone().baseUrl(approvalUrl).build();
    this.mapper = mapper;
  }

  public Use use(String approvalId, String type, String targetRef, String actorId, String useRef) {
    if (approval == null) {
      throw PmartException.configRequired("pmart.approval-ref.approval-base-url");
    }
    RestClient http = approval;
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
    return new Use(
        n.path("usable").asBoolean(false),
        n.hasNonNull("reason") ? n.get("reason").asString() : null);
  }
}
