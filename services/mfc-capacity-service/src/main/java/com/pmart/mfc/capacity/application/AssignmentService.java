package com.pmart.mfc.capacity.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.capacity.infrastructure.AssignmentStore;
import com.pmart.mfc.capacity.infrastructure.AssignmentStore.AssignmentRow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 작업 배정(WK-02·WK-05, 슬라이스 6b). Q-08(우선순위·자동 배정) 결정 전에는 권한자 수동 지정만 — 그 센터의 {@code MFC_SUPERVISOR}.
 * 작업자는 운영자 ID(작업자 로그인 BR-02 결정 전 잠정). 작업자 자격·근무·장비 정본이 없어 강제 제약은 아직 검사하지 않는다.
 */
@Service
public class AssignmentService {

  public static final String TOPIC = "mfc.capacity.v1";
  public static final String SUPERVISOR_ROLE = "MFC_SUPERVISOR";

  /** 현장 작업자 역할 — 이 역할만 가진 토큰은 작업자 정보를 본인 것만 본다. */
  public static final String WORKER_ROLE = "MFC_WORKER";

  /**
   * 업무 유형 목록과 하나로(RETURN 포함 — 2026-11-08 LAN E2E 에서 빠진 것 발견). (0.27.0) + 미리 정한 유형(QUARANTINE_MOVE —
   * 업무 검사 없이 등록·ACTIVE 만).
   */
  private static final Set<String> TASK_TYPES =
      com.pmart.mfc.capacity.domain.WorkerRules.ASSIGNABLE_TASK_TYPES;

  private static final Set<String> STATES = Set.of("ACTIVE", "SUPERSEDED", "RELEASED");
  private static final Logger log = LoggerFactory.getLogger(AssignmentService.class);

  public record AssignCommand(
      String mfcId,
      String taskId,
      String taskType,
      String workerId,
      @Nullable String reason,
      @Nullable Integer expectedVersion,
      @Nullable String temperatureZone) {}

  /** created = false 면 이미 그 작업자(변화 없음). warnings = 막지 않는 경고(OFF_SHIFT). */
  public record Assigned(AssignmentRow assignment, boolean created, List<String> warnings) {}

  private final AssignmentStore store;
  private final RequestIdempotency idempotency;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;
  private final Clock clock;
  private final WorkerService workers;

  public AssignmentService(
      AssignmentStore store,
      RequestIdempotency idempotency,
      EventPublisher events,
      EventEnvelopeCodec codec,
      Clock clock,
      WorkerService workers) {
    this.store = store;
    this.idempotency = idempotency;
    this.events = events;
    this.codec = codec;
    this.clock = clock;
    this.workers = workers;
  }

  /** 다음 작업 받기 배정의 assignedBy(사람이 아님). */
  public static final String SYSTEM = "SYSTEM";

  public static final String NO_TASK_AVAILABLE = "NO_TASK_AVAILABLE";
  public static final String OFF_SHIFT_BLOCKED = "OFF_SHIFT";

  private com.pmart.mfc.capacity.infrastructure.@Nullable FulfillmentClient fulfillment;
  private com.pmart.mfc.capacity.infrastructure.@Nullable InboundClient inbound;

  private com.pmart.mfc.capacity.infrastructure.@Nullable CountWorkClient countWork;

  @org.springframework.beans.factory.annotation.Autowired
  void setInbound(com.pmart.mfc.capacity.infrastructure.InboundClient inbound) {
    this.inbound = inbound;
  }

  @org.springframework.beans.factory.annotation.Autowired
  void setCountWork(com.pmart.mfc.capacity.infrastructure.CountWorkClient countWork) {
    this.countWork = countWork;
  }

  /** 열린 이동 지시(배정 추천용). 이동 조회를 안 쓰면 빈 목록, 조회 실패면 null(추천은 이동 없이 — 한계로 남긴다). */
  public @Nullable List<com.pmart.mfc.capacity.infrastructure.InboundClient.MoveView> openMoves(
      String mfcId) {
    if (inbound == null || !inbound.configured()) {
      return List.of();
    }
    try {
      return inbound.openMoves(mfcId);
    } catch (RuntimeException ex) {
      log.warn("이동 지시 조회 실패 — 배정 추천에서 이동 제외: {}", ex.getMessage());
      return null;
    }
  }

  /**
   * (0.27.0) 열린 격리 이동(mfc-inbound READY 입고 작업 중 QUARANTINE_MOVE — 배정 추천용). 이동 조회를 안 쓰면 빈 목록, 조회 실패면
   * null(추천은 격리 이동 없이 — 한계 QUARANTINE_MOVES_NOT_INCLUDED).
   */
  public @Nullable List<com.pmart.mfc.capacity.infrastructure.InboundClient.InboundTaskView>
      openQuarantineMoves(String mfcId) {
    if (inbound == null || !inbound.configured()) {
      return List.of();
    }
    try {
      return inbound.openInboundTasks(mfcId).stream()
          .filter(t -> QUARANTINE_MOVE.equals(t.taskType()))
          .toList();
    } catch (RuntimeException ex) {
      log.warn("입고 작업 조회 실패 — 배정 추천에서 격리 이동 제외: {}", ex.getMessage());
      return null;
    }
  }

  /**
   * 정해진 순서의 앞섬(배정 추천 지연 벌점용, 클수록 급함). 배정 추천 입력은 주문 작업·이동뿐이라 0.13 값 그대로 둔다(같은 문제 = 같은 입력 지문). 긴급 이동은
   * 보충과 같다. (0.27.0) 격리 이동 4 — 다음 작업 받기처럼 맨 앞(새 유형이라 기존 입력 지문은 그대로).
   */
  public static int priority(String taskType, boolean urgent) {
    return switch (urgent ? "REPLENISH" : taskType) {
      case QUARANTINE_MOVE -> 4;
      case "PICK", "PACK" -> 3;
      case "REPLENISH" -> 2;
      case "MOVE" -> 1;
      default -> 0;
    };
  }

  /** 받은 작업의 상세(0.9.1) — 읽지 못하면 available=false(배정은 그대로). */
  public NextTaskDetails.Detail detailOf(String mfcId, String taskId, String taskType) {
    try {
      if (CYCLE_COUNT.equals(taskType)) {
        return counts(mfcId).stream()
            .filter(c -> c.sessionId().equals(taskId))
            .findFirst()
            .map(c -> NextTaskDetails.fromCountWork(c.locationCodes()))
            .orElseGet(NextTaskDetails.Detail::unavailable);
      }
      if (RECEIVE.equals(taskType)) {
        if (inbound == null || !inbound.configured()) {
          return NextTaskDetails.Detail.unavailable();
        }
        return NextTaskDetails.fromInboundPromise(inbound.inboundPromise(taskId));
      }
      if (RETURN_RECEIPT.equals(taskType) || RETURN_INSPECT.equals(taskType)) {
        if (inbound == null || !inbound.configured()) {
          return NextTaskDetails.Detail.unavailable();
        }
        if (RETURN_INSPECT.equals(taskType)) {
          return NextTaskDetails.fromReturnReceipt(inbound.returnReceipt(taskId));
        }
        return inbound.waitingReturnArrivals(mfcId).stream()
            .filter(a -> taskId.equals(a.path("arrivalId").asString("")))
            .findFirst()
            .map(NextTaskDetails::fromReturnArrival)
            .orElseGet(NextTaskDetails.Detail::unavailable);
      }
      if ("MOVE".equals(taskType) || "REPLENISH".equals(taskType)) {
        if (inbound == null || !inbound.configured()) {
          return NextTaskDetails.Detail.unavailable();
        }
        return NextTaskDetails.fromMove(inbound.moveOrder(taskId));
      }
      if (INBOUND_TASK_TYPES.contains(taskType) || QUARANTINE_MOVE.equals(taskType)) {
        if (inbound == null || !inbound.configured()) {
          return NextTaskDetails.Detail.unavailable();
        }
        return NextTaskDetails.fromInboundTask(inbound.inboundTask(taskId));
      }
      if (fulfillment == null) {
        return NextTaskDetails.Detail.unavailable();
      }
      return NextTaskDetails.fromTask(fulfillment.taskDetail(taskId));
    } catch (RuntimeException ex) {
      log.warn("작업 {} 상세 조회 실패 — 상세 없이 응답: {}", taskId, ex.getMessage());
      return NextTaskDetails.Detail.unavailable();
    }
  }

  /** (0.14.0, BR-52 R2) mfc-inbound 입고 작업으로 오는 유형. */
  static final Set<String> INBOUND_TASK_TYPES = Set.of("INSPECT", "PUTAWAY");

  /**
   * (0.26.0, MC-2 — 사용자 결정 2026-11-20) 격리 이동 — mfc-inbound 입고 작업, 만들 때 담당(검수자·반입자·현장 예외 보고자)이 정해져
   * 있다. 다음 작업 받기는 그 담당에게만, 다른 어떤 새 작업보다 먼저(고른 업무와 상관없이) 준다.
   */
  public static final String QUARANTINE_MOVE = "QUARANTINE_MOVE";

  /** (0.24.0, MC-1 — BR-52 R2·R14) 재고 실사 — mfc-inventory 계획 실사, taskId = sessionId. */
  public static final String CYCLE_COUNT = "CYCLE_COUNT";

  /** (0.25.0, BR-52 R2·BR-53 R9) 입고(도착·수령) — mfc-inbound 입고 예정, taskId = trfId. */
  public static final String RECEIVE = "RECEIVE";

  /** (0.24.0, BR-52 R2·R13) 회수품 반입 — mfc-inbound 반납 도착 대기, taskId = arrivalId. */
  public static final String RETURN_RECEIPT = "RETURN_RECEIPT";

  /** (0.24.0, BR-52 R2·R12) 회수품 검수 — mfc-inbound 검수 대기 반입, taskId = returnReceiptId. */
  public static final String RETURN_INSPECT = "RETURN_INSPECT";

  /**
   * 다음 작업 후보(작업 지시·이동 지시 공통). finishedHolders = 이 작업의 ACTIVE 배정이 이 사람 것이면 이미 제 몫을 끝낸 것(실사 첫 계수자) —
   * 그 배정을 RELEASED 로 닫고 새로 배정한다.
   */
  record Candidate(
      String taskId,
      String taskType,
      int rank,
      @Nullable Instant when,
      Set<String> finishedHolders) {

    Candidate(String taskId, String taskType, int rank, @Nullable Instant when) {
      this(taskId, taskType, rank, when, Set.of());
    }
  }

  /** 열린 실사 — 주소가 없거나 조회가 안 되면 빈 목록(실사는 건너뛴다). */
  private List<com.pmart.mfc.capacity.infrastructure.CountWorkClient.CountWork> counts(
      String mfcId) {
    if (countWork == null || !countWork.configured()) {
      return List.of();
    }
    try {
      return countWork.list(mfcId);
    } catch (RuntimeException ex) {
      log.warn("실사 조회 실패 — 실사는 건너뜀: {}", ex.getMessage());
      return List.of();
    }
  }

  /** (0.25.0) 끝나지 않은 입고 예정 — 조회가 안 되면 빈 목록(입고는 건너뛴다). */
  private List<tools.jackson.databind.JsonNode> openPromises(String mfcId) {
    if (inbound == null || !inbound.configured()) {
      return List.of();
    }
    try {
      return inbound.openInboundPromises(mfcId);
    } catch (RuntimeException ex) {
      log.warn("입고 예정 조회 실패 — 입고는 건너뜀: {}", ex.getMessage());
      return List.of();
    }
  }

  /** 회수품 반입·검수 — 조회가 안 되면 그 종류만 건너뛴다. */
  private List<com.pmart.mfc.capacity.infrastructure.InboundClient.ReturnWorkView> returnWork(
      String mfcId) {
    List<com.pmart.mfc.capacity.infrastructure.InboundClient.ReturnWorkView> out =
        new java.util.ArrayList<>();
    if (inbound == null || !inbound.configured()) {
      return out;
    }
    try {
      out.addAll(inbound.returnReceiptWork(mfcId));
    } catch (RuntimeException ex) {
      log.warn("반납 도착 대기 조회 실패 — 회수품 반입은 건너뜀: {}", ex.getMessage());
    }
    try {
      out.addAll(inbound.returnInspectWork(mfcId));
    } catch (RuntimeException ex) {
      log.warn("검수 대기 반입 조회 실패 — 회수품 검수는 건너뜀: {}", ex.getMessage());
    }
    return out;
  }

  private org.springframework.transaction.support.@Nullable TransactionTemplate tx;

  @org.springframework.beans.factory.annotation.Autowired
  void setPullDeps(
      com.pmart.mfc.capacity.infrastructure.FulfillmentClient fulfillment,
      org.springframework.transaction.PlatformTransactionManager txm) {
    this.fulfillment = fulfillment;
    this.tx = new org.springframework.transaction.support.TransactionTemplate(txm);
  }

  /**
   * 다음 작업 받기(2026-11-08 사용자 결정 — 현장 크루는 시스템이 작업을 하나씩 배정). 작업자 본인만. 들고 있는 작업이 있으면 그 배정(created =
   * false), 없으면 업무 안의 담당 없는 READY 작업 중 가장 급한 것(PICK·PACK 약속 이른 순 → RETURN)을 SYSTEM 배정.
   */
  public Assigned pullNext(String mfcId, String workerId) {
    return pullNext(mfcId, workerId, null);
  }

  /**
   * (0.13.0, BR-52 R1) 고른 업무(workArea) 안에서만 새 작업 — 업무 묶음 ∩ 유효 업무가 비면 422 WORK_AREA_NOT_ALLOWED. 들고
   * 있는 작업은 업무와 상관없이 돌려준다(한 번에 하나).
   */
  public Assigned pullNext(String mfcId, String workerId, @Nullable String workArea) {
    requireSelf(mfcId, workerId);
    String reject = workers.rejection(mfcId, workerId, "PICK", null);
    if (com.pmart.mfc.capacity.domain.WorkerRules.WORKER_NOT_REGISTERED.equals(reject)
        || com.pmart.mfc.capacity.domain.WorkerRules.WORKER_NOT_ACTIVE.equals(reject)
        || com.pmart.mfc.capacity.domain.WorkerRules.DAILY_NOT_ENROLLED.equals(reject)) {
      throw new PmartException(
          reject,
          org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT,
          "작업을 받을 수 없는 작업자입니다: " + reject,
          false,
          List.of());
    }
    Instant now = clock.instant();
    if (workers.offShift(mfcId, workerId, now)) {
      throw new PmartException(
          OFF_SHIFT_BLOCKED,
          org.springframework.http.HttpStatus.CONFLICT,
          "등록 근무 밖에는 시스템이 작업을 주지 않습니다(센터 관리자 배정만).",
          false,
          List.of());
    }
    var tasks =
        java.util.Objects.requireNonNull(fulfillment)
            .tasks(mfcId, List.of("READY", "ASSIGNED", "IN_PROGRESS"));
    java.util.Map<String, com.pmart.mfc.capacity.infrastructure.FulfillmentClient.TaskView> open =
        new java.util.HashMap<>();
    tasks.forEach(t -> open.put(t.taskId(), t));
    // 이동·보충(mfc-inbound) — 조회가 안 되면 건너뛴다(주문 작업은 계속)
    List<com.pmart.mfc.capacity.infrastructure.InboundClient.MoveView> moves = List.of();
    if (inbound != null && inbound.configured()) {
      try {
        moves = inbound.openMoves(mfcId);
      } catch (RuntimeException ex) {
        log.warn("이동 지시 조회 실패 — 이동은 건너뜀: {}", ex.getMessage());
      }
    }
    java.util.Set<String> openMoves = new java.util.HashSet<>();
    moves.forEach(m -> openMoves.add(m.moveId()));
    // 품질검사·적치(mfc-inbound 입고 작업, 0.14.0) — 조회가 안 되면 건너뛴다
    List<com.pmart.mfc.capacity.infrastructure.InboundClient.InboundTaskView> inboundTasks =
        List.of();
    if (inbound != null && inbound.configured()) {
      try {
        inboundTasks = inbound.openInboundTasks(mfcId);
      } catch (RuntimeException ex) {
        log.warn("입고 작업 조회 실패 — 품질검사·적치는 건너뜀: {}", ex.getMessage());
      }
    }
    inboundTasks.forEach(t -> openMoves.add(t.taskId()));
    // 회수품 반입·검수(mfc-inbound)·재고 실사(mfc-inventory, 0.24.0) — 조회가 안 되면 그 종류만 건너뛴다
    var returns = returnWork(mfcId);
    returns.forEach(r -> openMoves.add(r.taskId()));
    // 입고(mfc-inbound 입고 예정, 0.25.0) — 끝나지 않은 예정(EXPECTED·RECEIVING)은 들고 있는 작업, 받을 수 있는 것(0.26.0 도착한
    // 것)만 후보
    var promises = openPromises(mfcId);
    promises.forEach(p -> openMoves.add(p.path("trfId").asString("")));
    var receives = com.pmart.mfc.capacity.infrastructure.InboundClient.receiveWork(promises);
    var counts = counts(mfcId);
    java.util.Map<String, com.pmart.mfc.capacity.infrastructure.CountWorkClient.CountWork>
        openCounts = new java.util.HashMap<>();
    counts.forEach(c -> openCounts.put(c.sessionId(), c));
    // 한 번에 하나: 이 작업자의 ACTIVE 배정 중 아직 끝나지 않은 작업
    for (AssignmentRow a : store.list(mfcId, null, workerId, "ACTIVE", null)) {
      if (CYCLE_COUNT.equals(a.taskType())) {
        // 실사는 계수 단계에 남아 있어도 내가 이미 셌으면(재계수 대기 — 셀 수 없는 사람) 끝난 것
        var c = openCounts.get(a.taskId());
        if (c != null && !c.excludedOperatorIds().contains(workerId)) {
          return new Assigned(a, false, List.of());
        }
        continue;
      }
      if (open.containsKey(a.taskId()) || openMoves.contains(a.taskId())) {
        return new Assigned(a, false, List.of());
      }
    }
    // 휴게 중에는 새 작업 없음(들고 있던 작업은 위에서 돌려줌)
    if (workers.onBreak(mfcId, workerId)) {
      throw new PmartException(
          "ON_BREAK",
          org.springframework.http.HttpStatus.CONFLICT,
          "휴게 중에는 새 작업을 받지 않습니다 — 휴게를 끝낸 뒤.",
          false,
          List.of());
    }
    // (0.26.0, MC-2 — 사용자 결정 2026-11-20 "다음 업무에서 픽업하기 전에 새 업무로") 나에게 미리 정해진 격리 이동이 맨 먼저 —
    // 고른 업무·업무 범위와 상관없이(담당은 mfc-inbound 가 만들 때 정했다). 다른 사람에게는 주지 않는다.
    // (0.27.0) capacity 배정이 정본 — 센터 관리자가 다른 사람에게 바꿨으면(ACTIVE 배정이 그 사람) 그 사람은 위 "들고 있는 작업"으로 받고,
    // inbound assigneeId 가 아직 옛 담당이어도(사건 투영 전) 옛 담당은 배정 잠금(lockActive)에서 걸러져 받지 않는다
    List<Candidate> quarantine = new java.util.ArrayList<>();
    inboundTasks.stream()
        .filter(t -> QUARANTINE_MOVE.equals(t.taskType()) && workerId.equals(t.assigneeId()))
        .forEach(
            t -> quarantine.add(new Candidate(t.taskId(), QUARANTINE_MOVE, -2, t.createdAt())));
    // (0.29.0, 사용자 결정 2026-11-20 "실사는 순서에 넣지 않고 계획 때 담당자 지정 — 그 사람에게 맨 먼저") 나를 담당으로 정한 계획 실사 —
    // 격리 이동 다음, 고른 업무·업무 범위와 상관없이(센터 관리자가 사람을 정했다)
    counts.stream()
        .filter(c -> workerId.equals(c.effectiveAssignee()))
        .forEach(
            c ->
                quarantine.add(
                    new Candidate(
                        c.sessionId(),
                        CYCLE_COUNT,
                        ASSIGNED_COUNT_RANK,
                        c.baselineAt(),
                        c.excludedOperatorIds())));
    Assigned first = assignFirst(mfcId, workerId, quarantine);
    if (first != null) {
      return first;
    }
    java.util.Set<String> duty =
        com.pmart.mfc.capacity.domain.WorkAreas.narrow(
            workers.taskTypes(mfcId, workerId), workArea);
    if (workArea != null && duty.isEmpty()) {
      throw new PmartException(
          com.pmart.mfc.capacity.domain.WorkAreas.WORK_AREA_NOT_ALLOWED,
          org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT,
          "이 업무(" + workArea + ")를 할 수 있는 자격·업무가 없습니다.",
          false,
          List.of());
    }
    List<Candidate> candidates = new java.util.ArrayList<>();
    tasks.stream()
        .filter(t -> "READY".equals(t.state()) && t.workerId() == null)
        .filter(t -> duty.contains(t.taskType()))
        .filter(t -> pickerMayTake(t, workerId))
        .forEach(
            t ->
                candidates.add(
                    new Candidate(
                        t.taskId(),
                        t.taskType(),
                        workerId.equals(t.pickedBy()) ? OWN_PACK_RANK : rank(t.taskType()),
                        t.promisedReadyAt())));
    moves.stream()
        .filter(m -> "ACTIVE".equals(m.state()) && m.pickedQty() == 0 && m.assigneeId() == null)
        .filter(m -> duty.contains(m.taskType()))
        .forEach(
            m ->
                candidates.add(
                    new Candidate(
                        m.moveId(),
                        m.taskType(),
                        m.urgent() ? rank("REPLENISH") : rank(m.taskType()),
                        m.createdAt())));
    inboundTasks.stream()
        .filter(t -> t.assigneeId() == null)
        .filter(t -> INBOUND_TASK_TYPES.contains(t.taskType()) && duty.contains(t.taskType()))
        .forEach(
            t ->
                candidates.add(
                    new Candidate(t.taskId(), t.taskType(), rank(t.taskType()), t.createdAt())));
    // 입고·회수품 반입·검수: 담당은 이 서비스 배정만이 안다 — 다른 사람이 들고 있으면 배정 때 잠금(lockActive)에서 걸러진다
    java.util.stream.Stream.concat(receives.stream(), returns.stream())
        .filter(r -> duty.contains(r.taskType()))
        .forEach(
            r ->
                candidates.add(
                    new Candidate(r.taskId(), r.taskType(), rank(r.taskType()), r.at())));
    // 재고 실사: 셀 수 없는 사람(재계수의 첫 계수자)에게는 주지 않는다. 첫 계수자의 배정은 끝난 것으로 닫고 다른 사람에게
    if (duty.contains(CYCLE_COUNT)) {
      counts.stream()
          .filter(c -> !c.excludedOperatorIds().contains(workerId))
          .filter(c -> c.effectiveAssignee() == null) // 담당이 정해진 실사는 그 사람만(위)
          .forEach(
              c ->
                  candidates.add(
                      new Candidate(
                          c.sessionId(),
                          CYCLE_COUNT,
                          rank(CYCLE_COUNT),
                          c.baselineAt(),
                          c.excludedOperatorIds())));
    }
    Assigned made = assignFirst(mfcId, workerId, aged(candidates, now));
    if (made != null) {
      return made;
    }
    throw new PmartException(
        NO_TASK_AVAILABLE,
        org.springframework.http.HttpStatus.CONFLICT,
        "지금 받을 수 있는 작업이 없습니다.",
        true,
        List.of());
  }

  /** 후보를 순서(rank → 시각 → ID)대로 배정해 본다 — 처음 잡힌 것, 하나도 안 되면 null. */
  private @Nullable Assigned assignFirst(
      String mfcId, String workerId, List<Candidate> candidates) {
    candidates.sort(
        java.util.Comparator.comparingInt(Candidate::rank)
            .thenComparing(
                Candidate::when,
                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
            .thenComparing(Candidate::taskId));
    for (var t : candidates) {
      AssignmentRow made;
      try {
        made = tryAssignNext(mfcId, workerId, t);
      } catch (org.springframework.dao.DuplicateKeyException race) {
        // 활성 배정이 없을 때는 FOR UPDATE 로 잠글 행이 없다 — 동시에 같은 작업을 잡으면 부분 유일 인덱스가 한쪽을 막는다. 진 쪽은 다음 후보로
        log.info("다음 작업 {} 경합 — 다른 배정이 먼저, 다음 후보로", t.taskId());
        continue;
      }
      if (made != null) {
        log.info("다음 작업 {} {} → {} (시스템 배정)", made.taskType(), made.taskId(), workerId);
        return new Assigned(made, true, List.of());
      }
    }
    return null;
  }

  /** 자기가 피킹한 출고의 PACK — 다른 어떤 후보보다 먼저(장바구니를 든 채 다른 주문을 받지 않게). */
  static final int OWN_PACK_RANK = -1;

  /** (0.29.0) 나를 담당으로 정한 계획 실사 — 격리 이동(−2) 다음. */
  static final int ASSIGNED_COUNT_RANK = -1;

  /**
   * (0.29.0) 오래 기다린 작업 앞당기기 설정 접두사 — {@code pmart.capacity.next-task.aging.<작업 유형>} = 기간(예: 30m,
   * PT30M).
   */
  static final String AGING_PREFIX = "pmart.capacity.next-task.aging.";

  /**
   * 앞당기기 기본 기준(사용자 결정 2026-11-20 "복귀 30분, 회수품 검수 1시간") — 설정 {@link #AGING_PREFIX}+유형이 있으면 그 값,
   * {@code off}·0 이면 끔.
   */
  static final java.util.Map<String, Duration> DEFAULT_AGING =
      java.util.Map.of("RETURN", Duration.ofMinutes(30), RETURN_INSPECT, Duration.ofHours(1));

  private org.springframework.core.env.@Nullable Environment env;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setEnvironment(org.springframework.core.env.Environment env) {
    this.env = env;
  }

  /**
   * (0.29.0, 사용자 결정 2026-11-20 "오래 밀린 복귀·회수품 검수는 피킹 바로 뒤로") 그 유형의 기준 시간({@link #AGING_PREFIX}+유형)을
   * 넘게 기다린 후보(기다린 기준 = 후보의 when — 작업이 READY 가 된 시각·반입 시각 등)는 보충 자리(1)로 올린다. 설정이 없으면 {@link
   * #DEFAULT_AGING}(복귀 30분·회수품 검수 1시간, 사용자 결정), 그 밖 유형은 하지 않는다. 호출마다 읽는다(설정 갱신 반영).
   */
  List<Candidate> aged(List<Candidate> in, Instant now) {
    List<Candidate> out = new java.util.ArrayList<>(in.size());
    for (Candidate c : in) {
      Duration limit = agingLimit(c.taskType());
      int up = rank("REPLENISH");
      if (limit != null
          && c.when() != null
          && c.rank() > up
          && !now.isBefore(c.when().plus(limit))) {
        out.add(new Candidate(c.taskId(), c.taskType(), up, c.when(), c.finishedHolders()));
      } else {
        out.add(c);
      }
    }
    return out;
  }

  private @Nullable Duration agingLimit(String taskType) {
    String raw = env == null ? null : env.getProperty(AGING_PREFIX + taskType);
    if (raw == null || raw.isBlank()) {
      return DEFAULT_AGING.get(taskType);
    }
    if ("off".equalsIgnoreCase(raw.trim())) {
      return null;
    }
    try {
      Duration d = org.springframework.boot.convert.DurationStyle.detectAndParse(raw.trim());
      return d.isNegative() || d.isZero() ? null : d;
    } catch (RuntimeException ex) {
      log.warn("설정 {}{} 값을 읽을 수 없음 — 앞당기기 안 함: {}", AGING_PREFIX, taskType, raw);
      return null;
    }
  }

  /**
   * 피킹한 사람만 그 출고 PACK 을 받는다(사용자 결정 2026-11-18 "피커만 받음" — 업무 §7-6 장바구니를 든 피커가 패킹대로, mfc-app 요청
   * R1·R2). 피커가 휴게·퇴근·정지면 센터 관리자 배정(`POST /assignments`)으로만 다른 사람에게. 피커를 모르는 PACK(예전 작업)은 누구나.
   */
  static boolean pickerMayTake(
      com.pmart.mfc.capacity.infrastructure.FulfillmentClient.TaskView t, String workerId) {
    return !"PACK".equals(t.taskType()) || t.pickedBy() == null || t.pickedBy().equals(workerId);
  }

  /** 후보 하나를 한 트랜잭션에서 배정. 이미 활성 배정이 있으면 null. 동시 삽입 경합은 DuplicateKeyException(호출자가 다음 후보로). */
  private @Nullable AssignmentRow tryAssignNext(String mfcId, String workerId, Candidate t) {
    return java.util.Objects.requireNonNull(tx)
        .execute(
            s -> {
              Instant at = clock.instant();
              var active = store.lockActive(t.taskId());
              if (active.isPresent()) {
                if (!t.finishedHolders().contains(active.get().workerId())) {
                  return null; // 다른 작업자가 먼저(또는 센터 관리자 배정)
                }
                // 제 몫을 끝낸 사람의 배정(실사 첫 계수) — 닫고 다음 사람에게(사건 없음, 새 배정 버전이 담당을 바꾼다)
                store.release(active.get().assignmentId(), at);
              }
              AssignmentRow row =
                  new AssignmentRow(
                      "ASG-" + UUID.randomUUID(),
                      mfcId,
                      t.taskId(),
                      t.taskType(),
                      workerId,
                      store.maxVersion(t.taskId()) + 1,
                      "ACTIVE",
                      null,
                      SYSTEM,
                      "NEXT_TASK",
                      at,
                      null);
              store.insert(row);
              publish(row, SYSTEM);
              return row;
            });
  }

  /**
   * 작업 내놓기(Q-09, fulfillment 가 잔량 확인 뒤 부름): 그 작업의 ACTIVE 배정을 RELEASED. 담당이 아니면 409 NOT_ASSIGNEE, 활성
   * 배정이 없으면 false. 사건 없음(작업 상태 정본은 fulfillment).
   */
  @Transactional
  public boolean releaseTask(String taskId, String workerId) {
    var active = store.lockActive(taskId);
    if (active.isEmpty()) {
      return false;
    }
    if (!active.get().workerId().equals(workerId)) {
      throw new PmartException(
          "NOT_ASSIGNEE",
          org.springframework.http.HttpStatus.CONFLICT,
          "지금 담당(" + active.get().workerId() + ")이 아닙니다.",
          false,
          List.of());
    }
    store.release(active.get().assignmentId(), clock.instant());
    log.info("작업 {} 배정 해제(작업 내놓기) — {}", taskId, workerId);
    return true;
  }

  /**
   * 다음 작업 순서(작을수록 먼저): 주문 작업 → 자동 보충·긴급 이동 → (0.25.0) 입고 → 품질검사 → 적치 → 센터 관리자 이동 → 복귀(사용자 결정
   * 2026-11-08, 품질검사·적치 자리는 2026-11-14 — Q-08 전 임시) → (0.24.0) 회수품 반입 → 회수품 검수 → 재고 실사. 입고는 입고
   * 흐름(수령 → 품질검사 → 적치, BR-52 R2) 앞이라 품질검사 앞 — 업무 INBOUND 는 RECEIVE 하나라 업무를 안 골랐을 때만 의미가 있다(임시). 새
   * 셋은 같은 업무의 기존 유형 뒤(업무는 작업자가 고른다) — 사용자 순서 결정 전 임시.
   */
  static int rank(String taskType) {
    return switch (taskType) {
      case "PICK", "PACK" -> 0;
      case "REPLENISH" -> 1;
      case RECEIVE -> 2;
      case "INSPECT" -> 3;
      case "PUTAWAY" -> 4;
      case "MOVE" -> 5;
      case "RETURN" -> 6;
      case RETURN_RECEIPT -> 7;
      case RETURN_INSPECT -> 8;
      case CYCLE_COUNT -> 9;
      default -> 10;
    };
  }

  private static void requireSelf(String mfcId, String workerId) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "작업자(운영자)만 할 수 있습니다."));
    if (!p.canAccessCenter(mfcId) || !p.subject().equals(workerId)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "작업자 본인만 다음 작업을 받습니다.");
    }
  }

  public Assigned assign(String requestId, String sourceSystem, AssignCommand cmd) {
    String supervisor = requireSupervisor(cmd.mfcId());
    if (!TASK_TYPES.contains(cmd.taskType())) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "알 수 없는 작업 유형: " + cmd.taskType());
    }
    if (cmd.workerId().isBlank()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "workerId 가 비었습니다.");
    }
    // WK-02 강제 제약: 등록·ACTIVE·업무(작업 유형)·온도대 자격. 근무 밖은 경고만(Q-09 미결)
    String reject =
        workers.rejection(cmd.mfcId(), cmd.workerId(), cmd.taskType(), cmd.temperatureZone());
    if (reject != null) {
      throw new PmartException(
          reject,
          org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT,
          "작업자 " + cmd.workerId() + " 에게 이 작업을 배정할 수 없습니다: " + reject,
          false,
          List.of());
    }
    List<String> warnings;
    try {
      warnings =
          workers.offShift(cmd.mfcId(), cmd.workerId(), clock.instant())
              ? List.of(com.pmart.mfc.capacity.domain.WorkerRules.OFF_SHIFT)
              : List.of();
    } catch (PmartException ex) {
      // 수동 배정은 근무 밖이어도 막지 않는다(경고만) — 직원 시간표를 못 읽으면 경고 없이(Q-09)
      log.warn("근무 판단 못 함({}) — 경고 없이 배정: {}", cmd.workerId(), ex.getMessage());
      warnings = List.of();
    }
    try {
      return assignOnce(requestId, sourceSystem, cmd, supervisor, warnings);
    } catch (org.springframework.dao.DuplicateKeyException race) {
      // 같은 작업에 동시에 다른 배정(다음 작업 받기·다른 센터 관리자)이 먼저 들어갔다 — 500 이 아니라 버전 충돌로
      throw PmartException.versionConflict("같은 작업에 동시에 다른 배정이 들어갔습니다 — 다시 조회한 뒤 배정하세요.");
    }
  }

  private Assigned assignOnce(
      String requestId,
      String sourceSystem,
      AssignCommand cmd,
      String supervisor,
      List<String> warnings) {
    return idempotency
        .execute(
            new RequestKey(sourceSystem, requestId, "assignTask", supervisor),
            cmd,
            Assigned.class,
            () -> {
              Optional<AssignmentRow> active = store.lockActive(cmd.taskId());
              int current = active.map(AssignmentRow::assignmentVersion).orElse(0);
              if (cmd.expectedVersion() != null && cmd.expectedVersion() != current) {
                throw PmartException.versionConflict(
                    "배정이 이미 바뀌었습니다(기대 " + cmd.expectedVersion() + ", 현재 " + current + ").");
              }
              if (active.isPresent()) {
                AssignmentRow a = active.get();
                if (!a.mfcId().equals(cmd.mfcId())) {
                  throw new PmartException(ErrorCode.FORBIDDEN, "다른 센터의 작업입니다.");
                }
                if (a.workerId().equals(cmd.workerId())) {
                  return new Assigned(a, false, warnings);
                }
              }
              Instant at = clock.instant();
              active.ifPresent(a -> store.supersede(a.assignmentId(), at));
              AssignmentRow row =
                  new AssignmentRow(
                      "ASG-" + UUID.randomUUID(),
                      cmd.mfcId(),
                      cmd.taskId(),
                      cmd.taskType(),
                      cmd.workerId(),
                      store.maxVersion(cmd.taskId()) + 1,
                      "ACTIVE",
                      null,
                      supervisor,
                      cmd.reason(),
                      at,
                      cmd.temperatureZone());
              store.insert(row);
              publish(row, supervisor);
              log.info(
                  "task {} assigned to {} (v{}) at {} by {}",
                  row.taskId(),
                  row.workerId(),
                  row.assignmentVersion(),
                  row.mfcId(),
                  supervisor);
              return new Assigned(row, true, warnings);
            })
        .value();
  }

  /**
   * 배정 추천 확정(WK-10)의 한 항목. 호출자가 트랜잭션 안에서 그 작업에 활성 배정이 없음을 잠가 확인한 뒤 부른다. 사건 집계 키는 수동과 같은 {@code
   * MANUAL:<taskId>}(작업마다 assignmentVersion 순서를 한 파티션에서 지키려고), payload 에 planId·source PLAN.
   */
  AssignmentRow insertFromPlan(
      String mfcId,
      String taskId,
      String taskType,
      String workerId,
      String planId,
      String by,
      Instant at) {
    AssignmentRow row =
        new AssignmentRow(
            "ASG-" + UUID.randomUUID(),
            mfcId,
            taskId,
            taskType,
            workerId,
            store.maxVersion(taskId) + 1,
            "ACTIVE",
            planId,
            by,
            "PLAN " + planId,
            at,
            null);
    store.insert(row);
    publish(row, by);
    return row;
  }

  /** 배정 작업자가 지금도 적격인가(null = 적격). 조회 때 계산 — 배정 뒤 정지·업무 변경을 시작 판정에 쓴다. */
  public @Nullable String eligibility(AssignmentRow r) {
    return workers.rejection(r.mfcId(), r.workerId(), r.taskType(), r.temperatureZone());
  }

  @Transactional(readOnly = true)
  public List<AssignmentRow> list(
      String mfcId,
      @Nullable String taskId,
      @Nullable String workerId,
      @Nullable String state,
      @Nullable LocalDate date) {
    requireReader(mfcId);
    String s = state == null ? "ACTIVE" : state;
    if (!STATES.contains(s)) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "state 는 ACTIVE/SUPERSEDED/RELEASED.");
    }
    return store.list(mfcId, taskId, workerId, s, date);
  }

  private void publish(AssignmentRow r, String supervisor) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("taskId", r.taskId());
    item.put("workerId", r.workerId());
    item.put("assignmentVersion", r.assignmentVersion());
    item.put("taskType", r.taskType());
    item.put("assignmentId", r.assignmentId());
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("planVersion", r.assignmentVersion());
    payload.put("mfcId", r.mfcId());
    payload.put("assignments", List.of(item));
    if (r.planId() != null) {
      payload.put("planId", r.planId());
    }
    payload.put(
        "source",
        r.planId() != null ? "PLAN" : SYSTEM.equals(r.assignedBy()) ? "SYSTEM" : "MANUAL");
    payload.put("publishedAt", r.assignedAt().toString());
    events.publish(
        codec.create(
            events.sourceSystem(),
            "AssignmentPublished",
            1,
            "AssignmentPlan",
            "MANUAL:" + r.taskId(),
            r.assignmentVersion(),
            new EventEnvelope.Actor(EventEnvelope.ActorType.OPERATOR, supervisor),
            EventEnvelope.EventSource.REAL,
            payload),
        TOPIC);
  }

  static String requireSupervisor(String mfcId) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "운영자만 할 수 있습니다."));
    if (!p.canAccessCenter(mfcId) || !p.hasRole(SUPERVISOR_ROLE)) {
      throw new PmartException(
          ErrorCode.FORBIDDEN, "이 센터의 " + SUPERVISOR_ROLE + " 만 작업을 배정할 수 있습니다.");
    }
    return p.subject();
  }

  /**
   * 작업자 정보 조회(목록·상세·근무 일정 — 사용자 결정 2026-11-20 "센터 관리자만 전체, 작업자는 자기 것만", Q-16 최소 권한). 그 센터 운영자 중
   * <b>작업자 역할(MFC_WORKER)만</b> 가진 토큰은 본인({@code workerId} = 토큰 주체)만 — 목록({@code workerId} null)은
   * 403. 센터 관리자·본사 역할·서비스 토큰은 전처럼.
   */
  static void requireWorkerReader(String mfcId, @Nullable String workerId) {
    requireReader(mfcId);
    PmartPrincipal p = PmartPrincipal.current().orElseThrow();
    if (p.isService()) {
      return;
    }
    boolean crewOnly = p.roles().stream().allMatch(WORKER_ROLE::equals);
    if (crewOnly && (workerId == null || !workerId.equals(p.subject()))) {
      throw new PmartException(ErrorCode.FORBIDDEN, "작업자는 본인 정보만 볼 수 있습니다 — 센터 작업자 목록은 센터 관리자만.");
    }
  }

  static void requireReader(String mfcId) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "토큰이 필요합니다."));
    if (p.isService()) {
      return;
    }
    if (p.type() != PmartPrincipal.PrincipalType.OPERATOR || !p.canAccessCenter(mfcId)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "이 센터에 대한 권한이 없습니다.");
    }
  }
}
