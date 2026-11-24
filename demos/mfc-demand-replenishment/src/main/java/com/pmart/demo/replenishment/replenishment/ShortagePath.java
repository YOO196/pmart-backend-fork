package com.pmart.demo.replenishment.replenishment;

import com.pmart.demo.replenishment.replenishment.Model.Granularity;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 시간대별 부족(명세 3장 "시간대별 부족"): 수요·입고 사건을 시각 순서로 적용해 잔량을 따라간다. 같은 시각(DAY 면 같은 날짜)에는 수요
 * 먼저, 입고 나중. 나중 입고가 앞선 부족을 해소한 것으로 치지 않는다.
 *
 * <p>기존 공급만 반영한 경로에서 최초 부족·최대 부족·회복 시각을, 추천분을 proposedReadyAt 에 넣은 경로에서 추천분 도착 전 부족과
 * 보충 뒤 잔여 부족을 낸다.
 */
public final class ShortagePath {

  /** 사건. qty 음수 = 수요·적격 상실, 양수 = 입고·준비. proposal 이면 추천분. */
  public record Event(LocalDateTime at, double qty, String kind, boolean proposal) {}

  public record Result(
      LocalDateTime firstShortageAt,
      double maxShortage,
      LocalDateTime recoveryAt,
      double shortageBeforeProposedReady,
      double residualShortageAfterReplenishment) {}

  private ShortagePath() {}

  /** DAY 면 날짜 시작으로, HOUR 면 그대로. */
  static LocalDateTime key(LocalDateTime t, Granularity g) {
    return g == Granularity.DAY ? t.toLocalDate().atStartOfDay() : t;
  }

  /**
   * @param start 기준시각(시작 잔량의 시각)
   * @param startLevel 시작 잔량(H)
   * @return recoveryAt 은 최초 부족 뒤 처음으로 잔량이 0 이상이 된 시각(기간 안에 회복하지 않으면 null)
   */
  public static Result compute(
      LocalDateTime start, double startLevel, List<Event> events, Granularity granularity) {
    List<Event> sorted = new ArrayList<>(events);
    sorted.sort(
        Comparator.comparing((Event e) -> key(e.at(), granularity))
            .thenComparing(e -> e.qty() < 0 ? 0 : 1) // 같은 시각: 수요 먼저
            .thenComparing(e -> e.proposal() ? 1 : 0));

    // 기존 공급만 반영한 경로.
    double level = startLevel;
    LocalDateTime first = level < 0 ? key(start, granularity) : null;
    LocalDateTime recovery = null;
    double max = Math.max(0, -level);
    boolean inShortage = level < 0;
    for (Event e : sorted) {
      if (e.proposal()) {
        continue;
      }
      level += e.qty();
      LocalDateTime k = key(e.at(), granularity);
      if (level < 0) {
        max = Math.max(max, -level);
        if (first == null) {
          first = k;
        }
        inShortage = true;
      } else if (inShortage && e.qty() > 0) {
        inShortage = false;
        if (recovery == null) {
          recovery = k;
        }
      }
    }

    // 추천분 반영 경로.
    double lvl = startLevel;
    double before = Math.max(0, -lvl);
    double after = 0;
    boolean applied = false;
    for (Event e : sorted) {
      lvl += e.qty();
      if (e.proposal()) {
        applied = true;
        after = Math.max(after, Math.max(0, -lvl));
        continue;
      }
      double s = Math.max(0, -lvl);
      if (applied) {
        after = Math.max(after, s);
      } else {
        before = Math.max(before, s);
      }
    }
    return new Result(first, max, recovery, before, after);
  }
}
