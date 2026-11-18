package com.pmart.mfc.inbound.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.inbound.domain.InitialStockRules;
import com.pmart.mfc.inbound.domain.InitialStockRules.Plan;
import com.pmart.mfc.inbound.domain.InitialStockRules.RawLine;
import com.pmart.mfc.inbound.infrastructure.InitialStockStore;
import com.pmart.mfc.inbound.infrastructure.InitialStockStore.LoadRow;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * 초기 보유 반입(결정 D2, 2026-11-07 입고 예정으로 변경 — ADR-0040 3단계): INITIAL_STOCK 승인(ApprovalDecided) → 센터별 입고
 * 예정(EXPECTED) → 현장 수령({@link InboundTaskService#receive}, PENDING) → {@link InitialStockPoster} 가
 * mfc-inventory ConfirmReceipt 로 전기하고 품질검사 작업을 만든다. 승인 반영은 InboxGuard 트랜잭션 안에서 기록만 만들고 HTTP 는 부르지
 * 않는다(외부 호출을 이벤트 처리 트랜잭션에 묶지 않음). 승인 내용이 틀리면 예외로 재전달을 반복하지 않고 REJECTED(INVALID: …) 기록을 남긴다.
 */
@Service
public class InitialStockService {

  public static final String TYPE = "INITIAL_STOCK";
  public static final String SERVICE = "mfc-inbound-service";
  public static final String NOT_FOUND = "INITIAL_STOCK_LOAD_NOT_FOUND";

  private static final Logger log = LoggerFactory.getLogger(InitialStockService.class);

  private final InitialStockStore store;
  private final Clock clock;

  public InitialStockService(InitialStockStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  /** InboxGuard 트랜잭션 안에서 호출된다. INITIAL_STOCK·이 서비스 대상·APPROVED 만 반영한다. */
  public void applyApproval(EventEnvelope e) {
    JsonNode p = e.payload();
    if (!"ApprovalDecided".equals(e.eventType())
        || !TYPE.equals(p.path("type").asString())
        || !SERVICE.equals(p.path("targetRef").path("targetService").asString())) {
      return;
    }
    String approvalId = p.path("approvalId").asString();
    if (!"APPROVED".equals(p.path("decision").asString())) {
      log.info("초기 보유 승인 {} 는 {} — 반입 없음", approvalId, p.path("decision").asString());
      return;
    }
    if (store.existsForApproval(approvalId)) {
      return;
    }
    String approver = p.hasNonNull("approverId") ? p.get("approverId").asString() : null;
    List<RawLine> lines = new ArrayList<>();
    for (JsonNode line : p.path("lines")) {
      if ("REJECTED".equals(line.path("decision").asString())) {
        continue;
      }
      JsonNode a = line.path("attributes");
      lines.add(
          new RawLine(
              text(line, "lineId"),
              text(a, "mfcId"),
              text(a, "sku"),
              a.path("qty").isIntegralNumber() ? a.get("qty").asInt() : null));
    }
    // receivedAt 은 재시도에도 같은 본문이 되도록 DB 정밀도(마이크로초) 아래를 버린다
    Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
    Plan plan = InitialStockRules.plan(lines);
    if (!plan.valid()) {
      store.insert(
          receiptId(),
          approvalId,
          plan.soleCenter(lines),
          "REJECTED",
          plan.rejectReason(),
          approver,
          now,
          Map.of());
      log.warn("초기 보유 승인 {} 반영 안 함: {}", approvalId, plan.rejectReason());
      return;
    }
    plan.byCenter()
        .forEach(
            (mfcId, skus) -> {
              String receiptId = receiptId();
              store.insert(receiptId, approvalId, mfcId, "EXPECTED", null, approver, now, skus);
              log.info(
                  "초기 보유 승인 {} → {} 입고 예정 {} ({} SKU)", approvalId, mfcId, receiptId, skus.size());
            });
  }

  @Transactional(readOnly = true)
  public LoadRow get(String receiptId) {
    return store
        .find(receiptId)
        .orElseThrow(() -> PmartException.notFound(NOT_FOUND, "초기 보유 반입 기록이 없습니다: " + receiptId));
  }

  /** 운영자 조회. 승인 수신 전이면 빈 목록. */
  @Transactional(readOnly = true)
  public List<LoadRow> byApproval(String approvalId) {
    PmartPrincipal.current()
        .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
        .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "운영자만 조회할 수 있습니다."));
    return store.byApproval(approvalId);
  }

  /** 받은 receiptId 순서대로(MB-1 목록 — 권한은 InboundLists 가 본다). */
  @Transactional(readOnly = true)
  public List<LoadRow> byReceiptIds(List<String> receiptIds) {
    return store.byReceiptIds(receiptIds);
  }

  private static String receiptId() {
    return "RCV-INIT-" + UUID.randomUUID();
  }

  private static @Nullable String text(JsonNode n, String field) {
    return n.hasNonNull(field) ? n.get(field).asString() : null;
  }
}
