package com.pmart.mfc.quality.application;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.quality.infrastructure.WorkActivityStore;
import com.pmart.mfc.quality.infrastructure.WorkActivityStore.Activity;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 작업자 한 명의 처리 내역(요청 F-7 R3, 2026-11-17) — mfc-capacity "오늘 업무 내역"이 모으는 이 서비스 몫:
 * QUARANTINE·RELEASE·DISPOSE·RETURN_INSPECTION. 내부 호출. 기간 최대 48시간, 최대 {@value #MAX}건.
 */
@Service
public class WorkActivities {

  static final Duration MAX_SPAN = Duration.ofHours(48);
  static final int MAX = 500;

  private final WorkActivityStore store;

  public WorkActivities(WorkActivityStore store) {
    this.store = store;
  }

  @Transactional(readOnly = true)
  public List<Activity> list(String mfcId, String operatorId, Instant from, Instant to) {
    if (!from.isBefore(to) || Duration.between(from, to).compareTo(MAX_SPAN) > 0) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "기간은 from < to, 최대 48시간입니다.");
    }
    return store.list(mfcId, operatorId, from, to, MAX);
  }
}
