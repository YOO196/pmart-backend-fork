package com.pmart.mfc.inventory.application;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.inventory.domain.StockRules.Balance;
import com.pmart.mfc.inventory.domain.StockUnit.Change;
import com.pmart.mfc.inventory.domain.StockUnit.Key;
import com.pmart.mfc.inventory.infrastructure.StockStore;
import com.pmart.mfc.inventory.infrastructure.StockStore.CommitRow;
import com.pmart.mfc.inventory.infrastructure.StockStore.EntryRow;
import com.pmart.mfc.inventory.infrastructure.StockStore.LotBalance;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 한 사건의 수량 반영(IV-01, ADR-0040 결정 1·6). 단위 행 증감과 예약(R) 증감을 받아 센터 요약 → LOT 요약 → 단위 행 순으로 잠그고, 감소분의
 * 잔량과 불변식(0≤R≤E≤H, 0≤Q≤H, E+Q≤H)을 반영 전에 검사한 뒤 세 표와 원장을 함께 바꾼다. 호출자의 트랜잭션 안에서만 부른다.
 *
 * <p>분실 조사 보류(ADR-0044, {@code stock_hold})는 단위 행을 바꾸지 않고 E 에서만 뺀다. 단위 행의 감소·보류 증가는 "단위 수량 − OPEN
 * 보류" 안에서만 허용한다(보류된 물량은 집품·이동할 수 없다). 보류 행 자체는 호출자가 쓴다.
 */
@Component
public class Ledger {

  public static final String INVARIANT_VIOLATION = "INVARIANT_VIOLATION";

  /** LOT 단위 예약 증감. */
  public record ReservedChange(String sku, String lotId, int delta) {}

  /**
   * 분실 조사 보류 증감(단위 키, +q = 보류). 판매 가능 키(NORMAL·적치 뒤)는 E−q. (0.35.0) NORMAL·UNSTOWED(적치 전 — E 밖)도
   * 받는다: E 변화 없이 그 양을 적치·이동 못 하게만 막는다(입고 적치 현장 예외 SHORT·BLOCKED).
   */
  public record HoldChange(Key key, int delta) {

    /** 이 보류가 E 에서 빼는 양(판매 가능 키만). */
    int de() {
      return key.sellable() ? -delta : 0;
    }
  }

  private record LotKey(String sku, String lotId) implements Comparable<LotKey> {
    @Override
    public int compareTo(LotKey o) {
      int c = sku.compareTo(o.sku);
      return c != 0 ? c : lotId.compareTo(o.lotId);
    }
  }

  private static final class Acc {
    int dh;
    int dq;
    int de;
    int dr;
  }

  private final StockStore stock;

  public Ledger(StockStore stock) {
    this.stock = stock;
  }

  /** 새 확정 단위를 만들고 반영한다. */
  public String post(
      String commitType,
      String mfcId,
      @Nullable String requestId,
      String sourceSystem,
      @Nullable String sourceRef,
      int appliedQty,
      List<Change> units,
      List<ReservedChange> reserved,
      Instant at) {
    return postAs(
        newCommitId(),
        commitType,
        mfcId,
        requestId,
        sourceSystem,
        sourceRef,
        appliedQty,
        units,
        reserved,
        at);
  }

  public static String newCommitId() {
    return "CMT-" + UUID.randomUUID();
  }

  /** 미리 정한 commitId 로 반영(수령·실행 선점 행이 같은 ID 를 가리키게). */
  public String postAs(
      String commitId,
      String commitType,
      String mfcId,
      @Nullable String requestId,
      String sourceSystem,
      @Nullable String sourceRef,
      int appliedQty,
      List<Change> units,
      List<ReservedChange> reserved,
      Instant at) {
    return postWithHolds(
        commitId,
        commitType,
        mfcId,
        requestId,
        sourceSystem,
        sourceRef,
        appliedQty,
        units,
        reserved,
        List.of(),
        at);
  }

  /** 분실 조사 보류 증감을 함께 반영(IV-09). */
  public String postWithHolds(
      String commitId,
      String commitType,
      String mfcId,
      @Nullable String requestId,
      String sourceSystem,
      @Nullable String sourceRef,
      int appliedQty,
      List<Change> units,
      List<ReservedChange> reserved,
      List<HoldChange> holds,
      Instant at) {
    for (HoldChange h : holds) {
      if (!h.key().sellable()
          && !(h.key().quality() == com.pmart.mfc.inventory.domain.StockUnit.Quality.NORMAL
              && h.key().work() == com.pmart.mfc.inventory.domain.StockUnit.Work.UNSTOWED)) {
        throw new IllegalArgumentException("보류는 판매 가능 또는 NORMAL·UNSTOWED 단위 키만: " + h.key());
      }
    }
    lockAndCheck(mfcId, units, reserved, holds, at);
    stock.insertCommit(
        new CommitRow(
            commitId, commitType, mfcId, requestId, sourceSystem, sourceRef, appliedQty, at));
    write(commitId, mfcId, units, reserved, holds, at);
    return commitId;
  }

  /** 반영 없이 확정 단위만(수량 변화가 없는 사건 — 예약 확정). */
  public String record(
      String commitType,
      String mfcId,
      @Nullable String requestId,
      String sourceSystem,
      @Nullable String sourceRef,
      int appliedQty,
      Instant at) {
    String commitId = newCommitId();
    stock.insertCommit(
        new CommitRow(
            commitId, commitType, mfcId, requestId, sourceSystem, sourceRef, appliedQty, at));
    return commitId;
  }

  private void lockAndCheck(
      String mfcId,
      List<Change> units,
      List<ReservedChange> reserved,
      List<HoldChange> holds,
      Instant at) {
    SortedMap<String, Acc> bySku = new TreeMap<>();
    SortedMap<LotKey, Acc> byLot = new TreeMap<>();
    for (Change c : units) {
      Key k = c.key();
      add(bySku.computeIfAbsent(k.sku(), s -> new Acc()), c.dh(), c.dq(), c.de(), 0);
      add(
          byLot.computeIfAbsent(new LotKey(k.sku(), k.lotId()), s -> new Acc()),
          c.dh(),
          c.dq(),
          c.de(),
          0);
    }
    for (ReservedChange r : reserved) {
      add(bySku.computeIfAbsent(r.sku(), s -> new Acc()), 0, 0, 0, r.delta());
      add(
          byLot.computeIfAbsent(new LotKey(r.sku(), r.lotId()), s -> new Acc()),
          0,
          0,
          0,
          r.delta());
    }
    for (HoldChange h : holds) {
      Key k = h.key();
      add(bySku.computeIfAbsent(k.sku(), s -> new Acc()), 0, 0, h.de(), 0);
      add(byLot.computeIfAbsent(new LotKey(k.sku(), k.lotId()), s -> new Acc()), 0, 0, h.de(), 0);
    }
    stock.ensureCenterRows(mfcId, bySku.keySet(), at);
    Map<String, Balance> centers = stock.lockBalances(mfcId, bySku.keySet());
    for (LotKey lk : byLot.keySet()) {
      stock.ensureLotRow(mfcId, lk.sku(), lk.lotId(), at);
    }
    SortedSet<String> skus = new TreeSet<>(bySku.keySet());
    Map<LotKey, LotBalance> lots = new TreeMap<>();
    for (LotBalance b : stock.lockLotBalances(mfcId, skus)) {
      lots.put(new LotKey(b.sku(), b.lotId()), b);
    }
    // 단위 행: 같은 순서(키 문자열)로 잠그고 감소분 잔량 검사
    List<Change> sorted = new ArrayList<>(units);
    sorted.sort((a, b) -> String.valueOf(a.key()).compareTo(String.valueOf(b.key())));
    Map<Key, Integer> net = new TreeMap<>((a, b) -> String.valueOf(a).compareTo(String.valueOf(b)));
    for (Change c : sorted) {
      net.merge(c.key(), c.delta(), Integer::sum);
    }
    // 보류 증가는 단위에서 빼는 것과 같게 검사(단위 − OPEN 보류 ≥ 0 유지)
    for (HoldChange h : holds) {
      net.merge(h.key(), -h.delta(), Integer::sum);
    }
    List<PmartException.Detail> short_ = new ArrayList<>();
    for (Map.Entry<Key, Integer> e : net.entrySet()) {
      if (e.getValue() < 0) {
        int have = stock.lockUnit(mfcId, e.getKey()) - stock.openHold(mfcId, e.getKey());
        if (have + e.getValue() < 0) {
          Key k = e.getKey();
          short_.add(
              new PmartException.Detail(
                  k.sku(),
                  "lotId="
                      + k.lotId()
                      + ";location="
                      + k.locationCode()
                      + ";state="
                      + k.quality()
                      + "/"
                      + k.work()
                      + ";requestedQty="
                      + (-e.getValue())
                      + ";availableQty="
                      + have));
        }
      }
    }
    if (!short_.isEmpty()) {
      throw new PmartException(
          ErrorCode.INSUFFICIENT_STOCK, "위치 잔량이 부족합니다(" + short_.size() + "건).", short_);
    }
    List<PmartException.Detail> bad = new ArrayList<>();
    bySku.forEach(
        (sku, a) -> {
          Balance b = centers.getOrDefault(sku, Balance.empty(sku));
          check(bad, sku, null, b.onHand(), b.quarantined(), b.sellable(), b.reserved(), a);
        });
    byLot.forEach(
        (lk, a) -> {
          LotBalance b = lots.get(lk);
          int h = b == null ? 0 : b.onHand();
          int q = b == null ? 0 : b.quarantined();
          int e = b == null ? 0 : b.sellable();
          int r = b == null ? 0 : b.reserved();
          check(bad, lk.sku(), lk.lotId(), h, q, e, r, a);
        });
    if (!bad.isEmpty()) {
      throw new PmartException(
          INVARIANT_VIOLATION,
          HttpStatus.CONFLICT,
          "재고 불변식(0≤R≤E≤H, 0≤Q≤H, E+Q≤H)을 깨는 변경입니다.",
          false,
          bad);
    }
  }

  private static void check(
      List<PmartException.Detail> bad,
      String sku,
      @Nullable String lotId,
      int h,
      int q,
      int e,
      int r,
      Acc a) {
    int nh = h + a.dh;
    int nq = q + a.dq;
    int ne = e + a.de;
    int nr = r + a.dr;
    if (nr < 0 || nr > ne || ne > nh || nq < 0 || nq > nh || ne + nq > nh) {
      bad.add(
          new PmartException.Detail(
              sku,
              (lotId == null ? "" : "lotId=" + lotId + ";")
                  + "H="
                  + nh
                  + ";Q="
                  + nq
                  + ";E="
                  + ne
                  + ";R="
                  + nr));
    }
  }

  private void write(
      String commitId,
      String mfcId,
      List<Change> units,
      List<ReservedChange> reserved,
      List<HoldChange> holds,
      Instant at) {
    SortedMap<String, Acc> bySku = new TreeMap<>();
    SortedMap<LotKey, Acc> byLot = new TreeMap<>();
    for (Change c : units) {
      Key k = c.key();
      add(bySku.computeIfAbsent(k.sku(), s -> new Acc()), c.dh(), c.dq(), c.de(), 0);
      add(
          byLot.computeIfAbsent(new LotKey(k.sku(), k.lotId()), s -> new Acc()),
          c.dh(),
          c.dq(),
          c.de(),
          0);
    }
    for (ReservedChange r : reserved) {
      add(bySku.computeIfAbsent(r.sku(), s -> new Acc()), 0, 0, 0, r.delta());
      add(
          byLot.computeIfAbsent(new LotKey(r.sku(), r.lotId()), s -> new Acc()),
          0,
          0,
          0,
          r.delta());
    }
    for (HoldChange h : holds) {
      Key k = h.key();
      add(bySku.computeIfAbsent(k.sku(), s -> new Acc()), 0, 0, h.de(), 0);
      add(byLot.computeIfAbsent(new LotKey(k.sku(), k.lotId()), s -> new Acc()), 0, 0, h.de(), 0);
    }
    Map<LotKey, Long> lotVersions = new TreeMap<>();
    byLot.forEach(
        (lk, a) ->
            lotVersions.put(
                lk, stock.applyLot(mfcId, lk.sku(), lk.lotId(), a.dh, a.dq, a.de, a.dr, at)));
    bySku.forEach((sku, a) -> stock.applyCenter(mfcId, sku, a.dh, a.dq, a.de, a.dr, at));
    for (Change c : units) {
      if (c.delta() == 0) {
        continue;
      }
      Key k = c.key();
      stock.applyUnit(mfcId, k, c.delta(), at);
      long v = lotVersions.get(new LotKey(k.sku(), k.lotId()));
      entry(commitId, mfcId, k, "H", c.dh(), v);
      if (c.dq() != 0) {
        entry(commitId, mfcId, k, "Q", c.dq(), v);
      }
      if (c.de() != 0) {
        entry(commitId, mfcId, k, "E", c.de(), v);
      }
    }
    for (ReservedChange r : reserved) {
      long v = lotVersions.get(new LotKey(r.sku(), r.lotId()));
      stock.insertEntry(
          commitId, mfcId, new EntryRow(r.sku(), r.lotId(), null, null, null, "R", r.delta(), v));
    }
    for (HoldChange h : holds) {
      if (h.de() == 0) {
        continue; // 적치 전(UNSTOWED) 보류 — E 변화 없음, 보류 행만(호출자)
      }
      long v = lotVersions.get(new LotKey(h.key().sku(), h.key().lotId()));
      entry(commitId, mfcId, h.key(), "E", h.de(), v); // 단위 행은 그대로, E 만 — 원장에 보류 위치를 남긴다
    }
  }

  private void entry(String commitId, String mfcId, Key k, String symbol, int delta, long v) {
    stock.insertEntry(
        commitId,
        mfcId,
        new EntryRow(
            k.sku(),
            k.lotId(),
            k.locationCode(),
            k.quality().name(),
            k.work().name(),
            symbol,
            delta,
            v));
  }

  private static void add(Acc a, int dh, int dq, int de, int dr) {
    a.dh += dh;
    a.dq += dq;
    a.de += de;
    a.dr += dr;
  }
}
