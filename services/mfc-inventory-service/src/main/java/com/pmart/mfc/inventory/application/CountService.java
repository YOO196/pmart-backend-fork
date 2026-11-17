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

  /** 조회 모양: 계수 중에는 differences = null. */
  public record View(
      SessionRow row,
      List<String> locations,
      List<BookLine> book,
      Map<String, LotRow> lots,
      @Nullable List<Difference> differences) {}

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
                  Instant now = clock.instant();
                  List<BookLine> book = new ArrayList<>();
                  for (UnitRow u : stock.storedUnitsAt(mfcId, locs)) {
                    book.add(
                        new BookLine(
                            u.key().locationCode(),
                            u.key().sku(),
                            u.key().lotId(),
                            u.qty(),
                            stock.openHold(mfcId, u.key())));
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
                          now));
                  log.info(
                      "실사 {} 시작 {} 위치 {}개·장부 {}줄 by {}", sid, mfcId, locs.size(), book.size(), me);
                  return sid;
                })
            .value();
    return view(mfcId, id);
  }

  // ---------- 계수 ----------

  @Transactional
  public View count(String mfcId, String sessionId, List<CountLine> lines) {
    String me = worker(mfcId);
    SessionRow s = lock(mfcId, sessionId);
    List<String> locs = mapper.readValue(s.locationCodes(), STRINGS);
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
    String next = CountRules.next(s.state(), CountRules.matches(table), CountRules.hasHolds(book));
    Instant now = clock.instant();
    String json = mapper.writeValueAsString(lines);
    switch (s.state()) {
      case "COUNTING" -> store.firstCount(sessionId, me, json, next, now);
      case "RECOUNT_REQUIRED" -> {
        if (me.equals(s.firstCounter())) {
          throw new PmartException(
              CountRules.SAME_COUNTER,
              HttpStatus.CONFLICT,
              "재계수는 첫 계수와 다른 작업자가 합니다.",
              false,
              List.of());
        }
        store.recount(sessionId, me, json, next, now);
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
    for (String l : locs) {
      locations.requireCountLocked(mfcId, l);
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
          stock.openHold(
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
    for (Difference d : table) {
      Key k = new Key(d.locationCode(), d.sku(), d.lotId(), Quality.NORMAL, Work.STORED);
      int held = stock.openHold(mfcId, k);
      if (d.delta() != 0) {
        units.add(new Change(k, d.delta()));
        moved += Math.abs(d.delta());
      }
      if (held > 0) {
        holds.add(new HoldChange(k, -held));
      }
      int de = d.delta() + held; // E 변화 = 단위 증감 + 보류 해소
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
    boolean open = "COUNTING".equals(s.state()) || "RECOUNT_REQUIRED".equals(s.state());
    return new View(
        s,
        mapper.readValue(s.locationCodes(), STRINGS),
        book,
        lots,
        open ? null : finalTable(s, book));
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
