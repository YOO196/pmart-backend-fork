package com.pmart.mfc.quality.api;

import com.pmart.mfc.quality.api.generated.DecisionsApi;
import com.pmart.mfc.quality.api.generated.model.ApproveQualityDecisionRequest;
import com.pmart.mfc.quality.api.generated.model.CreateQualityDecision202Response;
import com.pmart.mfc.quality.api.generated.model.InventoryApplyStatus;
import com.pmart.mfc.quality.api.generated.model.QualityDecision;
import com.pmart.mfc.quality.api.generated.model.QualityDecisionRequest;
import com.pmart.mfc.quality.application.QualityDecisionService;
import com.pmart.mfc.quality.infrastructure.QualityStore.DecisionRow;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** 계약 DecisionsApi — 격리·해제 판정(0.2.0). 업무 규칙은 {@link QualityDecisionService}. */
@RestController
public class DecisionsController implements DecisionsApi {

  private final QualityDecisionService service;
  private final ObjectMapper mapper;

  private final com.pmart.mfc.quality.application.QualityLists lists;

  public DecisionsController(
      QualityDecisionService service,
      ObjectMapper mapper,
      com.pmart.mfc.quality.application.QualityLists lists) {
    this.lists = lists;
    this.service = service;
    this.mapper = mapper;
  }

  @Override
  public ResponseEntity<CreateQualityDecision202Response> createQualityDecision(
      String idempotencyKey, String xSourceSystem, QualityDecisionRequest body) {
    var r =
        service.create(
            idempotencyKey,
            xSourceSystem,
            new QualityDecisionService.CreateCommand(
                body.getMfcId(),
                body.getDecisionType().getValue(),
                body.getDecidedBy(),
                body.getReason().getValue(),
                body.getIncidentId(),
                body.getNote(),
                body.getEvidenceRefs() == null ? List.of() : body.getEvidenceRefs(),
                Dtos.lines(body.getLines())));
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(Dtos.command(r));
  }

  @Override
  public ResponseEntity<QualityDecision> approveQualityDecision(
      String qualityDecisionId,
      String idempotencyKey,
      String xSourceSystem,
      ApproveQualityDecisionRequest body) {
    return ResponseEntity.ok(
        dto(
            service.approve(
                qualityDecisionId,
                body.getAction().getValue(),
                body.getNote(),
                body.getApprovalRef())));
  }

  /** 목록(MB-1, 0.5.0) — 항목은 단건 조회와 같은 변환. */
  @Override
  public ResponseEntity<com.pmart.mfc.quality.api.generated.model.ListQualityDecisions200Response>
      listQualityDecisions(
          String mfcId,
          java.util.@org.jspecify.annotations.Nullable List<String> state,
          Integer size,
          @org.jspecify.annotations.Nullable String cursor) {
    int limit = size == null ? 50 : size;
    var ids =
        lists.ids(
            com.pmart.mfc.quality.infrastructure.QualityListStore.Kind.DECISION,
            mfcId,
            state,
            limit + 1,
            cursor);
    boolean more = ids.size() > limit;
    var page = more ? ids.subList(0, limit) : ids;
    var out = new com.pmart.mfc.quality.api.generated.model.ListQualityDecisions200Response();
    out.setItems(page.stream().map(id -> getQualityDecision(id).getBody()).toList());
    out.setNextCursor(more ? page.get(page.size() - 1) : null);
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<QualityDecision> getQualityDecision(String qualityDecisionId) {
    return ResponseEntity.ok(dto(service.find(qualityDecisionId)));
  }

  private QualityDecision dto(DecisionRow d) {
    var out = new QualityDecision();
    out.setQualityDecisionId(d.decisionId());
    out.setMfcId(d.mfcId());
    out.setDecisionType(QualityDecision.DecisionTypeEnum.fromValue(d.decisionType()));
    out.setDecidedBy(d.decidedBy());
    out.setApproverId(d.approverId());
    out.setReason(QualityDecision.ReasonEnum.fromValue(d.reason()));
    out.setIncidentId(d.incidentId());
    out.setNote(d.note());
    out.setEvidenceRefs(mapper.readValue(d.evidenceRefs(), new TypeReference<List<String>>() {}));
    out.setLines(Dtos.qualityLines(service.lines(d)));
    out.setVersion(d.version());
    out.setState(QualityDecision.StateEnum.fromValue(d.state()));
    out.setRejectReason(d.rejectReason());
    out.setApprovedAt(Dtos.time(d.approvedAt()));
    out.setInventoryApplyStatus(
        switch (d.state()) {
          case "APPLIED" -> InventoryApplyStatus.APPLIED;
          case "REJECTED" -> InventoryApplyStatus.REJECTED;
          case "UNKNOWN_RESULT" -> InventoryApplyStatus.UNKNOWN_RESULT;
          case "SUBMITTED" -> InventoryApplyStatus.SUBMITTED;
          default -> InventoryApplyStatus.NOT_REQUESTED;
        });
    out.setInventoryCommitId(d.inventoryCommitId());
    out.setAppliedQty(d.appliedQty());
    out.setDecidedAt(Dtos.time(d.createdAt()));
    return out;
  }
}
