package com.pmart.mfc.inbound.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.inbound.application.InboundProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * cdc-outbound {@code POST /internal/transfer-intakes} — 승인된 센터 발주를 보급 DC 에 이관(TRF) 요청으로
 * 보낸다(ADR-0091, 사용자 2026-11-26 "자동보충 만들어"). 원래 replenishment(SCM)의 TransferApproved 소비 경로지만 그 서비스가
 * 없어 mfc-inbound 가 대신 부른다. 토큰 없음(ADR-0026). Idempotency-Key·trfId·버전이 고정이라 다시 보내도 CDC 는 한 번만 인수한다.
 *
 * <p>결과: 2xx 인수, 409(같은 버전 다른 본문·이미 출발·취소)·400·422 는 내용 거절(다시 보내지 않음), 그 밖은 다시.
 */
@Component
public class CdcTransferIntakeClient {

  /** 보낼 TRF. */
  public record Transfer(
      String trfId,
      String cdcId,
      String mfcId,
      String approvalRef,
      Instant dueAt,
      String temperatureZone,
      Map<String, Integer> lines) {}

  /** (0.25.0, R-52) TRF·Wave 화면 번호 — 모르면 null. */
  public record Numbers(
      @org.jspecify.annotations.Nullable String trfNumber,
      @org.jspecify.annotations.Nullable String waveId,
      @org.jspecify.annotations.Nullable String waveNumber) {}

  /** (0.27.0, CO-3 ①) 미출발 취소 결과. */
  public sealed interface Cancel {
    /** 취소됨(APPLIED, 또는 이미 CANCELLED). */
    record Cancelled() implements Cancel {}

    /** DC 에 그 TRF 가 없음(인수 전 — 받을 센터가 달라도 404). */
    record NotAtCdc() implements Cancel {}

    /** 재고 해제 결과 불명(ACCEPTED) — 같은 키·본문으로 다시. */
    record Pending(String detail) implements Cancel {}

    /** DC 가 거절(409 PICKING_STARTED·INVALID_STATE… 또는 REJECTED). */
    record Refused(String code, String detail) implements Cancel {}

    /** 응답 없음·장애. */
    record Unavailable(String detail) implements Cancel {}
  }

  /** 결과. */
  public sealed interface Result {
    record Accepted() implements Result {}

    record Rejected(String reason) implements Result {}

    record Retry(String error) implements Result {}
  }

  static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

  private final RestClient http;
  private final tools.jackson.databind.ObjectMapper mapper;

  public CdcTransferIntakeClient(
      RestClient.Builder builder,
      InboundProperties props,
      tools.jackson.databind.ObjectMapper mapper) {
    // cdc-outbound 는 인수와 같은 요청 안에서 cdc-inventory 할당까지 해 기본 5초를 넘길 때가 있다(2026-11-27 LAN 냉장 TRF 첫 호출
    // UNKNOWN_RESULT — 실제로는 인수됨, 재시도로 1분 늦음). 이 호출만 읽기 15초.
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(2));
    factory.setReadTimeout(READ_TIMEOUT);
    this.http = builder.clone().baseUrl(props.cdcOutboundBaseUrl()).requestFactory(factory).build();
    this.mapper = mapper;
  }

  /**
   * (0.25.0, R-52) cdc-outbound {@code GET /internal/transfer-intakes/{trfId}} 의
   * trfNumber·waveId·waveNumber — 표시용이라 실패(없는 TRF·미배포·장애)는 empty.
   */
  public java.util.Optional<Numbers> numbers(String trfId) {
    try {
      String raw =
          Downstream.call(
              "cdc-outbound-service transfer-intakes",
              () ->
                  http.get()
                      .uri("/api/v1/internal/transfer-intakes/{id}", trfId)
                      .retrieve()
                      .body(String.class));
      tools.jackson.databind.JsonNode n = mapper.readTree(raw == null ? "{}" : raw);
      return java.util.Optional.of(
          new Numbers(text(n, "trfNumber"), text(n, "waveId"), text(n, "waveNumber")));
    } catch (RuntimeException e) {
      return java.util.Optional.empty();
    }
  }

  private static @org.jspecify.annotations.Nullable String text(
      tools.jackson.databind.JsonNode n, String f) {
    return n.hasNonNull(f) && !n.get(f).asString("").isBlank() ? n.get(f).asString() : null;
  }

  /**
   * (0.27.0, CO-3 ①) cdc-outbound {@code POST /internal/transfer-intakes/{trfId}/cancellations} —
   * FULL, 피킹 시작 전만. 409 INVALID_STATE 면 인수 조회로 이미 CANCELLED 인지 본다(다른 키로 먼저 취소된 경우).
   */
  public Cancel cancel(
      String trfId, String mfcId, String supplyRequestId, String requestedBy, String key) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("mfcId", mfcId);
    body.put("reason", "TRANSFER_CANCELLED");
    body.put("sourceRef", supplyRequestId);
    body.put("requestedBy", requestedBy.length() > 64 ? requestedBy.substring(0, 64) : requestedBy);
    try {
      String raw =
          Downstream.call(
              "cdc-outbound-service transfer-intake cancellations",
              () ->
                  http.post()
                      .uri("/api/v1/internal/transfer-intakes/{id}/cancellations", trfId)
                      .header("Idempotency-Key", key)
                      .header("X-Source-System", "mfc-inbound-service")
                      .contentType(MediaType.APPLICATION_JSON)
                      .body(body)
                      .retrieve()
                      .body(String.class));
      tools.jackson.databind.JsonNode n = mapper.readTree(raw == null ? "{}" : raw);
      String status = n.path("status").asString("");
      return switch (status) {
        case "APPLIED" -> new Cancel.Cancelled();
        case "REJECTED" ->
            new Cancel.Refused(
                "CDC_CANCEL_REJECTED", "DC 재고가 할당 해제를 거절했습니다(TRF CANCEL_HOLD) — DC 확인 필요");
        default -> new Cancel.Pending("DC 미출발 취소 결과를 아직 모릅니다(" + status + ")");
      };
    } catch (HttpClientErrorException e) {
      int st = e.getStatusCode().value();
      if (st == 404) {
        return new Cancel.NotAtCdc();
      }
      String code = "HTTP_" + st;
      String detail = e.getStatusText();
      try {
        tools.jackson.databind.JsonNode p = mapper.readTree(e.getResponseBodyAsString());
        code = p.path("code").asString(code);
        detail = p.path("detail").asString(detail);
      } catch (RuntimeException ignored) {
        // 본문이 문제 형식이 아니면 HTTP 상태로
      }
      if (st == 409 && "INVALID_STATE".equals(code) && "CANCELLED".equals(state(trfId))) {
        return new Cancel.Cancelled();
      }
      if (st == 409 || st == 400) {
        return new Cancel.Refused(code, detail);
      }
      return new Cancel.Unavailable("HTTP " + st + " " + code);
    } catch (PmartException e) {
      return new Cancel.Unavailable(e.code() + ": " + e.getMessage());
    } catch (RuntimeException e) {
      return new Cancel.Unavailable(e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  private @org.jspecify.annotations.Nullable String state(String trfId) {
    try {
      String raw =
          Downstream.call(
              "cdc-outbound-service transfer-intakes",
              () ->
                  http.get()
                      .uri("/api/v1/internal/transfer-intakes/{id}", trfId)
                      .retrieve()
                      .body(String.class));
      return text(mapper.readTree(raw == null ? "{}" : raw), "state");
    } catch (RuntimeException e) {
      return null;
    }
  }

  public Result send(Transfer t) {
    List<Map<String, Object>> lines = new ArrayList<>();
    t.lines()
        .forEach(
            (sku, qty) -> {
              Map<String, Object> l = new LinkedHashMap<>();
              l.put("sku", sku);
              l.put("approvedQty", qty);
              lines.add(l);
            });
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("trfId", t.trfId());
    body.put("trfVersion", 1);
    body.put("cdcId", t.cdcId());
    body.put("mfcId", t.mfcId());
    body.put("approvalRef", t.approvalRef());
    // R-27(사용자 2026-11-27 PC 3 전달): 기한 = 희망 사용준비 시각(READY), TRF 하나에 온도대 하나
    body.put("deadlineMeaning", "READY");
    body.put("dueAt", t.dueAt().toString());
    body.put(
        "conditions", List.of(Map.of("kind", "TEMPERATURE_ZONE", "value", t.temperatureZone())));
    body.put("lines", lines);
    try {
      Downstream.call(
          "cdc-outbound-service transfer-intakes",
          () ->
              http.post()
                  .uri("/api/v1/internal/transfer-intakes")
                  .header("Idempotency-Key", "mfc-inbound-supply-trf:" + t.trfId())
                  .header("X-Source-System", "mfc-inbound-service")
                  .contentType(MediaType.APPLICATION_JSON)
                  .body(body)
                  .retrieve()
                  .toBodilessEntity());
      return new Result.Accepted();
    } catch (HttpClientErrorException e) {
      int status = e.getStatusCode().value();
      // 401·403·404·408·429 는 설정·배포 순서 문제일 수 있어 다시, 그 밖 4xx 는 내용 거절
      if (status != 401 && status != 403 && status != 404 && status != 408 && status != 429) {
        return new Result.Rejected("HTTP " + status + " " + e.getResponseBodyAsString());
      }
      return new Result.Retry("HTTP " + status + " " + e.getResponseBodyAsString());
    } catch (PmartException e) {
      return new Result.Retry(e.code() + ": " + e.getMessage());
    } catch (RuntimeException e) {
      return new Result.Retry(e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }
}
