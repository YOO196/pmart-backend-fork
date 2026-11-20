package com.pmart.mfc.capacity.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.capacity.domain.WorkerRules;
import com.pmart.mfc.capacity.infrastructure.AssignmentStore;
import com.pmart.mfc.capacity.infrastructure.AssignmentStore.AssignmentRow;
import com.pmart.mfc.capacity.infrastructure.QualificationStore;
import com.pmart.mfc.capacity.infrastructure.WorkerStore;
import com.pmart.mfc.capacity.infrastructure.WorkerStore.DutyRow;
import com.pmart.mfc.capacity.infrastructure.WorkerStore.ShiftRow;
import com.pmart.mfc.capacity.infrastructure.WorkerStore.StateChangeRow;
import com.pmart.mfc.capacity.infrastructure.WorkerStore.WorkerRow;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 작업자·업무·근무(WK-02 입력, V5). 사용자 결정(2026-11-08): 센터 관리자가 등록하고, 업무가 바뀌어도 로그인 정보는 그대로 — workerId(운영자
 * ID)는 바꾸지 않는 열쇠이고 업무(작업 유형·온도대 자격)는 버전마다 따로 남는다. 상태(ACTIVE·SUSPENDED·LEFT)는 새 배정만 막고 로그인·센터
 * 권한(auth)은 건드리지 않는다. 업무·상태가 바뀌어도 기존 배정은 지우지 않고 맞지 않는 것을 알려 준다.
 */
@Service
public class WorkerService {

  public static final String WORKER_ALREADY_REGISTERED = "WORKER_ALREADY_REGISTERED";
  public static final String WORKER_NOT_FOUND = "WORKER_NOT_FOUND";
  public static final String SHIFT_NOT_FOUND = "SHIFT_NOT_FOUND";
  private static final Set<String> STATES = Set.of("ACTIVE", "SUSPENDED", "LEFT");
  private static final Logger log = LoggerFactory.getLogger(WorkerService.class);

  /**
   * @param effective 지금 배정 조건 = 업무 ∩ 유효 자격(일일크루는 오늘 등록한 날의 업무 ∩ 자격)
   * @param today 일일크루의 오늘 등록(없으면 null)
   */
  public record View(
      WorkerRow worker,
      DutyRow duty,
      String employmentType,
      QualificationStore.@Nullable Qualification qualification,
      Set<String> effective,
      QualificationStore.@Nullable Day today,
      boolean onBreak) {

    /** (0.13.0) 지금 고를 수 있는 PDA 업무 — {@link com.pmart.mfc.capacity.domain.WorkAreas#allowed}. */
    public List<String> workAreas() {
      return com.pmart.mfc.capacity.domain.WorkAreas.allowed(effective);
    }
  }

  public record Detail(View view, List<DutyRow> duties, List<StateChangeRow> states) {}

  public record ChangeResult(View view, boolean changed, List<AssignmentRow> affected) {}

  private final WorkerStore store;
  private QualificationStore quals;

  @org.springframework.beans.factory.annotation.Autowired
  void setQuals(QualificationStore quals) {
    this.quals = quals;
  }

  private @Nullable StaffHours staffHours;

  @org.springframework.beans.factory.annotation.Autowired
  void setStaffHours(StaffHours staffHours) {
    this.staffHours = staffHours;
  }

  private static final java.time.ZoneId SEOUL = java.time.ZoneId.of("Asia/Seoul");

  private final AssignmentStore assignments;
  private final RequestIdempotency idempotency;
  private final Clock clock;

  public WorkerService(
      WorkerStore store, AssignmentStore assignments, RequestIdempotency idempotency, Clock clock) {
    this.store = store;
    this.assignments = assignments;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  // ---------- 배정 판정 ----------

  /** WK-02 거절 코드, 적격이면 null. */
  public @Nullable String rejection(
      String mfcId, String workerId, String taskType, @Nullable String temperatureZone) {
    var p = profile(mfcId, workerId);
    if (p != null
        && "ACTIVE".equals(p.state())
        && "DAILY".equals(quals.employmentType(mfcId, workerId))
        && quals.day(mfcId, workerId, today()).isEmpty()) {
      return WorkerRules.DAILY_NOT_ENROLLED; // 일일크루는 그날 근무 등록한 날만(사용자 결정)
    }
    return WorkerRules.rejection(p, taskType, temperatureZone);
  }

  private java.time.LocalDate today() {
    return java.time.LocalDate.ofInstant(clock.instant(), SEOUL);
  }

  /** 지금 배정 조건 = 업무 ∩ 유효 자격. 일일크루는 오늘 등록한 날의 업무(시스템이 정한 것) ∩ 자격. */
  private Set<String> effective(String mfcId, String workerId, DutyRow d) {
    Instant now = clock.instant();
    var q = quals.current(mfcId, workerId);
    Set<String> qual =
        q.isEmpty() ? d.taskTypes() : q.get().valid(now) ? q.get().taskTypes() : Set.<String>of();
    Set<String> base;
    if ("DAILY".equals(quals.employmentType(mfcId, workerId))) {
      base =
          quals
              .day(mfcId, workerId, today())
              .map(QualificationStore.Day::dutyTaskTypes)
              .orElse(Set.of());
    } else {
      base = d.taskTypes();
    }
    Set<String> out = new TreeSet<>(base);
    out.retainAll(qual);
    return out;
  }

  private View build(WorkerRow w) {
    DutyRow d = store.duty(w.mfcId(), w.workerId(), w.dutyVersion());
    return new View(
        w,
        d,
        quals.employmentType(w.mfcId(), w.workerId()),
        quals.current(w.mfcId(), w.workerId()).orElse(null),
        effective(w.mfcId(), w.workerId(), d),
        quals.day(w.mfcId(), w.workerId(), today()).orElse(null),
        breaks.open(w.mfcId(), w.workerId()).isPresent());
  }

  private com.pmart.mfc.capacity.infrastructure.BreakStore breaks;

  @org.springframework.beans.factory.annotation.Autowired
  void setBreaks(com.pmart.mfc.capacity.infrastructure.BreakStore breaks) {
    this.breaks = breaks;
  }

  public boolean onBreak(String mfcId, String workerId) {
    return breaks.open(mfcId, workerId).isPresent();
  }

  /** 휴게 켜기 판정(멱등 기록 — 거절도 같은 키면 같은 판정). 번호는 담지 않는다. */
  public record BreakVerdict(
      @Nullable String breakId,
      @Nullable String reject,
      @Nullable Integer remainingAttempts,
      @Nullable String lockedUntil) {}

  /**
   * 휴게 시작(본인 또는 센터 관리자) — 이미 휴게 중이면 그 휴게. (0.20.0, 사용자 결정 2026-11-18) 작업자 본인이면 센터 휴게 번호 필수 — 없으면
   * 400, 미설정·틀림·잠금은 422(틀린 횟수는 그 작업자마다, 5번이면 15분).
   */
  public com.pmart.mfc.capacity.infrastructure.BreakStore.Break startBreak(
      String requestId,
      String sourceSystem,
      String mfcId,
      String workerId,
      @Nullable String supervisorPin) {
    var p = selfOrSupervisorPrincipal(mfcId, workerId);
    String me = p.subject();
    boolean needPin = !p.hasRole(AssignmentService.SUPERVISOR_ROLE);
    if (needPin && !com.pmart.mfc.capacity.domain.BreakPins.wellFormed(supervisorPin)) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "센터 관리자 휴게 번호 4자리가 필요합니다.");
    }
    store
        .find(mfcId, workerId)
        .orElseThrow(() -> PmartException.notFound(WORKER_NOT_FOUND, "작업자가 없습니다: " + workerId));
    BreakVerdict v =
        idempotency
            .execute(
                new RequestKey(sourceSystem, requestId, "startWorkerBreak", me),
                List.of(mfcId, workerId, needPin ? "PIN" : "-"),
                BreakVerdict.class,
                () -> {
                  Instant now = clock.instant();
                  if (needPin) {
                    BreakVerdict rejected =
                        checkPin(
                            mfcId, workerId, java.util.Objects.requireNonNull(supervisorPin), now);
                    if (rejected != null) {
                      return rejected;
                    }
                  }
                  var b = breaks.start(mfcId, workerId, me, now);
                  log.info("작업자 {} 휴게 시작 by {}{}", workerId, me, needPin ? " (휴게 번호 확인)" : "");
                  return new BreakVerdict(b.breakId(), null, null, null);
                })
            .value();
    if (v.reject() != null) {
      List<PmartException.Detail> details = new ArrayList<>();
      if (v.remainingAttempts() != null) {
        details.add(
            new PmartException.Detail("remainingAttempts", v.remainingAttempts().toString()));
      }
      if (v.lockedUntil() != null) {
        details.add(new PmartException.Detail("lockedUntil", v.lockedUntil()));
      }
      throw new PmartException(
          v.reject(), HttpStatus.UNPROCESSABLE_CONTENT, pinMessage(v.reject()), false, details);
    }
    return breaks
        .open(mfcId, workerId)
        .or(() -> breaks.last(mfcId, workerId))
        .orElseThrow(() -> PmartException.notFound("BREAK_NOT_FOUND", "휴게 기록이 없습니다: " + workerId));
  }

  /** 번호 판정(트랜잭션 안) — 맞으면 null, 아니면 거절 판정(틀린 횟수·잠금 갱신). */
  private @Nullable BreakVerdict checkPin(String mfcId, String workerId, String pin, Instant now) {
    var stored = breaks.pin(mfcId);
    if (stored.isEmpty()) {
      return new BreakVerdict(null, com.pmart.mfc.capacity.domain.BreakPins.NOT_SET, null, null);
    }
    var f = breaks.lockFailure(mfcId, workerId);
    if (f.lockedUntil() != null && f.lockedUntil().isAfter(now)) {
      return new BreakVerdict(
          null, com.pmart.mfc.capacity.domain.BreakPins.LOCKED, 0, f.lockedUntil().toString());
    }
    int before = f.lockedUntil() != null ? 0 : f.failedCount(); // 잠금이 풀렸으면 새로 센다
    if (com.pmart.mfc.capacity.domain.BreakPins.matches(pin, stored.get().pinHash())) {
      breaks.clearFailure(mfcId, workerId);
      return null;
    }
    int failed = before + 1;
    if (failed >= com.pmart.mfc.capacity.domain.BreakPins.MAX_FAILURES) {
      Instant until = now.plus(com.pmart.mfc.capacity.domain.BreakPins.LOCK);
      breaks.saveFailure(mfcId, workerId, 0, until, now);
      log.warn("작업자 {} 휴게 번호 {}번 틀림 — {}까지 잠금", workerId, failed, until);
      return new BreakVerdict(
          null, com.pmart.mfc.capacity.domain.BreakPins.LOCKED, 0, until.toString());
    }
    breaks.saveFailure(mfcId, workerId, failed, null, now);
    log.info("작업자 {} 휴게 번호 틀림 {}번째", workerId, failed);
    return new BreakVerdict(
        null,
        com.pmart.mfc.capacity.domain.BreakPins.INVALID,
        com.pmart.mfc.capacity.domain.BreakPins.MAX_FAILURES - failed,
        null);
  }

  private static String pinMessage(String code) {
    return switch (code) {
      case com.pmart.mfc.capacity.domain.BreakPins.NOT_SET ->
          "센터 휴게 번호가 아직 정해지지 않았습니다 — 센터 관리자에게 요청하세요.";
      case com.pmart.mfc.capacity.domain.BreakPins.LOCKED ->
          "휴게 번호를 여러 번 틀려 잠시 막혔습니다 — 잠시 뒤 또는 센터 관리자에게.";
      default -> "휴게 번호가 맞지 않습니다.";
    };
  }

  /** 센터 휴게 번호 상태(그 센터 운영자) — 번호는 돌려주지 않는다. */
  @Transactional(readOnly = true)
  public java.util.Optional<com.pmart.mfc.capacity.infrastructure.BreakStore.Pin> breakPin(
      String mfcId) {
    AssignmentService.requireReader(mfcId);
    return breaks.pin(mfcId);
  }

  /** 센터 휴게 번호 정하기(그 센터 MFC_SUPERVISOR) — 그 센터 틀린 횟수·잠금을 지운다. */
  public com.pmart.mfc.capacity.infrastructure.BreakStore.Pin setBreakPin(
      String requestId, String sourceSystem, String mfcId, @Nullable String pin) {
    String me = AssignmentService.requireSupervisor(mfcId);
    if (!com.pmart.mfc.capacity.domain.BreakPins.wellFormed(pin)) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "휴게 번호는 숫자 4자리입니다.");
    }
    String hash =
        com.pmart.mfc.capacity.domain.BreakPins.hash(java.util.Objects.requireNonNull(pin));
    idempotency.execute(
        new RequestKey(sourceSystem, requestId, "setBreakPin", me),
        List.of(mfcId), // 번호는 요청 해시에도 넣지 않는다
        Boolean.class,
        () -> {
          breaks.setPin(mfcId, hash, me, clock.instant());
          log.info("센터 {} 휴게 번호 변경 by {}", mfcId, me);
          return true;
        });
    return breaks.pin(mfcId).orElseThrow();
  }

  /**
   * 자동 휴게(0.20.0, 사용자 결정 2026-11-18): 근무 휴게 구간이 되면 열고 끝 시각이 지나면 닫는다. 들고 있던 작업은 계속(다음 작업 받기만
   * ON_BREAK), 일찍 끝내기는 센터 관리자만. 조건부 SQL 이라 여러 인스턴스가 같이 돌아도 한 번만.
   */
  @org.springframework.scheduling.annotation.Scheduled(
      fixedDelayString = "${pmart.capacity.auto-break.sweep-interval:PT30S}",
      initialDelayString = "PT25S")
  @Transactional
  public void sweepAutoBreaks() {
    Instant now = clock.instant();
    int ended = breaks.endDueAutoBreaks(now);
    int started = breaks.startDueAutoBreaks(now);
    if (ended > 0 || started > 0) {
      log.info("자동 휴게 시작 {}건·끝 {}건", started, ended);
    }
  }

  public com.pmart.mfc.capacity.infrastructure.BreakStore.Break endBreak(
      String mfcId, String workerId) {
    var p = selfOrSupervisorPrincipal(mfcId, workerId);
    String me = p.subject();
    var open = breaks.open(mfcId, workerId);
    if (open.isPresent()) {
      if (com.pmart.mfc.capacity.infrastructure.BreakStore.AUTO.equals(open.get().source())
          && !p.hasRole(AssignmentService.SUPERVISOR_ROLE)) {
        throw new PmartException(
            com.pmart.mfc.capacity.domain.BreakPins.AUTO_BREAK_SUPERVISOR_ONLY,
            HttpStatus.CONFLICT,
            "정해진 휴게 시간입니다 — 일찍 끝내려면 센터 관리자에게.",
            false,
            List.of());
      }
      breaks.end(open.get().breakId(), me, clock.instant());
      log.info("작업자 {} 휴게 끝 by {}", workerId, me);
    }
    return breaks
        .last(mfcId, workerId)
        .orElseThrow(() -> PmartException.notFound("BREAK_NOT_FOUND", "휴게 기록이 없습니다: " + workerId));
  }

  private static com.pmart.common.security.PmartPrincipal selfOrSupervisorPrincipal(
      String mfcId, String workerId) {
    var p =
        com.pmart.common.security.PmartPrincipal.current()
            .filter(
                x -> x.type() == com.pmart.common.security.PmartPrincipal.PrincipalType.OPERATOR)
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "운영자만 할 수 있습니다."));
    if (!p.canAccessCenter(mfcId)
        || !(p.subject().equals(workerId) || p.hasRole(AssignmentService.SUPERVISOR_ROLE))) {
      throw new PmartException(ErrorCode.FORBIDDEN, "그 센터 관리자 또는 본인만 할 수 있습니다.");
    }
    return p;
  }

  /** 지금 업무의 작업 유형(등록 안 됨이면 빈 집합). */
  public java.util.Set<String> taskTypes(String mfcId, String workerId) {
    var p = profile(mfcId, workerId);
    return p == null ? java.util.Set.of() : p.duty().taskTypes();
  }

  /**
   * 근무 밖인가. 앞뒤 하루에 등록한 근무가 있으면 그 근무로, 없으면 센터 직원 근무 시간표(STAFF)로 판단한다(사용자 결정 2026-11-09 — 근무를 따로 등록하지
   * 않은 작업자는 풀타임 = 센터 운영시간). 시간표가 없으면 CONFIG_REQUIRED.
   */
  public boolean offShift(String mfcId, String workerId, Instant now) {
    List<Instant[]> s =
        store.shifts(mfcId, workerId, now.minusSeconds(86_400), now.plusSeconds(86_400)).stream()
            .map(x -> new Instant[] {x.startAt(), x.endAt()})
            .toList();
    StaffHours staff = staffHours;
    if (s.isEmpty() && staff != null) {
      return staff.outside(mfcId, now);
    }
    return WorkerRules.offShift(s, now);
  }

  private WorkerRules.@Nullable Profile profile(String mfcId, String workerId) {
    return store
        .find(mfcId, workerId)
        .map(
            w -> {
              DutyRow d = store.duty(mfcId, workerId, w.dutyVersion());
              return new WorkerRules.Profile(
                  w.state(),
                  new WorkerRules.Duty(d.dutyVersion(), effective(mfcId, workerId, d), d.zones()));
            })
        .orElse(null);
  }

  // ---------- 등록·변경 ----------

  public View register(
      String requestId,
      String sourceSystem,
      String mfcId,
      String workerId,
      String displayName,
      Set<String> taskTypes,
      Set<String> zones,
      @Nullable String employmentType,
      @Nullable Set<String> qualificationTypes,
      @Nullable Instant qualificationExpiresAt) {
    String me = AssignmentService.requireSupervisor(mfcId);
    checkDuty(taskTypes, zones);
    String type = employmentType == null ? "REGULAR" : employmentType;
    if (!WorkerRules.EMPLOYMENT_TYPES.contains(type)) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED, "고용 형태는 " + WorkerRules.EMPLOYMENT_TYPES + " 입니다.");
    }
    Set<String> qual =
        qualificationTypes != null && !qualificationTypes.isEmpty()
            ? qualificationTypes
            : "DAILY".equals(type) ? WorkerRules.DAILY_DEFAULT_QUALIFICATION : taskTypes;
    checkDuty(qual, Set.of());
    if (!"DAILY".equals(type) && !qual.containsAll(taskTypes)) {
      throw outsideQualification(taskTypes, qual);
    }
    if (workerId.isBlank() || displayName.isBlank()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "workerId·displayName 이 비었습니다.");
    }
    idempotency
        .execute(
            new RequestKey(sourceSystem, requestId, "registerWorker", me),
            List.of(mfcId, workerId, displayName, new TreeSet<>(taskTypes), new TreeSet<>(zones)),
            String.class,
            () -> {
              Instant at = clock.instant();
              if (!store.insert(mfcId, workerId, displayName, me, at)) {
                throw new PmartException(
                    WORKER_ALREADY_REGISTERED,
                    HttpStatus.CONFLICT,
                    workerId
                        + " 는 이미 "
                        + mfcId
                        + " 에 등록돼 있습니다 — 업무는 duty-changes, 복귀는 state-changes.",
                    false,
                    List.of());
              }
              store.insertDuty(mfcId, workerId, 1, taskTypes, zones, "등록", me, at);
              quals.setEmploymentType(mfcId, workerId, type);
              quals.insert(mfcId, workerId, 1, qual, qualificationExpiresAt, "등록", me, at);
              store.setState(mfcId, workerId, null, "ACTIVE", "등록", me, at);
              log.info("작업자 {} 등록 {} by {} (업무 {} {})", workerId, mfcId, me, taskTypes, zones);
              return workerId;
            })
        .value();
    return view(mfcId, workerId);
  }

  public ChangeResult changeDuty(
      String requestId,
      String sourceSystem,
      String mfcId,
      String workerId,
      @Nullable Integer expectedVersion,
      Set<String> taskTypes,
      Set<String> zones,
      String reason) {
    String me = AssignmentService.requireSupervisor(mfcId);
    checkDuty(taskTypes, zones);
    var q = quals.current(mfcId, workerId);
    if (q.isPresent() && !"DAILY".equals(quals.employmentType(mfcId, workerId))) {
      Set<String> qual = q.get().valid(clock.instant()) ? q.get().taskTypes() : Set.of();
      if (!qual.containsAll(taskTypes)) {
        throw outsideQualification(taskTypes, qual);
      }
    }
    boolean changed =
        idempotency
            .execute(
                new RequestKey(sourceSystem, requestId, "changeWorkerDuty", me),
                List.of(
                    mfcId,
                    workerId,
                    new TreeSet<>(taskTypes),
                    new TreeSet<>(zones),
                    reason,
                    String.valueOf(expectedVersion)),
                Boolean.class,
                () -> {
                  WorkerRow w = lockOrThrow(mfcId, workerId);
                  if (expectedVersion != null && expectedVersion != w.dutyVersion()) {
                    throw PmartException.versionConflict(
                        "업무가 이미 바뀌었습니다(기대 " + expectedVersion + ", 현재 " + w.dutyVersion() + ").");
                  }
                  DutyRow cur = store.duty(mfcId, workerId, w.dutyVersion());
                  if (cur.taskTypes().equals(taskTypes) && cur.zones().equals(zones)) {
                    return false;
                  }
                  store.insertDuty(
                      mfcId,
                      workerId,
                      w.dutyVersion() + 1,
                      taskTypes,
                      zones,
                      reason,
                      me,
                      clock.instant());
                  log.info(
                      "작업자 {} 업무 v{} {} {} by {}",
                      workerId,
                      w.dutyVersion() + 1,
                      taskTypes,
                      zones,
                      me);
                  return true;
                })
            .value();
    return new ChangeResult(view(mfcId, workerId), changed, affected(mfcId, workerId));
  }

  public ChangeResult changeState(
      String requestId,
      String sourceSystem,
      String mfcId,
      String workerId,
      String state,
      String reason) {
    String me = AssignmentService.requireSupervisor(mfcId);
    if (!STATES.contains(state)) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "상태는 ACTIVE·SUSPENDED·LEFT.");
    }
    boolean changed =
        idempotency
            .execute(
                new RequestKey(sourceSystem, requestId, "changeWorkerState", me),
                List.of(mfcId, workerId, state, reason),
                Boolean.class,
                () -> {
                  WorkerRow w = lockOrThrow(mfcId, workerId);
                  if (w.state().equals(state)) {
                    return false;
                  }
                  store.setState(mfcId, workerId, w.state(), state, reason, me, clock.instant());
                  log.info("작업자 {} {} → {} by {} ({})", workerId, w.state(), state, me, reason);
                  return true;
                })
            .value();
    return new ChangeResult(view(mfcId, workerId), changed, affected(mfcId, workerId));
  }

  /** 속도 배율 변경(0.11.0, 사용자 결정 2026-11-09) — 배정 추천의 작업 시간 = 추정 분 ÷ 배율. 0.5~2.0. */
  public View changeSpeed(
      String requestId,
      String sourceSystem,
      String mfcId,
      String workerId,
      double factor,
      String reason) {
    String me = AssignmentService.requireSupervisor(mfcId);
    if (factor < 0.5 || factor > 2.0) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "속도 배율은 0.5~2.0 입니다.");
    }
    idempotency.execute(
        new RequestKey(sourceSystem, requestId, "changeWorkerSpeed", me),
        List.of(mfcId, workerId, String.valueOf(factor), reason),
        Boolean.class,
        () -> {
          WorkerRow w = lockOrThrow(mfcId, workerId);
          if (Math.abs(w.speedFactor() - factor) < 0.005) {
            return false;
          }
          store.setSpeed(mfcId, workerId, factor);
          log.info(
              "작업자 {} 속도 배율 {} → {} by {} ({})", workerId, w.speedFactor(), factor, me, reason);
          return true;
        });
    return view(mfcId, workerId);
  }

  /** 이 작업자의 ACTIVE 배정 중 지금 업무·상태로는 받을 수 없는 것. */
  private List<AssignmentRow> affected(String mfcId, String workerId) {
    WorkerRules.Profile p = profile(mfcId, workerId);
    return assignments.list(mfcId, null, workerId, "ACTIVE", null).stream()
        .filter(a -> WorkerRules.rejection(p, a.taskType(), a.temperatureZone()) != null)
        .toList();
  }

  // ---------- 조회 ----------

  @Transactional(readOnly = true)
  public List<View> list(String mfcId, @Nullable String state) {
    AssignmentService.requireWorkerReader(mfcId, null);
    return store.list(mfcId, state).stream().map(this::build).toList();
  }

  @Transactional(readOnly = true)
  public Detail detail(String mfcId, String workerId) {
    AssignmentService.requireWorkerReader(mfcId, workerId);
    return new Detail(
        view(mfcId, workerId), store.duties(mfcId, workerId), store.stateChanges(mfcId, workerId));
  }

  private View view(String mfcId, String workerId) {
    WorkerRow w =
        store
            .find(mfcId, workerId)
            .orElseThrow(() -> PmartException.notFound(WORKER_NOT_FOUND, "작업자가 없습니다: " + workerId));
    return build(w);
  }

  // ---------- 자격·일일크루(작업자 쪽 2단계) ----------

  private static PmartException outsideQualification(Set<String> duty, Set<String> qual) {
    return new PmartException(
        WorkerRules.DUTY_OUTSIDE_QUALIFICATION,
        HttpStatus.UNPROCESSABLE_CONTENT,
        "업무 " + duty + " 는 자격 " + qual + " 안이어야 합니다.",
        false,
        List.of());
  }

  /** 자격 변경(센터 관리자) — 새 버전. 자격에서 빠진 업무 유형은 배정 조건에서 빠진다(업무 행은 그대로). */
  public ChangeResult changeQualification(
      String requestId,
      String sourceSystem,
      String mfcId,
      String workerId,
      @Nullable Integer expectedVersion,
      Set<String> taskTypes,
      @Nullable Instant expiresAt,
      @Nullable String reason) {
    String me = AssignmentService.requireSupervisor(mfcId);
    checkDuty(taskTypes, Set.of());
    boolean changed =
        idempotency
            .execute(
                new RequestKey(sourceSystem, requestId, "changeWorkerQualification", me),
                List.of(
                    mfcId,
                    workerId,
                    new TreeSet<>(taskTypes),
                    String.valueOf(expiresAt),
                    String.valueOf(expectedVersion)),
                Boolean.class,
                () -> {
                  lockOrThrow(mfcId, workerId);
                  var cur = quals.current(mfcId, workerId).orElse(null);
                  int v = cur == null ? 0 : cur.version();
                  if (expectedVersion != null && expectedVersion != v) {
                    throw PmartException.versionConflict(
                        "자격이 이미 바뀌었습니다(기대 " + expectedVersion + ", 현재 " + v + ").");
                  }
                  if (cur != null
                      && cur.taskTypes().equals(taskTypes)
                      && java.util.Objects.equals(cur.expiresAt(), expiresAt)) {
                    return false;
                  }
                  quals.insert(
                      mfcId, workerId, v + 1, taskTypes, expiresAt, reason, me, clock.instant());
                  log.info(
                      "작업자 {} 자격 v{} {} (만료 {}) by {}", workerId, v + 1, taskTypes, expiresAt, me);
                  return true;
                })
            .value();
    return new ChangeResult(view(mfcId, workerId), changed, affected(mfcId, workerId));
  }

  /** 일일크루 그날 근무 등록(센터 관리자 또는 본인). 그날 업무 = 유효 자격(시스템이 정함 — 수요 맞춤 세분은 나중). */
  public QualificationStore.Day enrollDay(
      String requestId,
      String sourceSystem,
      String mfcId,
      String workerId,
      java.time.@Nullable LocalDate date) {
    var p =
        com.pmart.common.security.PmartPrincipal.current()
            .filter(
                x -> x.type() == com.pmart.common.security.PmartPrincipal.PrincipalType.OPERATOR)
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "운영자만 할 수 있습니다."));
    if (!p.canAccessCenter(mfcId)
        || !(p.subject().equals(workerId) || p.hasRole(AssignmentService.SUPERVISOR_ROLE))) {
      throw new PmartException(ErrorCode.FORBIDDEN, "그 센터 관리자 또는 본인만 근무를 등록합니다.");
    }
    WorkerRow w =
        store
            .find(mfcId, workerId)
            .orElseThrow(() -> PmartException.notFound(WORKER_NOT_FOUND, "작업자가 없습니다: " + workerId));
    if (!"DAILY".equals(quals.employmentType(mfcId, workerId))) {
      throw new PmartException(
          WorkerRules.NOT_DAILY_CREW,
          HttpStatus.UNPROCESSABLE_CONTENT,
          "일일크루만 그날 근무를 등록합니다.",
          false,
          List.of());
    }
    if (!"ACTIVE".equals(w.state())) {
      throw new PmartException(
          WorkerRules.WORKER_NOT_ACTIVE,
          HttpStatus.UNPROCESSABLE_CONTENT,
          "ACTIVE 가 아닌 작업자입니다.",
          false,
          List.of());
    }
    java.time.LocalDate d = date == null ? today() : date;
    Instant now = clock.instant();
    var q = quals.current(mfcId, workerId).orElse(null);
    Set<String> duty = q == null || !q.valid(now) ? Set.of() : q.taskTypes();
    if (quals.enroll(mfcId, workerId, d, duty, p.subject(), now)) {
      log.info("일일크루 {} {} 근무 등록 — 그날 업무 {} by {}", workerId, d, duty, p.subject());
    }
    return quals.day(mfcId, workerId, d).orElseThrow();
  }

  // ---------- 근무 ----------

  public ShiftRow addShift(
      String requestId,
      String sourceSystem,
      String mfcId,
      String workerId,
      Instant start,
      Instant end,
      @Nullable String note,
      @Nullable Instant breakStart,
      @Nullable Instant breakEnd) {
    String me = AssignmentService.requireSupervisor(mfcId);
    String bad = WorkerRules.invalidShift(start, end);
    if (bad == null) {
      bad = WorkerRules.invalidShiftBreak(start, end, breakStart, breakEnd);
    }
    if (bad != null) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, bad);
    }
    String id =
        idempotency
            .execute(
                new RequestKey(sourceSystem, requestId, "addWorkerShift", me),
                List.of(
                    mfcId,
                    workerId,
                    start.toString(),
                    end.toString(),
                    String.valueOf(note),
                    String.valueOf(breakStart),
                    String.valueOf(breakEnd)),
                String.class,
                () -> {
                  lockOrThrow(mfcId, workerId); // 같은 작업자 근무 등록을 줄 세운다
                  if (store.overlaps(mfcId, workerId, start, end)) {
                    throw new PmartException(
                        WorkerRules.SHIFT_OVERLAP,
                        HttpStatus.CONFLICT,
                        "같은 작업자의 다른 근무와 겹칩니다.",
                        false,
                        List.of());
                  }
                  return store
                      .insertShift(
                          mfcId,
                          workerId,
                          start,
                          end,
                          note,
                          breakStart,
                          breakEnd,
                          me,
                          clock.instant())
                      .shiftId();
                })
            .value();
    return store.shift(id).orElseThrow();
  }

  @Transactional(readOnly = true)
  public List<ShiftRow> shifts(
      String mfcId, String workerId, @Nullable Instant from, @Nullable Instant to) {
    AssignmentService.requireWorkerReader(mfcId, workerId);
    return store.shifts(mfcId, workerId, from, to);
  }

  @Transactional
  public ShiftRow cancelShift(String mfcId, String workerId, String shiftId, String reason) {
    String me = AssignmentService.requireSupervisor(mfcId);
    ShiftRow s =
        store
            .shift(shiftId)
            .filter(x -> x.mfcId().equals(mfcId) && x.workerId().equals(workerId))
            .orElseThrow(() -> PmartException.notFound(SHIFT_NOT_FOUND, "근무가 없습니다: " + shiftId));
    if ("PLANNED".equals(s.state())) {
      store.cancelShift(shiftId, reason, me, clock.instant());
    }
    return store.shift(shiftId).orElseThrow();
  }

  private WorkerRow lockOrThrow(String mfcId, String workerId) {
    return store
        .lock(mfcId, workerId)
        .orElseThrow(() -> PmartException.notFound(WORKER_NOT_FOUND, "작업자가 없습니다: " + workerId));
  }

  private static void checkDuty(Set<String> taskTypes, Set<String> zones) {
    String bad = WorkerRules.invalidDuty(taskTypes, zones);
    if (bad != null) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, bad);
    }
  }
}
