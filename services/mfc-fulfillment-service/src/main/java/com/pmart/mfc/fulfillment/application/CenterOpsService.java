package com.pmart.mfc.fulfillment.application;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.fulfillment.infrastructure.CenterOpsStore;
import com.pmart.mfc.fulfillment.infrastructure.CenterOpsStore.PromiseSummary;
import com.pmart.mfc.fulfillment.infrastructure.CenterOpsStore.ShortageRow;
import com.pmart.mfc.fulfillment.infrastructure.InventoryClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 센터 운영판(2026-11-19, MFC 백오피스 OE-1·OE-2) — 결품 보고 목록·출하 약속 요약. 그 센터 운영자(작업자·센터 관리자),
 * OPERATOR_ADMIN·서비스는 모든 센터. 출하 임박 기본 15분(사용자 2026-11-19 "임박 기준은 15분").
 */
@Service
public class CenterOpsService {

  public static final int DEFAULT_DUE_MINUTES = 15;
  static final List<String> REASONS = List.of("SHORT", "DAMAGED", "SPOILED", "BLOCKED");
  private static final Logger log = LoggerFactory.getLogger(CenterOpsService.class);

  public record ShortageItem(ShortageRow row, @Nullable String productName) {}

  public record ShortagePage(List<ShortageItem> items, @Nullable String nextCursor, int total) {}

  private final CenterOpsStore store;
  private final InventoryClient inventory;
  private final Clock clock;

  public CenterOpsService(CenterOpsStore store, InventoryClient inventory, Clock clock) {
    this.store = store;
    this.inventory = inventory;
    this.clock = clock;
  }

  static void requireCenter(String mfcId) {
    if (mfcId == null || mfcId.isBlank()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "mfcId 가 필요합니다.");
    }
    PmartPrincipal p =
        PmartPrincipal.current()
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "토큰이 필요합니다."));
    if (p.isService()
        || (p.type() == PmartPrincipal.PrincipalType.OPERATOR && p.hasRole("OPERATOR_ADMIN"))) {
      return;
    }
    if (p.type() != PmartPrincipal.PrincipalType.OPERATOR || !p.canAccessCenter(mfcId)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "이 센터에 대한 권한이 없습니다.");
    }
  }

  @Transactional(readOnly = true)
  public ShortagePage shortages(
      String mfcId,
      @Nullable List<String> reasons,
      @Nullable Instant from,
      @Nullable Instant to,
      int size,
      @Nullable String cursor) {
    requireCenter(mfcId);
    if (reasons != null && !REASONS.containsAll(reasons)) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED, "reason 은 SHORT·DAMAGED·SPOILED·BLOCKED 입니다.");
    }
    if (from != null && to != null && !from.isBefore(to)) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "from 은 to 보다 앞이어야 합니다.");
    }
    int limit = Math.max(1, Math.min(size, 200));
    Long after;
    try {
      after = cursor == null || cursor.isBlank() ? null : Long.parseLong(cursor);
    } catch (NumberFormatException ex) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "cursor 가 맞지 않습니다.");
    }
    List<ShortageRow> rows = store.shortages(mfcId, reasons, from, to, after, limit + 1);
    boolean more = rows.size() > limit;
    List<ShortageRow> page = more ? rows.subList(0, limit) : rows;
    Map<String, String> names = names(page);
    return new ShortagePage(
        page.stream()
            .map(r -> new ShortageItem(r, r.sku() == null ? null : names.get(r.sku())))
            .toList(),
        more ? String.valueOf(page.get(page.size() - 1).shortageId()) : null,
        store.countShortages(mfcId, reasons, from, to));
  }

  /** 상품명은 표시용 — 조회 실패면 빈 값(목록은 그대로). */
  private Map<String, String> names(List<ShortageRow> rows) {
    List<String> skus =
        rows.stream().map(ShortageRow::sku).filter(java.util.Objects::nonNull).distinct().toList();
    if (skus.isEmpty()) {
      return Map.of();
    }
    try {
      return inventory.productNames(skus);
    } catch (RuntimeException ex) {
      log.warn("결품 목록 상품명 조회 실패(목록은 그대로): {}", ex.getMessage());
      return Map.of();
    }
  }

  public record Summary(PromiseSummary counts, int dueWithinMinutes, Instant asOf) {}

  @Transactional(readOnly = true)
  public Summary promiseSummary(String mfcId, @Nullable Integer dueWithinMinutes) {
    requireCenter(mfcId);
    int minutes = dueWithinMinutes == null ? DEFAULT_DUE_MINUTES : dueWithinMinutes;
    if (minutes < 1 || minutes > 240) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "dueWithinMinutes 는 1~240 입니다.");
    }
    Instant now = clock.instant();
    return new Summary(
        store.promiseSummary(mfcId, now, now.plus(Duration.ofMinutes(minutes))), minutes, now);
  }
}
