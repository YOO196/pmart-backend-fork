package com.pmart.mfc.inbound.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * 입고 예정(InboundPromise) 수량 규칙(0.16.0) — 예정 줄, 수령 검사, 차이. 근거: MFC IV-02(인수 = 정상 + 격리 + 보류, 원출발 초과·불명
 * 실물은 정상 TIN 에 합산 금지, 원출발 연결 뒤 수령), 흐름 S5 §7(출발 q = 수령 r + 손실 l + 미정산 t).
 *
 * <p>예정 수량은 실제 출발(TOUT) 줄이 있으면 그것(SKU·LOT 별), 출발 전이면 승인 수량(SKU 별, 취소분 뺀 값)이다.
 */
public final class InboundPromiseRules {

  private InboundPromiseRules() {}

  /** 승인 줄(TransferApproved·Changed lines — approvedQty). */
  public record ApprovedLine(String sku, int qty, @Nullable String unit) {}

  /** 출발·매니페스트 줄(SkuLotLpnQty). */
  public record ShipLine(
      String sku,
      @Nullable String lot,
      @Nullable String lpn,
      int qty,
      @Nullable String expiryDate) {}

  /** 현장 수령 줄. lotId 는 CDC LOT 표기(inventory sourceLotRef). */
  public record ReceiptLine(
      String sku,
      @Nullable String lotId,
      @Nullable String expiryDate,
      int normalQty,
      int quarantinedQty,
      int pendingQty,
      @Nullable String toLocationCode,
      @Nullable String discrepancyReason) {

    public int total() {
      return normalQty + quarantinedQty + pendingQty;
    }
  }

  /** 예정 줄(SKU·LOT). */
  public record ExpectedLine(
      String sku, @Nullable String lot, @Nullable String expiryDate, int expectedQty) {}

  /** 화면 줄 — 예정과 재고 반영된 인수. */
  public record ViewLine(
      String sku,
      @Nullable String lot,
      @Nullable String expiryDate,
      int expectedQty,
      int receivedQty,
      int quarantinedQty,
      int pendingQty) {}

  /** SKU 별 수량 차이. */
  public record Discrepancy(String sku, int expectedQty, int receivedQty, String reason) {}

  /** 예정 줄 — 출발 줄이 있으면 SKU·LOT 별 합, 없으면 승인 수량(0 은 뺀다). */
  public static List<ExpectedLine> expected(
      List<ApprovedLine> approved, @Nullable List<ShipLine> departed) {
    if (departed != null && !departed.isEmpty()) {
      Map<List<Object>, ExpectedLine> out = new LinkedHashMap<>();
      for (ShipLine l : departed) {
        List<Object> key = java.util.Arrays.asList(l.sku(), l.lot());
        ExpectedLine prev = out.get(key);
        out.put(
            key,
            prev == null
                ? new ExpectedLine(l.sku(), l.lot(), l.expiryDate(), l.qty())
                : new ExpectedLine(
                    l.sku(),
                    l.lot(),
                    prev.expiryDate() != null ? prev.expiryDate() : l.expiryDate(),
                    prev.expectedQty() + l.qty()));
      }
      return List.copyOf(out.values());
    }
    Map<String, Integer> bySku = new LinkedHashMap<>();
    for (ApprovedLine l : approved) {
      bySku.merge(l.sku(), l.qty(), Integer::sum);
    }
    List<ExpectedLine> out = new ArrayList<>();
    bySku.forEach(
        (sku, qty) -> {
          if (qty > 0) {
            out.add(new ExpectedLine(sku, null, null, qty));
          }
        });
    return out;
  }

  public static Map<String, Integer> expectedBySku(List<ExpectedLine> expected) {
    Map<String, Integer> out = new LinkedHashMap<>();
    expected.forEach(e -> out.merge(e.sku(), e.expectedQty(), Integer::sum));
    return out;
  }

  /**
   * 부분 취소(cancelledLines) — SKU 별 승인 수량에서 뺀다(0 아래로는 안 내려감). 줄이 없으면 전체 취소(빈 목록). 남은 수량이 없으면 빈 목록.
   */
  public static List<ApprovedLine> cancel(List<ApprovedLine> approved, List<ShipLine> cancelled) {
    if (cancelled.isEmpty()) {
      return List.of();
    }
    Map<String, Integer> cut = new LinkedHashMap<>();
    cancelled.forEach(c -> cut.merge(c.sku(), c.qty(), Integer::sum));
    List<ApprovedLine> out = new ArrayList<>();
    for (ApprovedLine l : approved) {
      int c = cut.getOrDefault(l.sku(), 0);
      int take = Math.min(c, l.qty());
      cut.put(l.sku(), c - take);
      if (l.qty() - take > 0) {
        out.add(new ApprovedLine(l.sku(), l.qty() - take, l.unit()));
      }
    }
    return out;
  }

  /** 수령 줄 검사. 틀리면 사유(400), 맞으면 비어 있음. */
  public static Optional<String> validate(List<ExpectedLine> expected, List<ReceiptLine> lines) {
    if (lines.isEmpty()) {
      return Optional.of("수령 줄이 없습니다.");
    }
    Map<String, Integer> exp = expectedBySku(expected);
    Map<String, Integer> got = new LinkedHashMap<>();
    int total = 0;
    for (ReceiptLine l : lines) {
      if (l.normalQty() < 0 || l.quarantinedQty() < 0 || l.pendingQty() < 0) {
        return Optional.of(l.sku() + " 수량은 0 이상입니다.");
      }
      if (l.toLocationCode() == null || l.toLocationCode().isBlank()) {
        return Optional.of(l.sku() + " 줄에 toLocationCode(수령 위치)가 필요합니다.");
      }
      if (!exp.containsKey(l.sku())) {
        return Optional.of(l.sku() + " 는 이 입고 예정(출발)에 없는 상품입니다 — 출처 확인 뒤 별도 경로(IV-02).");
      }
      got.merge(l.sku(), l.total(), Integer::sum);
      total += l.total();
    }
    if (total == 0) {
      return Optional.of("수령 수량 합이 0 입니다.");
    }
    for (Map.Entry<String, Integer> e : got.entrySet()) {
      int x = exp.get(e.getKey());
      if (e.getValue() > x) {
        return Optional.of(
            e.getKey()
                + " 인수 합 "
                + e.getValue()
                + " 이 출발 수량 "
                + x
                + " 보다 많습니다 — 초과분은 정상 수령에 넣지 않습니다(IV-02).");
      }
    }
    return Optional.empty();
  }

  /** LOT 을 비운 줄 — 그 SKU 의 예정 LOT 이 하나면 그 LOT·소비기한으로 채운다. */
  public static List<ReceiptLine> fillLots(List<ExpectedLine> expected, List<ReceiptLine> lines) {
    Map<String, Set<ExpectedLine>> bySku = new LinkedHashMap<>();
    for (ExpectedLine e : expected) {
      if (e.lot() != null) {
        bySku.computeIfAbsent(e.sku(), k -> new LinkedHashSet<>()).add(e);
      }
    }
    List<ReceiptLine> out = new ArrayList<>();
    for (ReceiptLine l : lines) {
      Set<ExpectedLine> lots = bySku.getOrDefault(l.sku(), Set.of());
      if (l.lotId() == null && lots.size() == 1) {
        ExpectedLine only = lots.iterator().next();
        out.add(
            new ReceiptLine(
                l.sku(),
                only.lot(),
                l.expiryDate() != null ? l.expiryDate() : only.expiryDate(),
                l.normalQty(),
                l.quarantinedQty(),
                l.pendingQty(),
                l.toLocationCode(),
                l.discrepancyReason()));
      } else if (l.lotId() != null && l.expiryDate() == null) {
        String exp =
            lots.stream()
                .filter(e -> Objects.equals(e.lot(), l.lotId()))
                .map(ExpectedLine::expiryDate)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        out.add(
            new ReceiptLine(
                l.sku(),
                l.lotId(),
                exp,
                l.normalQty(),
                l.quarantinedQty(),
                l.pendingQty(),
                l.toLocationCode(),
                l.discrepancyReason()));
      } else {
        out.add(l);
      }
    }
    return out;
  }

  /**
   * SKU 별 출발 수량 ≠ 인수 합인 줄(빠진 SKU = 0 인수). 사유 = 그 SKU 줄의 discrepancyReason(첫 값), 없으면 인수 0 이면
   * NOT_RECEIVED, 적으면 SHORT (클라이언트 BR-53 R11 — 수량 차이 우선).
   */
  public static List<Discrepancy> discrepancies(
      List<ExpectedLine> expected, List<ReceiptLine> lines) {
    Map<String, Integer> got = new LinkedHashMap<>();
    Map<String, String> reason = new LinkedHashMap<>();
    for (ReceiptLine l : lines) {
      got.merge(l.sku(), l.total(), Integer::sum);
      if (l.discrepancyReason() != null) {
        reason.putIfAbsent(l.sku(), l.discrepancyReason());
      }
    }
    List<Discrepancy> out = new ArrayList<>();
    expectedBySku(expected)
        .forEach(
            (sku, exp) -> {
              int r = got.getOrDefault(sku, 0);
              if (r != exp) {
                String why = reason.get(sku);
                if (why == null || !List.of("NOT_RECEIVED", "SHORT", "OVER").contains(why)) {
                  why = r == 0 ? "NOT_RECEIVED" : r < exp ? "SHORT" : "OVER";
                }
                out.add(new Discrepancy(sku, exp, r, why));
              }
            });
    return out;
  }

  /** 화면 줄 — 예정 줄마다 재고 반영된 수령 줄의 인수를 더한다. 같은 SKU·LOT 줄에 먼저, LOT 이 맞는 줄이 없으면 그 SKU 의 첫 줄에. */
  public static List<ViewLine> view(List<ExpectedLine> expected, List<ReceiptLine> applied) {
    List<int[]> acc = new ArrayList<>();
    for (int i = 0; i < expected.size(); i++) {
      acc.add(new int[3]);
    }
    for (ReceiptLine l : applied) {
      int idx = -1;
      for (int i = 0; i < expected.size(); i++) {
        ExpectedLine e = expected.get(i);
        if (e.sku().equals(l.sku()) && Objects.equals(e.lot(), l.lotId())) {
          idx = i;
          break;
        }
      }
      if (idx < 0) {
        for (int i = 0; i < expected.size(); i++) {
          if (expected.get(i).sku().equals(l.sku())) {
            idx = i;
            break;
          }
        }
      }
      if (idx >= 0) {
        int[] a = acc.get(idx);
        a[0] += l.total();
        a[1] += l.quarantinedQty();
        a[2] += l.pendingQty();
      }
    }
    List<ViewLine> out = new ArrayList<>();
    for (int i = 0; i < expected.size(); i++) {
      ExpectedLine e = expected.get(i);
      int[] a = acc.get(i);
      out.add(new ViewLine(e.sku(), e.lot(), e.expiryDate(), e.expectedQty(), a[0], a[1], a[2]));
    }
    return out;
  }
}
