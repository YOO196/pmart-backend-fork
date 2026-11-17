package com.pmart.mfc.capacity.application;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.capacity.infrastructure.WorkActivityClient;
import com.pmart.mfc.capacity.infrastructure.WorkActivityClient.Item;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * 작업자 "오늘 업무 내역"(요청 F-7 R3·R4, 2026-11-17 — 사용자 "작업자마다 본인의 금일 작업내용을 체크할 수 있어야"). 작업자 정본인 이 서비스가 네
 * 정본(fulfillment 피킹·포장·복귀, inbound 입고·품질검사·적치·이동·회수품, inventory 실사, quality 품질 판정·폐기·회수품 검수)의 내부
 * 조회를 모아 시각 순 목록과 종류별 건수·수량 합계를 준다. 본인 또는 그 센터 MFC_SUPERVISOR. 날짜는 Asia/Seoul(없으면 오늘).
 */
@Service
public class WorkActivityService {

  static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

  /** 종류별 합계(R4). */
  public record Total(String source, String activityType, int count, int qty) {}

  public record Activities(
      String mfcId,
      String workerId,
      LocalDate date,
      List<Item> items,
      List<Total> totals,
      List<String> unavailableSources) {}

  private final WorkActivityClient client;
  private final Clock clock;

  public WorkActivityService(WorkActivityClient client, Clock clock) {
    this.client = client;
    this.clock = clock;
  }

  public Activities list(String mfcId, String workerId, @Nullable LocalDate date) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "운영자만 볼 수 있습니다."));
    String me = p.subject();
    String who = "me".equals(workerId) ? me : workerId;
    if (!p.canAccessCenter(mfcId)
        || (!who.equals(me) && !p.hasRole(AssignmentService.SUPERVISOR_ROLE))) {
      throw new PmartException(ErrorCode.FORBIDDEN, "본인 또는 이 센터의 MFC_SUPERVISOR 만 볼 수 있습니다.");
    }
    LocalDate d = date != null ? date : LocalDate.now(clock.withZone(SEOUL));
    Instant from = d.atStartOfDay(SEOUL).toInstant();
    Instant to = d.plusDays(1).atStartOfDay(SEOUL).toInstant();
    var c = client.collect(mfcId, who, from, to);
    List<Item> items =
        c.items().stream()
            .sorted(Comparator.comparing(Item::at).thenComparing(Item::refId))
            .toList();
    return new Activities(mfcId, who, d, items, totals(items), c.unavailable());
  }

  /** 종류별 건수·수량 합 — 거절(REJECTED)은 건수에만, 수량에는 넣지 않는다. */
  static List<Total> totals(List<Item> items) {
    Map<String, int[]> by = new TreeMap<>();
    for (Item i : items) {
      int[] v = by.computeIfAbsent(i.source() + "|" + i.activityType(), k -> new int[2]);
      v[0]++;
      if (i.qty() != null && !"REJECTED".equals(i.result())) {
        v[1] += i.qty();
      }
    }
    return by.entrySet().stream()
        .map(
            e -> {
              String[] k = e.getKey().split("\\|", 2);
              return new Total(k[0], k[1], e.getValue()[0], e.getValue()[1]);
            })
        .toList();
  }
}
