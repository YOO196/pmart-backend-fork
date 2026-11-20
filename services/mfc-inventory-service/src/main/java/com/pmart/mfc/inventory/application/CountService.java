package com.pmart.mfc.inventory.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.inventory.application.Ledger.HoldChange;
import com.pmart.mfc.inventory.domain.CountRules;
import com.pmart.mfc.inventory.domain.CountRules.BookLine;
import com.pmart.mfc.inventory.domain.CountRules.CountLine;
import com.pmart.mfc.inventory.domain.CountRules.Difference;
import com.pmart.mfc.inventory.domain.CountRules.Unexpected;
import com.pmart.mfc.inventory.domain.CountRules.Unresolved;
import com.pmart.mfc.inventory.domain.StockUnit.Change;
import com.pmart.mfc.inventory.domain.StockUnit.Key;
import com.pmart.mfc.inventory.domain.StockUnit.Quality;
import com.pmart.mfc.inventory.domain.StockUnit.Work;
import com.pmart.mfc.inventory.infrastructure.CountStore;
import com.pmart.mfc.inventory.infrastructure.CountStore.SessionRow;
import com.pmart.mfc.inventory.infrastructure.StockStore;
import com.pmart.mfc.inventory.infrastructure.StockStore.LotRow;
import com.pmart.mfc.inventory.infrastructure.StockStore.UnitRow;
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
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 순환 실사(IV-11). 실사 잠금 위치의 판매 단위 장부를 고정 → 첫 계수(장부 숨김) → 차이면 다른 작업자 재계수 → 센터 관리자 승인(시작·계수자 아님, 근거 필수)
 * → 한 확정 단위(COUNT_ADJ)로 차이 반영 + 그 위치 조사 보류 해소. 반영 직전 장부가 기준과 다르면 거절(원인 복구 우선 — 같은 이동을 ADJ 로 또 세지
 * 않는다).
 */
@Service
public class CountService {

  static final String WORKER = "MFC_WORKER";
  static final String SUPERVISOR = "MFC_SUPERVISOR";

  private static final Logger log = LoggerFactory.getLogger(CountService.class);
  private static final TypeReference<List<BookLine>> BOOK = new TypeReference<>() {};
  private static final TypeReference<List<CountLine>> COUNTS = new TypeReference<>() {};
  private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
  private static final TypeReference<List<Unresolved>> UNRESOLVED = new TypeReference<>() {};
  private static final TypeReference<List<CountException>> EXCEPTIONS = new TypeReference<>() {};

  /** 조회 모양: 계수 중에는 differences·unresolved = null. */
  public record View(
      SessionRow row,
      List<String> locations,
      List<BookLine> book,
      Map<String, LotRow> lots,
      @Nullable List<Difference> differences,
      @Nullable List<Unresolved> unresolved,
      List<CountException> exceptions) {}

  /**
   * 실사 예외 보고(0.34.0, BR-52 R19-7). reason = LOCATION_INACCESSIBLE·DAMAGE_FOUND. resolved* = 센터 관리자가
   * 다시 시작(0.35.0) — null 이면 열린 예외(작업 후보에서 빠짐). 0.34.0 에 쓴 JSON 에는 resolved* 가 없다(null 로 읽힌다).
   */
  public record CountException(
      String exceptionId,
      String reason,
      String locationCode,
      @Nullable String sku,
      @Nullable String lotId,
      @Nullable Integer qty,
      @Nullable String note,
      String reportedBy,
      String reportedAt,
      @Nullable String resolvedBy,
      @Nullable String resolvedAt,
      @Nullable String resolutionNote) {

    public CountException(
        String exceptionId,
        String reason,
        String locationCode,
        @Nullable String sku,
        @Nullable String lotId,
        @Nullable Integer qty,
        @Nullable String note,
        String reportedBy,
        String reportedAt) {
      this(
          exceptionId,
          reason,
          locationCode,
          sku,
          lotId,
          qty,
          note,
          reportedBy,
          reportedAt,
          null,
          null,
          null);
    }

    boolean open() {
      return resolvedAt == null;
    }

    CountException resolve(String by, Instant at, @Nullable String how) {
      return new CountException(
          exceptionId,
          reason,
          locationCode,
          sku,
          lotId,
          qty,
          note,
          reportedBy,
          reportedAt,
          by,
          at.toString(),
          how);
    }
  }

  static final Set<String> EXCEPTION_REASONS = Set.of("LOCATION_INACCESSIBLE", "DAMAGE_FOUND");

  /** 실사 계획 조회 모양(0.34.0, BR-52 R14). state = OPEN·COMPLETED·CANCELLED. */
  public record PlanView(CountStore.PlanRow plan, String state, List<PlanSessionView> sessions) {}

  public record PlanSessionView(
      String sessionId,
      String locationCode,
      String state,
      int exceptionCount,
      int openExceptionCount) {}

  /** mfc-capacity 에 줄 실사 한 건 — excluded = 셀 수 없는 사람(재계수면 첫 계수자). */
  public record CountWork(
      String sessionId,
      String planId,
      String state,
      List<String> locations,
      List<String> excluded,
      Instant baselineAt,
      @Nullable String assigneeId) {}

  private final CountStore store;
  private final StockStore stock;
  private final Locations locations;
  private final Ledger ledger;
  private final ReservationBlocker blocker;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;
  private final RequestIdempotency idempotency;
  private final ObjectMapper mapper;
  private final Clock clock;

  private final com.pmart.mfc.inventory.infrastructure.MoveStore moves;

  /** 진행 중 재고 이동이 있는 칸은 실사를 시작하지 않는다(이동 보류·카트 물량이 장부를 흔든다, IV-03). */
  public static final String MOVE_IN_PROGRESS = "MOVE_IN_PROGRESS";

  private final ApprovalRefs approvalRefs;

  public CountService(
      CountStore store,
      StockStore stock,
      Locations locations,
      Ledger ledger,
      ReservationBlocker blocker,
      EventPublisher events,
      EventEnvelopeCodec codec,
      RequestIdempotency idempotency,
      ObjectMapper mapper,
      Clock clock,
      com.pmart.mfc.inventory.infrastructure.MoveStore moves,
      ApprovalRefs approvalRefs) {
    this.approvalRefs = approvalRefs;
    this.moves = moves;
    this.store = store;
    this.stock = stock;
    this.locations = locations;
    this.ledger = ledger;
    this.blocker = blocker;
    this.events = events;
    this.codec = codec;
    this.idempotency = idempotency;
    this.mapper = mapper;
    this.clock = clock;
  }

  // ---------- 시작 ----------

  public View create(
      String mfcId,
      String requestId,
      String sourceSystem,
      List<String> codes,
      @Nullable String reason) {
    String me = worker(mfcId);
    Set<String> locs = new LinkedHashSet<>(codes);
    if (locs.isEmpty() || locs.size() > 50) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "위치는 1~50개입니다.");
    }
    String id =
        idempotency
            .execute(
                new RequestIdempotency.RequestKey(
                    sourceSystem, requestId, "createCountSession", me),
                Map.of(
                    "mfcId",
                    mfcId,
                    "locations",
                    List.copyOf(locs),
                    "reason",
                    String.valueOf(reason)),
                String.class,
                () -> {
                  store.lockCenter(mfcId);
                  for (String l : locs) {
                    locations.requireCountLocked(mfcId, l);
                    if (!moves.openAt(mfcId, l).isEmpty()) {
                      throw new PmartException(
                          MOVE_IN_PROGRESS,
                          HttpStatus.CONFLICT,
                          l + " 에 진행 중인 재고 이동이 있습니다 — 끝내거나 취소한 뒤 실사합니다.",
                          false,
                          List.of());
                    }
                  }
                  List<String> busy = store.openLocations(mfcId, locs);
                  if (!busy.isEmpty()) {
                    throw new PmartException(
                        CountRules.COUNT_IN_PROGRESS,
                        HttpStatus.CONFLICT,
                        "열린 실사에 이미 있는 위치입니다: " + busy,
                        false,
                        List.of());
                  }
                  return start(mfcId, locs, reason, me, null, clock.instant());
                })
            .value();
    return view(mfcId, id);
  }

  /** 기준 시각에 그 칸들의 판매 단위 장부·조사 보류를 고정한 실사(COUNTING) 하나. */
  private String start(
      String mfcId,
      Set<String> locs,
      @Nullable String reason,
      String me,
      @Nullable String planId,
      Instant now) {
    List<BookLine> book = new ArrayList<>();
    for (UnitRow u : stock.storedUnitsAt(mfcId, locs)) {
      book.add(
          new BookLine(
              u.key().locationCode(),
              u.key().sku(),
              u.key().lotId(),
              u.qty(),
              stock.openLossHold(mfcId, u.key())));
    }
    String sid = "CNT-" + UUID.randomUUID();
    store.insert(
        new SessionRow(
            sid,
            mfcId,
            "COUNTING",
            mapper.writeValueAsString(List.copyOf(locs)),
            reason,
            me,
            null,
            null,
            null,
            null,
            null,
            mapper.writeValueAsString(book),
            null,
            null,
            null,
            1,
            now,
            now,
            planId,
            null,
            null,
            "[]",
            null));
    log.info(
        "실사 {} 시작 {} 위치 {}개·장부 {}줄 by {} (계획 {})",
        sid,
        mfcId,
        locs.size(),
        book.size(),
        me,
        planId);
    return sid;
  }

  private void requireNoMoves(String mfcId, String l) {
    if (!moves.openAt(mfcId, l).isEmpty()) {
      throw new PmartException(
          MOVE_IN_PROGRESS,
          HttpStatus.CONFLICT,
          l + " 에 진행 중인 재고 이동이 있습니다 — 끝내거나 취소한 뒤 실사합니다.",
          false,
          List.of());
    }
  }

  /** (0.42.0) 순환 실사 기본 칸 수(사용자 결정 2026-11-20 "20칸"). */
  static final int DEFAULT_ROTATION_SIZE = 20;

  static final String ROTATION_REASON = "순환 실사";
  public static final String NO_COUNT_LOCATIONS = "NO_COUNT_LOCATIONS";

  // ---------- 실사 계획(0.34.0, BR-52 R14 — 사용자 결정 2026-11-20 "관리자가 계획·서버 잠금") ----------

  /**
   * 센터 관리자가 칸 묶음을 계획 → 칸마다 실사 하나. 이 서비스가 그 칸을 잠근다(끝나지 않은 계획 실사 = 잠금, {@link
   * Locations#requireNotPlanLocked}) — mfc-location COUNT_LOCKED 는 필요 없다. 모두 또는 전무.
   */
  public PlanView createPlan(
      String mfcId,
      String requestId,
      String sourceSystem,
      @Nullable List<String> codes,
      @Nullable String reason,
      @Nullable String assigneeId,
      @Nullable Integer rotationSize) {
    String me = supervisor(mfcId);
    if (assigneeId != null && (assigneeId.isBlank() || assigneeId.length() > 64)) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "assigneeId 는 1~64자입니다.");
    }
    boolean rotation = codes == null || codes.isEmpty();
    if (!rotation && rotationSize != null) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED, "locationCodes 와 rotationSize 는 같이 줄 수 없습니다.");
    }
    int size = rotationSize == null ? DEFAULT_ROTATION_SIZE : rotationSize;
    if (rotation && (size < 1 || size > 200)) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "rotationSize 는 1~200 입니다.");
    }
    Set<String> requested = rotation ? Set.of() : new LinkedHashSet<>(codes);
    if (!rotation && requested.size() > 200) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "칸은 1~200개입니다.");
    }
    String why = rotation && (reason == null || reason.isBlank()) ? ROTATION_REASON : reason;
    String planId =
        idempotency
            .execute(
                new RequestIdempotency.RequestKey(sourceSystem, requestId, "createCountPlan", me),
                Map.of(
                    "mfcId",
                    mfcId,
                    "locations",
                    List.copyOf(requested),
                    "rotationSize",
                    rotation ? String.valueOf(size) : "-",
                    "reason",
                    String.valueOf(why),
                    "assigneeId",
                    String.valueOf(assigneeId)),
                String.class,
                () -> {
                  store.lockCenter(mfcId);
                  Set<String> locs =
                      rotation
                          ? new LinkedHashSet<>(store.rotationCandidates(mfcId, size))
                          : requested;
                  if (locs.isEmpty()) {
                    throw new PmartException(
                        NO_COUNT_LOCATIONS,
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "순환 실사로 고를 칸이 없습니다 — 선반·피킹 칸이 모두 실사·이동 중이거나 없습니다.",
                        false,
                        List.of());
                  }
                  for (String l : locs) {
                    var row =
                        locations
                            .projection(mfcId, l)
                            .orElseThrow(
                                () ->
                                    new PmartException(
                                        Locations.LOCATION_NOT_REGISTERED,
                                        HttpStatus.UNPROCESSABLE_CONTENT,
                                        "등록되지 않은 위치입니다: " + mfcId + "/" + l,
                                        false,
                                        List.of()));
                    if ("RETIRED".equals(row.state())) {
                      throw new PmartException(
                          Locations.LOCATION_CLOSED,
                          HttpStatus.CONFLICT,
                          l + " 는 폐지된 칸입니다.",
                          false,
                          List.of());
                    }
                    requireNoMoves(mfcId, l);
                  }
                  List<String> busy = store.openLocations(mfcId, locs);
                  if (!busy.isEmpty()) {
                    throw new PmartException(
                        CountRules.COUNT_IN_PROGRESS,
                        HttpStatus.CONFLICT,
                        "열린 실사에 이미 있는 칸입니다: " + busy,
                        false,
                        List.of());
                  }
                  Instant now = clock.instant();
                  String pid = "CPL-" + UUID.randomUUID();
                  store.insertPlan(
                      new CountStore.PlanRow(pid, mfcId, why, me, now, null, null, assigneeId));
                  for (String l : locs) {
                    start(mfcId, Set.of(l), why, me, pid, now);
                  }
                  log.info(
                      "실사 계획 {} {} 칸 {}개{} by {}",
                      pid,
                      mfcId,
                      locs.size(),
                      rotation ? " (순환 " + size + ")" : "",
                      me);
                  return pid;
                })
            .value();
    return planView(mfcId, planId);
  }

  @Transactional(readOnly = true)
  public PlanView planView(String mfcId, String planId) {
    Operator.requireCenter(mfcId);
    CountStore.PlanRow p =
        store
            .findPlan(planId)
            .filter(r -> r.mfcId().equals(mfcId))
            .orElseThrow(
                () -> PmartException.notFound("COUNT_PLAN_NOT_FOUND", "실사 계획이 없습니다: " + planId));
    List<PlanSessionView> sessions = new ArrayList<>();
    boolean open = false;
    for (CountStore.PlanSession s : store.planSessions(planId)) {
      List<String> locs = mapper.readValue(s.locationCodes(), STRINGS);
      sessions.add(
          new PlanSessionView(
              s.sessionId(),
              locs.isEmpty() ? "" : locs.get(0),
              s.state(),
              s.exceptions(),
              s.openExceptions()));
      open |= OPEN_STATES.contains(s.state());
    }
    String state = p.cancelledAt() != null ? "CANCELLED" : open ? "OPEN" : "COMPLETED";
    return new PlanView(p, state, sessions);
  }

  /** 계획 취소 — 끝나지 않은 실사를 모두 CANCELLED(재고 변화 없음) → 잠금 풀림. 끝난 실사는 그대로. */
  @Transactional
  public PlanView cancelPlan(String mfcId, String planId, @Nullable String reason) {
    String me = supervisor(mfcId);
    planView(mfcId, planId); // 있는지·센터
    store.lockPlan(planId);
    Instant now = clock.instant();
    for (CountStore.PlanSession s : store.planSessions(planId)) {
      if (OPEN_STATES.contains(s.state())) {
        SessionRow row = lock(mfcId, s.sessionId());
        if (OPEN_STATES.contains(row.state())) {
          store.cancel(s.sessionId(), me, reason, now);
        }
      }
    }
    store.cancelPlan(planId, me, reason, now);
    log.info("실사 계획 {} 취소 by {}", planId, me);
    return planView(mfcId, planId);
  }

  /** 실사 하나 취소(센터 관리자) — 끝나지 않은 실사만, 재고 변화 없음. 계획 실사면 그 칸 잠금이 풀린다. */
  @Transactional
  public View cancel(String mfcId, String sessionId, @Nullable String reason) {
    String me = supervisor(mfcId);
    SessionRow s = lock(mfcId, sessionId);
    if ("CANCELLED".equals(s.state())) {
      return view(mfcId, sessionId);
    }
    if (!OPEN_STATES.contains(s.state())) {
      throw PmartException.invalidState("끝난 실사는 취소할 수 없습니다(지금 " + s.state() + ").");
    }
    store.cancel(sessionId, me, reason, clock.instant());
    log.info("실사 {} 취소 by {}", sessionId, me);
    return view(mfcId, sessionId);
  }

  /** 실사 예외 보고(0.34.0, BR-52 R19-7) — 재고는 바꾸지 않고 기록해 센터 관리자에게 넘긴다(다음 작업 후보에서 빠짐). 계수 단계만. */
  public View reportException(
      String mfcId, String sessionId, String requestId, String sourceSystem, CountException in) {
    String me = worker(mfcId);
    if (!EXCEPTION_REASONS.contains(in.reason())) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "모르는 예외 사유: " + in.reason());
    }
    if (in.lotId() != null && in.sku() == null) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "lotId 를 주면 sku 도 줍니다.");
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("sessionId", sessionId);
    body.put("reason", in.reason());
    body.put("locationCode", in.locationCode());
    body.put("sku", String.valueOf(in.sku()));
    body.put("lotId", String.valueOf(in.lotId()));
    body.put("qty", String.valueOf(in.qty()));
    body.put("note", String.valueOf(in.note()));
    idempotency.execute(
        new RequestIdempotency.RequestKey(sourceSystem, requestId, "reportCountException", me),
        body,
        String.class,
        () -> {
          SessionRow s = lock(mfcId, sessionId);
          if (!"COUNTING".equals(s.state()) && !"RECOUNT_REQUIRED".equals(s.state())) {
            throw PmartException.invalidState("계수 단계가 아닙니다(지금 " + s.state() + ").");
          }
          if (!mapper.readValue(s.locationCodes(), STRINGS).contains(in.locationCode())) {
            throw new PmartException(
                ErrorCode.VALIDATION_FAILED, in.locationCode() + " 는 이 실사의 위치가 아닙니다.");
          }
          if (in.lotId() != null) {
            LotRow lot = stock.lots(Set.of(in.lotId())).get(in.lotId());
            if (lot == null || !lot.sku().equals(in.sku())) {
              throw new PmartException(
                  ErrorCode.VALIDATION_FAILED,
                  "lotId " + in.lotId() + " 는 SKU " + in.sku() + " 의 LOT 이 아닙니다.");
            }
          }
          Instant now = clock.instant();
          String eid = "CEX-" + UUID.randomUUID();
          store.addException(
              sessionId,
              mapper.writeValueAsString(
                  new CountException(
                      eid,
                      in.reason(),
                      in.locationCode(),
                      in.sku(),
                      in.lotId(),
                      in.qty(),
                      in.note(),
                      me,
                      now.toString())),
              now);
          log.info("실사 {} 예외 {} {} by {}", sessionId, in.reason(), in.locationCode(), me);
          return eid;
        });
    return view(mfcId, sessionId);
  }

  /**
   * 예외로 멈춘 실사 다시 시작(0.35.0, BR-52 R19-7 후속 — 사용자 결정 2026-11-20 "만들어. 꼭 해당 작업자에게 배정되지 않아도 돼"). 센터
   * 관리자가 열린 예외를 모두 해결로 표시 → 다시 작업 후보(누구든 — 보고자 우선 없음, 재계수면 첫 계수자 제외 그대로). 재고·장부 기준·칸 잠금은 그대로. 열린
   * 예외가 없으면 바꾸지 않는다(재시도 안전).
   */
  @Transactional
  public View resume(String mfcId, String sessionId, @Nullable String note) {
    String me = supervisor(mfcId);
    SessionRow s = lock(mfcId, sessionId);
    if (!"COUNTING".equals(s.state()) && !"RECOUNT_REQUIRED".equals(s.state())) {
      throw PmartException.invalidState("계수 단계가 아닙니다(지금 " + s.state() + ").");
    }
    List<CountException> all = mapper.readValue(s.exceptions(), EXCEPTIONS);
    if (all.stream().noneMatch(CountException::open)) {
      return view(mfcId, sessionId);
    }
    Instant now = clock.instant();
    List<CountException> next =
        all.stream().map(e -> e.open() ? e.resolve(me, now, note) : e).toList();
    store.replaceExceptions(sessionId, mapper.writeValueAsString(next), now);
    log.info("실사 {} 다시 시작(예외 해결) by {}", sessionId, me);
    return view(mfcId, sessionId);
  }

  /** mfc-capacity 다음 작업 후보(서비스 간, 토큰 없음) — 계획 실사 중 계수 단계·열린 예외 없는 것. */
  @Transactional(readOnly = true)
  public List<CountWork> countWork(String mfcId) {
    List<CountWork> out = new ArrayList<>();
    Map<String, java.util.Optional<String>> assignees = new java.util.HashMap<>();
    for (SessionRow s : store.countWork(mfcId, 500)) {
      List<String> excluded =
          "RECOUNT_REQUIRED".equals(s.state()) && s.firstCounter() != null
              ? List.of(s.firstCounter())
              : List.of();
      out.add(
          new CountWork(
              s.sessionId(),
              s.planId(),
              s.state(),
              mapper.readValue(s.locationCodes(), STRINGS),
              excluded,
              s.baselineAt(),
              s.planId() == null
                  ? null
                  : assignees
                      .computeIfAbsent(
                          s.planId(), id -> store.findPlan(id).map(CountStore.PlanRow::assigneeId))
                      .orElse(null)));
    }
    return out;
  }

  // ---------- 계수 ----------

  @Transactional
  public View count(String mfcId, String sessionId, List<CountLine> lines) {
    return count(mfcId, sessionId, lines, List.of());
  }

  /**
   * 계수 제출. unexpected = 계수표 밖에서 찾은 줄(R19-6) — SKU·LOT 이 정해지면 lines 와 같이 세고, 아니면 조정 없이 unresolved 로
   * 남긴다.
   */
  @Transactional
  public View count(
      String mfcId, String sessionId, List<CountLine> given, List<Unexpected> unexpected) {
    String me = worker(mfcId);
    SessionRow s = lock(mfcId, sessionId);
    List<String> locs = mapper.readValue(s.locationCodes(), STRINGS);
    List<CountLine> lines = new ArrayList<>(given);
    List<Unresolved> unresolved = new ArrayList<>();
    for (Unexpected u : unexpected) {
      if (u.qty() < 1) {
        throw new PmartException(ErrorCode.VALIDATION_FAILED, "목록 밖 줄 수량은 1 이상입니다.");
      }
      if (!locs.contains(u.locationCode())) {
        throw new PmartException(
            ErrorCode.VALIDATION_FAILED, u.locationCode() + " 는 이 실사의 위치가 아닙니다.");
      }
      if (u.sku() == null && (u.barcode() == null || u.barcode().isBlank())) {
        throw new PmartException(ErrorCode.VALIDATION_FAILED, "목록 밖 줄은 sku 또는 barcode 가 필요합니다.");
      }
      String sku = u.sku();
      if (sku == null) {
        List<String> found = store.skusByBarcode(u.barcode());
        if (found.size() != 1) {
          unresolved.add(
              new Unresolved(
                  u.locationCode(),
                  null,
                  u.barcode(),
                  u.expiryDate(),
                  u.qty(),
                  CountRules.SKU_UNKNOWN));
          continue;
        }
        sku = found.get(0);
      }
      List<String> same =
          u.lotId() == null && u.expiryDate() != null
              ? store.lotsByExpiry(sku, u.expiryDate())
              : List.of();
      String lot = CountRules.lotOf(u, same);
      if (lot == null) {
        unresolved.add(
            new Unresolved(
                u.locationCode(),
                sku,
                u.barcode(),
                u.expiryDate(),
                u.qty(),
                CountRules.lotCause(u, same)));
        continue;
      }
      lines.add(new CountLine(u.locationCode(), sku, lot, u.qty()));
    }
    for (CountLine l : lines) {
      if (l.countedQty() < 0) {
        throw new PmartException(ErrorCode.VALIDATION_FAILED, "수량은 0 이상입니다: " + l.sku());
      }
      if (!locs.contains(l.locationCode())) {
        throw new PmartException(
            ErrorCode.VALIDATION_FAILED, l.locationCode() + " 는 이 실사의 위치가 아닙니다.");
      }
    }
    Map<String, LotRow> lots =
        stock.lots(lines.stream().map(CountLine::lotId).collect(Collectors.toSet()));
    for (CountLine l : lines) {
      LotRow lot = lots.get(l.lotId());
      if (lot == null || !lot.sku().equals(l.sku())) {
        throw new PmartException(
            ErrorCode.VALIDATION_FAILED,
            "lotId " + l.lotId() + " 는 SKU " + l.sku() + " 의 LOT 이 아닙니다.");
      }
    }
    List<BookLine> book = mapper.readValue(s.book(), BOOK);
    List<Difference> table = CountRules.reconcile(book, lines);
    String next =
        CountRules.next(
            s.state(), CountRules.matches(table, unresolved), CountRules.hasHolds(book));
    Instant now = clock.instant();
    String json = mapper.writeValueAsString(lines);
    String open = mapper.writeValueAsString(unresolved);
    switch (s.state()) {
      case "COUNTING" -> store.firstCount(sessionId, me, json, open, next, now);
      case "RECOUNT_REQUIRED" -> {
        if (me.equals(s.firstCounter())) {
          throw new PmartException(
              CountRules.SAME_COUNTER,
              HttpStatus.CONFLICT,
              "재계수는 첫 계수와 다른 작업자가 합니다.",
              false,
              List.of());
        }
        store.recount(sessionId, me, json, open, next, now);
      }
      default -> throw PmartException.invalidState("계수 단계가 아닙니다(지금 " + s.state() + ").");
    }
    log.info("실사 {} 계수 by {} → {}", sessionId, me, next);
    return view(mfcId, sessionId);
  }

  // ---------- 승인 ----------

  @Transactional
  public View decide(
      String mfcId,
      String sessionId,
      String action,
      @Nullable String approvalRef,
      @Nullable String note) {
    String me = supervisor(mfcId);
    SessionRow s = lock(mfcId, sessionId);
    boolean approve = "APPROVE".equals(action);
    if (!approve && !"REJECT".equals(action)) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "action 은 APPROVE/REJECT 입니다.");
    }
    if (!"PENDING_APPROVAL".equals(s.state())) {
      boolean same =
          me.equals(s.approverId())
              && (approve ? "ADJUSTED".equals(s.state()) : "REJECTED".equals(s.state()));
      if (same) {
        return view(mfcId, sessionId); // 같은 결정 다시 — 한 번만 반영
      }
      throw PmartException.invalidState("승인 대기 실사가 아닙니다(지금 " + s.state() + ").");
    }
    String self = CountRules.approvalRejection(me, s.createdBy(), s.firstCounter(), s.recounter());
    if (self != null) {
      throw new PmartException(
          self, HttpStatus.UNPROCESSABLE_CONTENT, "실사를 시작·계수한 사람은 승인할 수 없습니다.", false, List.of());
    }
    Instant now = clock.instant();
    if (!approve) {
      store.decide(sessionId, "REJECTED", me, null, note, null, now);
      log.info("실사 {} 반려 by {}", sessionId, me);
      return view(mfcId, sessionId);
    }
    List<String> locs = mapper.readValue(s.locationCodes(), STRINGS);
    if (s.planId() == null) { // 계획 실사는 이 서비스가 잠근다(열린 실사 = 잠금) — mfc-location 잠금을 보지 않는다
      for (String l : locs) {
        locations.requireCountLocked(mfcId, l);
      }
    }
    List<BookLine> book = mapper.readValue(s.book(), BOOK);
    List<Difference> table = finalTable(s, book);
    // 장부가 기준 시각과 같은가(잠금 중 바뀌었으면 원인 복구 뒤 다시 실사)
    Map<String, Integer> current = new TreeMap<>();
    for (UnitRow u : stock.storedUnitsAt(mfcId, locs)) {
      current.put(CountRules.key(u.key().locationCode(), u.key().sku(), u.key().lotId()), u.qty());
    }
    Map<String, Integer> baseline = new TreeMap<>();
    book.forEach(b -> baseline.put(b.key(), b.qty()));
    // 조사 보류도 기준과 같아야 한다 — 승인은 그 단위의 열린 보류를 모두 풀기 때문에, 실사 시작 뒤 생긴 보류(이 실사가 본 적 없는 결품
    // 조사)를 같이 풀지 않도록(2026-11-08 검토 반영 #12)
    Map<String, Integer> heldNow = new TreeMap<>();
    Map<String, Integer> heldBook = new TreeMap<>();
    for (BookLine b : book) {
      heldBook.put(b.key(), b.heldQty());
      heldNow.put(
          b.key(),
          stock.openLossHold(
              mfcId, new Key(b.locationCode(), b.sku(), b.lotId(), Quality.NORMAL, Work.STORED)));
    }
    if (!current.equals(baseline) || !heldNow.equals(heldBook)) {
      throw new PmartException(
          CountRules.BOOK_CHANGED,
          HttpStatus.CONFLICT,
          "실사 기준 뒤 장부가 바뀌었습니다 — 원인(이동·인계 누락 등)을 먼저 복구하고 다시 실사합니다.",
          false,
          List.of());
    }
    // 승인 번호 관문(AP-1): 본사 기준 이상이면 approval-service 승인 번호를 한 번 쓴다 — 아래면 센터 관리자 승인만
    String given =
        approvalRefs.require(
            mfcId,
            "COUNT_ADJUSTMENT",
            sessionId,
            me,
            mfcId + " 실사 조정 " + sessionId,
            adjustmentLines(table),
            approvalRef);
    String recorded = given != null ? given : CENTER_APPROVAL + sessionId;
    List<Change> units = new ArrayList<>();
    List<HoldChange> holds = new ArrayList<>();
    Map<String, Integer> sellableDrop = new TreeMap<>();
    Map<String, java.util.Set<String>> dropLocations = new TreeMap<>();
    int moved = 0;
    Map<Key, Integer> qualityShrink = new java.util.LinkedHashMap<>();
    for (Difference d : table) {
      Key k = new Key(d.locationCode(), d.sku(), d.lotId(), Quality.NORMAL, Work.STORED);
      int held = stock.openLossHold(mfcId, k);
      if (d.delta() != 0) {
        units.add(new Change(k, d.delta()));
        moved += Math.abs(d.delta());
      }
      // 품질 사고 판매 보류(0.39.0)는 실사가 풀지 않는다 — 다만 실물이 보류보다 적어지면 없는 만큼은 보류할 것이 없다
      int qualityHeld = stock.openQualityHold(mfcId, k);
      int after =
          baseline.getOrDefault(CountRules.key(d.locationCode(), d.sku(), d.lotId()), 0)
              + d.delta();
      int shrink = Math.max(0, qualityHeld - Math.max(0, after));
      if (held + shrink > 0) {
        holds.add(new HoldChange(k, -(held + shrink)));
      }
      if (shrink > 0) {
        qualityShrink.put(k, shrink);
      }
      int de = d.delta() + held + shrink; // E 변화 = 단위 증감 + 보류 해소
      if (de < 0) {
        sellableDrop.merge(d.sku() + "|" + d.lotId(), -de, Integer::sum);
        dropLocations
            .computeIfAbsent(d.sku() + "|" + d.lotId(), key -> new java.util.TreeSet<>())
            .add(d.locationCode());
      }
    }
    String commitId = Ledger.newCommitId();
    ReservationBlocker.Result blocked =
        blocker.block(mfcId, sellableDrop, dropLocations, "COUNT_LOSS", sessionId, now);
    ledger.postWithHolds(
        commitId,
        "COUNT_ADJ",
        mfcId,
        sessionId + ":" + me,
        "mfc-count",
        sessionId,
        moved,
        units,
        blocked.reserved(),
        holds,
        now);
    for (Difference d : table) {
      if (holds.stream()
          .anyMatch(
              h ->
                  h.key().locationCode().equals(d.locationCode())
                      && h.key().sku().equals(d.sku())
                      && h.key().lotId().equals(d.lotId()))) {
        stock.resolveHolds(
            mfcId,
            new Key(d.locationCode(), d.sku(), d.lotId(), Quality.NORMAL, Work.STORED),
            sessionId,
            now);
      }
    }
    qualityShrink.forEach((k, x) -> stock.consumeQualityHold(mfcId, k, x, sessionId, now));
    blocked = blocker.resecure(blocked, sessionId + ":" + me, "mfc-count", now);
    blocker.publish(blocked, "COUNT_LOSS", now);
    store.decide(sessionId, "ADJUSTED", me, recorded, note, commitId, now);
    if (!units.isEmpty()) {
      publishAdjusted(s, table, me, recorded, commitId, now);
    }
    log.info(
        "실사 {} 승인 by {} — 조정 {}줄·보류 해소 {}건·예약 보류 {}건 ({})",
        sessionId,
        me,
        units.size(),
        holds.size(),
        blocked.blocked().size(),
        commitId);
    return view(mfcId, sessionId);
  }

  // ---------- 조회 ----------

  static final List<String> OPEN_STATES =
      List.of("COUNTING", "RECOUNT_REQUIRED", "PENDING_APPROVAL");

  static final java.util.Set<String> STATES =
      java.util.Set.of(
          "COUNTING",
          "RECOUNT_REQUIRED",
          "PENDING_APPROVAL",
          "MATCHED",
          "ADJUSTED",
          "REJECTED",
          "CANCELLED");

  /** 실사 ID 목록(MB-1) — 그 센터 운영자, 비우면 끝나지 않은 것. */
  @Transactional(readOnly = true)
  public List<String> sessionIds(
      String mfcId, @Nullable List<String> states, int limit, @Nullable String cursor) {
    Operator.requireCenter(mfcId);
    List<String> s = states == null || states.isEmpty() ? OPEN_STATES : states;
    for (String x : s) {
      if (!STATES.contains(x)) {
        throw new PmartException(ErrorCode.VALIDATION_FAILED, "모르는 실사 상태: " + x);
      }
    }
    return store.sessionIds(mfcId, s, cursor, Math.max(1, Math.min(limit, 201)));
  }

  @Transactional(readOnly = true)
  public View view(String mfcId, String sessionId) {
    Operator.requireCenter(mfcId);
    SessionRow s =
        store
            .find(sessionId)
            .filter(r -> r.mfcId().equals(mfcId))
            .orElseThrow(
                () -> PmartException.notFound("COUNT_SESSION_NOT_FOUND", "실사가 없습니다: " + sessionId));
    List<BookLine> book = mapper.readValue(s.book(), BOOK);
    Map<String, LotRow> lots =
        stock.lots(book.stream().map(BookLine::lotId).collect(Collectors.toSet()));
    boolean open =
        "COUNTING".equals(s.state())
            || "RECOUNT_REQUIRED".equals(s.state())
            || ("CANCELLED".equals(s.state()) && s.firstCount() == null); // 세지 않고 취소 — 차이 없음
    String last = s.recounter() != null ? s.recountUnresolved() : s.firstUnresolved();
    return new View(
        s,
        mapper.readValue(s.locationCodes(), STRINGS),
        book,
        lots,
        open ? null : finalTable(s, book),
        open ? null : last == null ? List.of() : mapper.readValue(last, UNRESOLVED),
        mapper.readValue(s.exceptions(), EXCEPTIONS));
  }

  private List<Difference> finalTable(SessionRow s, List<BookLine> book) {
    String last = s.recount() != null ? s.recount() : s.firstCount();
    List<CountLine> lines = last == null ? List.of() : mapper.readValue(last, COUNTS);
    return CountRules.reconcile(book, lines);
  }

  private SessionRow lock(String mfcId, String sessionId) {
    return store
        .lock(sessionId)
        .filter(r -> r.mfcId().equals(mfcId))
        .orElseThrow(
            () -> PmartException.notFound("COUNT_SESSION_NOT_FOUND", "실사가 없습니다: " + sessionId));
  }

  /** 본사 기준 아래 — 센터 관리자 승인만일 때 기록하는 승인 표시(사건 approvalRef.approvalId 는 필수). */
  static final String CENTER_APPROVAL = "CENTER:";

  /** 실사 차이 → SKU 별 움직이는 수량(|증감| 합)·순감소(손실). */
  static List<com.pmart.mfc.inventory.domain.HqApprovalRules.Line> adjustmentLines(
      List<Difference> table) {
    Map<String, int[]> by = new TreeMap<>();
    for (Difference d : table) {
      int[] v = by.computeIfAbsent(d.sku(), k -> new int[2]);
      v[0] += Math.abs(d.delta());
      v[1] += d.delta();
    }
    List<com.pmart.mfc.inventory.domain.HqApprovalRules.Line> out = new ArrayList<>();
    by.forEach(
        (sku, v) -> {
          if (v[0] > 0) {
            out.add(
                new com.pmart.mfc.inventory.domain.HqApprovalRules.Line(
                    sku, v[0], Math.max(0, -v[1]), Map.of("delta", v[1])));
          }
        });
    return out;
  }

  private void publishAdjusted(
      SessionRow s,
      List<Difference> table,
      String approver,
      String approvalRef,
      String commitId,
      Instant at) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("adjId", s.sessionId());
    m.put("countId", s.sessionId());
    m.put("mfcId", s.mfcId());
    m.put(
        "lines",
        table.stream()
            .filter(d -> d.delta() != 0)
            .map(
                d -> {
                  Map<String, Object> x = new LinkedHashMap<>();
                  x.put("sku", d.sku());
                  x.put("lot", d.lotId());
                  x.put("locCode", d.locationCode());
                  x.put("deltaQty", d.delta());
                  x.put("reason", "CYCLE_COUNT");
                  return x;
                })
            .toList());
    m.put("approvalRef", Map.of("approvalId", approvalRef, "approverId", approver));
    m.put("commitId", commitId);
    m.put("adjustedAt", at.toString());
    events.publish(
        codec.create(
            events.sourceSystem(),
            "StockAdjusted",
            1,
            "InventoryAdjustment",
            s.sessionId(),
            s.version() + 1,
            new EventEnvelope.Actor(EventEnvelope.ActorType.OPERATOR, approver),
            EventEnvelope.EventSource.REAL,
            m),
        ReservationService.TOPIC);
  }

  // ---------- 권한 ----------

  private static String worker(String mfcId) {
    String me = Operator.requireCenter(mfcId);
    PmartPrincipal p = PmartPrincipal.current().orElseThrow();
    if (!p.hasRole(WORKER) && !p.hasRole(SUPERVISOR)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "이 센터의 MFC_WORKER 또는 MFC_SUPERVISOR 만 실사합니다.");
    }
    return me;
  }

  private static String supervisor(String mfcId) {
    String me = Operator.requireCenter(mfcId);
    if (!PmartPrincipal.current().orElseThrow().hasRole(SUPERVISOR)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "이 센터의 MFC_SUPERVISOR 만 실사 차이를 승인합니다.");
    }
    return me;
  }
}
