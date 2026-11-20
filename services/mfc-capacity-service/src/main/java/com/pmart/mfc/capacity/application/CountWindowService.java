package com.pmart.mfc.capacity.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.capacity.infrastructure.CountWindowStore;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재고 실사 시간대(0.33.0, 사용자 결정 2026-11-20 "아침 7시~8시, 저녁 8시~9시 디폴트이되 변경 가능하게"). 센터마다 바꿀 수 있고(그 센터
 * MFC_SUPERVISOR), 바꾸지 않은 센터는 {@link #DEFAULT_WINDOWS}. 시각은 Asia/Seoul(ADR-0010 — 달력 판정), [시작, 끝),
 * 자정을 넘지 않는다. 시간대 안에서는 다음 작업 받기가 실사 자격·업무가 있는 작업자에게 실사를 먼저 준다(없으면 다른 일 — 사용자 결정 "추천대로").
 */
@Service
public class CountWindowService {

  public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

  /** 센터 기본값(바꾼 적 없는 센터). */
  public static final List<Window> DEFAULT_WINDOWS =
      List.of(
          new Window(LocalTime.of(7, 0), LocalTime.of(8, 0)),
          new Window(LocalTime.of(20, 0), LocalTime.of(21, 0)));

  static final int MAX_WINDOWS = 6;
  private static final Logger log = LoggerFactory.getLogger(CountWindowService.class);

  /** [start, end) 서울 시각. */
  public record Window(LocalTime start, LocalTime end) {
    boolean contains(LocalTime t) {
      return !t.isBefore(start) && t.isBefore(end);
    }

    @Override
    public String toString() {
      return start + "-" + end;
    }
  }

  /** source = DEFAULT(바꾼 적 없음) 또는 CENTER. version 0 = 기본값. */
  public record Setting(
      String mfcId,
      List<Window> windows,
      String source,
      int version,
      @Nullable String updatedBy,
      @Nullable Instant updatedAt,
      boolean activeNow) {}

  private final CountWindowStore store;
  private final RequestIdempotency idempotency;
  private final Clock clock;

  public CountWindowService(CountWindowStore store, RequestIdempotency idempotency, Clock clock) {
    this.store = store;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  /** 그 센터 운영자(작업자 포함 — PDA 표시). */
  @Transactional(readOnly = true)
  public Setting get(String mfcId) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "직원만 볼 수 있습니다."));
    if (!p.canAccessCenter(mfcId)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "이 센터 직원만 볼 수 있습니다.");
    }
    return setting(mfcId);
  }

  /** 그 센터 MFC_SUPERVISOR. 빈 목록 = 실사 시간대 없음(실사는 순서 맨 뒤·담당 지정만). */
  public Setting replace(
      String requestId,
      String sourceSystem,
      String mfcId,
      @Nullable Integer expectedVersion,
      List<String[]> raw) {
    String me = AssignmentService.requireSupervisor(mfcId);
    List<Window> windows = validate(raw);
    String text = format(windows);
    idempotency.execute(
        new RequestKey(sourceSystem, requestId, "replaceCountWindows", me),
        List.of(mfcId, String.valueOf(expectedVersion), text),
        Boolean.class,
        () -> {
          var cur = store.find(mfcId, true);
          int v = cur.map(CountWindowStore.Row::version).orElse(0);
          if (expectedVersion != null && expectedVersion != v) {
            throw new PmartException(
                ErrorCode.VERSION_CONFLICT, "다른 곳에서 먼저 바뀌었습니다(지금 버전 " + v + ").");
          }
          store.save(mfcId, text, v + 1, me, clock.instant());
          log.info("센터 {} 실사 시간대 [{}] (버전 {}) by {}", mfcId, text, v + 1, me);
          return true;
        });
    return setting(mfcId);
  }

  /** 지금 그 센터 실사 시간대인가(다음 작업 받기). 권한 검사 없음. */
  @Transactional(readOnly = true)
  public boolean inWindow(String mfcId, Instant at) {
    LocalTime t = at.atZone(ZONE).toLocalTime();
    return windows(mfcId).stream().anyMatch(w -> w.contains(t));
  }

  private Setting setting(String mfcId) {
    var row = store.find(mfcId, false);
    List<Window> ws = row.map(r -> parse(r.windows())).orElse(DEFAULT_WINDOWS);
    LocalTime now = clock.instant().atZone(ZONE).toLocalTime();
    return new Setting(
        mfcId,
        ws,
        row.isPresent() ? "CENTER" : "DEFAULT",
        row.map(CountWindowStore.Row::version).orElse(0),
        row.map(CountWindowStore.Row::updatedBy).orElse(null),
        row.map(CountWindowStore.Row::updatedAt).orElse(null),
        ws.stream().anyMatch(w -> w.contains(now)));
  }

  private List<Window> windows(String mfcId) {
    return store.find(mfcId, false).map(r -> parse(r.windows())).orElse(DEFAULT_WINDOWS);
  }

  /** 각 [start, end] "HH:mm". 시작 < 끝(자정 넘김 안 됨), 겹침 안 됨, 최대 {@link #MAX_WINDOWS}개. 시작 순으로 정렬. */
  static List<Window> validate(List<String[]> raw) {
    if (raw.size() > MAX_WINDOWS) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "실사 시간대는 " + MAX_WINDOWS + "개까지입니다.");
    }
    List<Window> out = new ArrayList<>();
    for (String[] r : raw) {
      LocalTime s = time(r[0], "startTime");
      LocalTime e = time(r[1], "endTime");
      if (!s.isBefore(e)) {
        throw new PmartException(
            ErrorCode.VALIDATION_FAILED, "시작(" + s + ")이 끝(" + e + ")보다 앞이어야 합니다 — 자정을 넘길 수 없습니다.");
      }
      out.add(new Window(s, e));
    }
    out.sort(Comparator.comparing(Window::start));
    for (int i = 1; i < out.size(); i++) {
      if (out.get(i).start().isBefore(out.get(i - 1).end())) {
        throw new PmartException(
            ErrorCode.VALIDATION_FAILED, "실사 시간대가 겹칩니다: " + out.get(i - 1) + ", " + out.get(i));
      }
    }
    return out;
  }

  private static LocalTime time(@Nullable String v, String field) {
    try {
      if (v == null || !v.matches("\\d{2}:\\d{2}")) {
        throw new DateTimeParseException("HH:mm", String.valueOf(v), 0);
      }
      return LocalTime.parse(v);
    } catch (DateTimeParseException ex) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED,
          field + " 는 HH:mm(서울 시각)입니다: " + v,
          List.of(new PmartException.Detail(field, "HH:mm")));
    }
  }

  static String format(List<Window> ws) {
    return ws.stream().map(Window::toString).collect(Collectors.joining(","));
  }

  static List<Window> parse(String text) {
    List<Window> out = new ArrayList<>();
    if (text == null || text.isBlank()) {
      return out;
    }
    for (String part : text.split(",")) {
      String[] se = part.split("-");
      out.add(new Window(LocalTime.parse(se[0]), LocalTime.parse(se[1])));
    }
    return out;
  }
}
