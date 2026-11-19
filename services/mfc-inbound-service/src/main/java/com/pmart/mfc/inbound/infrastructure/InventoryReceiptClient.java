package com.pmart.mfc.inbound.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.inbound.application.InboundProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * mfc-inventory ConfirmReceipt 호출(토큰 없음(ADR-0026) — 공용 RestClient 가 {@code /internal/} 에 내부 호출(토큰
 * 없음)을 붙인다). Idempotency-Key = receiptId 라서 재시도해도 inventory 는 한 번만 계상한다.
 *
 * <p>결과 해석: 2xx → 전기됨(commitId), 4xx(401·403 제외) → inventory 의 명시적 거절, 그 밖(미전달·503·응답 불명·인증 설정 문제)
 * → 같은 receiptId 로 다시 보낸다(응답 불명을 실패로 단정하지 않는다).
 */
@Component
public class InventoryReceiptClient {

  /** 전기 결과. */
  public sealed interface Result {
    record Posted(String commitId) implements Result {}

    record Rejected(String reason) implements Result {}

    record Retry(String error) implements Result {}
  }

  /** 전기할 수령. */
  public record Receipt(
      String receiptId,
      String trfId,
      String departureId,
      String mfcId,
      Instant receivedAt,
      String toLocationCode,
      Map<String, Integer> normalQtyBySku) {}

  /** 수령 결과 줄(inventory lotId). */
  public record ReceiptLine(String sku, String lotId, String locationCode, int normalQty) {}

  /** 원장 기록 한 줄(작업 반영 결과에서 나뉜 LOT 을 읽는다). */
  public record Entry(
      String sku,
      @Nullable String lotId,
      @Nullable String locationCode,
      @Nullable String qualityState,
      @Nullable String workState,
      String symbol,
      int delta) {}

  private final RestClient http;
  private final ObjectMapper mapper;

  public InventoryReceiptClient(
      RestClient.Builder builder, InboundProperties props, ObjectMapper mapper) {
    this.http = builder.baseUrl(props.mfcInventoryBaseUrl()).build();
    this.mapper = mapper;
  }

  /** 줄을 직접 만든 수령(회수품 격리 반입 — IV-10). */
  public Result postRaw(
      String receiptId,
      String trfId,
      String departureId,
      String mfcId,
      Instant receivedAt,
      List<Map<String, Object>> lines) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("receiptId", receiptId);
    body.put("trfId", trfId);
    body.put("departureId", departureId);
    body.put("mfcId", mfcId);
    body.put("receivedAt", receivedAt.toString());
    body.put("lines", lines);
    return send(receiptId, body);
  }

  public Result post(Receipt r) {
    List<Map<String, Object>> lines = new ArrayList<>();
    r.normalQtyBySku()
        .forEach(
            (sku, qty) -> {
              Map<String, Object> l = new LinkedHashMap<>();
              l.put("sku", sku);
              l.put("normalQty", qty);
              l.put("quarantinedQty", 0);
              l.put("pendingQty", 0);
              l.put("toLocationCode", r.toLocationCode());
              lines.add(l);
            });
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("receiptId", r.receiptId());
    body.put("trfId", r.trfId());
    body.put("departureId", r.departureId());
    body.put("mfcId", r.mfcId());
    body.put("receivedAt", r.receivedAt().toString());
    body.put("lines", lines);
    return send(r.receiptId(), body);
  }

  private Result send(String receiptId, Map<String, Object> body) {
    try {
      String raw =
          Downstream.call(
              "mfc-inventory-service receipts",
              () ->
                  http.post()
                      .uri("/api/v1/internal/receipts")
                      .header("Idempotency-Key", receiptId)
                      .contentType(MediaType.APPLICATION_JSON)
                      .body(body)
                      .retrieve()
                      .body(String.class));
      JsonNode n = mapper.readTree(raw == null ? "{}" : raw);
      String commitId = n.path("resultId").asString("");
      if (commitId.isBlank()) {
        return new Result.Retry("inventory 응답에 commitId 가 없음");
      }
      return new Result.Posted(commitId);
    } catch (HttpClientErrorException e) {
      int status = e.getStatusCode().value();
      String code = code(e.getResponseBodyAsString());
      if (status == 401 || status == 403) {
        // 권한·설정 문제 — 내용 거절이 아니므로 설정이 고쳐지면 다시 보낸다
        return new Result.Retry("HTTP " + status + " " + code);
      }
      return new Result.Rejected("INVENTORY_REJECTED:" + code + " (HTTP " + status + ")");
    } catch (PmartException e) {
      return new Result.Retry(e.code() + ": " + e.getMessage());
    } catch (RuntimeException e) {
      return new Result.Retry(e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /** 수령 결과(줄별 lotId). 실패는 예외(호출자가 다음 주기에 다시). */
  public List<ReceiptLine> receiptLines(String receiptId) {
    return receiptLines(receiptId, false);
  }

  /**
   * 수령 결과. {@code withPending} 이면 줄 수량 = 정상 + 미판정 보류(둘 다 inventory PENDING_INSPECTION — 입고 예정 수령의
   * 품질검사 대상, 0.16.0).
   */
  public List<ReceiptLine> receiptLines(String receiptId, boolean withPending) {
    String raw =
        Downstream.call(
            "mfc-inventory-service receipts",
            () ->
                http.get()
                    .uri("/api/v1/internal/receipts/{id}", receiptId)
                    .retrieve()
                    .body(String.class));
    List<ReceiptLine> out = new ArrayList<>();
    for (JsonNode l : mapper.readTree(raw == null ? "{}" : raw).path("lines")) {
      out.add(
          new ReceiptLine(
              l.path("sku").asString(),
              l.path("lotId").asString(),
              l.path("locationCode").asString(),
              l.path("normalQty").asInt() + (withPending ? l.path("pendingQty").asInt() : 0)));
    }
    return out;
  }

  /** 작업 실행 반영(CommitTaskExecution). Idempotency-Key = executionId — 다시 보내도 inventory 는 한 번만 반영. */
  public Result execute(String executionId, Map<String, Object> body) {
    try {
      String raw =
          Downstream.call(
              "mfc-inventory-service task-executions",
              () ->
                  http.post()
                      .uri("/api/v1/internal/task-executions")
                      .header("Idempotency-Key", executionId)
                      .contentType(MediaType.APPLICATION_JSON)
                      .body(body)
                      .retrieve()
                      .body(String.class));
      String commitId = mapper.readTree(raw == null ? "{}" : raw).path("resultId").asString("");
      return commitId.isBlank()
          ? new Result.Retry("inventory 응답에 commitId 가 없음")
          : new Result.Posted(commitId);
    } catch (HttpClientErrorException e) {
      int status = e.getStatusCode().value();
      String code = code(e.getResponseBodyAsString());
      if (status == 401 || status == 403) {
        return new Result.Retry("HTTP " + status + " " + code);
      }
      return new Result.Rejected(code + ": " + message(e.getResponseBodyAsString()));
    } catch (PmartException e) {
      return new Result.Retry(e.code() + ": " + e.getMessage());
    } catch (RuntimeException e) {
      return new Result.Retry(e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /** 확정 단위의 원장 기록. 실패는 예외. */
  public List<Entry> commitEntries(String commitId) {
    String raw =
        Downstream.call(
            "mfc-inventory-service commits",
            () ->
                http.get()
                    .uri("/api/v1/internal/commits/{id}", commitId)
                    .retrieve()
                    .body(String.class));
    List<Entry> out = new ArrayList<>();
    for (JsonNode e : mapper.readTree(raw == null ? "{}" : raw).path("entries")) {
      out.add(
          new Entry(
              e.path("sku").asString(),
              e.hasNonNull("lotId") ? e.get("lotId").asString() : null,
              e.hasNonNull("locationCode") ? e.get("locationCode").asString() : null,
              e.hasNonNull("qualityState") ? e.get("qualityState").asString() : null,
              e.hasNonNull("workState") ? e.get("workState").asString() : null,
              e.path("symbol").asString(),
              e.path("delta").asInt()));
    }
    return out;
  }

  private String message(String problem) {
    try {
      return mapper
          .readTree(problem == null || problem.isBlank() ? "{}" : problem)
          .path("detail")
          .asString("");
    } catch (RuntimeException e) {
      return "";
    }
  }

  private String code(String problem) {
    try {
      return mapper
          .readTree(problem == null || problem.isBlank() ? "{}" : problem)
          .path("code")
          .asString("UNKNOWN");
    } catch (RuntimeException e) {
      return "UNKNOWN";
    }
  }
}
