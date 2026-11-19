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

/** mfc-inbound 이동 지시 조회(작업자 쪽 4단계 — 다음 작업 받기에 이동·보충). 호출자(작업자) 토큰을 넘긴다. */
@Component
public class InboundClient {

  public static final String INBOUND = "mfc-inbound-service";

  /** 이동 지시 사본. taskType = REPLENISH(자동 보충) / MOVE(센터 관리자). */
  public record MoveView(
      String moveId,
      String taskType,
      String state,
      boolean urgent,
      int pickedQty,
      @Nullable String assigneeId,
      @Nullable Instant createdAt,
      @Nullable String fromLocation,
      @Nullable String toLocation,
      int qty) {

    public MoveView(
        String moveId,
        String taskType,
        String state,
        boolean urgent,
        int pickedQty,
        @Nullable String assigneeId,
        @Nullable Instant createdAt) {
      this(moveId, taskType, state, urgent, pickedQty, assigneeId, createdAt, null, null, 0);
    }
  }

  /** (0.14.0, BR-52 R2) 입고 작업(품질검사·적치) 사본 — READY 만. */
  public record InboundTaskView(
      String taskId, String taskType, @Nullable String assigneeId, @Nullable Instant createdAt) {}

  private final @Nullable RestClient client;
  private final ObjectMapper mapper;

  public InboundClient(RestClient.Builder builder, PlanProperties props, ObjectMapper mapper) {
    this.client =
        props.mfcInboundBaseUrl() == null || props.mfcInboundBaseUrl().isBlank()
            ? null
            : builder.clone().baseUrl(Objects.requireNonNull(props.mfcInboundBaseUrl())).build();
    this.mapper = mapper;
  }

  public boolean configured() {
    return client != null;
  }

  /** 열린 이동 지시(ACTIVE·CANCEL_PENDING). */
  public List<MoveView> openMoves(String mfcId) {
    if (client == null) {
      return List.of();
    }
    String auth = currentAuthorization();
    String raw =
        Downstream.call(
            INBOUND,
            () ->
                client
                    .get()
                    .uri("/api/v1/move-orders?mfcId={m}&withLabels=false", mfcId)
                    .headers(
                        h -> {
                          if (auth != null) {
                            h.set(HttpHeaders.AUTHORIZATION, auth);
                          }
                        })
                    .retrieve()
                    .body(String.class));
    JsonNode n = raw == null ? mapper.createObjectNode() : mapper.readTree(raw);
    List<MoveView> out = new ArrayList<>();
    for (JsonNode m : n.path("items")) {
      String created = m.path("createdAt").asString(null);
      String assignee = m.path("assigneeId").asString(null);
      out.add(
          new MoveView(
              m.path("moveId").asString(""),
              "REPLENISH".equals(m.path("kind").asString("MOVE")) ? "REPLENISH" : "MOVE",
              m.path("state").asString(""),
              m.path("urgent").asBoolean(false),
              m.path("pickedQty").asInt(0),
              assignee == null || assignee.isBlank() ? null : assignee,
              created == null || created.isBlank() ? null : Instant.parse(created),
              m.path("fromLocation").asString(null),
              m.path("toLocation").asString(null),
              m.path("qty").asInt(0)));
    }
    return out;
  }

  /** 열린(READY) 입고 작업 — 품질검사·적치(0.14.0). 상품명·바코드는 받지 않는다(동기 호출 2 hop). */
  public List<InboundTaskView> openInboundTasks(String mfcId) {
    if (client == null) {
      return List.of();
    }
    String auth = currentAuthorization();
    String raw =
        Downstream.call(
            INBOUND,
            () ->
                client
                    .get()
                    .uri("/api/v1/inbound-tasks?mfcId={m}&state=READY&withLabels=false", mfcId)
                    .headers(
                        h -> {
                          if (auth != null) {
                            h.set(HttpHeaders.AUTHORIZATION, auth);
                          }
                        })
                    .retrieve()
                    .body(String.class));
    JsonNode n = raw == null ? mapper.createObjectNode() : mapper.readTree(raw);
    List<InboundTaskView> out = new ArrayList<>();
    for (JsonNode t : n.path("items")) {
      String created = t.path("createdAt").asString(null);
      String assignee = t.path("assigneeId").asString(null);
      out.add(
          new InboundTaskView(
              t.path("taskId").asString(""),
              t.path("taskType").asString(""),
              assignee == null || assignee.isBlank() ? null : assignee,
              created == null || created.isBlank() ? null : Instant.parse(created)));
    }
    return out;
  }

  /** 입고 작업 한 건 원문 — 다음 작업 받기 상세(0.14.0). */
  public JsonNode inboundTask(String taskId) {
    if (client == null) {
      return mapper.createObjectNode();
    }
    String auth = currentAuthorization();
    String raw =
        Downstream.call(
            INBOUND,
            () ->
                client
                    .get()
                    .uri("/api/v1/inbound-tasks/{t}?withLabels=false", taskId)
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

  /** 이동 지시 한 건 원문 — 다음 작업 받기 상세(0.9.1). */
  public JsonNode moveOrder(String moveId) {
    if (client == null) {
      return mapper.createObjectNode();
    }
    String auth = currentAuthorization();
    String raw =
        Downstream.call(
            INBOUND,
            () ->
                client
                    .get()
                    .uri("/api/v1/move-orders/{m}?withLabels=false", moveId)
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

  /**
   * (0.24.0, BR-52 R2·R12·R13) 회수품 일 하나 — RETURN_RECEIPT(반납 도착 대기, taskId = arrivalId) /
   * RETURN_INSPECT(검수 대기 반입, taskId = returnReceiptId). at = 도착·반입 시각(같은 유형 순서).
   */
  public record ReturnWorkView(String taskId, String taskType, @Nullable Instant at) {}

  /** 반납 도착 대기(WAITING, 오래된 순 최대 200) — 원문 그대로(상세용). */
  public List<JsonNode> waitingReturnArrivals(String mfcId) {
    JsonNode n = get("/api/v1/return-arrivals?mfcId={m}&state=WAITING&size=200", mfcId);
    List<JsonNode> out = new ArrayList<>();
    n.path("items").forEach(out::add);
    return out;
  }

  /** 반납 도착 대기 → 회수품 반입 일(RETURN_RECEIPT). */
  public List<ReturnWorkView> returnReceiptWork(String mfcId) {
    List<ReturnWorkView> out = new ArrayList<>();
    for (JsonNode a : waitingReturnArrivals(mfcId)) {
      out.add(
          new ReturnWorkView(
              a.path("arrivalId").asString(""), "RETURN_RECEIPT", at(a, "arrivedAt")));
    }
    return out;
  }

  /**
   * 검수 대기 반입(재고 반영 APPLIED·검수 PENDING) → 회수품 검수 일(RETURN_INSPECT). {@code inspectionState=PENDING}
   * 은 mfc-inbound 0.15.0 부터 서버가 걸러 오래된 순으로 준다 — 그 전(0.14.0)에는 무시되고 최근 반입 200건이 오므로 여기서도 거르고 반입 시각으로
   * 정렬한다.
   */
  public List<ReturnWorkView> returnInspectWork(String mfcId) {
    JsonNode n =
        get(
            "/api/v1/return-receipts?mfcId={m}&state=APPLIED&inspectionState=PENDING&size=200",
            mfcId);
    List<ReturnWorkView> out = new ArrayList<>();
    for (JsonNode r : n.path("items")) {
      if ("PENDING".equals(r.path("inspectionState").asString(""))
          && "APPLIED".equals(r.path("inventoryApplyStatus").asString(""))) {
        out.add(
            new ReturnWorkView(
                r.path("returnReceiptId").asString(""), "RETURN_INSPECT", at(r, "receivedAt")));
      }
    }
    return out;
  }

  /**
   * (0.25.0, BR-52 R2·BR-53 R9) 끝나지 않은 입고 예정(EXPECTED·RECEIVING — {@code state} 를 비우면 mfc-inbound 가
   * 둘 다, 예정 도착 이른 순 최대 200) 원문. 들고 있는 RECEIVE 판정(목록에 남아 있음)과 후보 고르기에 같이 쓴다.
   */
  public List<JsonNode> openInboundPromises(String mfcId) {
    JsonNode n = get("/api/v1/inbound-promises?mfcId={m}&size=200", mfcId);
    List<JsonNode> out = new ArrayList<>();
    n.path("items").forEach(out::add);
    return out;
  }

  /**
   * 지금 받을 수 있는 입고(RECEIVE, taskId = trfId)인지 — 순수 판정. (0.26.0, 사용자 결정 2026-11-20 "도착한 것만") 도착 등록된
   * RECEIVING 이면서 출발(TOUT, {@code departureId})을 받았고 재고 반영 전
   * 수령(CAPTURED·SUBMITTED·PROCESSING·UNKNOWN_RESULT)이 없는 것만. EXPECTED(차가 아직 안 왔다 — 0.25.0 은 출발했으면
   * 줬다)는 주지 않는다. 진행 중 수령이 있으면 그 수령을 낸 사람이 같은 키로 마저 해야 해서 새로 주지 않는다(mfc-inbound 도 409). REJECTED 수령만
   * 있으면 고쳐서 다시 — 다시 준다.
   */
  public static boolean receivable(JsonNode p) {
    String departure = p.path("departureId").asString(null);
    if (departure == null || departure.isBlank()) {
      return false;
    }
    if (!"RECEIVING".equals(p.path("state").asString(""))) {
      return false;
    }
    for (JsonNode r : p.path("receipts")) {
      String s = r.path("inventoryApplyStatus").asString("");
      if (!"APPLIED".equals(s) && !"REJECTED".equals(s)) {
        return false;
      }
    }
    return true;
  }

  /** 입고 예정 → 입고 일(RECEIVE). at = 예정 도착(없으면 출발 시각 — mfc-inbound 목록 순서와 같다). */
  public static List<ReturnWorkView> receiveWork(List<JsonNode> promises) {
    List<ReturnWorkView> out = new ArrayList<>();
    for (JsonNode p : promises) {
      if (receivable(p)) {
        Instant when = at(p, "plannedArrivalAt");
        out.add(
            new ReturnWorkView(
                p.path("trfId").asString(""),
                "RECEIVE",
                when != null ? when : at(p, "departedAt")));
      }
    }
    return out;
  }

  /** 입고 예정 한 건 원문 — 다음 작업 받기 상세(0.25.0). */
  public JsonNode inboundPromise(String trfId) {
    return get("/api/v1/inbound-promises/{t}", trfId);
  }

  /** 회수품 반입 한 건 원문 — 다음 작업 받기 상세(0.24.0). */
  public JsonNode returnReceipt(String returnReceiptId) {
    return get("/api/v1/return-receipts/{r}", returnReceiptId);
  }

  private JsonNode get(String uri, Object... vars) {
    RestClient http = client;
    if (http == null) {
      return mapper.createObjectNode();
    }
    String auth = currentAuthorization();
    String raw =
        Downstream.call(
            INBOUND,
            () ->
                http.get()
                    .uri(uri, vars)
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

  private static @Nullable Instant at(JsonNode n, String field) {
    String v = n.path(field).asString(null);
    return v == null || v.isBlank() ? null : Instant.parse(v);
  }

  private static @Nullable String currentAuthorization() {
    if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a) {
      HttpServletRequest r = a.getRequest();
      return r.getHeader(HttpHeaders.AUTHORIZATION);
    }
    return null;
  }
}
