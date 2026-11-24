package com.pmart.mfc.inbound.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.inbound.infrastructure.InitialStockStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 로컬 공급 요청(WK-14). 요청 원장만 — 공급 승인·이송 수량은 SCM(replenishment)이 정한다. 제출 = SUBMITTED +
 * SupplyRequestSubmitted. 변경·철회는 SCM 수신 확인(ACKNOWLEDGED) 전까지. 같은 센터·같은 SKU 의 미해결 요청이 있으면 새 요청을 받지
 * 않는다(중복 공급 방지 — 기존 요청을 바꾼다). 요청은 현물을 바꾸지 않는다(H 변화 0).
 *
 * <p>(0.21.0, 요청 MI-4 — 사용자 결정 "B", SCM 전 임시) 그 센터 MFC_SUPERVISOR 가 SUBMITTED 요청을 승인(줄별 승인 수량 — 부분
 * 승인은 사유 필수)하거나 거절(사유 필수)한다. 승인은 같은 트랜잭션에서 승인 수량 0 초과 줄로 입고 예정(initial_stock_load EXPECTED,
 * approvalId = supplyRequestId) 하나를 만든다 — 그 뒤 수령·품질검사·적치는 초기 보유 반입과 같은 흐름이다. approval-service 는 거치지
 * 않는다.
 */
@Service
public class SupplyRequestService {

  public static final String TOPIC = "mfc.inbound.v1";
  public static final String DUPLICATE_OPEN_REQUEST = "DUPLICATE_OPEN_REQUEST";
  static final String SUPERVISOR = "MFC_SUPERVISOR";

  private static final Logger log = LoggerFactory.getLogger(SupplyRequestService.class);
  private static final TypeReference<List<Line>> LINES = new TypeReference<>() {};
  private static final TypeReference<List<ApprovedLine>> APPROVED_LINES = new TypeReference<>() {};

  public record Line(String sku, int qty) {}

  /** (0.21.0) 승인된 줄 — 요청·승인 수량. */
  public record ApprovedLine(String sku, int requestedQty, int approvedQty) {}

  /** (0.21.0) 승인 본문 — lines 에 없는 줄은 요청 수량 그대로. */
  public record Approval(List<Line> lines, @Nullable String note) {}

  /** 멱등 키 본문(같은 키 = 같은 요청·같은 결정). */
  record Decision(String supplyRequestId, String kind, @Nullable Object body) {}

  public record Body(
      String mfcId,
      Instant requestedReadyAt,
      String urgency,
      String reason,
      @Nullable String recommendationRef,
      List<Line> lines) {}

  public record Row(
      String supplyRequestId,
      String mfcId,
      String state,
      int version,
      List<Line> lines,
      Instant requestedReadyAt,
      String urgency,
      String reason,
      @Nullable String recommendationRef,
      String submittedBy,
      Instant submittedAt,
      @Nullable Instant acknowledgedAt,
      @Nullable String linkedTrfId,
      List<ApprovedLine> approvedLines,
      @Nullable String decidedBy,
      @Nullable Instant decidedAt,
      @Nullable String decisionNote,
      @Nullable String rejectReason,
      @Nullable String linkedReceiptId) {}

  private final JdbcTemplate jdbc;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;
  private final RequestIdempotency idempotency;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final InitialStockStore loads;

  public SupplyRequestService(
      JdbcTemplate jdbc,
      EventPublisher events,
      EventEnvelopeCodec codec,
      RequestIdempotency idempotency,
      ObjectMapper mapper,
      Clock clock,
      InitialStockStore loads) {
    this.loads = loads;
    this.jdbc = jdbc;
    this.events = events;
    this.codec = codec;
    this.idempotency = idempotency;
    this.mapper = mapper;
    this.clock = clock;
  }

  public Row create(String requestId, String sourceSystem, Body b) {
    String me = supervisor(b.mfcId());
    validate(b);
    String id =
        idempotency
            .execute(
                new RequestKey(sourceSystem, requestId, "createSupplyRequest", me),
                b,
                String.class,
                () -> {
                  // 같은 센터의 요청 생성을 줄 세운다 — 미해결 요청이 하나도 없을 때 동시에 온 두 요청은 행 잠금으로 막을 수 없다
                  // (2026-11-08 검토 반영). 트랜잭션 끝에 풀린다.
                  jdbc.queryForList(
                      "SELECT pg_advisory_xact_lock(hashtext(?))", "supply-request:" + b.mfcId());
                  // 같은 센터 미해결 요청을 잠그고 SKU 겹침 확인
                  List<Row> open =
                      jdbc.query(
                          "SELECT * FROM supply_request WHERE mfc_id = ? AND state IN"
                              + " ('SUBMITTED', 'ACKNOWLEDGED') FOR UPDATE",
                          (rs, i) -> row(rs),
                          b.mfcId());
                  Set<String> skus = new LinkedHashSet<>();
                  b.lines().forEach(l -> skus.add(l.sku()));
                  for (Row o : open) {
                    for (Line l : o.lines()) {
                      if (skus.contains(l.sku())) {
                        throw new PmartException(
                            DUPLICATE_OPEN_REQUEST,
                            HttpStatus.CONFLICT,
                            "SKU "
                                + l.sku()
                                + " 는 미해결 공급 요청 "
                                + o.supplyRequestId()
                                + " 에 있습니다 — 그 요청을 바꾸거나 철회합니다.",
                            false,
                            List.of());
                      }
                    }
                  }
                  Instant now = clock.instant();
                  String sid = "SR-" + UUID.randomUUID();
                  jdbc.update(
                      "INSERT INTO supply_request (supply_request_id, mfc_id, state, version, lines,"
                          + " requested_ready_at, urgency, reason, recommendation_ref, submitted_by,"
                          + " submitted_at, updated_at) VALUES (?,?,'SUBMITTED',1,?::jsonb,?,?,?,?,?,?,?)",
                      sid,
                      b.mfcId(),
                      mapper.writeValueAsString(b.lines()),
                      Timestamp.from(b.requestedReadyAt()),
                      b.urgency(),
                      b.reason(),
                      b.recommendationRef(),
                      me,
                      Timestamp.from(now),
                      Timestamp.from(now));
                  publish(sid, 1, b, me);
                  log.info("공급 요청 {} {} {}줄 by {}", sid, b.mfcId(), b.lines().size(), me);
                  return sid;
                })
            .value();
    return get(id);
  }

  @Transactional
  public Row update(String supplyRequestId, @Nullable Integer expectedVersion, Body b) {
    Row cur = lock(supplyRequestId);
    String me = supervisor(cur.mfcId());
    if (!cur.mfcId().equals(b.mfcId())) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "센터는 바꿀 수 없습니다.");
    }
    validate(b);
    if (!"SUBMITTED".equals(cur.state())) {
      throw PmartException.invalidState(
          "SCM 수신 확인 전(SUBMITTED)만 바꿉니다(지금 " + cur.state() + ") — 철회 후 다시 요청합니다.");
    }
    if (expectedVersion != null && expectedVersion != cur.version()) {
      throw PmartException.versionConflict(
          "요청이 이미 바뀌었습니다(기대 " + expectedVersion + ", 현재 " + cur.version() + ").");
    }
    int version = cur.version() + 1;
    jdbc.update(
        "UPDATE supply_request SET version = ?, lines = ?::jsonb, requested_ready_at = ?, urgency = ?,"
            + " reason = ?, recommendation_ref = ?, updated_at = ? WHERE supply_request_id = ?",
        version,
        mapper.writeValueAsString(b.lines()),
        Timestamp.from(b.requestedReadyAt()),
        b.urgency(),
        b.reason(),
        b.recommendationRef(),
        Timestamp.from(clock.instant()),
        supplyRequestId);
    publish(supplyRequestId, version, b, me);
    log.info("공급 요청 {} 변경 v{} by {}", supplyRequestId, version, me);
    return get(supplyRequestId);
  }

  @Transactional
  public Row withdraw(String supplyRequestId, String reason) {
    Row cur = lock(supplyRequestId);
    String me = supervisor(cur.mfcId());
    if ("WITHDRAWN".equals(cur.state())) {
      return cur;
    }
    if (!"SUBMITTED".equals(cur.state())) {
      throw PmartException.invalidState(
          "SUBMITTED 만 철회합니다(지금 " + cur.state() + ") — SCM 이 받은 요청은 replenishment 에서 취소합니다.");
    }
    jdbc.update(
        "UPDATE supply_request SET state = 'WITHDRAWN', withdraw_reason = ?, version = version + 1,"
            + " updated_at = ? WHERE supply_request_id = ?",
        reason,
        Timestamp.from(clock.instant()),
        supplyRequestId);
    log.info("공급 요청 {} 철회 by {} ({})", supplyRequestId, me, reason);
    return get(supplyRequestId);
  }

  /**
   * (0.21.0, MI-4) 승인 — SUBMITTED → APPROVED + 입고 예정(EXPECTED). 같은 Idempotency-Key·같은 본문은 같은 결과(입고
   * 예정도 하나).
   */
  public Row approve(
      String requestId,
      String sourceSystem,
      String supplyRequestId,
      @Nullable Integer expectedVersion,
      Approval a) {
    String me = supervisor(get(supplyRequestId).mfcId());
    List<Line> asked = a.lines() == null ? List.of() : a.lines();
    String note = a.note() == null || a.note().isBlank() ? null : a.note().trim();
    String id =
        idempotency
            .execute(
                new RequestKey(sourceSystem, requestId, "approveSupplyRequest", me),
                new Decision(supplyRequestId, "APPROVE", new Approval(asked, note)),
                String.class,
                () -> {
                  Row cur = decidable(supplyRequestId, expectedVersion);
                  List<ApprovedLine> approved = approvedLines(cur, asked, note);
                  Map<String, Integer> expected = new LinkedHashMap<>();
                  approved.stream()
                      .filter(l -> l.approvedQty() > 0)
                      .forEach(l -> expected.put(l.sku(), l.approvedQty()));
                  Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
                  String receiptId = "RCV-SR-" + UUID.randomUUID();
                  loads.insert(
                      receiptId,
                      cur.supplyRequestId(),
                      cur.mfcId(),
                      "EXPECTED",
                      null,
                      me,
                      now,
                      expected);
                  jdbc.update(
                      "UPDATE supply_request SET state = 'APPROVED', approved_lines = ?::jsonb,"
                          + " decided_by = ?, decided_at = ?, decision_note = ?, linked_receipt_id = ?,"
                          + " version = version + 1, updated_at = ? WHERE supply_request_id = ?",
                      mapper.writeValueAsString(approved),
                      me,
                      Timestamp.from(now),
                      note,
                      receiptId,
                      Timestamp.from(now),
                      supplyRequestId);
                  log.info(
                      "공급 요청 {} 승인 {}줄(입고 예정 {} — {} SKU) by {}",
                      supplyRequestId,
                      approved.size(),
                      receiptId,
                      expected.size(),
                      me);
                  return supplyRequestId;
                })
            .value();
    return get(id);
  }

  /** (0.21.0, MI-4) 거절 — SUBMITTED → REJECTED(사유 필수). */
  public Row reject(
      String requestId,
      String sourceSystem,
      String supplyRequestId,
      @Nullable Integer expectedVersion,
      String reason) {
    String me = supervisor(get(supplyRequestId).mfcId());
    if (reason == null || reason.isBlank()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "거절 사유가 필요합니다.");
    }
    String why = reason.trim();
    String id =
        idempotency
            .execute(
                new RequestKey(sourceSystem, requestId, "rejectSupplyRequest", me),
                new Decision(supplyRequestId, "REJECT", why),
                String.class,
                () -> {
                  decidable(supplyRequestId, expectedVersion);
                  Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
                  jdbc.update(
                      "UPDATE supply_request SET state = 'REJECTED', reject_reason = ?, decided_by = ?,"
                          + " decided_at = ?, version = version + 1, updated_at = ? WHERE"
                          + " supply_request_id = ?",
                      why,
                      me,
                      Timestamp.from(now),
                      Timestamp.from(now),
                      supplyRequestId);
                  log.info("공급 요청 {} 거절 by {} ({})", supplyRequestId, me, why);
                  return supplyRequestId;
                })
            .value();
    return get(id);
  }

  private Row decidable(String supplyRequestId, @Nullable Integer expectedVersion) {
    Row cur = lock(supplyRequestId);
    if (!"SUBMITTED".equals(cur.state())) {
      throw PmartException.invalidState("SUBMITTED 만 승인·거절합니다(지금 " + cur.state() + ").");
    }
    if (expectedVersion != null && expectedVersion != cur.version()) {
      throw PmartException.versionConflict(
          "요청이 이미 바뀌었습니다(기대 " + expectedVersion + ", 현재 " + cur.version() + ").");
    }
    return cur;
  }

  /** 승인 줄 — 요청에 없는 SKU·중복·요청 초과·음수는 400, 줄였으면 사유 필수, 전부 0 이면 거절로. */
  static List<ApprovedLine> approvedLines(Row cur, List<Line> asked, @Nullable String note) {
    Map<String, Integer> requested = new LinkedHashMap<>();
    cur.lines().forEach(l -> requested.put(l.sku(), l.qty()));
    Map<String, Integer> given = new HashMap<>();
    for (Line l : asked) {
      Integer req = requested.get(l.sku());
      if (req == null) {
        throw new PmartException(ErrorCode.VALIDATION_FAILED, "요청에 없는 SKU 입니다: " + l.sku());
      }
      if (given.put(l.sku(), l.qty()) != null) {
        throw new PmartException(ErrorCode.VALIDATION_FAILED, "같은 SKU 가 두 줄입니다: " + l.sku());
      }
      if (l.qty() < 0 || l.qty() > req) {
        throw new PmartException(
            ErrorCode.VALIDATION_FAILED, "승인 수량은 0 ~ 요청 수량(" + req + ")입니다: " + l.sku());
      }
    }
    List<ApprovedLine> out =
        requested.entrySet().stream()
            .map(
                e ->
                    new ApprovedLine(
                        e.getKey(), e.getValue(), given.getOrDefault(e.getKey(), e.getValue())))
            .toList();
    if (out.stream().allMatch(l -> l.approvedQty() == 0)) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED, "모든 줄이 0 입니다 — 승인 대신 거절(rejections)로 합니다.");
    }
    if (note == null && out.stream().anyMatch(l -> l.approvedQty() < l.requestedQty())) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "요청보다 줄여 승인하면 사유(note)가 필요합니다.");
    }
    return out;
  }

  public Row find(String supplyRequestId) {
    Row r = get(supplyRequestId);
    PmartPrincipal p =
        PmartPrincipal.current()
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "토큰이 필요합니다."));
    if (!p.isService() && !p.canAccessCenter(r.mfcId())) {
      throw new PmartException(ErrorCode.FORBIDDEN, "이 센터에 대한 권한이 없습니다.");
    }
    return r;
  }

  private void validate(Body b) {
    if (b.lines().isEmpty() || b.lines().size() > 200) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "줄은 1~200개입니다.");
    }
    Set<String> seen = new LinkedHashSet<>();
    for (Line l : b.lines()) {
      if (l.qty() < 1) {
        throw new PmartException(ErrorCode.VALIDATION_FAILED, "수량은 1 이상입니다: " + l.sku());
      }
      if (!seen.add(l.sku())) {
        throw new PmartException(ErrorCode.VALIDATION_FAILED, "같은 SKU 가 두 줄입니다: " + l.sku());
      }
    }
  }

  private void publish(String id, int version, Body b, String actor) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("supplyRequestId", id);
    m.put("version", version);
    m.put("mfcId", b.mfcId());
    m.put(
        "lines",
        b.lines().stream()
            .map(l -> Map.<String, Object>of("sku", l.sku(), "requestedQty", l.qty()))
            .toList());
    m.put("requestedReadyAt", b.requestedReadyAt().toString());
    m.put("urgency", b.urgency());
    m.put("reason", b.reason());
    if (b.recommendationRef() != null) {
      m.put("recommendationRef", b.recommendationRef());
    }
    events.publish(
        codec.create(
            events.sourceSystem(),
            "SupplyRequestSubmitted",
            1,
            "SupplyRequest",
            id,
            version,
            new EventEnvelope.Actor(EventEnvelope.ActorType.OPERATOR, actor),
            EventEnvelope.EventSource.REAL,
            m),
        TOPIC);
  }

  private Row get(String id) {
    return jdbc
        .query("SELECT * FROM supply_request WHERE supply_request_id = ?", (rs, i) -> row(rs), id)
        .stream()
        .findFirst()
        .orElseThrow(
            () -> PmartException.notFound("SUPPLY_REQUEST_NOT_FOUND", "공급 요청이 없습니다: " + id));
  }

  private Row lock(String id) {
    return jdbc
        .query(
            "SELECT * FROM supply_request WHERE supply_request_id = ? FOR UPDATE",
            (rs, i) -> row(rs),
            id)
        .stream()
        .findFirst()
        .orElseThrow(
            () -> PmartException.notFound("SUPPLY_REQUEST_NOT_FOUND", "공급 요청이 없습니다: " + id));
  }

  private Row row(ResultSet rs) throws SQLException {
    Timestamp ack = rs.getTimestamp("acknowledged_at");
    Timestamp decided = rs.getTimestamp("decided_at");
    String approved = rs.getString("approved_lines");
    return new Row(
        rs.getString("supply_request_id"),
        rs.getString("mfc_id"),
        rs.getString("state"),
        rs.getInt("version"),
        mapper.readValue(rs.getString("lines"), LINES),
        rs.getTimestamp("requested_ready_at").toInstant(),
        rs.getString("urgency"),
        rs.getString("reason"),
        rs.getString("recommendation_ref"),
        rs.getString("submitted_by"),
        rs.getTimestamp("submitted_at").toInstant(),
        ack == null ? null : ack.toInstant(),
        rs.getString("linked_trf_id"),
        approved == null ? List.of() : mapper.readValue(approved, APPROVED_LINES),
        rs.getString("decided_by"),
        decided == null ? null : decided.toInstant(),
        rs.getString("decision_note"),
        rs.getString("reject_reason"),
        rs.getString("linked_receipt_id"));
  }

  private static String supervisor(String mfcId) {
    String me = InboundTaskService.requireOperator(mfcId);
    if (!PmartPrincipal.current().orElseThrow().hasRole(SUPERVISOR)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "이 센터의 MFC_SUPERVISOR 만 공급을 요청합니다.");
    }
    return me;
  }
}
