package com.pmart.mfc.capacity.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.mfc.capacity.application.PlanProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * mfc-fulfillment 작업 목록(동기 1 hop, 호출자 토큰 전달). 작업 정본은 fulfillment — 여기서는 계산 입력 사본만. 실패는
 * PmartException (503 DEPENDENCY_UNAVAILABLE 등) 그대로.
 */
@Component
public class FulfillmentClient {

  public static final String FULFILLMENT = "mfc-fulfillment-service";
  static final int MAX_TASKS = 500;

  /**
   * 작업 사본. workerId = 지금 담당(fulfillment 투영, 없으면 null). pickedBy = PACK 의 같은 출고 PICK 을 끝낸
   * 사람(fulfillment 0.18.0, 그 밖 null).
   */
  public record TaskView(
      String taskId,
      String taskType,
      String state,
      int version,
      @Nullable String workerId,
      @Nullable Instant promisedReadyAt,
      @Nullable Integer plannedQty,
      @Nullable Integer remainingQty,
      @Nullable String pickedBy) {

    public TaskView(
        String taskId,
        String taskType,
        String state,
        int version,
        @Nullable String workerId,
        @Nullable Instant promisedReadyAt) {
      this(taskId, taskType, state, version, workerId, promisedReadyAt, null, null, null);
    }

    public TaskView(
        String taskId,
        String taskType,
        String state,
        int version,
        @Nullable String workerId,
        @Nullable Instant promisedReadyAt,
        @Nullable Integer plannedQty,
        @Nullable Integer remainingQty) {
      this(
          taskId,
          taskType,
          state,
          version,
          workerId,
          promisedReadyAt,
          plannedQty,
          remainingQty,
          null);
    }
  }

  private final RestClient client;
  private final ObjectMapper mapper;

  public FulfillmentClient(RestClient.Builder builder, PlanProperties props, ObjectMapper mapper) {
    this.client =
        builder
            .clone()
            .baseUrl(
                Objects.requireNonNullElse(props.mfcFulfillmentBaseUrl(), "http://unconfigured"))
            .build();
    this.mapper = mapper;
  }

  /** 상태들의 작업(최대 500개, 페이지를 따라간다). */
  public List<TaskView> tasks(String mfcId, List<String> states) {
    String auth = currentAuthorization();
    List<TaskView> out = new ArrayList<>();
    String cursor = null;
    do {
      StringBuilder uri = new StringBuilder("/api/v1/tasks?mfcId={m}&size=200");
      for (String s : states) {
        uri.append("&state=").append(s);
      }
      if (cursor != null) {
        uri.append("&cursor={c}");
      }
      String c = cursor;
      String raw =
          Downstream.call(
              FULFILLMENT,
              () ->
                  client
                      .get()
                      .uri(
                          uri.toString(),
                          c == null ? new Object[] {mfcId} : new Object[] {mfcId, c})
                      .headers(
                          h -> {
                            if (auth != null) {
                              h.set(HttpHeaders.AUTHORIZATION, auth);
                            }
                          })
                      .retrieve()
                      .body(String.class));
      JsonNode n = raw == null ? mapper.createObjectNode() : mapper.readTree(raw);
      for (JsonNode t : n.path("items")) {
        String promised = t.path("promisedReadyAt").asString(null);
        String worker = t.path("assignment").path("workerId").asString(null);
        out.add(
            new TaskView(
                t.path("taskId").asString(""),
                t.path("taskType").asString(""),
                t.path("state").asString(""),
                t.path("version").asInt(0),
                worker == null || worker.isBlank() ? null : worker,
                promised == null || promised.isBlank() ? null : Instant.parse(promised),
                t.path("plannedQty").isNumber() ? t.path("plannedQty").asInt() : null,
                t.path("remainingQty").isNumber() ? t.path("remainingQty").asInt() : null,
                blankToNull(t.path("pickedBy").asString(null))));
      }
      cursor = n.path("page").path("nextCursor").asString(null);
      if (cursor != null && cursor.isBlank()) {
        cursor = null;
      }
    } while (cursor != null && out.size() < MAX_TASKS);
    return out.size() > MAX_TASKS ? out.subList(0, MAX_TASKS) : out;
  }

  /** 작업 상세 원문(GET /tasks/{id}) — 다음 작업 받기 상세(0.9.1). 호출자 토큰. */
  public JsonNode taskDetail(String taskId) {
    String auth = currentAuthorization();
    String raw =
        Downstream.call(
            FULFILLMENT,
            () ->
                client
                    .get()
                    .uri("/api/v1/tasks/{t}", taskId)
                    .headers(
                        h -> {
                          if (auth != null) {
                            h.set(HttpHeaders.AUTHORIZATION, auth);
                          }
                        })
                    .retrieve()
                    .body(String.class));
    return raw == null ? mapper.createObjectNode() : mapper.readTree(raw);
  }

  private static @Nullable String currentAuthorization() {
    if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a) {
      HttpServletRequest r = a.getRequest();
      return r.getHeader(HttpHeaders.AUTHORIZATION);
    }
    return null;
  }

  private static @Nullable String blankToNull(@Nullable String v) {
    return v == null || v.isBlank() ? null : v;
  }
}
