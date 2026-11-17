package com.pmart.mfc.inventory.application;

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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * LOT·위치 정정(CORRECTION, 총량 불변 — WMS 문서 LOT 식별 정정, ADR-0040 결정 7). 출발 단위 행에서 빼서 목적 위치·목적 LOT 으로 옮긴다.
 * 품질·업무상태는 그대로. 예약(R)이 걸린 수량은 LOT 의 R ≤ E 검사(Ledger)로 막힌다. 센터 운영자만, 근거(approvalRef) 필수.
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

  public record CorrectionCommand(
      String mfcId, String reason, String approvalRef, List<CorrectionLine> lines) {}

  /** 센터 재고 상세. */
  public record CenterStockView(List<LotBalance> lots, List<UnitRow> units) {}

  private final StockStore stock;
  private final Ledger ledger;
  private final Locations locations;
  private final SkuProjectionStore skus;
  private final RequestIdempotency idempotency;
  private final Clock clock;

  private final ApprovalRefs approvalRefs;

  public CorrectionService(
      StockStore stock,
      Ledger ledger,
      Locations locations,
      SkuProjectionStore skus,
      RequestIdempotency idempotency,
      Clock clock,
      ApprovalRefs approvalRefs) {
    this.approvalRefs = approvalRefs;
    this.stock = stock;
    this.ledger = ledger;
    this.locations = locations;
    this.skus = skus;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  public CommandResponse correct(String requestId, String sourceSystem, CorrectionCommand cmd) {
    Operator op = Operator.forCenter(cmd.mfcId(), sourceSystem);
    if (cmd.lines().isEmpty() || cmd.lines().size() > 500) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "정정 줄은 1~500개입니다.");
    }
    for (CorrectionLine l : cmd.lines()) {
      if (l.qty() < 1) {
        throw new PmartException(ErrorCode.VALIDATION_FAILED, "수량은 1 이상이어야 합니다: " + l.sku());
      }
    }
    // 승인 번호 관문(AP-1): 정정은 총량 불변(손실 없음) — 한 SKU 수량 기준만 걸린다. 참조 = 이 요청 키(승인 요청 targetRef 와 같아야)
    Map<String, Integer> bySku = new java.util.TreeMap<>();
    cmd.lines().forEach(l -> bySku.merge(l.sku(), l.qty(), Integer::sum));
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
                            e.getKey(), e.getValue(), 0, Map.of()))
                .toList(),
            cmd.approvalRef());
    String recorded = given != null ? given : CountService.CENTER_APPROVAL + requestId;
    int total = cmd.lines().stream().mapToInt(CorrectionLine::qty).sum();
    return idempotency
        .execute(
            op.key(requestId, OP),
            cmd,
            CommandResponse.class,
            () -> {
              Instant at = clock.instant();
              Map<String, LotRow> lots =
                  stock.lots(
                      cmd.lines().stream().map(CorrectionLine::lotId).collect(Collectors.toSet()));
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
              String commitId =
                  ledger.post(
                      "CORRECTION",
                      cmd.mfcId(),
                      requestId,
                      sourceSystem,
                      recorded,
                      total,
                      changes,
                      List.of(),
                      at);
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

  @Transactional(readOnly = true)
  public CenterStockView stock(String mfcId, String sku) {
    Operator.requireCenter(mfcId);
    return new CenterStockView(stock.lotBalances(mfcId, sku), stock.units(mfcId, sku));
  }
}
