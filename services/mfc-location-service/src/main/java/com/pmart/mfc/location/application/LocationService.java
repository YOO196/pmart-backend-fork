package com.pmart.mfc.location.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.CommandResponse;
import com.pmart.common.web.CommandStatus;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.location.domain.LocationCodes;
import com.pmart.mfc.location.domain.LocationRules;
import com.pmart.mfc.location.domain.LocationRules.State;
import com.pmart.mfc.location.domain.LocationRules.Transition;
import com.pmart.mfc.location.domain.LocationView;
import com.pmart.mfc.location.infrastructure.LocationStore;
import com.pmart.mfc.location.infrastructure.LocationStore.StateChangeRow;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 위치 마스터(ADR-0040 2단계). 쓰기는 그 센터의 운영자, 읽기는 그 센터 운영자 또는 서비스 토큰. 위치가 생기거나 바뀔 때마다 같은 트랜잭션에서
 * MfcLocationStateChanged(전체 속성)를 Outbox 에 쓴다. 코드는 바꾸지 않는다(새 위치로 등록). 코드 문법은 Q-03 미결이라 검사하지 않는다.
 */
@Service
public class LocationService {

  public static final String TOPIC = "mfc.location.v1";
  public static final String LOCATION_CODE_DUPLICATE = "LOCATION_CODE_DUPLICATE";
  public static final String LOCATION_NOT_FOUND = "LOCATION_NOT_FOUND";
  static final int REPUBLISH_MAX = 20_000;

  private static final Logger log = LoggerFactory.getLogger(LocationService.class);

  /** 등록·변경 내용(코드·센터는 등록 때만). */
  public record Body(
      String mfcId,
      String locationCode,
      @Nullable String areaCode,
      String zone,
      String temperatureZone,
      @Nullable Integer widthMm,
      @Nullable Integer depthMm,
      @Nullable Integer heightMm,
      @Nullable Integer slotCount,
      @Nullable Integer maxWeightG,
      @Nullable Integer pickSequence,
      boolean highTurnover,
      @Nullable String categoryId,
      @Nullable Double posXM,
      @Nullable Double posZM) {}

  record StateRequest(
      String locationId,
      String targetState,
      String reason,
      @Nullable String countSessionId,
      @Nullable Integer expectedVersion) {}

  record UpdateRequest(String locationId, Body body, @Nullable Integer expectedVersion) {}

  /** 목록 한 쪽. */
  public record Page(List<LocationView> items, @Nullable String nextCursor) {}

  private final LocationStore store;
  private final com.pmart.mfc.location.infrastructure.InventoryClient inventory;
  private final RequestIdempotency idempotency;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;
  private final Clock clock;

  public LocationService(
      com.pmart.mfc.location.infrastructure.InventoryClient inventory,
      LocationStore store,
      RequestIdempotency idempotency,
      EventPublisher events,
      EventEnvelopeCodec codec,
      Clock clock) {
    this.store = store;
    this.inventory = inventory;
    this.idempotency = idempotency;
    this.events = events;
    this.codec = codec;
    this.clock = clock;
  }

  /** 거리표 결과(0.7.0). */
  public record DistanceTable(
      List<String> codes, double[][] meters, List<String> missing, List<LocationView> views) {}

  /** 칸 사이 걷는 거리표(내부 경로 — 서비스가 부른다). 같은 코드는 한 번만, 요청 순서 유지. */
  public DistanceTable distances(String mfcId, List<String> codes) {
    if (codes.size() > 300) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "locationCodes 는 최대 300개입니다.");
    }
    List<String> uniq = new java.util.ArrayList<>(new java.util.LinkedHashSet<>(codes));
    java.util.Map<String, LocationView> found = new java.util.HashMap<>();
    for (LocationView v : store.byCodes(mfcId, uniq)) {
      found.put(v.locationCode(), v);
    }
    List<String> ok = new java.util.ArrayList<>();
    List<LocationView> views = new java.util.ArrayList<>();
    List<Double> xs = new java.util.ArrayList<>();
    List<Double> zs = new java.util.ArrayList<>();
    List<String> missing = new java.util.ArrayList<>();
    for (String c : uniq) {
      LocationView v = found.get(c);
      if (v == null || v.posXM() == null || v.posZM() == null) {
        missing.add(c);
        continue;
      }
      ok.add(c);
      views.add(v);
      xs.add(v.posXM());
      zs.add(v.posZM());
    }
    return new DistanceTable(
        ok, com.pmart.mfc.location.domain.Distances.manhattan(xs, zs), missing, views);
  }

  // ---------- 쓰기 ----------

  public LocationView create(String requestId, String sourceSystem, Body b) {
    String operator = requireOperator(b.mfcId());
    return idempotency
        .execute(
            new RequestKey(sourceSystem, requestId, "createLocation", operator),
            b,
            LocationView.class,
            () -> {
              // 위치 코드 문법(Q-03 결정 2026-11-07, ADR-0042): 새 등록은 계층형 고정 문법만
              LocationCodes.Checked checked =
                  LocationCodes.check(
                      b.locationCode(), b.zone(), b.temperatureZone(), b.areaCode());
              checked.problem().ifPresent(LocationService::invalidCode);
              Instant at = clock.instant();
              LocationView v =
                  new LocationView(
                      "LOC-" + UUID.randomUUID(),
                      b.mfcId(),
                      b.locationCode(),
                      checked.areaCode(),
                      b.zone(),
                      b.temperatureZone(),
                      State.ACTIVE.name(),
                      null,
                      b.widthMm(),
                      b.depthMm(),
                      b.heightMm(),
                      b.slotCount(),
                      b.maxWeightG(),
                      b.pickSequence(),
                      b.highTurnover(),
                      b.categoryId(),
                      b.posXM(),
                      b.posZM(),
                      1,
                      at);
              if (!store.insert(v, operator, at)) {
                throw new PmartException(
                    LOCATION_CODE_DUPLICATE,
                    HttpStatus.CONFLICT,
                    "센터 " + b.mfcId() + " 에 위치 코드 " + b.locationCode() + " 가 이미 있습니다.",
                    false,
                    List.of());
              }
              publish(v, null, "REGISTERED", operator);
              log.info(
                  "location {} {} registered at {}", v.locationId(), v.locationCode(), v.mfcId());
              return v;
            })
        .value();
  }

  public LocationView update(
      String requestId,
      String sourceSystem,
      String locationId,
      Body b,
      @Nullable Integer expectedVersion) {
    String operator = requireOperator(b.mfcId());
    return idempotency
        .execute(
            new RequestKey(sourceSystem, requestId, "updateLocation", operator),
            new UpdateRequest(locationId, b, expectedVersion),
            LocationView.class,
            () -> {
              LocationView cur = lockOrThrow(locationId);
              if (!cur.mfcId().equals(b.mfcId()) || !cur.locationCode().equals(b.locationCode())) {
                throw new PmartException(
                    ErrorCode.VALIDATION_FAILED, "위치의 센터·코드는 바꿀 수 없습니다(새 위치로 등록).");
              }
              if (State.valueOf(cur.state()) == State.RETIRED) {
                throw PmartException.invalidState("폐지된 위치는 바꿀 수 없습니다: " + locationId);
              }
              checkVersion(cur, expectedVersion);
              // 새 문법 코드는 온도대·구역·areaCode 가 코드와 어긋나게 바뀌지 않게. 옛 자유 형식 코드는 검사하지 않는다
              String areaCode = b.areaCode();
              if (LocationCodes.followsGrammar(cur.locationCode())) {
                LocationCodes.Checked checked =
                    LocationCodes.check(
                        cur.locationCode(), b.zone(), b.temperatureZone(), b.areaCode());
                checked.problem().ifPresent(LocationService::invalidCode);
                areaCode = checked.areaCode();
              }
              Instant at = clock.instant();
              LocationView v =
                  new LocationView(
                      cur.locationId(),
                      cur.mfcId(),
                      cur.locationCode(),
                      areaCode,
                      b.zone(),
                      b.temperatureZone(),
                      cur.state(),
                      cur.stateReason(),
                      b.widthMm(),
                      b.depthMm(),
                      b.heightMm(),
                      b.slotCount(),
                      b.maxWeightG(),
                      b.pickSequence(),
                      b.highTurnover(),
                      b.categoryId(),
                      b.posXM(),
                      b.posZM(),
                      cur.version() + 1,
                      at);
              store.updateAttributes(v);
              publish(v, cur.state(), "ATTRIBUTES_CHANGED", operator);
              return v;
            })
        .value();
  }

  private static void invalidCode(String why) {
    throw new PmartException(
        LocationCodes.LOCATION_CODE_INVALID, HttpStatus.BAD_REQUEST, why, false, List.of());
  }

  public CommandResponse changeState(
      String requestId,
      String sourceSystem,
      String locationId,
      String targetState,
      String reason,
      @Nullable String countSessionId,
      @Nullable Integer expectedVersion) {
    LocationView peek =
        store
            .find(locationId)
            .orElseThrow(
                () -> PmartException.notFound(LOCATION_NOT_FOUND, "위치가 없습니다: " + locationId));
    String operator = requireOperator(peek.mfcId());
    return idempotency
        .execute(
            new RequestKey(sourceSystem, requestId, "changeLocationState", operator),
            new StateRequest(locationId, targetState, reason, countSessionId, expectedVersion),
            CommandResponse.class,
            () -> {
              LocationView cur = lockOrThrow(locationId);
              Instant at = clock.instant();
              State from = State.valueOf(cur.state());
              State to = State.valueOf(targetState);
              Transition t = LocationRules.change(from, to);
              if (t == Transition.INVALID) {
                throw PmartException.invalidState(
                    "위치 상태 "
                        + from
                        + " → "
                        + to
                        + " 는 할 수 없습니다"
                        + (to == State.RETIRED ? "(폐지는 DELETE)." : "(ACTIVE 를 거친다)."));
              }
              if (t == Transition.ALREADY) {
                return new CommandResponse(
                    CommandStatus.APPLIED,
                    requestId,
                    locationId,
                    0,
                    cur.version(),
                    false,
                    null,
                    at);
              }
              checkVersion(cur, expectedVersion);
              int version = cur.version() + 1;
              store.updateState(locationId, to.name(), reason, version, at);
              store.insertStateChange(
                  locationId,
                  requestId,
                  sourceSystem,
                  from.name(),
                  to.name(),
                  version,
                  reason,
                  countSessionId,
                  operator,
                  at);
              LocationView v = store.find(locationId).orElseThrow();
              publish(v, from.name(), reason, operator);
              log.info("location {} {} → {} ({})", locationId, from, to, reason);
              return new CommandResponse(
                  CommandStatus.APPLIED, requestId, locationId, 1, version, false, null, at);
            })
        .value();
  }

  /**
   * 위치 폐지(RETIRED, 되돌리지 않음). 먼저 CLOSED 로 닫아 새 적치·수령을 막은 뒤에만 — mfc-inventory 에 그 위치 재고 단위가 하나도 없어야
   * 한다(조회 실패면 폐지하지 않음 503). 이미 RETIRED 면 지금 상태.
   */
  public CommandResponse retire(
      String requestId, String sourceSystem, String locationId, @Nullable Integer expectedVersion) {
    LocationView peek =
        store
            .find(locationId)
            .orElseThrow(
                () -> PmartException.notFound(LOCATION_NOT_FOUND, "위치가 없습니다: " + locationId));
    String operator = requireOperator(peek.mfcId());
    if (State.valueOf(peek.state()) == State.CLOSED
        && inventory.hasStockAt(peek.mfcId(), peek.locationCode())) {
      throw PmartException.invalidState(peek.locationCode() + " 에 재고가 남아 있습니다 — 옮기거나 정리한 뒤 폐지합니다.");
    }
    return idempotency
        .execute(
            new RequestKey(sourceSystem, requestId, "retireLocation", operator),
            new StateRequest(locationId, "RETIRED", "RETIRE", null, expectedVersion),
            CommandResponse.class,
            () -> {
              LocationView cur = lockOrThrow(locationId);
              Instant at = clock.instant();
              State from = State.valueOf(cur.state());
              if (from == State.RETIRED) {
                return new CommandResponse(
                    CommandStatus.APPLIED,
                    requestId,
                    locationId,
                    0,
                    cur.version(),
                    false,
                    null,
                    at);
              }
              if (from != State.CLOSED) {
                throw PmartException.invalidState(
                    "폐지는 CLOSED 위치만 합니다(지금 " + from + ") — 먼저 닫아 새 적치를 막는다.");
              }
              checkVersion(cur, expectedVersion);
              int version = cur.version() + 1;
              store.updateState(locationId, State.RETIRED.name(), "RETIRE", version, at);
              store.insertStateChange(
                  locationId,
                  requestId,
                  sourceSystem,
                  from.name(),
                  State.RETIRED.name(),
                  version,
                  "RETIRE",
                  null,
                  operator,
                  at);
              publish(store.find(locationId).orElseThrow(), from.name(), "RETIRE", operator);
              log.info("location {} retired by {}", locationId, operator);
              return new CommandResponse(
                  CommandStatus.APPLIED, requestId, locationId, 1, version, false, null, at);
            })
        .value();
  }

  /** 지금 상태를 같은 버전으로 다시 알린다(상태 변화 없음). */
  public CommandResponse republish(String requestId, String sourceSystem, @Nullable String mfcId) {
    return idempotency
        .execute(
            new RequestKey(sourceSystem, requestId, "republishLocations", actor()),
            mfcId == null ? "ALL" : mfcId,
            CommandResponse.class,
            () -> {
              List<LocationView> rows = store.all(mfcId, REPUBLISH_MAX);
              for (LocationView v : rows) {
                publish(v, null, "REPUBLISH", null);
              }
              log.info("republished {} locations ({})", rows.size(), mfcId == null ? "all" : mfcId);
              return new CommandResponse(
                  CommandStatus.APPLIED,
                  requestId,
                  mfcId,
                  rows.size(),
                  null,
                  false,
                  null,
                  clock.instant());
            })
        .value();
  }

  // ---------- 읽기 ----------

  @Transactional(readOnly = true)
  public LocationView get(String locationId) {
    LocationView v =
        store
            .find(locationId)
            .orElseThrow(
                () -> PmartException.notFound(LOCATION_NOT_FOUND, "위치가 없습니다: " + locationId));
    requireReader(v.mfcId());
    return v;
  }

  @Transactional(readOnly = true)
  public Page list(
      String mfcId,
      @Nullable String zone,
      @Nullable String temperatureZone,
      @Nullable String state,
      int size,
      @Nullable String cursor) {
    requireReader(mfcId);
    List<LocationView> rows = store.list(mfcId, zone, temperatureZone, state, cursor, size + 1);
    String next = null;
    if (rows.size() > size) {
      rows = rows.subList(0, size);
      next = rows.get(size - 1).locationCode();
    }
    return new Page(rows, next);
  }

  /** 적치 후보: 온도대가 맞고 사용 중인 선반·피킹 위치. 공간 잔여 V 는 mfc-inventory 정본이라 여기서는 모른다(null). */
  @Transactional(readOnly = true)
  public List<LocationView> putawayCandidates(String mfcId, @Nullable String temperatureZone) {
    requireReader(mfcId);
    if (temperatureZone == null) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED, "temperatureZone 이 필요합니다(위치 서비스는 SKU 보관 유형을 모른다).");
    }
    return store.putawayCandidates(mfcId, temperatureZone, 50);
  }

  @Transactional(readOnly = true)
  public StateChangeRow stateChange(String locationId, String requestId) {
    requireReader(get(locationId).mfcId());
    return store
        .stateChange(locationId, requestId)
        .orElseThrow(
            () -> PmartException.notFound("REQUEST_NOT_FOUND", "상태 변경 요청이 없습니다: " + requestId));
  }

  // ---------- 내부 ----------

  private void publish(
      LocationView v, @Nullable String previousState, String reason, @Nullable String operator) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("locationId", v.locationId());
    payload.put("mfcId", v.mfcId());
    payload.put("locationCode", v.locationCode());
    payload.put("areaCode", v.areaCode());
    payload.put("zone", v.zone());
    payload.put("temperatureZone", v.temperatureZone());
    payload.put("state", v.state());
    if (previousState != null) {
      payload.put("previousState", previousState);
    }
    payload.put("reason", reason);
    payload.put("version", v.version());
    payload.put("pickSequence", v.pickSequence());
    payload.put("highTurnover", v.highTurnover());
    payload.put("categoryId", v.categoryId());
    payload.put("widthMm", v.widthMm());
    payload.put("depthMm", v.depthMm());
    payload.put("heightMm", v.heightMm());
    payload.put("maxWeightG", v.maxWeightG());
    payload.put("changedAt", v.updatedAt().toString());
    EventEnvelope.Actor actor =
        operator == null
            ? new EventEnvelope.Actor(EventEnvelope.ActorType.SYSTEM, events.sourceSystem())
            : new EventEnvelope.Actor(EventEnvelope.ActorType.OPERATOR, operator);
    events.publish(
        codec.create(
            events.sourceSystem(),
            "MfcLocationStateChanged",
            1,
            "Location",
            v.locationId(),
            v.version(),
            actor,
            EventEnvelope.EventSource.REAL,
            payload),
        TOPIC);
  }

  private LocationView lockOrThrow(String locationId) {
    return store
        .lock(locationId)
        .orElseThrow(() -> PmartException.notFound(LOCATION_NOT_FOUND, "위치가 없습니다: " + locationId));
  }

  private static void checkVersion(LocationView cur, @Nullable Integer expectedVersion) {
    if (expectedVersion != null && expectedVersion != cur.version()) {
      throw PmartException.versionConflict(
          "위치가 이미 바뀌었습니다(기대 버전 " + expectedVersion + ", 현재 " + cur.version() + ").");
    }
  }

  static final String SUPERVISOR_ROLE = "MFC_SUPERVISOR";

  /**
   * 칸 등록·수정·닫기/열기·폐지 — 그 센터의 MFC_SUPERVISOR 만(MB-3, 2026-11-18 PC 3 결정: 칸 구조를 바꾸는 관리 동작, PDA 는 부르지
   * 않음). 조회는 {@link #requireReader} 그대로.
   */
  static String requireOperator(String mfcId) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "운영자만 할 수 있습니다."));
    if (!p.canAccessCenter(mfcId)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "이 센터에 대한 권한이 없습니다.");
    }
    if (!p.hasRole(SUPERVISOR_ROLE)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "이 센터의 MFC_SUPERVISOR 만 칸을 바꿀 수 있습니다.");
    }
    return p.subject();
  }

  /** 그 센터 운영자 또는 서비스 토큰. */
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

  private static String actor() {
    return PmartPrincipal.current()
        .map(PmartPrincipal::subject)
        .orElse(RequestIdempotency.ANONYMOUS);
  }
}
