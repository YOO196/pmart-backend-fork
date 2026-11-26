package com.pmart.mfc.inventory.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.web.CommandResponse;
import com.pmart.common.web.CommandStatus;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.inventory.domain.StockRules;
import com.pmart.mfc.inventory.domain.StockRules.ReceiptLine;
import com.pmart.mfc.inventory.domain.StockUnit.Change;
import com.pmart.mfc.inventory.domain.StockUnit.Key;
import com.pmart.mfc.inventory.domain.StockUnit.Quality;
import com.pmart.mfc.inventory.domain.StockUnit.Work;
import com.pmart.mfc.inventory.infrastructure.StockStore;
import com.pmart.mfc.inventory.infrastructure.StockStore.ReceiptLineRow;
import com.pmart.mfc.inventory.infrastructure.StockStore.ReceiptRow;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ConfirmReceipt(TIN 전기, IV-02, ADR-0040 결정 3). 수령 위치에 UNSTOWED 로 넣는다 — 정상·미판정 PENDING_INSPECTION,
 * 격리 QUARANTINED. H(와 격리분 Q)만 늘고 E 는 검수(INSPECT)·적치(PUTAWAY) 뒤에 생긴다. 줄마다 LOT 을 정한다: 상류 LOT 표기나
 * 소비기한이 있으면 같은 SKU·표기·기한의 추적 LOT, 둘 다 없으면 추적 미확인 LOT. 같은 receiptId 는 한 번만 계상한다(내용 지문이 같으면 처음
 * commitId).
 *
 * <p>(0.52.0, 요청 IV-8 — cdc-inventory 운송 중 재고 대사) CDC 이관(TRF) 수령({@code cdcTransfer})은 처음 전기하는 같은
 * 트랜잭션에서 {@code MfcReceiptPosted}(mfc.inventory.v1) — 줄 lot = CDC 출발 LOT 표기(sourceLotRef), mfcLotId
 * = 이 서비스 lotId, receivedQty = 정상 + 격리 + 미판정(사용자 결정 2026-11-25). 같은 receiptId 재전송은 사건을 다시 내지 않는다.
 * 수령 수량 승인 정정 흐름이 아직 없어 {@code ReceiptCorrectionPosted} 는 내지 않는다.
 */
@Service
public class ReceiptService {

  public static final String RECEIPT_ALREADY_POSTED = "RECEIPT_ALREADY_POSTED";
  public static final String RECEIPT_NOT_FOUND = "RECEIPT_NOT_FOUND";
  static final String OP = "confirmReceipt";

  private static final Logger log = LoggerFactory.getLogger(ReceiptService.class);

  /** 수령 명령. */
  public record ReceiptCommand(
      String receiptId,
      String trfId,
      String departureId,
      String mfcId,
      @Nullable Instant receivedAt,
      List<ReceiptLine> lines,
      boolean cdcTransfer,
      @Nullable String fromCdc) {

    public ReceiptCommand(
        String receiptId,
        String trfId,
        String departureId,
        String mfcId,
        @Nullable Instant receivedAt,
        List<ReceiptLine> lines) {
      this(receiptId, trfId, departureId, mfcId, receivedAt, lines, false, null);
    }
  }

  private final StockStore stock;
  private final Ledger ledger;
  private final Locations locations;
  private final RequestIdempotency idempotency;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;
  private final Clock clock;

  public ReceiptService(
      StockStore stock,
      Ledger ledger,
      Locations locations,
      RequestIdempotency idempotency,
      EventPublisher events,
      EventEnvelopeCodec codec,
      Clock clock) {
    this.stock = stock;
    this.ledger = ledger;
    this.locations = locations;
    this.idempotency = idempotency;
    this.events = events;
    this.codec = codec;
    this.clock = clock;
  }

  public CommandResponse confirm(String requestId, String sourceSystem, ReceiptCommand cmd) {
    int total;
    try {
      total = StockRules.validateReceipt(cmd.lines());
    } catch (IllegalArgumentException | ArithmeticException e) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, e.getMessage());
    }
    String fingerprint =
        StockRules.receiptFingerprint(cmd.mfcId(), cmd.trfId(), cmd.departureId(), cmd.lines());
    return idempotency
        .execute(
            Callers.key(sourceSystem, requestId, OP),
            cmd,
            CommandResponse.class,
            () -> {
              Instant at = clock.instant();
              cmd.lines().stream()
                  .map(ReceiptLine::locationCode)
                  .distinct()
                  .forEach(loc -> locations.requireReceiving(cmd.mfcId(), loc));
              String commitId = Ledger.newCommitId();
              if (!stock.claimReceipt(
                  cmd.receiptId(), cmd.mfcId(), fingerprint, commitId, total, at)) {
                return already(requestId, cmd.receiptId(), fingerprint);
              }
              List<Change> changes = new ArrayList<>();
              List<ReceiptLineRow> rows = new ArrayList<>();
              int lineNo = 0;
              for (ReceiptLine l : cmd.lines()) {
                lineNo++;
                String lotId = lotFor(l, cmd.receiptId(), at);
                Key pending =
                    new Key(
                        l.locationCode(),
                        l.sku(),
                        lotId,
                        Quality.PENDING_INSPECTION,
                        Work.UNSTOWED);
                int pend = l.normalQty() + l.pendingQty();
                if (pend > 0) {
                  changes.add(new Change(pending, pend));
                }
                if (l.quarantinedQty() > 0) {
                  changes.add(
                      new Change(
                          pending.with(Quality.QUARANTINED, Work.UNSTOWED), l.quarantinedQty()));
                }
                rows.add(
                    new ReceiptLineRow(
                        lineNo,
                        l.sku(),
                        lotId,
                        l.locationCode(),
                        l.normalQty(),
                        l.quarantinedQty(),
                        l.pendingQty()));
              }
              ledger.postAs(
                  commitId,
                  "TIN",
                  cmd.mfcId(),
                  requestId,
                  sourceSystem,
                  cmd.receiptId(),
                  total,
                  changes,
                  List.of(),
                  at);
              for (ReceiptLineRow r : rows) {
                stock.insertReceiptLine(cmd.receiptId(), r);
              }
              if (cmd.cdcTransfer()) {
                publishPosted(cmd, commitId, rows, total, at);
              }
              log.info(
                  "receipt {} posted to {} as {} ({} units, {} lines)",
                  cmd.receiptId(),
                  cmd.mfcId(),
                  commitId,
                  total,
                  rows.size());
              return new CommandResponse(
                  CommandStatus.APPLIED, requestId, commitId, total, null, false, null, at);
            })
        .value();
  }

  /** MfcReceiptPosted — 줄 순서는 수령 줄 그대로(rows 와 cmd.lines 는 같은 순서). */
  private void publishPosted(
      ReceiptCommand cmd, String commitId, List<ReceiptLineRow> rows, int total, Instant at) {
    List<Map<String, Object>> lines = new ArrayList<>();
    for (int i = 0; i < rows.size(); i++) {
      ReceiptLineRow r = rows.get(i);
      ReceiptLine in = cmd.lines().get(i);
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("sku", r.sku());
      if (in.sourceLotRef() != null) {
        m.put("lot", in.sourceLotRef());
      }
      m.put("mfcLotId", r.lotId());
      m.put("normalQty", r.normalQty());
      m.put("quarantinedQty", r.quarantinedQty());
      m.put("pendingQty", r.pendingQty());
      lines.add(m);
    }
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("receiptId", cmd.receiptId());
    p.put("postingId", commitId);
    p.put("commitId", commitId);
    p.put("trfId", cmd.trfId());
    p.put("departureId", cmd.departureId());
    p.put("mfcId", cmd.mfcId());
    if (cmd.fromCdc() != null) {
      p.put("fromCdc", cmd.fromCdc());
    }
    p.put("lines", lines);
    p.put("receivedQty", total);
    p.put("postedAt", at.toString());
    events.publish(
        codec.create(
            events.sourceSystem(),
            "MfcReceiptPosted",
            1,
            "Receipt",
            cmd.receiptId(),
            1,
            new EventEnvelope.Actor(EventEnvelope.ActorType.SYSTEM, events.sourceSystem()),
            EventEnvelope.EventSource.REAL,
            p),
        HandoverPostingService.TOPIC);
  }

  private String lotFor(ReceiptLine l, String receiptId, Instant at) {
    String newId = "LOT-" + UUID.randomUUID();
    if (l.sourceLotRef() == null && l.expiryDate() == null) {
      return stock.untrackedLot(l.sku(), newId, receiptId, at);
    }
    return stock.trackedLot(l.sku(), l.sourceLotRef(), l.expiryDate(), null, newId, receiptId, at);
  }

  /** 수령 결과. */
  public record ReceiptView(String commitId, List<ReceiptLineRow> lines) {}

  /** 수령 결과(줄별 LOT·위치). inbound 가 검수·적치에 쓴다. */
  @Transactional(readOnly = true)
  public ReceiptView result(String receiptId) {
    ReceiptRow r =
        stock
            .receipt(receiptId)
            .orElseThrow(
                () -> PmartException.notFound(RECEIPT_NOT_FOUND, "수령 전기가 없습니다: " + receiptId));
    return new ReceiptView(r.commitId(), stock.receiptLines(receiptId));
  }

  /** 같은 receiptId 가 이미 전기됨: 같은 내용이면 처음 commitId, 다르면 409. */
  private CommandResponse already(String requestId, String receiptId, String fingerprint) {
    Optional<ReceiptRow> row = stock.receipt(receiptId);
    if (row.isEmpty() || !row.get().contentHash().equals(fingerprint)) {
      throw new PmartException(
          RECEIPT_ALREADY_POSTED,
          HttpStatus.CONFLICT,
          "receiptId " + receiptId + " 는 다른 내용으로 이미 전기되었습니다.",
          false,
          List.of());
    }
    return new CommandResponse(
        CommandStatus.APPLIED,
        requestId,
        row.get().commitId(),
        row.get().appliedQty(),
        null,
        false,
        null,
        clock.instant());
  }
}
