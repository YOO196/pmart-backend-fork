package com.pmart.mfc.inbound.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.CommandResponse;
import com.pmart.common.web.CommandStatus;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.inbound.infrastructure.InboundTaskStore;
import com.pmart.mfc.inbound.infrastructure.InventoryReceiptClient;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 회수품 반입(IV-10, 2026-11-08 사용자 결정: "환불 격리로 뒀다가 품질 검수, 적격이면 재배치").
 *
 * <ol>
 *   <li>반입 기록(SUBMITTED) → mfc-inventory 수령: 반품 위치에 **격리(QUARANTINED)** 로만(receiptId = 반입 ID, trfId
 *       = claimId, departureId = 회수 ID). 판매 가능 아님.
 *   <li>반영(APPLIED)되면 줄별 inventory lotId 를 받아 `ReturnItemReceived`(mfc.inbound.v1) → mfc-quality
 *       회수품 검수.
 *   <li>`ReturnInspected`(mfc.quality.v1) 의 RESALE 분 → 적치(PUTAWAY) 작업(반품 위치 → 선반, 진열 규칙은 inventory
 *       가 판정). DISPOSE 분은 quality 폐기 승인으로.
 * </ol>
 *
 * 환불(claim)은 여기서 하지 않는다 — claim 이 ReturnItemReceived·ReturnInspected 를 받아 처리(반품 클레임 구현 때).
 */
@Service
public class ReturnReceiptService {

  public static final String NOT_FOUND = "RETURN_RECEIPT_NOT_FOUND";
  private static final Logger log = LoggerFactory.getLogger(ReturnReceiptService.class);
  private static final TypeReference<List<Line>> LINES = new TypeReference<>() {};

  public record Line(
      @Nullable String orderLineId,
      String sku,
      int qty,
      @Nullable String sourceLotRef,
      @Nullable LocalDate expiryDate,
      @Nullable String conditionNote,
      @Nullable String evidenceRef,
      @Nullable String lotId) {

    Line with(@Nullable String orderLine, int q) {
      return new Line(
          orderLine, sku, q, sourceLotRef, expiryDate, conditionNote, evidenceRef, lotId);
    }
  }

  public record Command(
      String mfcId,
      String toLocationCode,
      @Nullable String claimId,
      @Nullable String returnPickupId,
      @Nullable String orderId,
      @Nullable Instant receivedAt,
      List<Line> lines,
      @Nullable String arrivalId) {}

  public record Row(
      String returnReceiptId,
      String mfcId,
      @Nullable String claimId,
      String returnPickupId,
      @Nullable String orderId,
      String toLocation,
      List<Line> lines,
      String state,
      @Nullable String commitId,
      @Nullable String rejectReason,
      String inspectionState,
      String receivedBy,
      Instant receivedAt,
      String purpose,
      @Nullable String caseId,
      @Nullable String originDeliveryId,
      @Nullable String arrivalId) {}

  private final JdbcTemplate jdbc;
  private final InventoryReceiptClient inventory;
  private final InboundTaskStore tasks;
  private final RequestIdempotency idempotency;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;
  private final ObjectMapper mapper;
  private final TransactionTemplate tx;
  private final Clock clock;
  private @Nullable ReturnArrivals arrivals;

  @org.springframework.beans.factory.annotation.Autowired
  void setArrivals(ReturnArrivals arrivals) {
    this.arrivals = arrivals;
  }

  public ReturnReceiptService(
      JdbcTemplate jdbc,
      InventoryReceiptClient inventory,
      InboundTaskStore tasks,
      RequestIdempotency idempotency,
      EventPublisher events,
      EventEnvelopeCodec codec,
      ObjectMapper mapper,
      PlatformTransactionManager txm,
      Clock clock) {
    this.jdbc = jdbc;
    this.inventory = inventory;
    this.tasks = tasks;
    this.idempotency = idempotency;
    this.events = events;
    this.codec = codec;
    this.mapper = mapper;
    this.tx = new TransactionTemplate(txm);
    this.clock = clock;
  }

  public CommandResponse record(String requestId, String sourceSystem, Command c) {
    String me = InboundTaskService.requireOperator(c.mfcId());
    if (c.toLocationCode() == null || c.toLocationCode().isBlank()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "toLocationCode(반품 위치)가 필요합니다.");
    }
    if (c.lines().stream().anyMatch(l -> l.qty() < 1)) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "줄마다 수량은 1 이상입니다.");
    }
    if (c.arrivalId() == null
        && (blank(c.claimId()) || blank(c.returnPickupId()) || c.lines().isEmpty())) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED,
          "반납 도착 건(arrivalId)을 고르거나 claimId·returnPickupId·lines 를 보내 주세요.");
    }
    String id =
        idempotency
            .execute(
                new RequestKey(sourceSystem, requestId, "recordReturnReceipt", me),
                c,
                String.class,
                () -> {
                  String rid = "RRC-" + UUID.randomUUID();
                  Instant now = clock.instant();
                  Resolved v = resolve(c);
                  jdbc.update(
                      "INSERT INTO return_receipt (return_receipt_id, mfc_id, claim_id, return_pickup_id,"
                          + " order_id, to_location, lines, state, received_by, received_at, created_at,"
                          + " updated_at, purpose, case_id, origin_delivery_id, arrival_id)"
                          + " VALUES (?,?,?,?,?,?,?::jsonb,'SUBMITTED',?,?,?,?,?,?,?,?)",
                      rid,
                      c.mfcId(),
                      v.claimId(),
                      v.returnPickupId(),
                      v.orderId(),
                      c.toLocationCode(),
                      mapper.writeValueAsString(v.lines()),
                      me,
                      Timestamp.from(c.receivedAt() == null ? now : c.receivedAt()),
                      Timestamp.from(now),
                      Timestamp.from(now),
                      v.purpose(),
                      v.caseId(),
                      v.originDeliveryId(),
                      c.arrivalId());
                  if (c.arrivalId() != null) {
                    java.util.Objects.requireNonNull(arrivals).link(c.arrivalId(), rid);
                  }
                  log.info(
                      "회수품 반입 {} 기록 — {} 클레임 {} 반납 {} {}줄 by {}",
                      rid,
                      v.purpose(),
                      v.claimId(),
                      c.arrivalId(),
                      v.lines().size(),
                      me);
                  return rid;
                })
            .value();
    post(id);
    Row r = find(id);
    CommandStatus st =
        switch (r.state()) {
          case "APPLIED" -> CommandStatus.APPLIED;
          case "REJECTED" -> CommandStatus.REJECTED;
          default -> CommandStatus.UNKNOWN_RESULT;
        };
    return new CommandResponse(
        st,
        requestId,
        id,
        r.lines().stream().mapToInt(Line::qty).sum(),
        1,
        st == CommandStatus.UNKNOWN_RESULT,
        st == CommandStatus.UNKNOWN_RESULT ? "RETRY_SAME_KEY" : null,
        clock.instant());
  }

  /** 재고 수령(격리) — 아직 반영 안 된 반입만. 결과 모름은 같은 반입 ID 로 다시(재고는 receiptId 로 한 번). */
  private void post(String id) {
    Row r = find(id);
    if (!"SUBMITTED".equals(r.state()) && !"UNKNOWN_RESULT".equals(r.state())) {
      return;
    }
    List<Map<String, Object>> lines = new ArrayList<>();
    for (Line l : r.lines()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("sku", l.sku());
      m.put("normalQty", 0);
      m.put("quarantinedQty", l.qty());
      m.put("pendingQty", 0);
      m.put("toLocationCode", r.toLocation());
      m.put("sourceLotRef", l.sourceLotRef() == null ? "RET-" + refOf(r) : l.sourceLotRef());
      if (l.expiryDate() != null) {
        m.put("expiryDate", l.expiryDate().toString());
      }
      lines.add(m);
    }
    InventoryReceiptClient.Result res =
        inventory.postRaw(id, refOf(r), r.returnPickupId(), r.mfcId(), r.receivedAt(), lines);
    Instant now = clock.instant();
    switch (res) {
      case InventoryReceiptClient.Result.Posted p -> {
        Map<String, String> lotBySku = new LinkedHashMap<>();
        try {
          for (var rl : inventory.receiptLines(id)) {
            lotBySku.putIfAbsent(rl.sku(), rl.lotId());
          }
        } catch (RuntimeException e) {
          log.warn("회수품 반입 {} lot 조회 실패(반영은 됨): {}", id, e.getMessage());
        }
        List<Line> withLots =
            r.lines().stream()
                .map(
                    l ->
                        new Line(
                            l.orderLineId(),
                            l.sku(),
                            l.qty(),
                            l.sourceLotRef(),
                            l.expiryDate(),
                            l.conditionNote(),
                            l.evidenceRef(),
                            lotBySku.get(l.sku())))
                .toList();
        tx.executeWithoutResult(
            s -> {
              int n =
                  jdbc.update(
                      "UPDATE return_receipt SET state = 'APPLIED', commit_id = ?, lines = ?::jsonb,"
                          + " updated_at = ? WHERE return_receipt_id = ? AND state IN ('SUBMITTED', 'UNKNOWN_RESULT')",
                      p.commitId(),
                      mapper.writeValueAsString(withLots),
                      Timestamp.from(now),
                      id);
              if (n == 1) {
                publishReceived(find(id));
                log.info("회수품 반입 {} 격리 반영 {}", id, p.commitId());
              }
            });
      }
      case InventoryReceiptClient.Result.Rejected rj ->
          jdbc.update(
              "UPDATE return_receipt SET state = 'REJECTED', reject_reason = ?, updated_at = ?"
                  + " WHERE return_receipt_id = ?",
              rj.reason(),
              Timestamp.from(now),
              id);
      default ->
          jdbc.update(
              "UPDATE return_receipt SET state = 'UNKNOWN_RESULT', updated_at = ? WHERE return_receipt_id = ?",
              Timestamp.from(now),
              id);
    }
  }

  private void publishReceived(Row r) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("returnReceiptId", r.returnReceiptId());
    if (r.claimId() != null) {
      p.put("claimId", r.claimId());
    }
    p.put("returnPickupId", r.returnPickupId());
    p.put("purpose", r.purpose());
    if (r.caseId() != null) {
      p.put("caseId", r.caseId());
    }
    if (r.originDeliveryId() != null) {
      p.put("originDeliveryId", r.originDeliveryId());
    }
    if (r.arrivalId() != null) {
      p.put("arrivalId", r.arrivalId());
    }
    if (r.orderId() != null) {
      p.put("orderId", r.orderId());
    }
    p.put("mfcId", r.mfcId());
    p.put(
        "lines",
        r.lines().stream()
            .map(
                l -> {
                  Map<String, Object> m = new LinkedHashMap<>();
                  m.put("sku", l.sku());
                  if (l.lotId() != null) {
                    m.put("lot", l.lotId());
                  }
                  m.put("qty", l.qty());
                  m.put("locationCode", r.toLocation());
                  if (l.orderLineId() != null) {
                    m.put("orderLineId", l.orderLineId());
                  }
                  return m;
                })
            .toList());
    p.put("receivedBy", r.receivedBy());
    p.put("receivedAt", r.receivedAt().toString());
    events.publish(
        codec.create(
            events.sourceSystem(),
            "ReturnItemReceived",
            1,
            "ReturnReceipt",
            r.returnReceiptId(),
            1,
            new EventEnvelope.Actor(EventEnvelope.ActorType.OPERATOR, r.receivedBy()),
            EventEnvelope.EventSource.REAL,
            p),
        SupplyRequestService.TOPIC);
  }

  /** ReturnInspected(mfc.quality.v1, inbox 트랜잭션 안) — RESALE 분 적치 작업, 검수 완료 표시. */
  public void onQualityEvent(EventEnvelope e) {
    if (!"ReturnInspected".equals(e.eventType())) {
      return;
    }
    JsonNode p = e.payload();
    String id = p.path("returnReceiptId").asString(null);
    if (id == null) {
      return;
    }
    Row r =
        jdbc
            .query(
                "SELECT * FROM return_receipt WHERE return_receipt_id = ? FOR UPDATE",
                (rs, i) -> row(rs),
                id)
            .stream()
            .findFirst()
            .orElse(null);
    if (r == null || "INSPECTED".equals(r.inspectionState())) {
      return;
    }
    Instant now = clock.instant();
    List<String> created = new ArrayList<>();
    for (JsonNode l : p.path("lines")) {
      if (!"RESALE".equals(l.path("disposition").asString())) {
        continue;
      }
      String lot = l.path("lot").asString(null);
      if (lot == null) {
        lot =
            r.lines().stream()
                .filter(x -> x.sku().equals(l.path("sku").asString()))
                .map(Line::lotId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
      }
      if (lot == null) {
        log.warn("회수품 {} {} 재판매 적치 — lot 을 몰라 작업을 만들지 못함", id, l.path("sku").asString());
        continue;
      }
      created.add(
          tasks.insert(
              "PUTAWAY",
              r.mfcId(),
              id,
              null,
              l.path("sku").asString(),
              lot,
              r.toLocation(),
              l.path("qty").asInt(),
              now));
    }
    jdbc.update(
        "UPDATE return_receipt SET inspection_state = 'INSPECTED', updated_at = ? WHERE return_receipt_id = ?",
        Timestamp.from(now),
        id);
    log.info("회수품 {} 검수 결과 — 재판매 적치 작업 {}", id, created);
  }

  public Row view(String id) {
    Row r = find(id);
    PmartPrincipal p =
        PmartPrincipal.current()
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "토큰이 필요합니다."));
    if (!p.isService()
        && (p.type() != PmartPrincipal.PrincipalType.OPERATOR || !p.canAccessCenter(r.mfcId()))) {
      throw new PmartException(ErrorCode.FORBIDDEN, "이 센터에 대한 권한이 없습니다.");
    }
    return r;
  }

  public List<String> putawayTasks(String id) {
    return jdbc.queryForList(
        "SELECT task_id FROM inbound_task WHERE receipt_id = ? AND task_type = 'PUTAWAY' ORDER BY created_at",
        String.class,
        id);
  }

  private Row find(String id) {
    return jdbc
        .query("SELECT * FROM return_receipt WHERE return_receipt_id = ?", (rs, i) -> row(rs), id)
        .stream()
        .findFirst()
        .orElseThrow(() -> PmartException.notFound(NOT_FOUND, "회수품 반입이 없습니다: " + id));
  }

  private Row row(ResultSet rs) throws SQLException {
    return new Row(
        rs.getString("return_receipt_id"),
        rs.getString("mfc_id"),
        rs.getString("claim_id"),
        rs.getString("return_pickup_id"),
        rs.getString("order_id"),
        rs.getString("to_location"),
        mapper.readValue(rs.getString("lines"), LINES),
        rs.getString("state"),
        rs.getString("commit_id"),
        rs.getString("reject_reason"),
        rs.getString("inspection_state"),
        rs.getString("received_by"),
        rs.getTimestamp("received_at").toInstant(),
        rs.getString("purpose"),
        rs.getString("case_id"),
        rs.getString("origin_delivery_id"),
        rs.getString("arrival_id"));
  }

  private record Resolved(
      @Nullable String claimId,
      String returnPickupId,
      @Nullable String orderId,
      String purpose,
      @Nullable String caseId,
      @Nullable String originDeliveryId,
      List<Line> lines) {}

  /** 반납 도착 건이 있으면 그 값으로(본문 값은 같아야 한다), 없으면 본문 그대로(고객 반품). 기록 트랜잭션 안. */
  private Resolved resolve(Command c) {
    if (c.arrivalId() == null) {
      return new Resolved(
          c.claimId(),
          java.util.Objects.requireNonNull(c.returnPickupId()),
          c.orderId(),
          "CLAIM_RETURN",
          null,
          null,
          c.lines());
    }
    ReturnArrivals.Arrival a =
        java.util.Objects.requireNonNull(arrivals).lockWaiting(c.mfcId(), c.arrivalId());
    same("claimId", c.claimId(), a.claimId());
    same("returnPickupId", c.returnPickupId(), a.arrivalId());
    same("orderId", c.orderId(), a.orderId());
    List<Line> lines =
        c.lines().isEmpty()
            ? ReturnArrivals.linesOf(a)
            : ReturnArrivals.withOrderLines(c.lines(), a.lines());
    return new Resolved(
        a.claimId(),
        a.arrivalId(),
        a.orderId(),
        a.purpose(),
        a.caseId(),
        a.originDeliveryId(),
        lines);
  }

  private static void same(String field, @Nullable String given, @Nullable String arrival) {
    if (given != null && !given.equals(arrival)) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED, field + " 가 반납 도착 건과 다릅니다(" + arrival + ").");
    }
  }

  /** 재고 수령의 trfId·LOT 표기 — 클레임 > 오배송 사건 > 원래 배송 > 회수 배송. */
  private static String refOf(Row r) {
    if (r.claimId() != null) {
      return r.claimId();
    }
    if (r.caseId() != null) {
      return r.caseId();
    }
    return r.originDeliveryId() != null ? r.originDeliveryId() : r.returnPickupId();
  }

  private static boolean blank(@Nullable String s) {
    return s == null || s.isBlank();
  }
}
