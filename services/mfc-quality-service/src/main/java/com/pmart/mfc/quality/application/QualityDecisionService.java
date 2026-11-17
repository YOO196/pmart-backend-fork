package com.pmart.mfc.quality.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.web.CommandResponse;
import com.pmart.common.web.CommandStatus;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.quality.domain.QualityRules;
import com.pmart.mfc.quality.domain.QualityRules.Line;
import com.pmart.mfc.quality.infrastructure.InventoryClient;
import com.pmart.mfc.quality.infrastructure.QualityStore;
import com.pmart.mfc.quality.infrastructure.QualityStore.DecisionRow;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 격리·해제 판정(IV-09, Q-11 요청·승인 분리).
 *
 * <ul>
 *   <li>QUARANTINE: 판정자 단독 → 기록(SUBMITTED) → 트랜잭션 밖에서 inventory QUARANTINE(키 = 판정 ID).
 *   <li>RELEASE: 요청(REQUESTED) → 다른 운영자(MFC_SUPERVISOR) 승인 → SUBMITTED → inventory RELEASE. 반려는
 *       DECLINED.
 *   <li>결과: APPLIED(QualityDecisionMade) / UNKNOWN_RESULT·미전달(sweep 이 같은 키로) / REJECTED(재고 거절 — 사유,
 *       자동 재시도 없음).
 * </ul>
 */
@Service
public class QualityDecisionService {

  private static final Logger log = LoggerFactory.getLogger(QualityDecisionService.class);
  private static final TypeReference<List<Line>> LINES = new TypeReference<>() {};

  public record CreateCommand(
      String mfcId,
      String decisionType,
      @Nullable String decidedBy,
      String reason,
      @Nullable String incidentId,
      @Nullable String note,
      List<String> evidenceRefs,
      List<Line> lines) {}

  private final QualityStore store;
  private final InventoryClient inventory;
  private final QualityEvents events;
  private final RequestIdempotency idempotency;
  private final ObjectMapper mapper;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final ApprovalRefs approvalRefs;

  public QualityDecisionService(
      QualityStore store,
      InventoryClient inventory,
      QualityEvents events,
      RequestIdempotency idempotency,
      ObjectMapper mapper,
      PlatformTransactionManager txManager,
      Clock clock,
      ApprovalRefs approvalRefs) {
    this.approvalRefs = approvalRefs;
    this.store = store;
    this.inventory = inventory;
    this.events = events;
    this.idempotency = idempotency;
    this.mapper = mapper;
    this.tx = new TransactionTemplate(txManager);
    this.clock = clock;
  }

  public CommandResponse create(String requestId, String sourceSystem, CreateCommand cmd) {
    String me = Actors.worker(cmd.mfcId(), cmd.decidedBy());
    if (!List.of("QUARANTINE", "RELEASE").contains(cmd.decisionType())) {
      throw invalid("decisionType 은 QUARANTINE/RELEASE 입니다.");
    }
    if (!QualityRules.REASONS.contains(cmd.reason())) {
      throw invalid("reason 은 " + QualityRules.REASONS + " 중 하나입니다.");
    }
    String bad = QualityRules.invalid(cmd.lines());
    if (bad != null) {
      throw invalid(bad);
    }
    CommandResponse r =
        idempotency
            .execute(
                Actors.key(sourceSystem, requestId, "createQualityDecision"),
                cmd,
                CommandResponse.class,
                () -> {
                  Instant now = clock.instant();
                  String id = "QD-" + UUID.randomUUID();
                  boolean release = "RELEASE".equals(cmd.decisionType());
                  store.insertDecision(
                      new DecisionRow(
                          id,
                          cmd.mfcId(),
                          cmd.decisionType(),
                          release ? "REQUESTED" : "SUBMITTED",
                          cmd.reason(),
                          me,
                          null,
                          cmd.incidentId(),
                          cmd.note(),
                          null,
                          mapper.writeValueAsString(cmd.evidenceRefs()),
                          mapper.writeValueAsString(
                              new ArrayList<>(QualityRules.merge(cmd.lines()).values())),
                          null,
                          0,
                          null,
                          1,
                          now,
                          null,
                          now));
                  log.info(
                      "품질 판정 {} {} {}개 by {}{}",
                      id,
                      cmd.decisionType(),
                      cmd.lines().stream().mapToInt(Line::qty).sum(),
                      me,
                      release ? " — 해제 승인 대기" : "");
                  return new CommandResponse(
                      CommandStatus.ACCEPTED, requestId, id, null, 1, false, null, now);
                })
            .value();
    String id = Objects.requireNonNull(r.resultId());
    post(id);
    DecisionRow d = get(id);
    return new CommandResponse(
        status(d.state()),
        requestId,
        id,
        d.appliedQty(),
        d.version(),
        "UNKNOWN_RESULT".equals(d.state()) || "SUBMITTED".equals(d.state()),
        nextAction(d.state()),
        d.updatedAt());
  }

  /**
   * 해제 승인·반려. (AP-1, 0.3.0) 본사 승인 기준(해제 수량 × 판매 기준가 ≥ 10만 원 또는 한 SKU 20개 — 사용자 2026-11-17 "같은 기준")
   * 이상이면 approvalRef(approval-service 승인 번호)를 재고 반영 전에 한 번 쓴다 — {@link ApprovalRefs}. 아래면 센터 관리자만.
   */
  public DecisionRow approve(
      String decisionId, String action, @Nullable String note, @Nullable String approvalRef) {
    DecisionRow d0 = get(decisionId);
    String me = Actors.supervisor(d0.mfcId(), null);
    if (!List.of("APPROVE", "REJECT").contains(action)) {
      throw invalid("action 은 APPROVE/REJECT 입니다.");
    }
    if ("APPROVE".equals(action) && "REQUESTED".equals(d0.state()) && !me.equals(d0.decidedBy())) {
      // 잠금 밖에서(외부 호출) — 상태·본인 검사는 아래 잠금 안에서 다시
      approvalRefs.require(
          d0.mfcId(),
          "QUALITY_RELEASE",
          decisionId,
          me,
          d0.mfcId() + " 격리 해제 " + decisionId,
          releaseLines(lines(d0)),
          approvalRef);
    }
    tx.executeWithoutResult(
        s -> {
          DecisionRow d = store.lockDecision(decisionId).orElseThrow();
          if (!"REQUESTED".equals(d.state())) {
            boolean same =
                me.equals(d.approverId())
                    && ("APPROVE".equals(action) != "DECLINED".equals(d.state()));
            if (same) {
              return; // 같은 결정 다시 — 지금 판정
            }
            throw PmartException.invalidState("승인 대기 판정이 아닙니다(지금 " + d.state() + ").");
          }
          if (me.equals(d.decidedBy())) {
            throw new PmartException(
                QualityRules.SELF_APPROVAL,
                HttpStatus.UNPROCESSABLE_CONTENT,
                "요청자 본인은 승인할 수 없습니다.",
                false,
                List.of());
          }
          Instant now = clock.instant();
          store.decide(
              decisionId, "APPROVE".equals(action) ? "SUBMITTED" : "DECLINED", me, note, now);
          log.info("품질 판정 {} 해제 {} by {}", decisionId, action, me);
        });
    post(decisionId);
    return get(decisionId);
  }

  /** 해제는 손실이 아니지만 같은 기준(사용자 2026-11-17) — 해제 수량을 금액 계산에 쓴다. */
  static List<com.pmart.mfc.quality.domain.HqApprovalRules.Line> releaseLines(List<Line> lines) {
    return lines.stream()
        .map(
            l ->
                new com.pmart.mfc.quality.domain.HqApprovalRules.Line(
                    l.sku(),
                    l.qty(),
                    l.qty(),
                    Map.of("lotId", l.lotId(), "locationCode", l.locationCode())))
        .toList();
  }

  public DecisionRow find(String decisionId) {
    DecisionRow d = get(decisionId);
    Actors.reader(d.mfcId());
    return d;
  }

  public List<Line> lines(DecisionRow d) {
    return mapper.readValue(d.lines(), LINES);
  }

  /** 재고 반영(트랜잭션 밖) → 결과 기록. 진행 중 상태가 아니면 아무것도 안 함. */
  private void post(String decisionId) {
    DecisionRow d = get(decisionId);
    if (!"SUBMITTED".equals(d.state()) && !"UNKNOWN_RESULT".equals(d.state())) {
      return;
    }
    List<Line> lines = lines(d);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("postingType", d.decisionType());
    body.put("mfcId", d.mfcId());
    body.put("qualityDecisionId", d.decisionId());
    body.put(
        "approvalRef",
        "RELEASE".equals(d.decisionType())
            ? Objects.requireNonNull(d.approverId()) + ":" + d.decisionId()
            : d.decisionId());
    body.put("reason", d.reason());
    body.put(
        "lines",
        lines.stream()
            .map(
                l -> {
                  Map<String, Object> m = new LinkedHashMap<>();
                  m.put("sku", l.sku());
                  m.put("lotId", l.lotId());
                  m.put("locationCode", l.locationCode());
                  m.put("qty", l.qty());
                  if (l.workState() != null) {
                    m.put("workState", l.workState());
                  }
                  return m;
                })
            .toList());
    InventoryClient.Posted p = inventory.post(d.decisionId(), body);
    tx.executeWithoutResult(
        s -> {
          Instant now = clock.instant();
          switch (p.outcome()) {
            case "APPLIED" -> {
              if (store.finishDecision(
                      decisionId, "APPLIED", p.commitId(), p.appliedQty(), null, now)
                  == 1) {
                events.decisionMade(
                    d.decisionId(),
                    d.mfcId(),
                    d.decisionType(),
                    d.reason(),
                    lines,
                    d.approverId() != null ? d.approverId() : d.decidedBy(),
                    d.approverId(),
                    d.version() + 1,
                    now);
                log.info("품질 판정 {} 재고 반영 {}", decisionId, p.commitId());
              }
            }
            case "REJECTED" -> {
              if (store.finishDecision(decisionId, "REJECTED", null, 0, p.reason(), now) == 1) {
                log.warn("품질 판정 {} — 재고가 거절: {}", decisionId, p.reason());
              }
            }
            default -> store.finishDecision(decisionId, "UNKNOWN_RESULT", null, 0, p.reason(), now);
          }
        });
  }

  /** 재고 결과를 모르는 판정을 같은 키로 다시. */
  @Scheduled(
      fixedDelayString = "${pmart.quality.sweep-interval:PT30S}",
      initialDelayString = "PT30S")
  public void sweep() {
    for (String id : store.openDecisions(clock.instant().minusSeconds(10), 50)) {
      try {
        post(id);
      } catch (RuntimeException e) {
        log.warn("품질 판정 {} 재고 반영 재시도 실패: {}", id, e.getMessage());
      }
    }
  }

  private DecisionRow get(String id) {
    return store
        .decision(id)
        .orElseThrow(
            () -> PmartException.notFound("QUALITY_DECISION_NOT_FOUND", "판정이 없습니다: " + id));
  }

  static CommandStatus status(String state) {
    return switch (state) {
      case "APPLIED" -> CommandStatus.APPLIED;
      case "REJECTED", "DECLINED" -> CommandStatus.REJECTED;
      case "REQUESTED" -> CommandStatus.ACCEPTED;
      default -> CommandStatus.UNKNOWN_RESULT;
    };
  }

  static @Nullable String nextAction(String state) {
    return switch (state) {
      case "REQUESTED" -> "AWAIT_APPROVAL";
      case "SUBMITTED", "UNKNOWN_RESULT" -> "WAIT_RESULT";
      case "REJECTED" -> "RECONCILE";
      default -> null;
    };
  }

  static PmartException invalid(String message) {
    return new PmartException(ErrorCode.VALIDATION_FAILED, message);
  }
}
