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
import com.pmart.mfc.inbound.infrastructure.InventorySkuClient;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
  private static final TypeReference<LinkedHashMap<String, Integer>> ZONE_LINES =
      new TypeReference<>() {};

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
      @Nullable String linkedReceiptId,
      @Nullable String transferCdcId,
      List<Transfer> transfers) {

    /** (0.23.0) 첫 TRF 기한(온도대가 나뉘어도 같은 기한). */
    public @Nullable Instant transferDueAt() {
      return transfers.isEmpty() ? null : transfers.getFirst().dueAt();
    }

    /** (0.23.0) 모든 TRF 를 DC 가 받았으면 마지막으로 받은 시각, 아직 남았으면 null. */
    public @Nullable Instant transferSentAt() {
      if (transfers.isEmpty() || transfers.stream().anyMatch(t -> t.sentAt() == null)) {
        return null;
      }
      return transfers.stream().map(Transfer::sentAt).max(Instant::compareTo).orElse(null);
    }

    /** (0.23.0) 아직 못 보낸 TRF 의 첫 실패. */
    public @Nullable String transferError() {
      return transfers.stream()
          .filter(t -> t.sentAt() == null && t.error() != null)
          .map(t -> t.trfId() + ": " + t.error())
          .findFirst()
          .orElse(null);
    }
  }

  /**
   * (0.23.0, ADR-0091·R-27) 승인이 만든 이관(TRF) 하나 — 온도대마다 하나(cdc-outbound 인수 조건 TEMPERATURE_ZONE 은
   * 하나뿐). lines = SKU → 승인 수량.
   */
  public record Transfer(
      String trfId,
      String temperatureZone,
      String cdcId,
      Map<String, Integer> lines,
      Instant dueAt,
      @Nullable Instant sentAt,
      int attempts,
      @Nullable String error,
      @Nullable String trfNumber,
      @Nullable String waveId,
      @Nullable String waveNumber) {}

  /** 온도대 순서(TRF 번호 꼬리). */
  static final List<String> ZONES = List.of("AMBIENT", "CHILLED", "FROZEN");

  private final JdbcTemplate jdbc;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;
  private final RequestIdempotency idempotency;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final InitialStockStore loads;
  private final InboundPromises promises;
  private final InboundProperties props;
  private final InventorySkuClient skuClient;

  public SupplyRequestService(
      JdbcTemplate jdbc,
      EventPublisher events,
      EventEnvelopeCodec codec,
      RequestIdempotency idempotency,
      ObjectMapper mapper,
      Clock clock,
      InitialStockStore loads,
      InboundPromises promises,
      InboundProperties props,
      InventorySkuClient skuClient) {
    this.skuClient = skuClient;
    this.loads = loads;
    this.promises = promises;
    this.props = props;
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
    Row before = get(supplyRequestId);
    String me = supervisor(before.mfcId());
    List<Line> asked = a.lines() == null ? List.of() : a.lines();
    String note = a.note() == null || a.note().isBlank() ? null : a.note().trim();
    // R-27: 보급 DC 가 있으면 SKU 온도대(mfc-inventory SKU 사본 storageType)로 TRF 를 나눈다 — 트랜잭션 밖에서 먼저 읽음.
    // 조회 실패는 그대로 실패(모두 상온으로 보내면 냉장·냉동이 상온 TRF 로 감), 모르는 SKU 는 422.
    Map<String, String> zones =
        props.supplyingCdc().isEmpty() || !"SUBMITTED".equals(before.state())
            ? Map.of()
            : zonesOf(before.lines().stream().map(Line::sku).distinct().toList());
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
                  String cdc = props.supplyingCdc();
                  if (!cdc.isEmpty()) {
                    // ADR-0091·R-27: 보급 DC 가 정해진 센터 — 온도대마다 이관(TRF) 요청 + 입고 예정(EXPECTED).
                    // MFC 쪽 초기 입고(RCV-SR)는 만들지 않는다. CDC 호출은 트랜잭션 밖(AutoSupply.send)
                    Map<String, Map<String, Integer>> byZone =
                        splitByZone(expected, zones, cur.supplyRequestId());
                    Instant due = dueAtOf(cur.requestedReadyAt(), now);
                    List<String> trfIds = new ArrayList<>();
                    int seq = 0;
                    for (var z : byZone.entrySet()) {
                      String trfId = trfIdOf(cur.supplyRequestId(), z.getKey(), byZone.size());
                      trfIds.add(trfId);
                      promises.expectFromSupply(trfId, cur.mfcId(), cdc, z.getValue(), due);
                      jdbc.update(
                          "INSERT INTO supply_request_transfer (trf_id, supply_request_id, seq, cdc_id,"
                              + " mfc_id, temperature_zone, lines, due_at, next_try_at, created_at)"
                              + " VALUES (?,?,?,?,?,?,?::jsonb,?,?,?)",
                          trfId,
                          supplyRequestId,
                          ++seq,
                          cdc,
                          cur.mfcId(),
                          z.getKey(),
                          mapper.writeValueAsString(z.getValue()),
                          Timestamp.from(due),
                          Timestamp.from(now),
                          Timestamp.from(now));
                    }
                    jdbc.update(
                        "UPDATE supply_request SET state = 'APPROVED', approved_lines = ?::jsonb,"
                            + " decided_by = ?, decided_at = ?, decision_note = ?, linked_trf_id = ?,"
                            + " transfer_cdc_id = ?, version = version + 1, updated_at = ? WHERE"
                            + " supply_request_id = ?",
                        mapper.writeValueAsString(approved),
                        me,
                        Timestamp.from(now),
                        note,
                        trfIds.getFirst(),
                        cdc,
                        Timestamp.from(now),
                        supplyRequestId);
                    log.info(
                        "공급 요청 {} 승인 {}줄 → {} 이관 요청 {} by {}",
                        supplyRequestId,
                        approved.size(),
                        cdc,
                        trfIds,
                        me);
                    return supplyRequestId;
                  }
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

  /**
   * (ADR-0091·R-27) 이관 ID — 공급 요청 ID 에서 결정(같은 승인 = 같은 TRF). 온도대가 하나면 {@code TRF-<요청 ID>}(DC 시뮬레이터
   * 임시 다리와 같은 번호), 여럿이면 {@code TRF-<요청 ID>-<온도대>}.
   */
  static String trfIdOf(String supplyRequestId, String zone, int zoneCount) {
    return zoneCount <= 1 ? "TRF-" + supplyRequestId : "TRF-" + supplyRequestId + "-" + zone;
  }

  /**
   * (R-27) TRF 기한 — 희망 사용준비 시각(deadlineMeaning READY, 사용자 2026-11-27 PC 3 전달). 이미 지났으면 승인 시각 +
   * supply-due-after(기본 1일). TMS 는 이 시각 − 12시간의 날을 출고일로 잡는다.
   */
  Instant dueAtOf(Instant requestedReadyAt, Instant now) {
    return requestedReadyAt.isAfter(now) ? requestedReadyAt : now.plus(props.supplyDueAfter());
  }

  /** SKU → 온도대. 조회 실패는 그대로 실패, 모르는 SKU 는 422 STORAGE_TYPE_UNKNOWN. */
  private Map<String, String> zonesOf(List<String> skus) {
    Map<String, String> out = new HashMap<>();
    skuClient
        .labels(skus)
        .forEach(
            (sku, l) -> {
              if (l.storageType() != null && ZONES.contains(l.storageType())) {
                out.put(sku, l.storageType());
              }
            });
    List<String> unknown = skus.stream().filter(x -> !out.containsKey(x)).toList();
    if (!unknown.isEmpty()) {
      throw new PmartException(
          "STORAGE_TYPE_UNKNOWN",
          HttpStatus.UNPROCESSABLE_CONTENT,
          "보관 온도대를 모르는 SKU 가 있어 DC 이관을 온도대별로 나눌 수 없습니다: " + String.join(", ", unknown),
          false,
          (Throwable) null);
    }
    return out;
  }

  /** 승인 줄을 온도대별로(AMBIENT → CHILLED → FROZEN 순). */
  static Map<String, Map<String, Integer>> splitByZone(
      Map<String, Integer> lines, Map<String, String> zones, String supplyRequestId) {
    Map<String, Map<String, Integer>> out = new LinkedHashMap<>();
    for (String z : ZONES) {
      lines.forEach(
          (sku, q) -> {
            if (z.equals(zones.get(sku))) {
              out.computeIfAbsent(z, k -> new LinkedHashMap<>()).put(sku, q);
            }
          });
    }
    if (out.values().stream().mapToInt(Map::size).sum() != lines.size()) {
      // 온도대를 읽은 뒤 요청 줄이 바뀜 — 다시 승인하게
      throw PmartException.versionConflict(
          "공급 요청 " + supplyRequestId + " 이 승인 중에 바뀌었습니다 — 다시 읽고 승인하세요.");
    }
    return out;
  }

  /**
   * (ADR-0091) 자동 발주 초안 — 토큰 없이 시스템이 SUBMITTED 로 제출(submitted_by SYSTEM). 미해결 요청에 이미 있는 SKU 는 빼고,
   * 남은 줄이 없으면 만들지 않는다. 같은 ref(센터·날짜)는 한 번. 만든 요청 ID 또는 null.
   */
  @Transactional
  public @Nullable String createSystem(
      String mfcId, List<Line> lines, Instant readyAt, String ref, String reason) {
    jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))", "supply-request:" + mfcId);
    Integer done =
        jdbc.queryForObject(
            "SELECT count(*) FROM supply_request WHERE submitted_by = 'SYSTEM' AND"
                + " recommendation_ref = ?",
            Integer.class,
            ref);
    if (done != null && done > 0) {
      return null;
    }
    Set<String> taken = new LinkedHashSet<>();
    jdbc.query(
            "SELECT * FROM supply_request WHERE mfc_id = ? AND state IN ('SUBMITTED',"
                + " 'ACKNOWLEDGED') FOR UPDATE",
            (rs, i) -> row(rs),
            mfcId)
        .forEach(o -> o.lines().forEach(l -> taken.add(l.sku())));
    List<Line> keep = lines.stream().filter(l -> !taken.contains(l.sku())).limit(200).toList();
    if (keep.isEmpty()) {
      return null;
    }
    Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
    String sid = "SR-" + UUID.randomUUID();
    Body b = new Body(mfcId, readyAt, "NORMAL", reason, ref, keep);
    jdbc.update(
        "INSERT INTO supply_request (supply_request_id, mfc_id, state, version, lines,"
            + " requested_ready_at, urgency, reason, recommendation_ref, submitted_by,"
            + " submitted_at, updated_at) VALUES (?,?,'SUBMITTED',1,?::jsonb,?,?,?,?,'SYSTEM',?,?)",
        sid,
        mfcId,
        mapper.writeValueAsString(keep),
        Timestamp.from(readyAt),
        b.urgency(),
        reason,
        ref,
        Timestamp.from(now),
        Timestamp.from(now));
    publish(sid, 1, b, "SYSTEM");
    log.info("자동 발주 초안 {} {} {}줄({})", sid, mfcId, keep.size(), ref);
    return sid;
  }

  /** (ADR-0091) CDC 에 아직 보내지 못한 이관(TRF ID). */
  public List<String> dueTransfers(Instant now, int limit) {
    return jdbc.queryForList(
        "SELECT trf_id FROM supply_request_transfer WHERE sent_at IS NULL AND next_try_at <= ?"
            + " ORDER BY next_try_at LIMIT ?",
        String.class,
        Timestamp.from(now),
        limit);
  }

  public Optional<Row> findRow(String supplyRequestId) {
    return jdbc
        .query(
            "SELECT * FROM supply_request WHERE supply_request_id = ?",
            (rs, i) -> row(rs),
            supplyRequestId)
        .stream()
        .findFirst();
  }

  /** (ADR-0091) TRF 하나 — 키 = 공급 요청 ID. */
  public Optional<Map.Entry<String, Transfer>> findTransfer(String trfId) {
    return jdbc
        .query(
            "SELECT t.*, p.trf_number, p.wave_id, p.wave_number FROM supply_request_transfer t"
                + " LEFT JOIN inbound_promise p ON p.trf_id = t.trf_id WHERE t.trf_id = ?",
            (rs, i) -> Map.entry(rs.getString("supply_request_id"), transfer(rs)),
            trfId)
        .stream()
        .findFirst();
  }

  public void transferSent(String trfId, Instant now) {
    jdbc.update(
        "UPDATE supply_request_transfer SET sent_at = ?, attempts = attempts + 1, error = NULL,"
            + " next_try_at = NULL WHERE trf_id = ? AND sent_at IS NULL",
        Timestamp.from(now),
        trfId);
  }

  /** nextTry null = 다시 보내지 않음(CDC 가 내용으로 거절). */
  public void transferFailed(String trfId, String error, @Nullable Instant nextTry) {
    jdbc.update(
        "UPDATE supply_request_transfer SET attempts = attempts + 1, error = ?, next_try_at = ?"
            + " WHERE trf_id = ? AND sent_at IS NULL",
        error.length() > 1000 ? error.substring(0, 1000) : error,
        nextTry == null ? null : Timestamp.from(nextTry),
        trfId);
  }

  private List<Transfer> transfers(String supplyRequestId) {
    return jdbc.query(
        "SELECT t.*, p.trf_number, p.wave_id, p.wave_number FROM supply_request_transfer t"
            + " LEFT JOIN inbound_promise p ON p.trf_id = t.trf_id WHERE t.supply_request_id = ?"
            + " ORDER BY t.seq",
        (rs, i) -> transfer(rs),
        supplyRequestId);
  }

  private Transfer transfer(ResultSet rs) throws SQLException {
    return new Transfer(
        rs.getString("trf_id"),
        rs.getString("temperature_zone"),
        rs.getString("cdc_id"),
        mapper.readValue(rs.getString("lines"), ZONE_LINES),
        rs.getTimestamp("due_at").toInstant(),
        instant(rs.getTimestamp("sent_at")),
        rs.getInt("attempts"),
        rs.getString("error"),
        rs.getString("trf_number"),
        rs.getString("wave_id"),
        rs.getString("wave_number"));
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
        rs.getString("linked_receipt_id"),
        rs.getString("transfer_cdc_id"),
        rs.getString("transfer_cdc_id") == null
            ? List.of()
            : transfers(rs.getString("supply_request_id")));
  }

  private static @Nullable Instant instant(@Nullable Timestamp t) {
    return t == null ? null : t.toInstant();
  }

  private static String supervisor(String mfcId) {
    String me = InboundTaskService.requireOperator(mfcId);
    if (!PmartPrincipal.current().orElseThrow().hasRole(SUPERVISOR)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "이 센터의 MFC_SUPERVISOR 만 공급을 요청합니다.");
    }
    return me;
  }
}
