package com.pmart.mfc.capacity.infrastructure;

import com.pmart.common.web.Downstream;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * (0.35.0, 2026-11-24 사용자 결정 "폐기 실행을 MFC 작업자에게 할 일로 자동 배정") mfc-quality {@code GET
 * /disposal-approvals?mfcId&state=APPROVED&state=PARTIALLY_EXECUTED}: 승인돼 실행을 기다리는 폐기(오래된 순).
 * 호출자(작업자) 토큰을 넘긴다(그 센터 운영자 조회). 주소는 작업자 처리 내역과 같은 {@code
 * pmart.capacity.activities.mfc-quality-base-url} — 없으면 폐기 실행은 다음 작업 후보에 없다.
 */
@Component
public class DisposalWorkClient {

  /** 실행 대기 폐기 하나. approverId = 승인한 센터 관리자(그 사람에게는 자동 배정하지 않는다). */
  public record DisposalWork(
      String disposalApprovalId,
      @Nullable String approverId,
      @Nullable Instant decidedAt,
      JsonNode raw) {}

  private final @Nullable RestClient client;
  private final ObjectMapper mapper;

  public DisposalWorkClient(
      RestClient.Builder builder,
      ObjectMapper mapper,
      @Value("${pmart.capacity.activities.mfc-quality-base-url:}") String qualityUrl) {
    this.client =
        qualityUrl == null || qualityUrl.isBlank()
            ? null
            : builder.clone().baseUrl(qualityUrl).build();
    this.mapper = mapper;
  }

  public boolean configured() {
    return client != null;
  }

  /** 실행 대기(APPROVED·PARTIALLY_EXECUTED, 남은 양 있음) — 승인 시각 오래된 순 최대 200. */
  public List<DisposalWork> list(String mfcId) {
    RestClient http = client;
    if (http == null) {
      return List.of();
    }
    String auth = currentAuthorization();
    String raw =
        Downstream.call(
            "mfc-quality-service disposal-approvals",
            () ->
                http.get()
                    .uri(
                        "/api/v1/disposal-approvals?mfcId={m}&state=APPROVED&state=PARTIALLY_EXECUTED&size=200",
                        mfcId)
                    .headers(
                        h -> {
                          if (auth != null) {
                            h.set(HttpHeaders.AUTHORIZATION, auth);
                          }
                        })
                    .retrieve()
                    .body(String.class));
    JsonNode n = raw == null ? mapper.createObjectNode() : mapper.readTree(raw);
    List<DisposalWork> out = new ArrayList<>();
    for (JsonNode d : n.path("items")) {
      String state = d.path("state").asString("");
      if (!"APPROVED".equals(state) && !"PARTIALLY_EXECUTED".equals(state)) {
        continue;
      }
      int remaining = 0;
      for (JsonNode l : d.path("lines")) {
        remaining += Math.max(0, l.path("remainingQty").asInt(0));
      }
      if (remaining <= 0) {
        continue;
      }
      String at = d.path("decidedAt").asString(null);
      out.add(
          new DisposalWork(
              d.path("disposalApprovalId").asString(""),
              d.path("approverId").isNull() ? null : d.path("approverId").asString(null),
              at == null || at.isBlank() ? null : Instant.parse(at),
              d));
    }
    return out;
  }

  private static @Nullable String currentAuthorization() {
    if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a) {
      HttpServletRequest r = a.getRequest();
      return r.getHeader(HttpHeaders.AUTHORIZATION);
    }
    return null;
  }
}
