package com.pmart.mfc.inbound.application;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.inbound.infrastructure.InboundListStore;
import com.pmart.mfc.inbound.infrastructure.InboundListStore.Kind;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 센터 관리자 웹 입고 목록(요청 MB-1, 2026-11-18): 초기 보유 수령 대기(오래된 순), 공급 요청·회수품 반입(최근 순). 그 센터 운영자. 항목 응답은
 * 컨트롤러가 단건과 같은 변환으로.
 */
@Service
public class InboundLists {

  /** 비우면 — 초기 보유·공급 요청은 끝나지 않은 것, 회수품은 전부. */
  static final Map<Kind, List<String>> DEFAULT =
      Map.of(
          Kind.INITIAL, List.of("EXPECTED", "PENDING"),
          Kind.SUPPLY, List.of("DRAFT", "SUBMITTED", "ACKNOWLEDGED"),
          Kind.RETURN, List.of());

  static final Map<Kind, Set<String>> ALL =
      Map.of(
          Kind.INITIAL, Set.of("EXPECTED", "PENDING", "APPLIED", "REJECTED"),
          Kind.SUPPLY,
              Set.of("DRAFT", "SUBMITTED", "ACKNOWLEDGED", "WITHDRAWN", "APPROVED", "REJECTED"),
          Kind.RETURN, Set.of("SUBMITTED", "APPLIED", "REJECTED", "UNKNOWN_RESULT"));

  private final InboundListStore store;

  public InboundLists(InboundListStore store) {
    this.store = store;
  }

  @Transactional(readOnly = true)
  public List<String> ids(
      Kind kind,
      String mfcId,
      @Nullable List<String> states,
      @Nullable Instant from,
      @Nullable Instant to,
      int limit,
      @Nullable String cursor) {
    return ids(kind, mfcId, states, null, from, to, limit, cursor);
  }

  /**
   * (0.15.0, PDA BR-52 R12) 회수품 검수 대기 — inspectionState 를 주면 오래된 순, state 를 비우면 재고 반영(APPLIED)만(검수할
   * 수 있는 것은 반영된 반입뿐).
   */
  @Transactional(readOnly = true)
  public List<String> ids(
      Kind kind,
      String mfcId,
      @Nullable List<String> states,
      @Nullable String inspectionState,
      @Nullable Instant from,
      @Nullable Instant to,
      int limit,
      @Nullable String cursor) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "토큰이 필요합니다."));
    if (!p.isService()
        && (p.type() != PmartPrincipal.PrincipalType.OPERATOR || !p.canAccessCenter(mfcId))) {
      throw new PmartException(ErrorCode.FORBIDDEN, "이 센터에 대한 권한이 없습니다.");
    }
    if (inspectionState != null
        && (kind != Kind.RETURN || !Set.of("PENDING", "INSPECTED").contains(inspectionState))) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "모르는 검수 상태: " + inspectionState);
    }
    List<String> s =
        states == null || states.isEmpty()
            ? (inspectionState != null ? List.of("APPLIED") : DEFAULT.get(kind))
            : states;
    for (String x : s) {
      if (!ALL.get(kind).contains(x)) {
        throw new PmartException(ErrorCode.VALIDATION_FAILED, "모르는 상태: " + x);
      }
    }
    if (from != null && to != null && !from.isBefore(to)) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "from 은 to 보다 앞이어야 합니다.");
    }
    return store.ids(
        kind, mfcId, s, inspectionState, from, to, cursor, Math.max(1, Math.min(limit, 201)));
  }
}
