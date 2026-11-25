package com.pmart.mfc.inventory.application;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.inventory.infrastructure.StockLedgerStore;
import com.pmart.mfc.inventory.infrastructure.StockLedgerStore.CommitRow;
import com.pmart.mfc.inventory.infrastructure.StockLedgerStore.Filter;
import com.pmart.mfc.inventory.infrastructure.StockLedgerStore.Totals;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 재고 이력(0.48.0, 사용자 "재고 이력 조회 만들어") — 그 센터 운영자, SKU 하나의 확정 단위 최신순. */
@Service
public class StockLedgerService {

  public record Page(
      @Nullable String productName,
      Totals current,
      List<CommitRow> items,
      @Nullable String nextCursor,
      Instant asOf) {}

  private final StockLedgerStore store;
  private final Clock clock;

  public StockLedgerService(StockLedgerStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  /** 한 트랜잭션(읽기)에서 지금 합계와 원장을 같이 읽어 after 가 어긋나지 않게 한다. */
  @Transactional(
      readOnly = true,
      isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  public Page ledger(
      String mfcId,
      String sku,
      @Nullable String lotId,
      @Nullable String locationCode,
      @Nullable List<String> causes,
      @Nullable Instant from,
      @Nullable Instant to,
      int size,
      @Nullable String cursor,
      @Nullable String orderId) {
    Operator.requireCenter(mfcId);
    if (from != null && to != null && !from.isBefore(to)) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "from 은 to 보다 앞이어야 합니다.");
    }
    Long before = cursor == null || cursor.isBlank() ? null : decode(cursor);
    Totals now = store.current(mfcId, sku);
    List<CommitRow> rows =
        store.commits(
            mfcId,
            sku,
            new Filter(lotId, locationCode, causes, from, to, before, orderId),
            now,
            size + 1);
    boolean more = rows.size() > size;
    List<CommitRow> page = more ? rows.subList(0, size) : rows;
    String next = more ? encode(page.get(page.size() - 1).orderKey()) : null;
    return new Page(store.productName(sku), now, page, next, clock.instant());
  }

  private static String encode(long k) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(("L" + k).getBytes(StandardCharsets.UTF_8));
  }

  private static long decode(String cursor) {
    try {
      String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      if (!raw.startsWith("L")) {
        throw new IllegalArgumentException();
      }
      return Long.parseLong(raw.substring(1));
    } catch (IllegalArgumentException e) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "cursor 가 올바르지 않습니다.");
    }
  }
}
