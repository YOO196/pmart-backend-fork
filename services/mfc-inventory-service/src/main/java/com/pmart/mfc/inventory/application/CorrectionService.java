package com.pmart.mfc.inventory.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.web.CommandResponse;
import com.pmart.common.web.CommandStatus;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.inventory.domain.StockUnit.Change;
import com.pmart.mfc.inventory.domain.StockUnit.Key;
import com.pmart.mfc.inventory.domain.StockUnit.Quality;
import com.pmart.mfc.inventory.domain.StockUnit.Work;
import com.pmart.mfc.inventory.infrastructure.SkuProjectionStore;
import com.pmart.mfc.inventory.infrastructure.StockStore;
import com.pmart.mfc.inventory.infrastructure.StockStore.LotBalance;
import com.pmart.mfc.inventory.infrastructure.StockStore.LotRow;
import com.pmart.mfc.inventory.infrastructure.StockStore.UnitRow;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * LOT·위치 정정(CORRECTION, 총량 불변 — WMS 문서 LOT 식별 정정, ADR-0040 결정 7). 출발 단위 행에서 빼서 목적 위치·목적 LOT 으로 옮긴다.
 * 품질·업무상태는 그대로. 예약(R)이 걸린 수량은 LOT 의 R ≤ E 검사(Ledger)로 막힌다. 센터 운영자만, 근거(approvalRef) 필수.
 *
 * <p>(0.37.0, 사용자 결정 2026-11-20 "수령장 분실 조사 보류는 센터 관리자 재고 정정으로") 적치 전(NORMAL·UNSTOWED) 분실 조사 보류
 * 정리({@link HoldResolution}) — 찾음 = 보류만 해제(E 는 원래 적치 전이라 그대로, 다시 적치 가능), 못 찾음 = 단위 −q(H−q 손실). 그
 * 단위의 열린 보류를 모두 해소한다(found + lost = 열린 보류 합). 같은 확정 단위(CORRECTION)·같은 권한·같은 승인 관문(손실 = lost).
 * 선반(STORED) 보류는 실사가 푼다. 반영하면 StockHoldResolved(mfc-inbound 가 찾은 양을 적치 작업으로), lost 가 있으면
 * StockAdjusted.
 */
@Service
public class CorrectionService {

  static final String OP = "correctStock";

  private static final Logger log = LoggerFactory.getLogger(CorrectionService.class);

  public record CorrectionLine(
      String sku,
      String lotId,
      String fromLocation,
      Quality quality,
      Work work,
      int qty,
      @Nullable String toLocation,
      @Nullable String toSourceLotRef,
      @Nullable LocalDate toExpiryDate,
      @Nullable LocalDate toManufactureDate) {}

  /** (0.37.0) 적치 전 분실 조사 보류 정리 — 위치·SKU·LOT 의 NORMAL·UNSTOWED. */
  public record HoldResolution(
      String sku, String lotId, String locationCode, int foundQty, int lostQty) {}

  public record CorrectionCommand(
      String mfcId,
      String reason,
      @Nullable String approvalRef,
      List<CorrectionLine> lines,
      List<HoldResolution> holdResolutions) {

    public CorrectionCommand(
        String mfcId, String reason, @Nullable String approvalRef, List<CorrectionLine> lines) {
      this(mfcId, reason, approvalRef, lines, List.of());
    }
  }

  static final String HOLD_QTY_MISMATCH = "HOLD_QTY_MISMATCH";
  static final String NO_OPEN_HOLD = "NO_OPEN_HOLD";
  static final String CORRECTION_REF = "CORRECTION:";

  /** 센터 재고 상세. */
  /** sku = SKU 사본(상품명·바코드 표시 — IV-2, 모르면 null). */
  public record CenterStockView(
      List<LotBalance> lots,
      List<UnitRow> units,
      com.pmart.mfc.inventory.domain.@org.jspecify.annotations.Nullable SkuProjection sku) {}

  private final StockStore stock;
  private final Ledger ledger;
  private final Locations locations;
  private final SkuProjectionStore skus;
  private final RequestIdempotency idempotency;
  private final Clock clock;

  private final ApprovalRefs approvalRefs;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;

  public CorrectionService(
      StockStore stock,
      Ledger ledger,
      Locations locations,
      SkuProjectionStore skus,
      RequestIdempotency idempotency,
      Clock clock,
      ApprovalRefs approvalRefs,
      EventPublisher events,
      EventEnvelopeCodec codec) {
    this.approvalRefs = approvalRefs;
    this.events = events;
    this.codec = codec;
    this.stock = stock;
    this.ledger = ledger;
    this.locations = locations;
    this.skus = skus;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  public CommandResponse correct(String requestId, String sourceSystem, CorrectionCommand cmd) {
    Operator op = Operator.forCenterRole(cmd.mfcId(), sourceSystem, "MFC_SUPERVISOR");
    List<HoldResolution> holdLines = cmd.holdResolutions();
    if (cmd.lines().size() > 500 || holdLines.size() > 100) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "정정 줄은 500개, 보류 정리는 100개까지입니다.");
    }
    if (cmd.lines().isEmpty() && holdLines.isEmpty()) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED, "lines 또는 holdResolutions 가 하나 이상 있어야 합니다.");
    }
    for (CorrectionLine l : cmd.lines()) {
      if (l.qty() < 1) {
        throw new PmartException(ErrorCode.VALIDATION_FAILED, "수량은 1 이상이어야 합니다: " + l.sku());
      }
    }
    java.util.Set<Key> seen = new java.util.HashSet<>();
    for (HoldResolution h : holdLines) {
      if (h.foundQty() < 0 || h.lostQty() < 0 || h.foundQty() + h.lostQty() < 1) {
        throw new PmartException(
            ErrorCode.VALIDATION_FAILED, "foundQty·lostQty 는 0 이상, 합은 1 이상입니다: " + h.sku());
      }
      if (!seen.add(holdKey(h))) {
        throw new PmartException(
            ErrorCode.VALIDATION_FAILED,
            "같은 위치·LOT 의 보류 정리가 두 번 있습니다: " + h.locationCode() + " " + h.lotId());
      }
    }
    // 승인 번호 관문(AP-1): 정정은 총량 불변(손실 없음) — 한 SKU 수량 기준만 걸린다. 참조 = 이 요청 키(승인 요청 targetRef 와 같아야)
    // (0.37.0) 보류 정리: 움직이는 수량 = found + lost, 손실 = lost(감소 × 판매 기준가)
    Map<String, Integer> bySku = new java.util.TreeMap<>();
    Map<String, Integer> lossBySku = new java.util.TreeMap<>();
    cmd.lines().forEach(l -> bySku.merge(l.sku(), l.qty(), Integer::sum));
    for (HoldResolution h : holdLines) {
      bySku.merge(h.sku(), h.foundQty() + h.lostQty(), Integer::sum);
      lossBySku.merge(h.sku(), h.lostQty(), Integer::sum);
    }
    String given =
        approvalRefs.require(
            cmd.mfcId(),
            "CORRECTION",
            requestId,
            op.id(),
            cmd.mfcId() + " 재고 정정 — " + cmd.reason(),
            bySku.entrySet().stream()
                .map(
                    e ->
                        new com.pmart.mfc.inventory.domain.HqApprovalRules.Line(
                            e.getKey(),
                            e.getValue(),
                            lossBySku.getOrDefault(e.getKey(), 0),
                            Map.of()))
                .toList(),
            cmd.approvalRef());
    String recorded = given != null ? given : CountService.CENTER_APPROVAL + requestId;
    int total =
        cmd.lines().stream().mapToInt(CorrectionLine::qty).sum()
            + holdLines.stream().mapToInt(h -> h.foundQty() + h.lostQty()).sum();
    return idempotency
        .execute(
            op.key(requestId, OP),
            cmd,
            CommandResponse.class,
            () -> {
              Instant at = clock.instant();
              java.util.Set<String> lotIds =
                  cmd.lines().stream()
                      .map(CorrectionLine::lotId)
                      .collect(Collectors.toCollection(java.util.HashSet::new));
              holdLines.forEach(h -> lotIds.add(h.lotId()));
              Map<String, LotRow> lots = stock.lots(lotIds);
              var projections =
                  skus.findAll(
                      cmd.lines().stream().map(CorrectionLine::sku).collect(Collectors.toSet()));
              List<Change> changes = new ArrayList<>();
              for (CorrectionLine l : cmd.lines()) {
                LotRow lot = lots.get(l.lotId());
                if (lot == null || !lot.sku().equals(l.sku())) {
                  throw new PmartException(
                      ErrorCode.VALIDATION_FAILED,
                      "lotId " + l.lotId() + " 는 SKU " + l.sku() + " 의 LOT 이 아닙니다.");
                }
                String toLoc = l.toLocation() == null ? l.fromLocation() : l.toLocation();
                if (!toLoc.equals(l.fromLocation())) {
                  if (l.work() == Work.STORED) {
                    var p = projections.get(l.sku());
                    locations.requireStorage(
                        cmd.mfcId(), toLoc, p == null ? null : p.storageType());
                  }
                }
                String toLot =
                    l.toSourceLotRef() != null
                            || l.toExpiryDate() != null
                            || l.toManufactureDate() != null
                        ? stock.trackedLot(
                            l.sku(),
                            l.toSourceLotRef(),
                            l.toExpiryDate(),
                            l.toManufactureDate(),
                            "LOT-" + UUID.randomUUID(),
                            "CORRECTION:" + requestId,
                            at)
                        : l.lotId();
                Key from = new Key(l.fromLocation(), l.sku(), l.lotId(), l.quality(), l.work());
                changes.add(new Change(from, -l.qty()));
                changes.add(
                    new Change(new Key(toLoc, l.sku(), toLot, l.quality(), l.work()), l.qty()));
              }
              // (0.37.0) 적치 전 분실 조사 보류 정리 — 보류 −(found + lost), 못 찾은 양은 단위 −lost
              List<Ledger.HoldChange> holds = new ArrayList<>();
              List<Map<String, Object>> resolved = new ArrayList<>();
              for (HoldResolution h : holdLines) {
                LotRow lot = lots.get(h.lotId());
                if (lot == null || !lot.sku().equals(h.sku())) {
                  throw new PmartException(
                      ErrorCode.VALIDATION_FAILED,
                      "lotId " + h.lotId() + " 는 SKU " + h.sku() + " 의 LOT 이 아닙니다.");
                }
                locations.requireNotCountLocked(cmd.mfcId(), h.locationCode());
                Key k = holdKey(h);
                requireHoldQty(h, stock.openHold(cmd.mfcId(), k));
                holds.add(new Ledger.HoldChange(k, -(h.foundQty() + h.lostQty())));
                if (h.lostQty() > 0) {
                  changes.add(new Change(k, -h.lostQty()));
                }
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("sku", h.sku());
                m.put("lot", h.lotId());
                m.put("locCode", h.locationCode());
                m.put("workState", Work.UNSTOWED.name());
                m.put("foundQty", h.foundQty());
                m.put("lostQty", h.lostQty());
                m.put("sourceRefs", stock.openHoldRefs(cmd.mfcId(), k));
                resolved.add(m);
              }
              String commitId = Ledger.newCommitId();
              ledger.postWithHolds(
                  commitId,
                  "CORRECTION",
                  cmd.mfcId(),
                  requestId,
                  sourceSystem,
                  recorded,
                  total,
                  changes,
                  List.of(),
                  holds,
                  at);
              // 잠금(Ledger — 센터 요약 행) 뒤 실제로 푼 양이 판정과 같아야(그 사이 다른 보고가 보류를 더했으면 되돌림)
              for (HoldResolution h : holdLines) {
                requireHoldQty(
                    h, stock.resolveHolds(cmd.mfcId(), holdKey(h), CORRECTION_REF + requestId, at));
              }
              if (!resolved.isEmpty()) {
                publishResolved(cmd.mfcId(), requestId, commitId, recorded, op.id(), resolved, at);
              }
              log.info(
                  "stock correction {} at {} ({} lines, {} units, ref {}) by {}",
                  commitId,
                  cmd.mfcId(),
                  cmd.lines().size(),
                  total,
                  recorded,
                  op.id());
              return new CommandResponse(
                  CommandStatus.APPLIED, requestId, commitId, total, null, false, null, at);
            })
        .value();
  }

  private static Key holdKey(HoldResolution h) {
    return new Key(h.locationCode(), h.sku(), h.lotId(), Quality.NORMAL, Work.UNSTOWED);
  }

  private static void requireHoldQty(HoldResolution h, int open) {
    if (open == 0) {
      throw new PmartException(
          NO_OPEN_HOLD,
          HttpStatus.CONFLICT,
          "적치 전 분실 조사 보류가 없습니다: " + h.locationCode() + " " + h.lotId(),
          false,
          List.of(new PmartException.Detail(h.sku(), "openHoldQty=0")));
    }
    if (h.foundQty() + h.lostQty() != open) {
      throw new PmartException(
          HOLD_QTY_MISMATCH,
          HttpStatus.CONFLICT,
          "찾음 + 못 찾음 = 열린 보류 " + open + "개여야 합니다: " + h.locationCode() + " " + h.lotId(),
          false,
          List.of(new PmartException.Detail(h.sku(), "openHoldQty=" + open)));
    }
  }

  /** StockHoldResolved(+ 못 찾은 양이 있으면 StockAdjusted) — 같은 트랜잭션 outbox. */
  private void publishResolved(
      String mfcId,
      String requestId,
      String commitId,
      String approvalRef,
      String by,
      List<Map<String, Object>> lines,
      Instant at) {
    Map<String, Object> p = new java.util.LinkedHashMap<>();
    p.put("resolutionId", requestId);
    p.put("mfcId", mfcId);
    p.put("commitId", commitId);
    p.put("lines", lines);
    p.put("approvalRef", approvalRef);
    p.put("resolvedBy", by);
    p.put("resolvedAt", at.toString());
    events.publish(
        codec.create(
            events.sourceSystem(),
            "StockHoldResolved",
            1,
            "StockHoldResolution",
            requestId,
            1,
            new EventEnvelope.Actor(EventEnvelope.ActorType.OPERATOR, by),
            EventEnvelope.EventSource.REAL,
            p),
        ReservationService.TOPIC);
    List<Map<String, Object>> lost = new ArrayList<>();
    for (Map<String, Object> l : lines) {
      int q = (Integer) l.get("lostQty");
      if (q > 0) {
        Map<String, Object> x = new java.util.LinkedHashMap<>();
        x.put("sku", l.get("sku"));
        x.put("lot", l.get("lot"));
        x.put("locCode", l.get("locCode"));
        x.put("deltaQty", -q);
        x.put("reason", "LOSS_WRITE_OFF");
        lost.add(x);
      }
    }
    if (lost.isEmpty()) {
      return;
    }
    Map<String, Object> a = new java.util.LinkedHashMap<>();
    a.put("adjId", requestId);
    a.put("mfcId", mfcId);
    a.put("lines", lost);
    a.put("approvalRef", Map.of("approvalId", approvalRef, "approverId", by));
    a.put("commitId", commitId);
    a.put("executedAt", at.toString());
    events.publish(
        codec.create(
            events.sourceSystem(),
            "StockAdjusted",
            1,
            "InventoryAdjustment",
            requestId,
            1,
            new EventEnvelope.Actor(EventEnvelope.ActorType.OPERATOR, by),
            EventEnvelope.EventSource.REAL,
            a),
        ReservationService.TOPIC);
  }

  @Transactional(readOnly = true)
  public CenterStockView stock(String mfcId, String sku) {
    Operator.requireCenter(mfcId);
    return new CenterStockView(
        stock.lotBalances(mfcId, sku),
        stock.units(mfcId, sku),
        skus.findAll(java.util.Set.of(sku)).get(sku));
  }

  static final int MAX_STOCK_SKUS = 100;

  /** 센터 재고 상세 여러 SKU(시뮬레이터 R-16, 0.33.0) — 같은 값은 한 번, 요청 순서. 1~100(넘으면 400). 재고 없는 SKU 도 빈 목록으로. */
  @Transactional(readOnly = true)
  public java.util.LinkedHashMap<String, CenterStockView> stocks(
      String mfcId, @Nullable List<String> skuList) {
    Operator.requireCenter(mfcId);
    if (skuList == null || skuList.isEmpty()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "sku 는 1~" + MAX_STOCK_SKUS + "개입니다.");
    }
    List<String> wanted = List.copyOf(new java.util.LinkedHashSet<>(skuList));
    if (wanted.size() > MAX_STOCK_SKUS) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "sku 는 1~" + MAX_STOCK_SKUS + "개입니다.");
    }
    java.util.Map<String, List<LotBalance>> lots = new java.util.HashMap<>();
    for (LotBalance b : stock.lotBalances(mfcId, wanted)) {
      lots.computeIfAbsent(b.sku(), k -> new java.util.ArrayList<>()).add(b);
    }
    java.util.Map<String, List<UnitRow>> units = new java.util.HashMap<>();
    for (UnitRow u : stock.units(mfcId, wanted)) {
      units.computeIfAbsent(u.key().sku(), k -> new java.util.ArrayList<>()).add(u);
    }
    var projections = skus.findAll(new java.util.HashSet<>(wanted));
    var out = new java.util.LinkedHashMap<String, CenterStockView>();
    for (String s : wanted) {
      out.put(
          s,
          new CenterStockView(
              lots.getOrDefault(s, List.of()),
              units.getOrDefault(s, List.of()),
              projections.get(s)));
    }
    return out;
  }
}
