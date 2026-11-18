package com.pmart.mfc.quality.application;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.quality.infrastructure.QualityListStore;
import com.pmart.mfc.quality.infrastructure.QualityListStore.Kind;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 센터 관리자 웹 품질 목록(요청 MB-1, 2026-11-18 — mfc-backoffice "지금은 있는지조차 모른다"). 그 센터 운영자, 상태를 비우면 끝나지 않은 것,
 * 오래된 순. 항목 응답은 컨트롤러가 단건 조회와 같은 변환으로.
 */
@Service
public class QualityLists {

  static final Map<Kind, List<String>> OPEN =
      Map.of(
          Kind.DECISION, List.of("REQUESTED", "SUBMITTED", "UNKNOWN_RESULT"),
          Kind.DISPOSAL, List.of("REQUESTED", "APPROVED", "PARTIALLY_EXECUTED"),
          Kind.INSPECTION, List.of("SUBMITTED", "UNKNOWN_RESULT"));

  static final Map<Kind, Set<String>> ALL =
      Map.of(
          Kind.DECISION,
          Set.of("REQUESTED", "SUBMITTED", "APPLIED", "UNKNOWN_RESULT", "REJECTED", "DECLINED"),
          Kind.DISPOSAL,
          Set.of("REQUESTED", "APPROVED", "PARTIALLY_EXECUTED", "COMPLETED", "REJECTED"),
          Kind.INSPECTION,
          Set.of("SUBMITTED", "APPLIED", "REJECTED", "UNKNOWN_RESULT"));

  private final QualityListStore store;

  public QualityLists(QualityListStore store) {
    this.store = store;
  }

  /** ID 목록(limit 개까지 — 다음 쪽 판단은 호출자가 limit + 1 로). */
  @Transactional(readOnly = true)
  public List<String> ids(
      Kind kind, String mfcId, @Nullable List<String> states, int limit, @Nullable String cursor) {
    Actors.reader(mfcId);
    List<String> s = states == null || states.isEmpty() ? OPEN.get(kind) : states;
    for (String x : s) {
      if (!ALL.get(kind).contains(x)) {
        throw new PmartException(ErrorCode.VALIDATION_FAILED, "모르는 상태: " + x);
      }
    }
    return store.ids(kind, mfcId, s, cursor, Math.max(1, Math.min(limit, 201)));
  }
}
