package com.pmart.mfc.capacity.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.capacity.infrastructure.StaffContactStore;
import com.pmart.mfc.capacity.infrastructure.StaffContactStore.Contact;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 센터 직원 연락처(0.21.0, 사용자 결정 2026-11-18 — mfc-backoffice "내 정보"): 시스템이 쓰지 않는 연락 수단 표시용. 본인이 등록·변경(그
 * 센터 운영자), 목록은 모든 운영자·라이더. 값은 로그에 남기지 않는다.
 */
@Service
public class StaffContactService {

  private static final Logger log = LoggerFactory.getLogger(StaffContactService.class);
  private static final Pattern PHONE = Pattern.compile("^[0-9-]{9,20}$");
  private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]{2,}$");

  private final StaffContactStore store;
  private final RequestIdempotency idempotency;
  private final Clock clock;

  public StaffContactService(StaffContactStore store, RequestIdempotency idempotency, Clock clock) {
    this.store = store;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<Contact> list(String mfcId) {
    PmartPrincipal p = PmartPrincipal.current().orElse(null);
    if (p == null
        || (p.type() != PmartPrincipal.PrincipalType.OPERATOR
            && p.type() != PmartPrincipal.PrincipalType.RIDER)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "직원·라이더만 볼 수 있습니다.");
    }
    return store.list(mfcId);
  }

  @Transactional(readOnly = true)
  public Contact mine(String mfcId) {
    String me = centerOperator(mfcId);
    return store.find(mfcId, me, false).orElse(new Contact(mfcId, me, null, null, null, 0, null));
  }

  public Contact saveMine(
      String requestId,
      String sourceSystem,
      String mfcId,
      @Nullable Integer expectedVersion,
      @Nullable String name,
      @Nullable String phone,
      @Nullable String email) {
    String me = centerOperator(mfcId);
    String n = blank(name);
    String ph = blank(phone);
    String em = blank(email);
    List<PmartException.Detail> bad = new ArrayList<>();
    if (ph != null && (!PHONE.matcher(ph).matches() || digits(ph) < 9 || digits(ph) > 11)) {
      bad.add(new PmartException.Detail("phone", "숫자와 - 만, 9~11자리"));
    }
    if (em != null && !EMAIL.matcher(em).matches()) {
      bad.add(new PmartException.Detail("email", "이메일 형식이 아닙니다"));
    }
    if (n != null && n.length() > 50) {
      bad.add(new PmartException.Detail("name", "50자 이하"));
    }
    if (!bad.isEmpty()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "연락처 형식이 맞지 않습니다.", bad);
    }
    idempotency.execute(
        new RequestKey(sourceSystem, requestId, "putMyStaffContact", me),
        List.of(
            mfcId,
            String.valueOf(expectedVersion),
            String.valueOf(n),
            String.valueOf(ph),
            String.valueOf(em)),
        Boolean.class,
        () -> {
          var cur = store.find(mfcId, me, true);
          int v = cur.map(Contact::version).orElse(0);
          if (expectedVersion != null && expectedVersion != v) {
            throw new PmartException(
                ErrorCode.VERSION_CONFLICT, "다른 곳에서 먼저 바뀌었습니다(지금 버전 " + v + ").");
          }
          store.save(mfcId, me, n, ph, em, v + 1, clock.instant());
          log.info("센터 {} 직원 {} 연락처 변경(버전 {})", mfcId, me, v + 1); // 값은 남기지 않는다
          return true;
        });
    return store.find(mfcId, me, false).orElseThrow();
  }

  private static String centerOperator(String mfcId) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "직원만 할 수 있습니다."));
    if (!p.canAccessCenter(mfcId)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "그 센터 직원만 자기 연락처를 둘 수 있습니다.");
    }
    return p.subject();
  }

  private static @Nullable String blank(@Nullable String v) {
    return v == null || v.isBlank() ? null : v.trim();
  }

  private static int digits(String v) {
    return (int) v.chars().filter(Character::isDigit).count();
  }
}
