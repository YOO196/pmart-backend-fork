package com.pmart.mfc.quality.api;

import com.pmart.mfc.quality.api.generated.DisposalsApi;
import com.pmart.mfc.quality.api.generated.model.CreateQualityDecision202Response;
import com.pmart.mfc.quality.api.generated.model.DisposalApproval;
import com.pmart.mfc.quality.api.generated.model.DisposalApprovalLinesInner;
import com.pmart.mfc.quality.api.generated.model.DisposalApprovalRequest;
import com.pmart.mfc.quality.api.generated.model.DisposalExecution;
import com.pmart.mfc.quality.api.generated.model.ExecuteDisposalRequest;
import com.pmart.mfc.quality.application.DisposalService;
import com.pmart.mfc.quality.infrastructure.QualityStore.ExecutionRow;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 DisposalsApi — 폐기 요청·승인·실행(0.2.0). 업무 규칙은 {@link DisposalService}. */
@RestController
public class DisposalsController implements DisposalsApi {

  private final DisposalService service;

  private final com.pmart.mfc.quality.application.QualityLists lists;

  public DisposalsController(
      DisposalService service, com.pmart.mfc.quality.application.QualityLists lists) {
    this.lists = lists;
    this.service = service;
  }

  @Override
  public ResponseEntity<CreateQualityDecision202Response> decideDisposal(
      String idempotencyKey,
      String xSourceSystem,
      DisposalApprovalRequest body,
      @Nullable Integer xExpectedVersion) {
    var r =
        service.decide(
            idempotencyKey,
            xSourceSystem,
            new DisposalService.DecideCommand(
                body.getAction().getValue(),
                body.getMfcId(),
                body.getDisposalApprovalId(),
                body.getActorId(),
                body.getApprovalRef(),
                body.getReason(),
                body.getSourceRef(),
                body.getLines() == null ? null : Dtos.lines(body.getLines())));
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(Dtos.command(r));
  }

  @Override
  public ResponseEntity<DisposalExecution> executeDisposal(
      String disposalApprovalId,
      String idempotencyKey,
      String xSourceSystem,
      ExecuteDisposalRequest body) {
    ExecutionRow e =
        service.execute(
            disposalApprovalId, idempotencyKey, Dtos.lines(body.getLines()), body.getNote());
    return ResponseEntity.status(HttpStatus.CREATED).body(execution(e));
  }

  /** 목록(MB-1, 0.5.0) — 항목은 단건 조회와 같은 변환. */
  @Override
  public ResponseEntity<com.pmart.mfc.quality.api.generated.model.ListDisposalApprovals200Response>
      listDisposalApprovals(
          String mfcId,
          java.util.@org.jspecify.annotations.Nullable List<String> state,
          Integer size,
          @org.jspecify.annotations.Nullable String cursor) {
    int limit = size == null ? 50 : size;
    var ids =
        lists.ids(
            com.pmart.mfc.quality.infrastructure.QualityListStore.Kind.DISPOSAL,
            mfcId,
            state,
            limit + 1,
            cursor);
    boolean more = ids.size() > limit;
    var page = more ? ids.subList(0, limit) : ids;
    var out = new com.pmart.mfc.quality.api.generated.model.ListDisposalApprovals200Response();
    out.setItems(page.stream().map(id -> getDisposalApproval(id).getBody()).toList());
    out.setNextCursor(more ? page.get(page.size() - 1) : null);
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<DisposalApproval> getDisposalApproval(String disposalApprovalId) {
    var v = service.view(disposalApprovalId);
    var out = new DisposalApproval();
    out.setDisposalApprovalId(v.row().disposalApprovalId());
    out.setMfcId(v.row().mfcId());
    out.setVersion(v.row().version());
    out.setState(DisposalApproval.StateEnum.fromValue(v.row().state()));
    out.setRequesterId(v.row().requesterId());
    out.setApproverId(v.row().approverId());
    out.setApprovalRef(v.row().approvalRef());
    out.setSourceRef(v.row().sourceRef());
    out.setReason(v.row().reason());
    out.setRequestedAt(Dtos.time(v.row().requestedAt()));
    out.setDecidedAt(Dtos.time(v.row().decidedAt()));
    out.setLines(
        v.lines().stream()
            .map(
                d -> {
                  var l = new DisposalApprovalLinesInner();
                  l.setSku(d.sku());
                  l.setLotId(d.lotId());
                  l.setLocationCode(d.locationCode());
                  l.setRequestedQty(d.requestedQty());
                  l.setApprovedQty(d.approvedQty());
                  l.setExecutedQty(d.executedQty());
                  l.setRemainingQty(d.remaining());
                  return l;
                })
            .toList());
    out.setExecutions(v.executions().stream().map(this::execution).toList());
    return ResponseEntity.ok(out);
  }

  private DisposalExecution execution(ExecutionRow e) {
    var x = new DisposalExecution();
    x.setExecutionId(e.executionId());
    x.setDisposalApprovalId(e.disposalApprovalId());
    x.setState(DisposalExecution.StateEnum.fromValue(e.state()));
    x.setExecutedBy(e.executedBy());
    x.setLines(Dtos.qualityLines(service.lines(e)));
    x.setQty(e.qty());
    x.setInventoryCommitId(e.inventoryCommitId());
    x.setRejectReason(e.rejectReason());
    x.setExecutedAt(Dtos.time(e.createdAt()));
    return x;
  }
}
