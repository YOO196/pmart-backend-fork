package com.pmart.mfc.inventory.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * 순환 실사 규칙(IV-11). 첫 계수 장부 숨김, 차이면 다른 작업자 재계수, 최종 = 재계수, 요청자·계수자와 승인자 분리, 승인된 차이만 한 번 반영. 대상은
 * 위치·SKU·LOT 의 판매 단위(NORMAL·STORED).
 */
public final class CountRules {

  public static final String SAME_COUNTER = "SAME_COUNTER";
  public static final String SELF_APPROVAL = "SELF_APPROVAL";
  public static final String LOCATION_NOT_LOCKED = "LOCATION_NOT_LOCKED";
  public static final String COUNT_IN_PROGRESS = "COUNT_IN_PROGRESS";
  public static final String BOOK_CHANGED = "BOOK_CHANGED";

  private CountRules() {}

  /** 장부 한 줄(기준 시각). heldQty = 그 단위의 열린 조사 보류. */
  public record BookLine(String locationCode, String sku, String lotId, int qty, int heldQty) {
    public String key() {
      return CountRules.key(locationCode, sku, lotId);
    }
  }

  /** 계수 한 줄. */
  public record CountLine(String locationCode, String sku, String lotId, int countedQty) {
    public String key() {
      return CountRules.key(locationCode, sku, lotId);
    }
  }

  /** 계수표 밖에서 찾은 줄(0.34.0, BR-52 R19-6). sku 또는 barcode 하나는 있어야 하고, LOT 은 lotId 또는 소비기한으로 정한다. */
  public record Unexpected(
      String locationCode,
      @Nullable String sku,
      @Nullable String barcode,
      @Nullable String lotId,
      java.time.@Nullable LocalDate expiryDate,
      int qty) {}

  /** 목록 밖 줄 중 SKU·LOT 을 정하지 못한 것 — 조정하지 않는다. cause = SKU_UNKNOWN·LOT_UNKNOWN·LOT_AMBIGUOUS. */
  public record Unresolved(
      String locationCode,
      @Nullable String sku,
      @Nullable String barcode,
      java.time.@Nullable LocalDate expiryDate,
      int qty,
      String cause) {}

  public static final String SKU_UNKNOWN = "SKU_UNKNOWN";
  public static final String LOT_UNKNOWN = "LOT_UNKNOWN";
  public static final String LOT_AMBIGUOUS = "LOT_AMBIGUOUS";

  /**
   * 목록 밖 줄의 LOT 판정: lotId 가 있으면 그것, 없으면 소비기한이 같은 그 SKU 의 LOT 이 하나뿐일 때만.
   *
   * @param sameExpiryLots 그 SKU·소비기한의 LOT(최대 2개면 충분) — 소비기한이 없으면 무시
   * @return LOT ID, 정하지 못하면 null
   */
  public static @Nullable String lotOf(Unexpected u, List<String> sameExpiryLots) {
    if (u.lotId() != null) {
      return u.lotId();
    }
    if (u.expiryDate() == null || sameExpiryLots.size() != 1) {
      return null;
    }
    return sameExpiryLots.get(0);
  }

  /** {@link #lotOf} 가 null 일 때의 이유. */
  public static String lotCause(Unexpected u, List<String> sameExpiryLots) {
    return u.expiryDate() != null && sameExpiryLots.size() > 1 ? LOT_AMBIGUOUS : LOT_UNKNOWN;
  }

  /** 차이 한 줄(최종 − 장부). */
  public record Difference(
      String locationCode, String sku, String lotId, int bookQty, int heldQty, int countedQty) {
    public int delta() {
      return countedQty - bookQty;
    }
  }

  public static String key(String locationCode, String sku, String lotId) {
    return locationCode + "|" + sku + "|" + lotId;
  }

  /**
   * 장부와 계수를 맞춘 표(장부 줄 + 장부에 없던 찾은 줄). 장부 줄에 계수가 없으면 0 으로 센 것. 같은 키 계수 줄은 더한다.
   *
   * @return 키 순서 표(차이 없는 줄도 포함)
   */
  public static List<Difference> reconcile(List<BookLine> book, List<CountLine> counted) {
    Map<String, Integer> c = new TreeMap<>();
    Map<String, CountLine> extra = new TreeMap<>();
    for (CountLine l : counted) {
      c.merge(l.key(), l.countedQty(), Integer::sum);
      extra.putIfAbsent(l.key(), l);
    }
    Map<String, Difference> out = new TreeMap<>();
    for (BookLine b : book) {
      out.put(
          b.key(),
          new Difference(
              b.locationCode(),
              b.sku(),
              b.lotId(),
              b.qty(),
              b.heldQty(),
              c.getOrDefault(b.key(), 0)));
      extra.remove(b.key());
    }
    for (CountLine l : extra.values()) {
      out.put(l.key(), new Difference(l.locationCode(), l.sku(), l.lotId(), 0, 0, c.get(l.key())));
    }
    return new ArrayList<>(out.values());
  }

  public static boolean matches(List<Difference> table) {
    return table.stream().allMatch(d -> d.delta() == 0);
  }

  /** 정하지 못한 목록 밖 줄이 있으면 장부와 같다고 보지 않는다(R19-6 — 잘못 놓인 재고를 관리자가 봐야 한다). */
  public static boolean matches(List<Difference> table, List<Unresolved> unresolved) {
    return matches(table) && unresolved.isEmpty();
  }

  public static boolean hasHolds(List<BookLine> book) {
    return book.stream().anyMatch(b -> b.heldQty() > 0);
  }

  /**
   * 계수 제출 뒤 다음 상태.
   *
   * @param state 지금 상태(COUNTING / RECOUNT_REQUIRED)
   */
  public static String next(String state, boolean matches, boolean holds) {
    if (matches && !holds) {
      return "MATCHED";
    }
    if ("COUNTING".equals(state) && !matches) {
      return "RECOUNT_REQUIRED";
    }
    return "PENDING_APPROVAL";
  }

  /**
   * 승인자 검사: 시작자·계수자는 승인 못 한다.
   *
   * @return 위반 코드, 없으면 null
   */
  public static @Nullable String approvalRejection(
      String approver,
      String createdBy,
      @Nullable String firstCounter,
      @Nullable String recounter) {
    if (approver.equals(createdBy) || approver.equals(firstCounter) || approver.equals(recounter)) {
      return SELF_APPROVAL;
    }
    return null;
  }
}
