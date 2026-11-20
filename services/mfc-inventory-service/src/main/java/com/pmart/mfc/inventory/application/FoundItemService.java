package com.pmart.mfc.inventory.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.inventory.domain.CountRules.Unresolved;
import com.pmart.mfc.inventory.domain.FoundItemRules;
import com.pmart.mfc.inventory.infrastructure.FoundItemStore;
import com.pmart.mfc.inventory.infrastructure.FoundItemStore.FoundRow;
import com.pmart.mfc.inventory.infrastructure.StockStore;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실사 발견품 조사(2026-11-20, 계약 0.43.0 — BR-52 R19-6 남은 것). 실사 승인 때 SKU·LOT 을 정하지 못한 줄을 조사 건으로 넘기고(장부는
 * 그대로), 센터 관리자가 결론을 기록한다. 근거 MFC IV-09·IV-11 — {@link FoundItemRules}.
 */
@Service
public class FoundItemService {

  private static final Logger log = LoggerFactory.getLogger(FoundItemService.class);

  static final String SUPERVISOR = "MFC_SUPERVISOR";
  public static final String ALREADY_RESOLVED = "FOUND_ITEM_ALREADY_RESOLVED";

  private final FoundItemStore store;
  private final StockStore stock;
  private final RequestIdempotency idempotency;
  private final Clock clock;

  public FoundItemService(
      FoundItemStore store, StockStore stock, RequestIdempotency idempotency, Clock clock) {
    this.store = store;
    this.stock = stock;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  /**
   * 실사 승인(같은 트랜잭션)에서 — 마지막 계수의 정하지 못한 줄마다 조사 건. 같은 칸·상품 표시·기한의 열린 건이 있으면 새로 만들지 않고 "다시 봄"으로 묶는다(같은
   * 칸을 다시 실사해 같은 물건이 또 나온 경우).
   */
  void recordFromCount(String mfcId, String sessionId, List<Unresolved> lines, Instant now) {
    for (int i = 0; i < lines.size(); i++) {
      Unresolved u = lines.get(i);
      if (store.recorded(sessionId, i)) {
        continue;
      }
      var same = store.lockOpenSame(mfcId, u.locationCode(), u.sku(), u.barcode(), u.expiryDate());
      if (same.isPresent()) {
        store.seenAgain(same.get(), sessionId, u.qty(), u.cause(), now);
        log.info("실사 {} 발견품 — 열린 조사 {} 에 다시 봄", sessionId, same.get());
        continue;
      }
      String id = "CFI-" + UUID.randomUUID();
      store.insert(
          new FoundRow(
              id,
              mfcId,
              sessionId,
              sessionId,
              1,
              u.locationCode(),
              u.sku(),
              u.barcode(),
              u.expiryDate(),
              u.qty(),
              u.cause(),
              "OPEN",
              null,
              null,
              null,
              null,
              null,
              null,
              now,
              now,
              0),
          i);
      log.info("실사 {} 발견품 조사 {} ({} {})", sessionId, id, u.locationCode(), u.cause());
    }
  }

  public record Page(List<FoundRow> items, @Nullable String nextCursor) {}

  /** 목록 — 그 센터 운영자. state 비우면 OPEN. 오래된 순(ID), 커서 = 마지막 ID. */
  @Transactional(readOnly = true)
  public Page list(String mfcId, @Nullable String state, int size, @Nullable String cursor) {
    Operator.requireCenter(mfcId);
    String st = state == null ? "OPEN" : state;
    if (!"OPEN".equals(st) && !"RESOLVED".equals(st)) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "state 는 OPEN·RESOLVED 입니다.");
    }
    if (size < 1 || size > 200) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "size 는 1~200 입니다.");
    }
    List<FoundRow> rows = store.list(mfcId, st, size + 1, cursor);
    boolean more = rows.size() > size;
    List<FoundRow> items = more ? rows.subList(0, size) : rows;
    return new Page(items, more ? items.get(items.size() - 1).foundId() : null);
  }

  @Transactional(readOnly = true)
  public FoundRow get(String mfcId, String foundId) {
    Operator.requireCenter(mfcId);
    return store
        .find(foundId, false)
        .filter(r -> r.mfcId().equals(mfcId))
        .orElseThrow(() -> notFound(foundId));
  }

  /** 결론 기록 — 그 센터 MFC_SUPERVISOR. 장부는 바꾸지 않는다(결론의 참조가 실제 처리). */
  public FoundRow resolve(
      String mfcId,
      String foundId,
      String requestId,
      String sourceSystem,
      String outcome,
      @Nullable String outcomeRef,
      @Nullable String lotId,
      @Nullable String note) {
    String me = Operator.requireCenter(mfcId);
    if (!PmartPrincipal.current().orElseThrow().hasRole(SUPERVISOR)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "이 센터의 MFC_SUPERVISOR 만 발견품 조사를 끝냅니다.");
    }
    String bad = FoundItemRules.rejection(outcome, outcomeRef, lotId, note);
    if (bad != null) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, bad);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("foundId", foundId);
    body.put("outcome", outcome);
    body.put("outcomeRef", String.valueOf(outcomeRef));
    body.put("lotId", String.valueOf(lotId));
    body.put("note", note);
    idempotency.execute(
        new RequestIdempotency.RequestKey(sourceSystem, requestId, "resolveFoundItem", me),
        body,
        String.class,
        () -> {
          FoundRow r =
              store
                  .find(foundId, true)
                  .filter(x -> x.mfcId().equals(mfcId))
                  .orElseThrow(() -> notFound(foundId));
          if (!"OPEN".equals(r.state())) {
            throw new PmartException(
                ALREADY_RESOLVED,
                HttpStatus.CONFLICT,
                "이미 끝난 조사입니다(" + r.outcome() + ").",
                false,
                List.of());
          }
          if (lotId != null) {
            var lot = stock.lots(Set.of(lotId)).get(lotId);
            if (lot == null || (r.sku() != null && !lot.sku().equals(r.sku()))) {
              throw new PmartException(
                  ErrorCode.VALIDATION_FAILED,
                  "lotId "
                      + lotId
                      + " 는 "
                      + (r.sku() == null ? "없는 LOT" : "SKU " + r.sku() + " 의 LOT 이 아닙니다")
                      + ".");
            }
          }
          store.resolve(foundId, outcome, outcomeRef, lotId, note, me, clock.instant());
          log.info("발견품 조사 {} → {} by {}", foundId, outcome, me);
          return foundId;
        });
    return store.find(foundId, false).orElseThrow();
  }

  private static PmartException notFound(String foundId) {
    return PmartException.notFound("FOUND_ITEM_NOT_FOUND", "발견품 조사가 없습니다: " + foundId);
  }
}
