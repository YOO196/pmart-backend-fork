package com.pmart.mfc.inventory.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.Outcome;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.inventory.domain.SkuHandlingRules;
import com.pmart.mfc.inventory.domain.SkuHandlingRules.Current;
import com.pmart.mfc.inventory.domain.SkuProjection;
import com.pmart.mfc.inventory.infrastructure.SkuHandlingStore;
import com.pmart.mfc.inventory.infrastructure.SkuHandlingStore.HandlingRow;
import com.pmart.mfc.inventory.infrastructure.SkuHandlingStore.ListedRow;
import com.pmart.mfc.inventory.infrastructure.SkuHandlingStore.RepublishRow;
import com.pmart.mfc.inventory.infrastructure.SkuProjectionStore;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 센터 SKU 취급 ON/OFF(사용자 결정 2026-10-30: 기본 OFF, 센터 운영자가 켬). 레코드 없음 = OFF. 변경은 {@link
 * RequestIdempotency} 안에서 하고, 바뀔 때만 이력을 남기고 같은 트랜잭션에서 SkuHandlingChanged 를 발행한다(토픽
 * mfc.sku-handling.v1 — 검색 색인의 센터 취급 목록, 2026-11-05).
 */
@Service
public class SkuHandlingService {

  public static final int MAX_BATCH = 500;
  static final String OP_CHANGE = "skuHandling:change";
  static final String OP_BATCH = "skuHandling:batch";

  /** 취급 상태 보기(레코드 없음 = OFF, version 0). */
  public record HandlingView(
      String sku,
      boolean enabled,
      int version,
      @Nullable String skuState,
      @Nullable String productId,
      @Nullable String changedBy,
      @Nullable String reason,
      @Nullable Instant updatedAt,
      @Nullable String saleState,
      @Nullable String offReason,
      @Nullable Instant introducedAt) {}

  public record ChangeResult(
      String requestId, boolean changed, Instant occurredAt, HandlingView handling) {}

  public record BatchItem(
      String sku, boolean enabled, @Nullable String reason, @Nullable Integer expectedVersion) {}

  public record BatchResult(
      String requestId, int changedCount, Instant occurredAt, List<HandlingView> items) {}

  public record Page(List<HandlingView> items, @Nullable String nextCursor, int totalCount) {}

  /** 멱등 비교용 요청(경로 변수 포함). */
  record ChangeRequest(
      String centerId,
      String sku,
      boolean enabled,
      @Nullable String reason,
      @Nullable Integer expectedVersion) {}

  record BatchRequest(String centerId, @Nullable String reason, List<BatchItem> items) {}

  private final SkuProjectionStore projections;
  private final SkuHandlingStore handlings;
  private final RequestIdempotency idempotency;
  private final EventPublisher events;
  private final Clock clock;

  /** 사건 이름·집합 이름. 집합 SkuHandling → 기본 토픽 mfc.sku-handling.v1(pmart.messaging.domain=mfc). */
  public static final String EVENT = "SkuHandlingChanged";

  static final String AGGREGATE = "SkuHandling";

  /** 재발행 한 번의 최대 건수(넘으면 센터를 나눠 부른다). */
  public static final int MAX_REPUBLISH = 20_000;

  public SkuHandlingService(
      SkuProjectionStore projections,
      SkuHandlingStore handlings,
      RequestIdempotency idempotency,
      EventPublisher events,
      Clock clock) {
    this.projections = projections;
    this.handlings = handlings;
    this.idempotency = idempotency;
    this.events = events;
    this.clock = clock;
  }

  // ---------- 조회 ----------

  @Transactional(readOnly = true)
  public Page list(
      String centerId,
      @Nullable Boolean enabled,
      @Nullable Boolean undecided,
      @Nullable Instant introducedSince,
      @Nullable String cursor,
      int size) {
    Operator.requireCenter(centerId);
    List<ListedRow> rows =
        handlings.list(centerId, enabled, undecided, introducedSince, cursor, size + 1);
    boolean more = rows.size() > size;
    List<HandlingView> items = new ArrayList<>();
    for (ListedRow r : more ? rows.subList(0, size) : rows) {
      items.add(
          new HandlingView(
              r.sku(),
              Boolean.TRUE.equals(r.enabled()),
              r.version() == null ? 0 : r.version(),
              r.skuState(),
              r.productId(),
              r.changedBy(),
              r.reason(),
              r.updatedAt(),
              r.saleState(),
              SkuHandlingRules.offReason(
                  Boolean.TRUE.equals(r.enabled()), r.version() != null, r.saleState()),
              r.introducedAt()));
    }
    return new Page(
        items,
        more ? items.get(items.size() - 1).sku() : null,
        handlings.count(centerId, enabled, undecided, introducedSince));
  }

  /** 가용 조회용 취급 여부(서비스 간). 레코드 없음 = false. */
  @Transactional(readOnly = true)
  public Map<String, Boolean> enabledFlags(String centerId, List<String> skus) {
    Map<String, HandlingRow> rows = handlings.findAll(centerId, skus);
    Map<String, Boolean> out = new LinkedHashMap<>();
    for (String s : skus) {
      HandlingRow r = rows.get(s);
      out.put(s, r != null && r.enabled());
    }
    return out;
  }

  // ---------- 변경 ----------

  public Outcome<ChangeResult> change(
      String requestId,
      Operator op,
      String centerId,
      String sku,
      boolean enable,
      @Nullable String reason,
      @Nullable Integer expectedVersion) {
    return idempotency.execute(
        op.key(requestId, OP_CHANGE),
        new ChangeRequest(centerId, sku, enable, reason, expectedVersion),
        ChangeResult.class,
        () -> {
          Instant at = Instant.now(clock);
          Optional<SkuProjection> p = projections.find(sku);
          Optional<HandlingRow> row = handlings.find(centerId, sku);
          Current current = current(row);
          Optional<String> rejected =
              SkuHandlingRules.rejection(
                  p.map(SkuProjection::state).orElse(null),
                  projections.saleState(sku),
                  current,
                  enable,
                  expectedVersion);
          if (rejected.isPresent()) {
            throw problem(rejected.get(), sku, current, expectedVersion);
          }
          Applied a = apply(requestId, op, centerId, p.get(), row, enable, reason, at);
          return new ChangeResult(requestId, a.changed(), at, a.view());
        });
  }

  public Outcome<BatchResult> changeBatch(
      String requestId,
      Operator op,
      String centerId,
      @Nullable String reason,
      List<BatchItem> items) {
    if (items.isEmpty() || items.size() > MAX_BATCH) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "items 는 1~" + MAX_BATCH + "행이어야 합니다.");
    }
    Set<String> seen = new HashSet<>();
    List<PmartException.Detail> dup = new ArrayList<>();
    for (int i = 0; i < items.size(); i++) {
      if (!seen.add(items.get(i).sku())) {
        dup.add(new PmartException.Detail("items[" + i + "].sku", "DUPLICATE_SKU"));
      }
    }
    if (!dup.isEmpty()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "같은 SKU 가 두 번 이상 있습니다.", dup);
    }
    return idempotency.execute(
        op.key(requestId, OP_BATCH),
        new BatchRequest(centerId, reason, items),
        BatchResult.class,
        () -> {
          Instant at = Instant.now(clock);
          List<String> skus = items.stream().map(BatchItem::sku).toList();
          Map<String, SkuProjection> ps = projections.findAll(skus);
          Map<String, HandlingRow> rows = handlings.findAll(centerId, skus);
          Map<String, String> sales = projections.saleStates(skus);
          // 1) 전 행 검증 — 하나라도 실패하면 아무것도 바꾸지 않는다
          List<PmartException.Detail> problems = new ArrayList<>();
          for (int i = 0; i < items.size(); i++) {
            BatchItem it = items.get(i);
            String field = "items[" + i + "]";
            SkuProjection p = ps.get(it.sku());
            SkuHandlingRules.rejection(
                    p == null ? null : p.state(),
                    sales.get(it.sku()),
                    current(Optional.ofNullable(rows.get(it.sku()))),
                    it.enabled(),
                    it.expectedVersion())
                .ifPresent(code -> problems.add(new PmartException.Detail(field, code)));
          }
          if (!problems.isEmpty()) {
            throw new PmartException(
                "SKU_HANDLING_BATCH_REJECTED",
                HttpStatus.UNPROCESSABLE_CONTENT,
                problems.size() + "행이 규칙에 맞지 않아 전체를 반영하지 않았습니다.",
                false,
                problems);
          }
          // 2) 반영 — 사이에 다른 요청이 바꿨으면 VERSION_CONFLICT 로 전체 롤백
          List<HandlingView> out = new ArrayList<>();
          int changed = 0;
          for (BatchItem it : items) {
            Applied a =
                apply(
                    requestId,
                    op,
                    centerId,
                    ps.get(it.sku()),
                    Optional.ofNullable(rows.get(it.sku())),
                    it.enabled(),
                    it.reason() != null ? it.reason() : reason,
                    at);
            changed += a.changed() ? 1 : 0;
            out.add(a.view());
          }
          return new BatchResult(requestId, changed, at, out);
        });
  }

  private record Applied(boolean changed, HandlingView view) {}

  private Applied apply(
      String requestId,
      Operator op,
      String centerId,
      SkuProjection p,
      Optional<HandlingRow> row,
      boolean enable,
      @Nullable String reason,
      Instant at) {
    Current current = current(row);
    if (!SkuHandlingRules.changes(current, enable)) {
      return new Applied(false, view(p, row));
    }
    boolean ok =
        row.isEmpty()
            ? handlings.insertFirst(centerId, p.sku(), enable, op.id(), reason, at)
            : handlings.update(centerId, p.sku(), enable, op.id(), reason, at, row.get().version());
    if (!ok) {
      throw PmartException.versionConflict(
          "SKU " + p.sku() + " 취급이 방금 다른 요청으로 바뀌었습니다. 다시 조회한 뒤 요청하세요.");
    }
    int newVersion = current.version() + 1;
    handlings.insertHistory(
        centerId, p.sku(), current.enabled(), enable, op.id(), reason, requestId, newVersion, at);
    publish(
        centerId,
        p.sku(),
        p.productId(),
        enable,
        newVersion,
        at,
        false,
        new EventEnvelope.Actor(EventEnvelope.ActorType.OPERATOR, op.id()));
    String sale = projections.saleState(p.sku());
    return new Applied(
        true,
        new HandlingView(
            p.sku(),
            enable,
            newVersion,
            p.state().name(),
            p.productId(),
            op.id(),
            reason,
            at,
            sale,
            SkuHandlingRules.offReason(enable, true, sale),
            null));
  }

  /**
   * 본사 단종(0.27.0, 사용자 결정 2026-11-19 R7): 이 SKU 를 켜 둔 모든 센터를 OFF(이력·SkuHandlingChanged, 주체
   * product-service). 단종인 동안 켜기는 {@link SkuHandlingRules#SKU_DISCONTINUED}. 반환 = 끈 센터 수.
   */
  @Transactional
  public int discontinue(String sku, Instant at) {
    String by = "product-service";
    String reason = "본사 단종";
    var p = projections.find(sku);
    int n = 0;
    for (HandlingRow r : handlings.lockEnabledCenters(sku)) {
      if (!handlings.update(r.centerId(), sku, false, by, reason, at, r.version())) {
        continue;
      }
      int v = r.version() + 1;
      handlings.insertHistory(
          r.centerId(), sku, true, false, by, reason, "discontinued:" + sku + ":" + at, v, at);
      publish(
          r.centerId(),
          sku,
          p.map(SkuProjection::productId).orElse(null),
          false,
          v,
          at,
          false,
          new EventEnvelope.Actor(EventEnvelope.ActorType.SYSTEM, by));
      n++;
    }
    return n;
  }

  /**
   * 지금 취급 상태를 SkuHandlingChanged(republished=true)로 다시 알린다 — 상태는 바꾸지 않는다. 사건 발행 전부터 있던 취급을 검색 색인에
   * 처음 연결하거나 구독 쪽 사본을 복구할 때. 같은 버전은 구독자가 무시하므로 여러 번 불러도 결과가 같다.
   */
  @Transactional
  public int republish(@Nullable String centerId) {
    List<RepublishRow> rows = handlings.forRepublish(centerId, MAX_REPUBLISH + 1);
    if (rows.size() > MAX_REPUBLISH) {
      throw new PmartException(
          ErrorCode.DOMAIN_REJECTED,
          "취급 레코드가 " + MAX_REPUBLISH + "건을 넘습니다. 센터(mfcId)를 나눠 다시 요청하세요.");
    }
    EventEnvelope.Actor actor =
        new EventEnvelope.Actor(EventEnvelope.ActorType.SYSTEM, events.sourceSystem());
    for (RepublishRow r : rows) {
      publish(
          r.centerId(),
          r.sku(),
          r.productId(),
          r.enabled(),
          r.version(),
          r.updatedAt(),
          true,
          actor);
    }
    return rows.size();
  }

  private void publish(
      String centerId,
      String sku,
      @Nullable String productId,
      boolean enabled,
      int version,
      Instant at,
      boolean republished,
      EventEnvelope.Actor actor) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("mfcId", centerId);
    payload.put("sku", sku);
    if (productId != null) {
      payload.put("productId", productId);
    }
    payload.put("handlingEnabled", enabled);
    payload.put("version", version);
    payload.put("changedAt", at.toString());
    if (republished) {
      payload.put("republished", true);
    }
    events.publish(EVENT, 1, AGGREGATE, centerId + ":" + sku, version, actor, payload);
  }

  private static Current current(Optional<HandlingRow> row) {
    return row.map(r -> new Current(r.enabled(), r.version())).orElse(Current.ABSENT);
  }

  private HandlingView view(SkuProjection p, Optional<HandlingRow> row) {
    String sale = projections.saleState(p.sku());
    boolean on = row.map(HandlingRow::enabled).orElse(false);
    return new HandlingView(
        p.sku(),
        row.map(HandlingRow::enabled).orElse(false),
        row.map(HandlingRow::version).orElse(0),
        p.state().name(),
        p.productId(),
        row.map(HandlingRow::changedBy).orElse(null),
        row.map(HandlingRow::reason).orElse(null),
        row.map(HandlingRow::updatedAt).orElse(null),
        sale,
        SkuHandlingRules.offReason(on, row.isPresent(), sale),
        null);
  }

  private static PmartException problem(
      String code, String sku, Current current, @Nullable Integer expectedVersion) {
    return switch (code) {
      case SkuHandlingRules.SKU_NOT_FOUND ->
          PmartException.notFound(code, "SKU " + sku + " 를 아직 모릅니다(상품 승인 이벤트 수신 전이거나 없는 SKU).");
      case SkuHandlingRules.SKU_DISCONTINUED ->
          new PmartException(
              code,
              HttpStatus.CONFLICT,
              "본사 단종 상품 " + sku + " 은 단종인 동안 취급을 켤 수 없습니다.",
              false,
              List.of());
      case SkuHandlingRules.SKU_INACTIVE ->
          new PmartException(
              code,
              HttpStatus.UNPROCESSABLE_CONTENT,
              "비활성 SKU " + sku + " 는 취급을 켤 수 없습니다.",
              false,
              List.of());
      case SkuHandlingRules.VERSION_CONFLICT ->
          PmartException.versionConflict(
              "대상이 이미 바뀌었습니다(기대 버전 "
                  + expectedVersion
                  + ", 현재 "
                  + current.version()
                  + "). 다시 조회한 뒤 요청하세요.");
      default -> new PmartException(ErrorCode.DOMAIN_REJECTED, code);
    };
  }
}
