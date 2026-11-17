package com.pmart.mfc.fulfillment.application;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.fulfillment.infrastructure.PendingListStore;
import com.pmart.mfc.fulfillment.infrastructure.PendingListStore.FailedStop;
import com.pmart.mfc.fulfillment.infrastructure.PendingListStore.RejectedHandover;
import com.pmart.mfc.fulfillment.infrastructure.PendingListStore.Retry;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * 백오피스 처리 대기(BO-12, 요청 F-4, 0.9.0) — 실패로 멈춘 집품 뒤 중지·재고가 거절한 인계. OPERATOR_ADMIN(모든 센터) 만. 읽기만 한다 —
 * 다시 시도는 {@link WorkStopService#retry(String, ManualRetry)}·{@link HandoverService#retry(String,
 * ManualRetry)}.
 */
@Service
public class PendingListService {

  static final String ADMIN_ROLE = "OPERATOR_ADMIN";

  public record Page<T>(
      List<T> items, Map<String, Retry> lastRetries, @Nullable String nextCursor) {}

  private final PendingListStore store;

  public PendingListService(PendingListStore store) {
    this.store = store;
  }

  public Page<FailedStop> failedStops(
      boolean failed, @Nullable String mfcId, int size, @Nullable String cursor) {
    requireAdmin();
    if (!failed) {
      throw unsupported("failed=true 만 받습니다.");
    }
    int limit = clamp(size);
    List<FailedStop> items = store.failedStops(mfcId, blankToNull(cursor), limit);
    return new Page<>(
        items,
        store.latestRetries(
            PendingListStore.STOP_REQUEST, items.stream().map(FailedStop::stopRequestId).toList()),
        items.size() == limit ? items.getLast().stopRequestId() : null);
  }

  public Page<RejectedHandover> rejectedHandovers(
      String status, @Nullable String mfcId, int size, @Nullable String cursor) {
    requireAdmin();
    if (!"REJECTED".equals(status)) {
      throw unsupported("status=REJECTED 만 받습니다.");
    }
    int limit = clamp(size);
    List<RejectedHandover> items = store.rejectedHandovers(mfcId, blankToNull(cursor), limit);
    return new Page<>(
        items,
        store.latestRetries(
            PendingListStore.HANDOVER, items.stream().map(RejectedHandover::handoverId).toList()),
        items.size() == limit ? items.getLast().handoverId() : null);
  }

  private static void requireAdmin() {
    PmartPrincipal p =
        PmartPrincipal.current()
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "토큰이 필요합니다."));
    if (p.type() != PmartPrincipal.PrincipalType.OPERATOR || !p.hasRole(ADMIN_ROLE)) {
      throw new PmartException(ErrorCode.FORBIDDEN, ADMIN_ROLE + " 만 볼 수 있습니다.");
    }
  }

  private static PmartException unsupported(String message) {
    return new PmartException(ErrorCode.VALIDATION_FAILED, message);
  }

  private static int clamp(int size) {
    return Math.max(1, Math.min(size, 200));
  }

  private static @Nullable String blankToNull(@Nullable String s) {
    return s == null || s.isBlank() ? null : s;
  }
}
