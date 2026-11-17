package com.pmart.mfc.inventory.application;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.common.web.PmartException.Detail;
import com.pmart.mfc.inventory.domain.HqApprovalRules;
import com.pmart.mfc.inventory.domain.HqApprovalRules.Assessment;
import com.pmart.mfc.inventory.domain.HqApprovalRules.Line;
import com.pmart.mfc.inventory.domain.HqApprovalRules.Price;
import com.pmart.mfc.inventory.infrastructure.ApprovalRefClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 승인 번호(approvalRef) 관문. 설정 {@code pmart.approval-ref.mode}:
 *
 * <ul>
 *   <li>{@code VERIFY}(요청 AP-1, 2026-11-17): 본사 승인 기준({@link HqApprovalRules} — 손실 {@code
 *       hq-loss-krw}원·한 SKU {@code hq-sku-qty}개, 단가 = pricing 판매 기준가) 아래면 센터 관리자 승인만(번호 확인 없음, 주면
 *       기록만). 이상이면 번호가 없을 때 422 APPROVAL_REQUIRED + details {@code
 *       approvalRequest}(approval-service {@code POST /change-requests} 에 보낼 본문 JSON), 있으면 집행 전
 *       {@code /internal/approval-ref-uses}(useRef = {@code mfc-inventory-service:<kind>:<ref>}) —
 *       쓸 수 없거나 승인 범위(SKU 수량)를 넘으면 422 APPROVAL_REQUIRED.
 *   <li>{@code RECORD_ONLY}(2026-11-08 외부 검토 #8 — LAN 시험용): 번호를 확인 없이 기록(번호 필수).
 *   <li>그 밖·미설정: CONFIG_REQUIRED(운영에서 확인 없이 집행하지 않는다).
 * </ul>
 */
@Component
public class ApprovalRefs {

  private static final Logger log = LoggerFactory.getLogger(ApprovalRefs.class);

  public static final String KEY = "pmart.approval-ref.mode (승인 번호 검증 — VERIFY 또는 시험용 RECORD_ONLY)";
  static final String TYPE = "MFC_STOCK_ADJUSTMENT";

  private final String mode;
  private final @Nullable Long lossThreshold;
  private final @Nullable Integer skuQtyThreshold;
  private final ApprovalRefClient client;
  private final ObjectMapper mapper;

  public ApprovalRefs(
      @Value("${pmart.approval-ref.mode:}") String mode,
      @Value("${pmart.approval-ref.hq-loss-krw:#{null}}") @Nullable Long lossThreshold,
      @Value("${pmart.approval-ref.hq-sku-qty:#{null}}") @Nullable Integer skuQtyThreshold,
      ApprovalRefClient client,
      ObjectMapper mapper) {
    this.mode = mode == null ? "" : mode.trim();
    this.lossThreshold = lossThreshold;
    this.skuQtyThreshold = skuQtyThreshold;
    this.client = client;
    this.mapper = mapper;
  }

  /**
   * 집행 전 관문. 통과하면 기록할 승인 번호(없을 수 있음)를 돌려준다.
   *
   * @param kind COUNT_ADJUSTMENT·CORRECTION
   * @param ref 실사 세션 ID·정정 요청 키 — 승인 요청의 targetRef 와 한 글자도 달라선 안 된다
   */
  public @Nullable String require(
      String mfcId,
      String kind,
      String ref,
      String actorId,
      String summary,
      List<Line> lines,
      @Nullable String approvalRef) {
    String given = approvalRef == null || approvalRef.isBlank() ? null : approvalRef.trim();
    if ("RECORD_ONLY".equals(mode)) {
      if (given == null) {
        throw new PmartException(ErrorCode.APPROVAL_REQUIRED, "승인 근거(approvalRef)가 필요합니다.");
      }
      return given;
    }
    if (!"VERIFY".equals(mode)) {
      throw PmartException.configRequired(KEY);
    }
    if (lossThreshold == null) {
      throw PmartException.configRequired("pmart.approval-ref.hq-loss-krw");
    }
    if (skuQtyThreshold == null) {
      throw PmartException.configRequired("pmart.approval-ref.hq-sku-qty");
    }
    List<String> decreasing =
        lines.stream().filter(l -> l.decrease() > 0).map(Line::sku).distinct().toList();
    Map<String, Price> prices = decreasing.isEmpty() ? Map.of() : client.prices(decreasing);
    Assessment a = HqApprovalRules.assess(lines, prices, lossThreshold, skuQtyThreshold);
    if (!a.hqRequired()) {
      return given; // 센터 관리자 승인만 — 번호는 확인하지 않고 주면 기록
    }
    String targetRef = mfcId + ":" + kind + ":" + ref;
    if (given == null) {
      List<Detail> details = new ArrayList<>();
      details.add(
          new Detail(
              "approvalRequest",
              mapper.writeValueAsString(request(mfcId, kind, targetRef, summary, a))));
      a.reasons().forEach(r -> details.add(new Detail("threshold", r)));
      throw new PmartException(
          ErrorCode.APPROVAL_REQUIRED,
          "본사 재고 승인이 필요합니다("
              + String.join(", ", a.reasons())
              + ") — details 의 approvalRequest 로 승인을 받고 그 승인 번호를 approvalRef 로 보내 주세요.",
          details);
    }
    String useRef = ApprovalRefClient.SELF + ":" + kind + ":" + ref;
    ApprovalRefClient.Use use = client.use(given, TYPE, targetRef, actorId, useRef);
    if (!use.usable()) {
      throw new PmartException(
          ErrorCode.APPROVAL_REQUIRED,
          "승인 번호 " + given + " 를 쓸 수 없습니다: " + use.reason(),
          List.of(new Detail("approvalRef", String.valueOf(use.reason()))));
    }
    String out = HqApprovalRules.outOfScope(lines, use.approved());
    if (out != null) {
      throw new PmartException(
          ErrorCode.APPROVAL_REQUIRED, out, List.of(new Detail("approvalRef", "OUT_OF_SCOPE")));
    }
    log.info("본사 승인 {} 사용 — {} by {}", given, targetRef, actorId);
    return given;
  }

  /** approval-service 변경 요청 본문(ChangeRequestBody). */
  static Map<String, Object> request(
      String mfcId, String kind, String targetRef, String summary, Assessment a) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("type", TYPE);
    m.put("targetService", ApprovalRefClient.SELF);
    m.put("targetRef", targetRef);
    m.put("centerScope", List.of(mfcId));
    m.put("allowedActions", List.of("POST_" + kind));
    m.put("summary", summary + " — " + String.join(", ", a.reasons()));
    m.put("lines", a.lines());
    return m;
  }
}
