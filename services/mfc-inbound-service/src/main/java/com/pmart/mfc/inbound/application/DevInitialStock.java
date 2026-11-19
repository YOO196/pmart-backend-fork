package com.pmart.mfc.inbound.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.inbound.infrastructure.InitialStockStore;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * [LAN 시험 전용] 승인 없이 시험 입고 예정(0.14.0, 요청 MI-3·MFC 시뮬레이터 R-23). {@code
 * pmart.inbound.dev-arrivals.enabled} 가 켜진 곳에서만 — 꺼지면 404. INITIAL_STOCK 승인과 같은 EXPECTED 하나만 만들고,
 * 재고는 지금 흐름(현장 수령 → 품질검사 → 적치)으로 들어간다. 정식 입고가 생기면 없앤다.
 */
@Service
public class DevInitialStock {

  public static final String UNKNOWN_SKU = "UNKNOWN_SKU";
  static final int MAX_LINES = 40;
  static final int MAX_QTY = 500;
  private static final Logger log = LoggerFactory.getLogger(DevInitialStock.class);

  public record Line(String sku, int qty) {}

  public record Command(String mfcId, List<Line> lines) {}

  private final boolean enabled;
  private final InitialStockStore store;
  private final SkuLabels labels;
  private final RequestIdempotency idempotency;
  private final Clock clock;

  public DevInitialStock(
      @Value("${pmart.inbound.dev-arrivals.enabled:false}") boolean enabled,
      InitialStockStore store,
      SkuLabels labels,
      RequestIdempotency idempotency,
      Clock clock) {
    this.enabled = enabled;
    this.store = store;
    this.labels = labels;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  public InitialStockStore.LoadRow create(String requestId, String sourceSystem, Command c) {
    if (!enabled) {
      throw PmartException.notFound(ErrorCode.NOT_FOUND.name(), "없는 경로입니다.");
    }
    String me = InboundTaskService.requireOperator(c.mfcId());
    List<Line> lines = c.lines() == null ? List.of() : c.lines();
    if (lines.isEmpty() || lines.size() > MAX_LINES) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "줄은 1~" + MAX_LINES + "개입니다.");
    }
    Map<String, Integer> skus = new TreeMap<>();
    for (Line l : lines) {
      if (l.qty() < 1 || l.qty() > MAX_QTY) {
        throw new PmartException(
            ErrorCode.VALIDATION_FAILED, "수량은 1~" + MAX_QTY + " 입니다: " + l.sku());
      }
      if (skus.put(l.sku(), l.qty()) != null) {
        throw new PmartException(ErrorCode.VALIDATION_FAILED, "같은 SKU 가 두 번 있습니다: " + l.sku());
      }
    }
    Map<String, SkuLabels.Label> known = labels.of(skus.keySet());
    List<String> unknown = new ArrayList<>();
    for (String sku : skus.keySet()) {
      if (SkuLabels.get(known, sku).productName() == null) {
        unknown.add(sku);
      }
    }
    if (!unknown.isEmpty()) {
      throw new PmartException(
          UNKNOWN_SKU,
          HttpStatus.BAD_REQUEST,
          "재고 서비스가 모르는 SKU 입니다(조회 실패일 수도 — 다시 시도): " + unknown,
          true,
          unknown.stream().map(s -> new PmartException.Detail("sku", s)).toList());
    }
    String receiptId =
        idempotency
            .execute(
                new RequestKey(sourceSystem, requestId, "createDevInitialStockLoad", me),
                new Command(c.mfcId(), lines),
                String.class,
                () -> {
                  String id = "RCV-INIT-" + UUID.randomUUID();
                  String approvalId = "DEV-" + UUID.randomUUID().toString().substring(0, 8);
                  Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
                  store.insert(
                      id,
                      approvalId,
                      c.mfcId(),
                      "EXPECTED",
                      null,
                      me,
                      now,
                      new LinkedHashMap<>(skus));
                  log.info(
                      "[LAN 시험] 시험 입고 예정 {} ({}) — {} {} SKU by {}",
                      id,
                      approvalId,
                      c.mfcId(),
                      skus.size(),
                      me);
                  return id;
                })
            .value();
    return store.find(receiptId).orElseThrow();
  }
}
