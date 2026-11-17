package com.pmart.mfc.capacity.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.web.CommandResponse;
import com.pmart.common.web.CommandStatus;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.capacity.domain.CapacityRules;
import com.pmart.mfc.capacity.domain.RestrictionRules;
import com.pmart.mfc.capacity.infrastructure.RestrictionStore;
import com.pmart.mfc.capacity.infrastructure.RestrictionStore.Row;
import com.pmart.mfc.capacity.infrastructure.RestrictionStore.WindowHold;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 운영 제한(BO-08) — 신규 접수 중지(INTAKE_STOP)·수용 축소(CAPACITY_REDUCE). 그 센터 MFC_SUPERVISOR, 승인 근거 필수. 접수
 * 중지는 기존 주문을 취소하지 않고, 축소는 기존 약속을 지우지 않는다(넘치는 창의 주문은 CapacityReduced 로 알림). 해제는 요청으로만(시간 경과로 자동 재개 안
 * 함).
 */
@Service
public class RestrictionService {

  private static final Logger log = LoggerFactory.getLogger(RestrictionService.class);

  public record ApplyCommand(
      String mfcId,
      String type,
      String approvalRef,
      @Nullable String reason,
      Instant effectiveFrom,
      @Nullable Instant effectiveTo,
      @Nullable Integer reducedCapacityUnits) {}

  private final RestrictionStore store;
  private final CapacityProperties props;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;
  private final RequestIdempotency idempotency;
  private final ObjectMapper mapper;
  private final Clock clock;

  private final ApprovalRefs approvalRefs;

  public RestrictionService(
      RestrictionStore store,
      CapacityProperties props,
      EventPublisher events,
      EventEnvelopeCodec codec,
      RequestIdempotency idempotency,
      ObjectMapper mapper,
      Clock clock,
      ApprovalRefs approvalRefs) {
    this.approvalRefs = approvalRefs;
    this.store = store;
    this.props = props;
    this.events = events;
    this.codec = codec;
    this.idempotency = idempotency;
    this.mapper = mapper;
    this.clock = clock;
  }

  public CommandResponse apply(String requestId, String sourceSystem, ApplyCommand cmd) {
    String me = AssignmentService.requireSupervisor(cmd.mfcId());
    if (cmd.approvalRef() == null || cmd.approvalRef().isBlank()) {
      throw new PmartException(ErrorCode.APPROVAL_REQUIRED, "운영 제한에는 승인 근거(approvalRef)가 필요합니다.");
    }
    String bad =
        RestrictionRules.invalid(
            cmd.type(), cmd.effectiveFrom(), cmd.effectiveTo(), cmd.reducedCapacityUnits());
    if (bad != null) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, bad);
    }
    return idempotency
        .execute(
            new RequestKey(sourceSystem, requestId, "applyOperationRestriction", me),
            cmd,
            CommandResponse.class,
            () -> {
              Instant now = clock.instant();
              String id = "OPR-" + UUID.randomUUID();
              store.insert(
                  new Row(
                      id,
                      cmd.mfcId(),
                      cmd.type(),
                      "ACTIVE",
                      cmd.approvalRef(),
                      null,
                      cmd.reason(),
                      cmd.effectiveFrom(),
                      cmd.effectiveTo(),
                      cmd.reducedCapacityUnits(),
                      "[]",
                      me,
                      null,
                      1,
                      now,
                      null));
              int affected = 0;
              if ("INTAKE_STOP".equals(cmd.type())) {
                publishRestriction("OperationRestrictionApplied", id, cmd.mfcId(), cmd, me, 1, now);
              } else {
                affected = reduced(id, cmd, now);
              }
              log.info(
                  "운영 제한 {} {} {} [{}, {}) by {} (근거 {}) — 영향 주문 {}",
                  id,
                  cmd.mfcId(),
                  cmd.type(),
                  cmd.effectiveFrom(),
                  cmd.effectiveTo(),
                  me,
                  cmd.approvalRef(),
                  affected);
              return new CommandResponse(
                  CommandStatus.APPLIED, requestId, id, affected, 1, false, null, now);
            })
        .value();
  }

  @Transactional
  public CommandResponse lift(
      String requestId,
      String restrictionId,
      @org.jspecify.annotations.Nullable String approvalRef) {
    Row r0 = get(restrictionId);
    String me = AssignmentService.requireSupervisor(r0.mfcId());
    Row r = store.find(restrictionId, true).orElseThrow();
    Instant now = clock.instant();
    if ("ACTIVE".equals(r.state())) {
      // 승인 번호 관문(AP-1): 해제는 늘 본사 승인(MFC_OPERATION_RESTRICTION) — 해제 전 한 번 사용. 이미 해제된 것은 다시 묻지 않는다
      approvalRef =
          approvalRefs.requireLift(
              r.mfcId(),
              restrictionId,
              me,
              r.mfcId() + " 운영 제한 해제 " + restrictionId + " (" + r.type() + ")",
              approvalRef);
      store.lift(restrictionId, approvalRef, me, now);
      if ("INTAKE_STOP".equals(r.type())) {
        publishRestriction(
            "OperationRestrictionLifted",
            restrictionId,
            r.mfcId(),
            new ApplyCommand(
                r.mfcId(),
                r.type(),
                approvalRef,
                r.reason(),
                r.effectiveFrom(),
                r.effectiveTo(),
                null),
            me,
            r.version() + 1,
            now);
      }
      log.info("운영 제한 {} 해제 by {} (근거 {})", restrictionId, me, approvalRef);
    }
    return new CommandResponse(
        CommandStatus.ACCEPTED, requestId, restrictionId, null, r.version() + 1, false, null, now);
  }

  public Row find(String id) {
    Row r = get(id);
    AssignmentService.requireReader(r.mfcId());
    return r;
  }

  public List<Row> list(String mfcId, boolean activeOnly) {
    AssignmentService.requireReader(mfcId);
    return store.list(mfcId, activeOnly);
  }

  public List<String> affected(Row r) {
    return List.of(mapper.readValue(r.affectedOrderIds(), String[].class));
  }

  /** 축소: 지금 활성 점유가 새 수용 단위를 넘는 창마다 CapacityReduced(그 창 주문). */
  private int reduced(String id, ApplyCommand cmd, Instant now) {
    Integer configured = props.unitsPerHour();
    int units = cmd.reducedCapacityUnits() == null ? 0 : cmd.reducedCapacityUnits();
    Map<Instant, List<WindowHold>> byWindow = new TreeMap<>();
    for (WindowHold h :
        store.activeHolds(cmd.mfcId(), cmd.effectiveFrom(), cmd.effectiveTo(), now)) {
      byWindow.computeIfAbsent(h.windowStart(), k -> new ArrayList<>()).add(h);
    }
    Set<String> all = new LinkedHashSet<>();
    for (var e : byWindow.entrySet()) {
      int used = e.getValue().stream().mapToInt(WindowHold::units).sum();
      if (used <= units) {
        continue;
      }
      List<String> orders = e.getValue().stream().map(WindowHold::orderId).toList();
      all.addAll(orders);
      WindowHold first = e.getValue().get(0);
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("mfcId", cmd.mfcId());
      m.put(
          "window",
          Map.of(
              "start", first.windowStart().toString(),
              "end", first.windowEnd().toString(),
              "meaning", "WORK"));
      m.put("oldCapacity", configured == null ? used : configured);
      m.put("newCapacity", units);
      m.put("affectedOrders", orders);
      m.put("reason", cmd.reason() == null ? "CAPACITY_REDUCE" : cmd.reason());
      m.put("restrictionId", id);
      m.put("reducedAt", now.toString());
      events.publish(
          codec.create(
              events.sourceSystem(),
              "CapacityReduced",
              1,
              "Capacity",
              cmd.mfcId()
                  + ":"
                  + new CapacityRules.HourWindow(first.windowStart(), first.windowEnd()).text(),
              (int) (now.getEpochSecond() % Integer.MAX_VALUE),
              new EventEnvelope.Actor(EventEnvelope.ActorType.SYSTEM, events.sourceSystem()),
              EventEnvelope.EventSource.REAL,
              m),
          AssignmentService.TOPIC);
    }
    store.setAffected(id, mapper.writeValueAsString(List.copyOf(all)));
    return all.size();
  }

  private void publishRestriction(
      String type,
      String id,
      String mfcId,
      ApplyCommand cmd,
      String actor,
      int version,
      Instant at) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("restrictionId", id);
    m.put("restrictionType", cmd.type());
    m.put(
        "scope",
        Map.of(
            "centerType",
            "MFC",
            "centerId",
            mfcId,
            "description",
            "슬롯 창 ["
                + cmd.effectiveFrom()
                + ", "
                + (cmd.effectiveTo() == null ? "∞" : cmd.effectiveTo())
                + ")"));
    m.put(type.endsWith("Applied") ? "appliedAt" : "liftedAt", at.toString());
    m.put("effectiveFrom", cmd.effectiveFrom().toString());
    if (cmd.effectiveTo() != null) {
      m.put("effectiveTo", cmd.effectiveTo().toString());
    }
    m.put("approvalRef", Map.of("approvalId", cmd.approvalRef(), "approverId", actor));
    if (cmd.reason() != null) {
      m.put("reason", cmd.reason());
    }
    events.publish(
        codec.create(
            events.sourceSystem(),
            type,
            1,
            "OperationRestriction",
            id,
            version,
            new EventEnvelope.Actor(EventEnvelope.ActorType.OPERATOR, actor),
            EventEnvelope.EventSource.REAL,
            m),
        AssignmentService.TOPIC);
  }

  private Row get(String id) {
    return store
        .find(id, false)
        .orElseThrow(() -> PmartException.notFound("RESTRICTION_NOT_FOUND", "운영 제한이 없습니다: " + id));
  }
}
