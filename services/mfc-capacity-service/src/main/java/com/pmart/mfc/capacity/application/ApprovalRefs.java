package com.pmart.mfc.capacity.application;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.common.web.PmartException.Detail;
import com.pmart.mfc.capacity.infrastructure.ApprovalRefClient;
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
 * 운영 제한 해제의 승인 번호 관문. 설정 {@code pmart.approval-ref.mode}:
 *
 * <ul>
 *   <li>{@code VERIFY}(요청 AP-1, 사용자 2026-11-17 "운영 제한 해제는 항상 본사"): 기준 없이 늘 본사 승인(approval-service
 *       {@code MFC_OPERATION_RESTRICTION}, OPERATOR_ADMIN). 번호가 없으면 422 APPROVAL_REQUIRED + details
 *       {@code approvalRequest}(변경 요청 본문 JSON), 있으면 해제 전 {@code /internal/approval-ref-uses}(useRef
 *       = {@code mfc-capacity-service:RESTRICTION_LIFT:<id>}) — 쓸 수 없으면 422 APPROVAL_REQUIRED.
 *   <li>{@code RECORD_ONLY}(2026-11-08 외부 검토 #8 — LAN 시험용): 번호를 확인 없이 기록(번호 필수).
 *   <li>그 밖·미설정: CONFIG_REQUIRED. 제한 적용은 보호 동작이라 이 관문을 거치지 않는다.
 * </ul>
 */
@Component
public class ApprovalRefs {

  private static final Logger log = LoggerFactory.getLogger(ApprovalRefs.class);

  public static final String KEY = "pmart.approval-ref.mode (승인 번호 검증 — VERIFY 또는 시험용 RECORD_ONLY)";
  static final String TYPE = "MFC_OPERATION_RESTRICTION";
  static final String KIND = "RESTRICTION_LIFT";

  private final String mode;
  private final ApprovalRefClient client;
  private final ObjectMapper mapper;

  public ApprovalRefs(
      @Value("${pmart.approval-ref.mode:}") String mode,
      ApprovalRefClient client,
      ObjectMapper mapper) {
    this.mode = mode == null ? "" : mode.trim();
    this.client = client;
    this.mapper = mapper;
  }

  /** 해제 전 관문 — 통과하면 기록할 승인 번호. */
  public String requireLift(
      String mfcId,
      String restrictionId,
      String actorId,
      String summary,
      @Nullable String approvalRef) {
    String given = approvalRef == null || approvalRef.isBlank() ? null : approvalRef.trim();
    if ("RECORD_ONLY".equals(mode)) {
      if (given == null) {
        throw new PmartException(ErrorCode.APPROVAL_REQUIRED, "해제에는 승인 근거(approvalRef)가 필요합니다.");
      }
      return given;
    }
    if (!"VERIFY".equals(mode)) {
      throw PmartException.configRequired(KEY);
    }
    String targetRef = mfcId + ":" + KIND + ":" + restrictionId;
    if (given == null) {
      throw new PmartException(
          ErrorCode.APPROVAL_REQUIRED,
          "운영 제한 해제는 본사 승인이 필요합니다 — details 의 approvalRequest 로 승인을 받고 그 승인 번호를 approvalRef 로 보내 주세요.",
          List.of(
              new Detail(
                  "approvalRequest",
                  mapper.writeValueAsString(request(mfcId, targetRef, summary)))));
    }
    ApprovalRefClient.Use use =
        client.use(
            given,
            TYPE,
            targetRef,
            actorId,
            ApprovalRefClient.SELF + ":" + KIND + ":" + restrictionId);
    if (!use.usable()) {
      throw new PmartException(
          ErrorCode.APPROVAL_REQUIRED,
          "승인 번호 " + given + " 를 쓸 수 없습니다: " + use.reason(),
          List.of(new Detail("approvalRef", String.valueOf(use.reason()))));
    }
    log.info("본사 승인 {} 사용 — {} by {}", given, targetRef, actorId);
    return given;
  }

  static Map<String, Object> request(String mfcId, String targetRef, String summary) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("type", TYPE);
    m.put("targetService", ApprovalRefClient.SELF);
    m.put("targetRef", targetRef);
    m.put("centerScope", List.of(mfcId));
    m.put("allowedActions", List.of("LIFT_OPERATION_RESTRICTION"));
    m.put("summary", summary.length() > 500 ? summary.substring(0, 500) : summary);
    return m;
  }
}
