package com.pmart.mfc.quality.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.web.CommandResponse;
import com.pmart.common.web.CommandStatus;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.quality.domain.QualityRules;
import com.pmart.mfc.quality.domain.QualityRules.DisposalLine;
import com.pmart.mfc.quality.domain.QualityRules.Line;
import com.pmart.mfc.quality.infrastructure.InventoryClient;
import com.pmart.mfc.quality.infrastructure.QualityStore;
import com.pmart.mfc.quality.infrastructure.QualityStore.DisposalRow;
import com.pmart.mfc.quality.infrastructure.QualityStore.ExecutionRow;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
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
 * 폐기 승인·실행(IV-10). 요청 → 다른 운영자(MFC_SUPERVISOR) 승인(approvalRef 필수, 부분 승인) → 실제 폐기 실행마다 승인 잔량 안에서
 * inventory DISPOSE(H−q, Q−q). 승인만으로 현물을 줄이지 않는다(IV-10 "폐기 승인2·실행1이면 H/Q 각1 감소하고 미실행1 유지").
 */
@Service
public class DisposalService {

  private static final Logger log = LoggerFactory.getLogger(DisposalService.class);
  private static final TypeReference<List<DisposalLine>> DLINES = new TypeReference<>() {};
  private static final TypeReference<List<Line>> LINES = new TypeReference<>() {};

  public record DecideCommand(
      String action,
      String mfcId,
      @Nullable String disposalApprovalId,
      @Nullable String actorId,
      @Nullable String approvalRef,
      @Nullable String reason,
      @Nullable String sourceRef,
      @Nullable List<Line> lines) {}

  public record View(DisposalRow row, List<DisposalLine> lines, List<ExecutionRow> executions) {}

  private final QualityStore store;
  private final InventoryClient inventory;
  private final QualityEvents events;
  private final RequestIdempotency idempotency;
  private final ObjectMapper mapper;
  private final TransactionTemplate tx;
  private final Clock clock;

  private final ApprovalRefs approvalRefs;

  public DisposalService(
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

  public CommandResponse decide(String requestId, String sourceSystem, DecideCommand cmd) {
    return switch (cmd.action()) {
      case "REQUEST" -> request(requestId, sourceSystem, cmd);
      case "APPROVE", "REJECT" -> approve(requestId, sourceSystem, cmd);
      default -> throw QualityDecisionService.invalid("action 은 REQUEST/APPROVE/REJECT 입니다.");
    };
  }

  private CommandResponse request(String requestId, String sourceSystem, DecideCommand cmd) {
    String me = Actors.worker(cmd.mfcId(), cmd.actorId());
    List<Line> lines = cmd.lines() == null ? List.of() : cmd.lines();
    String bad = QualityRules.invalid(lines);
    if (bad != null) {
      throw QualityDecisionService.invalid(bad);
    }
    return idempotency
        .execute(
            Actors.key(sourceSystem, requestId, "requestDisposal"),
            cmd,
            CommandResponse.class,
            () -> {
              Instant now = clock.instant();
              String id = "DSP-" + UUID.randomUUID();
              List<DisposalLine> ds = new ArrayList<>();
              for (Line l : QualityRules.merge(lines).values()) {
                ds.add(
                    new DisposalLine(
                        l.sku(), l.lotId(), l.locationCode(), l.workState(), l.qty(), 0, 0));
              }
              store.insertDisposal(
                  new DisposalRow(
                      id,
                      cmd.mfcId(),
                      "REQUESTED",
                      me,
                      null,
                      null,
                      cmd.reason(),
                      cmd.sourceRef(),
                      mapper.writeValueAsString(ds),
                      1,
                      now,
                      null,
                      now));
              log.info("폐기 요청 {} {}줄 by {}", id, ds.size(), me);
              return new CommandResponse(
                  CommandStatus.ACCEPTED, requestId, id, null, 1, false, "AWAIT_APPROVAL", now);
            })
        .value();
  }

  private CommandResponse approve(String requestId, String sourceSystem, DecideCommand cmd) {
    String id = cmd.disposalApprovalId();
    if (id == null || id.isBlank()) {
      throw QualityDecisionService.invalid("APPROVE/REJECT 는 disposalApprovalId 가 필요합니다.");
    }
    DisposalRow d0 = get(id);
    if (!d0.mfcId().equals(cmd.mfcId())) {
      throw QualityDecisionService.invalid("다른 센터의 폐기 요청입니다.");
    }
    String me = Actors.supervisor(d0.mfcId(), cmd.actorId());
    boolean approve = "APPROVE".equals(cmd.action());
    return idempotency
        .execute(
            Actors.key(sourceSystem, requestId, "decideDisposal"),
            cmd,
            CommandResponse.class,
            () -> {
              DisposalRow d = store.lockDisposal(id).orElseThrow();
              if (!"REQUESTED".equals(d.state())) {
                throw PmartException.invalidState("승인 대기 폐기 요청이 아닙니다(지금 " + d.state() + ").");
              }
              if (me.equals(d.requesterId())) {
                throw new PmartException(
                    QualityRules.SELF_APPROVAL,
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "요청자 본인은 승인할 수 없습니다.",
                    false,
                    List.of());
              }
              Instant now = clock.instant();
              List<DisposalLine> requested = mapper.readValue(d.lines(), DLINES);
              if (!approve) {
                store.decideDisposal(id, "REJECTED", me, null, d.lines(), now);
                log.info("폐기 요청 {} 반려 by {}", id, me);
                return new CommandResponse(
                    CommandStatus.REJECTED, requestId, id, 0, d.version() + 1, false, null, now);
              }
              List<DisposalLine> approved;
              try {
                approved = QualityRules.approve(requested, cmd.lines());
              } catch (IllegalArgumentException e) {
                throw QualityDecisionService.invalid(e.getMessage());
              }
              // 승인 번호 관문(AP-1): 승인 수량 × 판매 기준가 ≥ 10만 원 또는 한 SKU 20개면 본사 승인 번호를 한 번 쓴다
              String given =
                  approvalRefs.require(
                      d.mfcId(),
                      "DISPOSAL",
                      id,
                      me,
                      d.mfcId() + " 폐기 " + id,
                      approved.stream()
                          .filter(l -> l.approvedQty() > 0)
                          .map(
                              l ->
                                  new com.pmart.mfc.quality.domain.HqApprovalRules.Line(
                                      l.sku(),
                                      l.approvedQty(),
                                      l.approvedQty(),
                                      Map.of("lotId", l.lotId(), "locationCode", l.locationCode())))
                          .toList(),
                      cmd.approvalRef());
              String recorded = given != null ? given : "CENTER:" + id;
              store.decideDisposal(
                  id, "APPROVED", me, recorded, mapper.writeValueAsString(approved), now);
              events.disposalApproved(
                  id, d.mfcId(), approved, me, recorded, d.reason(), d.version() + 1, now);
              int qty = approved.stream().mapToInt(DisposalLine::approvedQty).sum();
              log.info("폐기 승인 {} {}개 by {} (근거 {})", id, qty, me, recorded);
              return new CommandResponse(
                  CommandStatus.APPLIED,
                  requestId,
                  id,
                  qty,
                  d.version() + 1,
                  false,
                  "EXECUTE",
                  now);
            })
        .value();
  }

  /** 실제 폐기 실행 — 승인 잔량 안에서 inventory DISPOSE. */
  public ExecutionRow execute(
      String disposalId, String requestKey, List<Line> lines, @Nullable String note) {
    DisposalRow d0 = get(disposalId);
    String me = Actors.worker(d0.mfcId(), null);
    String bad = QualityRules.invalid(lines);
    if (bad != null) {
      throw QualityDecisionService.invalid(bad);
    }
    ExecutionRow e =
        Objects.requireNonNull(
            tx.execute(
                s -> {
                  var done = store.executionByKey(disposalId, requestKey);
                  if (done.isPresent()) {
                    return done.get();
                  }
                  DisposalRow d = store.lockDisposal(disposalId).orElseThrow();
                  done = store.executionByKey(disposalId, requestKey);
                  if (done.isPresent()) {
                    return done.get();
                  }
                  if (!"APPROVED".equals(d.state()) && !"PARTIALLY_EXECUTED".equals(d.state())) {
                    throw PmartException.invalidState("승인된 폐기만 실행합니다(지금 " + d.state() + ").");
                  }
                  List<DisposalLine> approved = mapper.readValue(d.lines(), DLINES);
                  String over = QualityRules.exceeds(approved, pending(disposalId), lines);
                  if (over != null) {
                    throw new PmartException(
                        QualityRules.QTY_EXCEEDS_APPROVED,
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        over,
                        false,
                        List.of());
                  }
                  Instant now = clock.instant();
                  // 승인 줄의 업무 상태를 따른다(실행 줄은 sku·LOT·위치로 찾는다)
                  List<Line> merged = new ArrayList<>(QualityRules.merge(lines).values());
                  ExecutionRow fresh =
                      new ExecutionRow(
                          "DSX-" + UUID.randomUUID(),
                          disposalId,
                          requestKey,
                          "SUBMITTED",
                          me,
                          mapper.writeValueAsString(merged),
                          merged.stream().mapToInt(Line::qty).sum(),
                          note,
                          null,
                          null,
                          now,
                          now);
                  store.insertExecution(fresh);
                  return fresh;
                }));
    post(e.executionId());
    return store.execution(e.executionId()).orElseThrow();
  }

  public View view(String disposalId) {
    DisposalRow d = get(disposalId);
    Actors.reader(d.mfcId());
    return new View(d, mapper.readValue(d.lines(), DLINES), store.executions(disposalId));
  }

  public List<Line> lines(ExecutionRow e) {
    return mapper.readValue(e.lines(), LINES);
  }

  /** 진행 중 실행이 점유한 단위 키별 수량. */
  private Map<String, Integer> pending(String disposalId) {
    Map<String, Integer> out = new HashMap<>();
    for (ExecutionRow e : store.executions(disposalId)) {
      if ("SUBMITTED".equals(e.state()) || "UNKNOWN_RESULT".equals(e.state())) {
        for (Line l : lines(e)) {
          out.merge(l.key(), l.qty(), Integer::sum);
        }
      }
    }
    return out;
  }

  private void post(String executionId) {
    ExecutionRow e = store.execution(executionId).orElseThrow();
    if (!"SUBMITTED".equals(e.state()) && !"UNKNOWN_RESULT".equals(e.state())) {
      return;
    }
    DisposalRow d = get(e.disposalApprovalId());
    List<Line> lines = lines(e);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("postingType", "DISPOSE");
    body.put("mfcId", d.mfcId());
    body.put("approvalRef", d.disposalApprovalId());
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
    InventoryClient.Posted p = inventory.post(executionId, body);
    tx.executeWithoutResult(
        s -> {
          Instant now = clock.instant();
          switch (p.outcome()) {
            case "APPLIED" -> {
              DisposalRow locked = store.lockDisposal(d.disposalApprovalId()).orElseThrow();
              if (store.finishExecution(executionId, "APPLIED", p.commitId(), null, now) == 1) {
                List<DisposalLine> next =
                    QualityRules.addExecuted(mapper.readValue(locked.lines(), DLINES), lines);
                store.updateDisposalProgress(
                    d.disposalApprovalId(),
                    QualityRules.stateAfterExecution(next),
                    mapper.writeValueAsString(next),
                    now);
                log.info(
                    "폐기 실행 {} ({}) {}개 — 재고 {}",
                    executionId,
                    d.disposalApprovalId(),
                    e.qty(),
                    p.commitId());
              }
            }
            case "REJECTED" -> {
              if (store.finishExecution(executionId, "REJECTED", null, p.reason(), now) == 1) {
                log.warn("폐기 실행 {} — 재고가 거절: {}", executionId, p.reason());
              }
            }
            default -> store.finishExecution(executionId, "UNKNOWN_RESULT", null, p.reason(), now);
          }
        });
  }

  @Scheduled(
      fixedDelayString = "${pmart.quality.sweep-interval:PT30S}",
      initialDelayString = "PT40S")
  public void sweep() {
    for (String id : store.openExecutions(clock.instant().minusSeconds(10), 50)) {
      try {
        post(id);
      } catch (RuntimeException ex) {
        log.warn("폐기 실행 {} 재고 반영 재시도 실패: {}", id, ex.getMessage());
      }
    }
  }

  private DisposalRow get(String id) {
    return store
        .disposal(id)
        .orElseThrow(() -> PmartException.notFound("DISPOSAL_NOT_FOUND", "폐기 요청이 없습니다: " + id));
  }
}
