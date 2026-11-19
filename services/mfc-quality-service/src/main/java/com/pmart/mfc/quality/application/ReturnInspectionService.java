package com.pmart.mfc.quality.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.web.CommandResponse;
import com.pmart.common.web.CommandStatus;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.quality.domain.QualityRules;
import com.pmart.mfc.quality.infrastructure.InventoryClient;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 회수품 검수(IV-10, 2026-11-08 사용자 결정: "환불 격리로 뒀다가 품질 검수, 적격이면 재배치").
 *
 * <ul>
 *   <li>반입 사본(`ReturnItemReceived`) 기준 — 반입이 없으면 409 INVALID_STATE, 이미 검수했으면 같은 결과(같은 키) 또는 409.
 *   <li>검수자 = 그 센터 MFC_SUPERVISOR, **반입한 사람이 아니어야**(Q-11 요청/승인 분리 — 재판매는 격리 해제라서). 줄 합계 = 반입 수량(SKU
 *       별).
 *   <li>RESALE 은 상온·미개봉·신선 아님만(아니면 422 RESALE_NOT_ALLOWED) → mfc-inventory RELEASE(격리 → 정상, 반품 위치
 *       그대로 UNSTOWED). 재배치는 mfc-inbound 가 `ReturnInspected` 를 받아 적치 작업으로(진열 규칙).
 *   <li>DISPOSE 는 폐기 승인 요청(disposal-approvals REQUEST, sourceRef = 검수 ID) → 승인·실행은 기존 폐기 흐름.
 *   <li>재고 반영(RESALE)이 끝나면(없으면 바로) `ReturnInspected` — claim 환불·inbound 적치의 근거.
 * </ul>
 */
@Service
public class ReturnInspectionService {

  public static final String NOT_FOUND = "RETURN_INSPECTION_NOT_FOUND";
  public static final String RESALE_NOT_ALLOWED = "RESALE_NOT_ALLOWED";
  public static final String QTY_MISMATCH = "QTY_EXCEEDS_RECEIVED";
  private static final Logger log = LoggerFactory.getLogger(ReturnInspectionService.class);
  private static final TypeReference<List<RLine>> RLINES = new TypeReference<>() {};
  private static final TypeReference<List<ILine>> ILINES = new TypeReference<>() {};

  /** 반입 사본 줄. */
  public record RLine(
      String sku,
      @Nullable String lot,
      int qty,
      @Nullable String locationCode,
      @Nullable String orderLineId) {}

  /** 검수 줄(요청 + 정해진 lot·위치). */
  public record ILine(
      @Nullable String orderLineId,
      String sku,
      @Nullable String lotId,
      @Nullable String locationCode,
      int qty,
      String verdict,
      String temperatureZone,
      boolean fresh,
      boolean opened,
      @Nullable String disposeReason,
      @Nullable String evidenceRef) {}

  public record Command(
      String mfcId,
      @Nullable String claimId,
      String returnReceiptId,
      @Nullable String inspectedBy,
      List<ILine> lines) {}

  public record Row(
      String returnInspectionId,
      String mfcId,
      @Nullable String claimId,
      String returnReceiptId,
      String inspectedBy,
      List<ILine> lines,
      int resaleQty,
      int disposeQty,
      String applyStatus,
      @Nullable String inventoryCommitId,
      @Nullable String disposalApprovalId,
      int version,
      Instant inspectedAt,
      String purpose,
      @Nullable String caseId,
      @Nullable String originDeliveryId) {}

  private final JdbcTemplate jdbc;
  private final InventoryClient inventory;
  private final DisposalService disposals;
  private final RequestIdempotency idempotency;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;
  private final ObjectMapper mapper;
  private final TransactionTemplate tx;
  private final Clock clock;

  public ReturnInspectionService(
      JdbcTemplate jdbc,
      InventoryClient inventory,
      DisposalService disposals,
      RequestIdempotency idempotency,
      EventPublisher events,
      EventEnvelopeCodec codec,
      ObjectMapper mapper,
      PlatformTransactionManager txm,
      Clock clock) {
    this.jdbc = jdbc;
    this.inventory = inventory;
    this.disposals = disposals;
    this.idempotency = idempotency;
    this.events = events;
    this.codec = codec;
    this.mapper = mapper;
    this.tx = new TransactionTemplate(txm);
    this.clock = clock;
  }

  /** mfc.inbound.v1 ReturnItemReceived(inbox 트랜잭션 안) → 반입 사본. */
  public void onInboundEvent(EventEnvelope e) {
    if (!"ReturnItemReceived".equals(e.eventType())) {
      return;
    }
    JsonNode p = e.payload();
    String id = p.path("returnReceiptId").asString(null);
    if (id == null) {
      return;
    }
    List<RLine> lines = new ArrayList<>();
    for (JsonNode l : p.path("lines")) {
      lines.add(
          new RLine(
              l.path("sku").asString(),
              l.path("lot").asString(null),
              l.path("qty").asInt(),
              l.path("locationCode").asString(null),
              l.path("orderLineId").asString(null)));
    }
    String receivedAt = p.path("receivedAt").asString(null);
    jdbc.update(
        "INSERT INTO return_receipt_projection (return_receipt_id, mfc_id, claim_id, lines, received_by,"
            + " received_at, created_at, purpose, case_id, origin_delivery_id)"
            + " VALUES (?,?,?,?::jsonb,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
        id,
        p.path("mfcId").asString(),
        p.path("claimId").asString(null),
        mapper.writeValueAsString(lines),
        p.path("receivedBy").asString(null),
        Timestamp.from(receivedAt == null ? clock.instant() : Instant.parse(receivedAt)),
        Timestamp.from(clock.instant()),
        p.path("purpose").asString("CLAIM_RETURN"),
        p.path("caseId").asString(null),
        p.path("originDeliveryId").asString(null));
  }

  public CommandResponse inspect(String requestId, String sourceSystem, Command c) {
    String me = Actors.supervisor(c.mfcId(), c.inspectedBy());
    String id =
        idempotency
            .execute(
                new RequestKey(sourceSystem, requestId, "createReturnInspection", me),
                c,
                String.class,
                () -> record(me, c))
            .value();
    post(id);
    Row r = find(id);
    CommandStatus st =
        switch (r.applyStatus()) {
          case "APPLIED" -> CommandStatus.APPLIED;
          case "REJECTED" -> CommandStatus.REJECTED;
          default -> CommandStatus.UNKNOWN_RESULT;
        };
    return new CommandResponse(
        st,
        requestId,
        id,
        r.resaleQty() + r.disposeQty(),
        r.version(),
        st == CommandStatus.UNKNOWN_RESULT,
        st == CommandStatus.UNKNOWN_RESULT ? "RETRY_SAME_KEY" : null,
        clock.instant());
  }

  private String record(String me, Command c) {
    jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))", "rinsp:" + c.returnReceiptId());
    Map<String, Object> pr =
        jdbc
            .queryForList(
                "SELECT mfc_id, claim_id, lines::text AS lines, received_by, purpose, case_id,"
                    + " origin_delivery_id FROM return_receipt_projection"
                    + " WHERE return_receipt_id = ?",
                c.returnReceiptId())
            .stream()
            .findFirst()
            .orElse(null);
    if (pr == null || !c.mfcId().equals(pr.get("mfc_id"))) {
      throw PmartException.invalidState("반입이 등록되지 않은 회수품입니다: " + c.returnReceiptId());
    }
    String claimId = (String) pr.get("claim_id");
    if (c.claimId() != null && !c.claimId().equals(claimId)) {
      throw QualityDecisionService.invalid("claimId 가 반입과 다릅니다(반입 " + claimId + ").");
    }
    Integer done =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM return_inspection WHERE return_receipt_id = ?",
            Integer.class,
            c.returnReceiptId());
    if (done != null && done > 0) {
      throw PmartException.invalidState("이미 검수한 회수품입니다: " + c.returnReceiptId());
    }
    if (me.equals(pr.get("received_by"))) {
      throw new PmartException(
          "SELF_APPROVAL",
          HttpStatus.UNPROCESSABLE_CONTENT,
          "반입한 사람은 검수(격리 해제)하지 않습니다 — 다른 센터 관리자가 합니다(Q-11).",
          false,
          List.of());
    }
    List<RLine> received = mapper.readValue((String) pr.get("lines"), RLINES);
    Map<String, Integer> have = new LinkedHashMap<>();
    received.forEach(l -> have.merge(l.sku(), l.qty(), Integer::sum));
    Map<String, Integer> want = new LinkedHashMap<>();
    List<ILine> resolved = new ArrayList<>();
    for (ILine l : c.lines()) {
      if (l.qty() < 1) {
        throw QualityDecisionService.invalid("수량은 1 이상입니다: " + l.sku());
      }
      if ("RESALE".equals(l.verdict())
          && (!"AMBIENT".equals(l.temperatureZone()) || l.opened() || l.fresh())) {
        throw new PmartException(
            RESALE_NOT_ALLOWED,
            HttpStatus.UNPROCESSABLE_CONTENT,
            "재판매는 상온·미개봉·신선 아님만 됩니다: " + l.sku(),
            false,
            List.of());
      }
      want.merge(l.sku(), l.qty(), Integer::sum);
      RLine src =
          received.stream()
              .filter(
                  x -> x.sku().equals(l.sku()) && (l.lotId() == null || l.lotId().equals(x.lot())))
              .findFirst()
              .orElse(null);
      resolved.add(
          new ILine(
              l.orderLineId(),
              l.sku(),
              src == null ? l.lotId() : src.lot(),
              src == null ? null : src.locationCode(),
              l.qty(),
              l.verdict(),
              l.temperatureZone(),
              l.fresh(),
              l.opened(),
              l.disposeReason(),
              l.evidenceRef()));
    }
    if (!have.equals(want)) {
      throw new PmartException(
          QTY_MISMATCH,
          HttpStatus.UNPROCESSABLE_CONTENT,
          "반입 수량(" + have + ")을 모두 판정해야 합니다(요청 " + want + ").",
          false,
          List.of());
    }
    resolved = byOrderLine(resolved, received);
    String id = "RIN-" + UUID.randomUUID();
    Instant now = clock.instant();
    jdbc.update(
        "INSERT INTO return_inspection (return_inspection_id, mfc_id, claim_id, return_receipt_id,"
            + " inspected_by, lines, resale_qty, dispose_qty, apply_status, version, inspected_at,"
            + " purpose, case_id, origin_delivery_id)"
            + " VALUES (?,?,?,?,?,?::jsonb,?,?,'SUBMITTED',1,?,?,?,?)",
        id,
        c.mfcId(),
        claimId,
        c.returnReceiptId(),
        me,
        mapper.writeValueAsString(resolved),
        resolved.stream().filter(l -> "RESALE".equals(l.verdict())).mapToInt(ILine::qty).sum(),
        resolved.stream().filter(l -> "DISPOSE".equals(l.verdict())).mapToInt(ILine::qty).sum(),
        Timestamp.from(now),
        pr.get("purpose"),
        pr.get("case_id"),
        pr.get("origin_delivery_id"));
    log.info("회수품 검수 {} — 반입 {} by {}", id, c.returnReceiptId(), me);
    return id;
  }

  /** 재판매분 격리 해제(재고) → 폐기 요청 → ReturnInspected. 결과 모름은 같은 키로 다시(재고는 검수 ID 로 한 번). */
  private void post(String id) {
    Row r = find(id);
    if (!"SUBMITTED".equals(r.applyStatus()) && !"UNKNOWN_RESULT".equals(r.applyStatus())) {
      return;
    }
    List<ILine> resale = r.lines().stream().filter(l -> "RESALE".equals(l.verdict())).toList();
    List<ILine> dispose = r.lines().stream().filter(l -> "DISPOSE".equals(l.verdict())).toList();
    String commitId = r.inventoryCommitId();
    if (!resale.isEmpty() && commitId == null) {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("postingType", "RELEASE");
      body.put("mfcId", r.mfcId());
      body.put("qualityDecisionId", id);
      body.put("approvalRef", r.inspectedBy() + ":" + id);
      body.put("reason", "RETURN_RESALE");
      body.put(
          "lines",
          resale.stream()
              .map(
                  l -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("sku", l.sku());
                    m.put("lotId", l.lotId());
                    m.put("locationCode", l.locationCode());
                    m.put("qty", l.qty());
                    m.put("workState", "UNSTOWED");
                    return m;
                  })
              .toList());
      InventoryClient.Posted p = inventory.post(id, body);
      if (!"APPLIED".equals(p.outcome())) {
        String st = "REJECTED".equals(p.outcome()) ? "REJECTED" : "UNKNOWN_RESULT";
        jdbc.update(
            "UPDATE return_inspection SET apply_status = ?, reject_reason = ? WHERE return_inspection_id = ?",
            st,
            p.reason(),
            id);
        log.warn("회수품 검수 {} 재판매 격리 해제 {} — {}", id, st, p.reason());
        return;
      }
      commitId = p.commitId();
      jdbc.update(
          "UPDATE return_inspection SET inventory_commit_id = ? WHERE return_inspection_id = ?",
          commitId,
          id);
    }
    String disposalId = r.disposalApprovalId();
    if (!dispose.isEmpty() && disposalId == null) {
      CommandResponse d =
          disposals.decide(
              id + ":DSP",
              InventoryClient.SOURCE,
              new DisposalService.DecideCommand(
                  "REQUEST",
                  r.mfcId(),
                  null,
                  null,
                  null,
                  "회수품 폐기(검수 " + id + ")",
                  id,
                  dispose.stream()
                      .map(
                          l ->
                              new QualityRules.Line(
                                  l.sku(),
                                  Objects.requireNonNull(l.lotId()),
                                  Objects.requireNonNull(l.locationCode()),
                                  l.qty(),
                                  "UNSTOWED"))
                      .toList()));
      disposalId = d.resultId();
    }
    String finalCommit = commitId;
    String finalDisposal = disposalId;
    tx.executeWithoutResult(
        s -> {
          int n =
              jdbc.update(
                  "UPDATE return_inspection SET apply_status = 'APPLIED', inventory_commit_id = ?,"
                      + " disposal_approval_id = ?, reject_reason = NULL, version = version + 1"
                      + " WHERE return_inspection_id = ? AND apply_status IN ('SUBMITTED', 'UNKNOWN_RESULT')",
                  finalCommit,
                  finalDisposal,
                  id);
          if (n == 1) {
            publishInspected(find(id));
          }
        });
  }

  private void publishInspected(Row r) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("inspectionId", r.returnInspectionId());
    if (r.claimId() != null) {
      p.put("claimId", r.claimId());
    }
    p.put("purpose", r.purpose());
    if (r.caseId() != null) {
      p.put("caseId", r.caseId());
    }
    if (r.originDeliveryId() != null) {
      p.put("originDeliveryId", r.originDeliveryId());
    }
    p.put("returnReceiptId", r.returnReceiptId());
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
                  if (l.orderLineId() != null) {
                    m.put("orderLineId", l.orderLineId());
                  }
                  m.put("disposition", l.verdict());
                  if (l.disposeReason() != null) {
                    m.put("reason", l.disposeReason());
                  }
                  return m;
                })
            .toList());
    if (r.disposalApprovalId() != null) {
      p.put("disposalApprovalId", r.disposalApprovalId());
    }
    p.put("inspectedAt", r.inspectedAt().toString());
    events.publish(
        codec.create(
            events.sourceSystem(),
            "ReturnInspected",
            1,
            "ReturnInspection",
            r.returnInspectionId(),
            r.version(),
            new EventEnvelope.Actor(EventEnvelope.ActorType.OPERATOR, r.inspectedBy()),
            EventEnvelope.EventSource.REAL,
            p),
        QualityEvents.TOPIC);
  }

  public Row view(String id) {
    Row r = find(id);
    Actors.reader(r.mfcId());
    return r;
  }

  private Row find(String id) {
    return jdbc
        .query(
            "SELECT * FROM return_inspection WHERE return_inspection_id = ?",
            (rs, i) -> row(rs),
            id)
        .stream()
        .findFirst()
        .orElseThrow(() -> PmartException.notFound(NOT_FOUND, "회수품 검수가 없습니다: " + id));
  }

  private Row row(ResultSet rs) throws SQLException {
    return new Row(
        rs.getString("return_inspection_id"),
        rs.getString("mfc_id"),
        rs.getString("claim_id"),
        rs.getString("return_receipt_id"),
        rs.getString("inspected_by"),
        mapper.readValue(rs.getString("lines"), ILINES),
        rs.getInt("resale_qty"),
        rs.getInt("dispose_qty"),
        rs.getString("apply_status"),
        rs.getString("inventory_commit_id"),
        rs.getString("disposal_approval_id"),
        rs.getInt("version"),
        rs.getTimestamp("inspected_at").toInstant(),
        rs.getString("purpose"),
        rs.getString("case_id"),
        rs.getString("origin_delivery_id"));
  }

  /**
   * 주문 줄 번호 잇기(MQ-1, 2026-11-19) — 번호 없는 검수 줄은 같은 상품(lot 이 있으면 같은 lot)의 반입 줄에서 차례로, 반입 줄 수량만큼
   * 나눈다(같은 상품이 두 주문 줄이면 두 줄로). 번호가 있는 검수 줄은 그대로, 그 수량만큼 반입 줄에서 먼저 뺀다. 남는 수량은 번호 없이.
   */
  static List<ILine> byOrderLine(List<ILine> lines, List<RLine> received) {
    int[] left = received.stream().mapToInt(RLine::qty).toArray();
    for (ILine l : lines) {
      if (l.orderLineId() == null) {
        continue;
      }
      int need = l.qty();
      for (int k = 0; k < received.size() && need > 0; k++) {
        if (l.orderLineId().equals(received.get(k).orderLineId())) {
          int take = Math.min(left[k], need);
          left[k] -= take;
          need -= take;
        }
      }
    }
    List<ILine> out = new ArrayList<>();
    for (ILine l : lines) {
      if (l.orderLineId() != null) {
        out.add(l);
        continue;
      }
      int need = l.qty();
      for (int k = 0; k < received.size() && need > 0; k++) {
        RLine r = received.get(k);
        if (r.orderLineId() == null
            || left[k] <= 0
            || !r.sku().equals(l.sku())
            || (l.lotId() != null && !l.lotId().equals(r.lot()))) {
          continue;
        }
        int take = Math.min(left[k], need);
        left[k] -= take;
        need -= take;
        out.add(copy(l, r.orderLineId(), take));
      }
      if (need > 0) {
        out.add(copy(l, null, need));
      }
    }
    return out;
  }

  private static ILine copy(ILine l, @Nullable String orderLineId, int qty) {
    return new ILine(
        orderLineId,
        l.sku(),
        l.lotId(),
        l.locationCode(),
        qty,
        l.verdict(),
        l.temperatureZone(),
        l.fresh(),
        l.opened(),
        l.disposeReason(),
        l.evidenceRef());
  }
}
