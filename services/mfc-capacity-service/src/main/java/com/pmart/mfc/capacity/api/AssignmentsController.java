package com.pmart.mfc.capacity.api;

import com.pmart.mfc.capacity.api.generated.AssignmentsApi;
import com.pmart.mfc.capacity.api.generated.model.AssignTaskRequest;
import com.pmart.mfc.capacity.api.generated.model.Assignment;
import com.pmart.mfc.capacity.api.generated.model.GetReceivingCapabilities200ResponseAsOf;
import com.pmart.mfc.capacity.api.generated.model.ListAssignments200Response;
import com.pmart.mfc.capacity.application.AssignmentService;
import com.pmart.mfc.capacity.application.AssignmentService.AssignCommand;
import com.pmart.mfc.capacity.infrastructure.AssignmentStore.AssignmentRow;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 AssignmentsApi. 수동 배정·조회(슬라이스 6b). 계획(OR-Tools)은 아직 구현하지 않아 인터페이스 default 가 501 을 돌려준다. 업무 규칙은
 * application 에 두고 여기서는 요청·응답 변환만 한다.
 */
@RestController
public class AssignmentsController implements AssignmentsApi {

  private final AssignmentService service;
  private final com.pmart.mfc.capacity.application.PlanService plans;

  private com.pmart.mfc.capacity.application.PickRouteService routes;

  @org.springframework.beans.factory.annotation.Autowired
  void setRoutes(com.pmart.mfc.capacity.application.PickRouteService routes) {
    this.routes = routes;
  }

  /** 피킹 방문 순서(0.10.0, ADR-0050 B2) — fulfillment 가 피킹 시작 때 부른다. */
  @Override
  public ResponseEntity<com.pmart.mfc.capacity.api.generated.model.PlanPickRoute200Response>
      planPickRoute(
          String idempotencyKey,
          String xSourceSystem,
          com.pmart.mfc.capacity.api.generated.model.PlanPickRouteRequest body) {
    var r = routes.route(body.getMfcId(), body.getLocationCodes());
    var out = new com.pmart.mfc.capacity.api.generated.model.PlanPickRoute200Response();
    out.setOrdered(r.ordered());
    out.setStartLocation(r.startLocation());
    out.setMethod(
        com.pmart.mfc.capacity.api.generated.model.PlanPickRoute200Response.MethodEnum.fromValue(
            r.method()));
    out.setTotalMeters(java.math.BigDecimal.valueOf(r.totalMeters()));
    out.setMissing(r.missing());
    out.setLimitations(r.limitations());
    return ResponseEntity.ok(out);
  }

  public AssignmentsController(
      AssignmentService service, com.pmart.mfc.capacity.application.PlanService plans) {
    this.service = service;
    this.plans = plans;
  }

  @Override
  public ResponseEntity<com.pmart.mfc.capacity.api.generated.model.AssignmentPlan>
      createAssignmentPlan(
          String idempotencyKey,
          String xSourceSystem,
          com.pmart.mfc.capacity.api.generated.model.CreateAssignmentPlanRequest body) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            plan(
                plans.create(
                    idempotencyKey, xSourceSystem, body.getMfcId(), body.getHorizonMinutes())));
  }

  /** 지난 추천 목록(MB-1, 0.18.0) — 항목은 단건과 같은 변환. */
  @Override
  public ResponseEntity<com.pmart.mfc.capacity.api.generated.model.ListAssignmentPlans200Response>
      listAssignmentPlans(
          String mfcId,
          java.time.@org.jspecify.annotations.Nullable LocalDate date,
          java.util.@org.jspecify.annotations.Nullable List<String> state,
          Integer size,
          @org.jspecify.annotations.Nullable String cursor) {
    int limit = size == null ? 50 : size;
    var ids = plans.planIds(mfcId, date, state, limit + 1, cursor);
    boolean more = ids.size() > limit;
    var page = more ? ids.subList(0, limit) : ids;
    var out = new com.pmart.mfc.capacity.api.generated.model.ListAssignmentPlans200Response();
    out.setItems(page.stream().map(id -> plan(plans.get(id))).toList());
    out.setNextCursor(more ? page.get(page.size() - 1) : null);
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<com.pmart.mfc.capacity.api.generated.model.AssignmentPlan>
      getAssignmentPlan(String planId) {
    return ResponseEntity.ok(plan(plans.get(planId)));
  }

  @Override
  public ResponseEntity<com.pmart.mfc.capacity.api.generated.model.AssignmentPlan>
      commitAssignmentPlan(String planId, String idempotencyKey, String xSourceSystem) {
    return ResponseEntity.ok(plan(plans.commit(idempotencyKey, xSourceSystem, planId)));
  }

  @Override
  public ResponseEntity<
          com.pmart.mfc.capacity.api.generated.model.GetAssignmentPlanInput200Response>
      getAssignmentPlanInput(String planId) {
    var in = plans.input(planId);
    var out = new com.pmart.mfc.capacity.api.generated.model.GetAssignmentPlanInput200Response();
    out.setPlanId(in.planId());
    out.setInputHash(in.hash());
    out.setBytes(in.bytes());
    out.setCreatedAt(in.createdAt().atOffset(ZoneOffset.UTC));
    @SuppressWarnings("unchecked")
    java.util.Map<String, Object> body = JSON.readValue(in.json(), java.util.Map.class);
    out.setInput(body);
    return ResponseEntity.ok(out);
  }

  private static final tools.jackson.databind.ObjectMapper JSON =
      tools.jackson.databind.json.JsonMapper.builder().build();

  static com.pmart.mfc.capacity.api.generated.model.AssignmentPlan plan(
      com.pmart.mfc.capacity.application.PlanService.PlanView v) {
    var p = v.plan();
    var out = new com.pmart.mfc.capacity.api.generated.model.AssignmentPlan();
    out.setPlanId(p.planId());
    out.setInputHash(v.inputHash());
    out.setMfcId(p.mfcId());
    out.setState(
        com.pmart.mfc.capacity.api.generated.model.AssignmentPlan.StateEnum.fromValue(p.state()));
    out.setSolverStatus(
        com.pmart.mfc.capacity.api.generated.model.AssignmentPlan.SolverStatusEnum.fromValue(
            p.solverStatus()));
    out.setHorizonStart(p.horizonStart().atOffset(ZoneOffset.UTC));
    out.setHorizonMinutes(p.horizonMinutes());
    out.setItems(
        v.items().stream()
            .map(
                i -> {
                  var x = new com.pmart.mfc.capacity.api.generated.model.AssignmentPlanItemsInner();
                  x.setTaskId(i.taskId());
                  x.setTaskType(i.taskType());
                  x.setTaskVersion(i.taskVersion());
                  x.setWorkerId(i.workerId());
                  x.setDutyVersion(i.dutyVersion());
                  x.setStartAt(i.startAt().atOffset(ZoneOffset.UTC));
                  x.setEndAt(i.endAt().atOffset(ZoneOffset.UTC));
                  x.setLateMinutes(i.lateMinutes());
                  return x;
                })
            .toList());
    out.setUnassigned(
        v.unassigned().stream()
            .map(
                u -> {
                  var x =
                      new com.pmart.mfc.capacity.api.generated.model
                          .AssignmentPlanUnassignedInner();
                  x.setTaskId(u.taskId());
                  x.setTaskType(u.taskType());
                  x.setReason(
                      com.pmart.mfc.capacity.api.generated.model.AssignmentPlanUnassignedInner
                          .ReasonEnum.fromValue(u.reason()));
                  return x;
                })
            .toList());
    var o = new com.pmart.mfc.capacity.api.generated.model.AssignmentPlanObjective();
    o.setUnassignedCount(p.unassignedCount());
    o.setLateMinutes(p.lateMinutes());
    o.setMaxWorkerLoadMinutes(p.maxLoadMinutes());
    o.setObjectiveValue(p.objectiveValue());
    o.setWallTimeMs(p.wallTimeMs());
    out.setObjective(o);
    out.setStaleReasons(p.staleReasons());
    if (p.settings() != null) {
      @SuppressWarnings("unchecked")
      java.util.Map<String, Object> s = JSON.readValue(p.settings(), java.util.Map.class);
      Object lim = s.remove("limitations");
      out.setSettings(s);
      if (lim instanceof java.util.List<?> l) {
        out.setLimitations(l.stream().map(String::valueOf).toList());
      }
    }
    out.setAvailabilityUnverifiedWorkers(p.unverifiedWorkers());
    out.setAssignments(v.assignments().stream().map(AssignmentsController::dto).toList());
    out.setCreatedBy(p.createdBy());
    out.setCreatedAt(p.createdAt().atOffset(ZoneOffset.UTC));
    out.setCommittedBy(p.committedBy());
    out.setCommittedAt(p.committedAt() == null ? null : p.committedAt().atOffset(ZoneOffset.UTC));
    return out;
  }

  @Override
  public ResponseEntity<Assignment> assignTask(
      String idempotencyKey,
      String xSourceSystem,
      AssignTaskRequest body,
      @Nullable Integer xExpectedVersion) {
    var r =
        service.assign(
            idempotencyKey,
            xSourceSystem,
            new AssignCommand(
                body.getMfcId(),
                body.getTaskId(),
                body.getTaskType().getValue(),
                body.getWorkerId(),
                body.getReason(),
                xExpectedVersion,
                body.getTemperatureZone() == null ? null : body.getTemperatureZone().getValue()));
    Assignment out = dto(r.assignment());
    out.setWarnings(r.warnings());
    return ResponseEntity.status(r.created() ? HttpStatus.CREATED : HttpStatus.OK).body(out);
  }

  @Override
  public ResponseEntity<ListAssignments200Response> listAssignments(
      String mfcId,
      @Nullable LocalDate date,
      @Nullable String workerId,
      @Nullable String taskId,
      @Nullable String state) {
    var out = new ListAssignments200Response();
    out.setItems(
        service.list(mfcId, taskId, workerId, state, date).stream()
            .map(
                r -> {
                  Assignment a = dto(r);
                  if ("ACTIVE".equals(r.state())) {
                    a.setWorkerEligibility(service.eligibility(r));
                  }
                  return a;
                })
            .toList());
    var asOf = new GetReceivingCapabilities200ResponseAsOf();
    asOf.setAsOf(Instant.now().atOffset(ZoneOffset.UTC));
    asOf.setFreshness(GetReceivingCapabilities200ResponseAsOf.FreshnessEnum.CURRENT);
    out.setAsOf(asOf);
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<com.pmart.mfc.capacity.api.generated.model.ReleaseTaskAssignment200Response>
      releaseTaskAssignment(
          String taskId,
          String idempotencyKey,
          String xSourceSystem,
          com.pmart.mfc.capacity.api.generated.model.ReleaseTaskAssignmentRequest body) {
    var out = new com.pmart.mfc.capacity.api.generated.model.ReleaseTaskAssignment200Response();
    out.setTaskId(taskId);
    out.setReleased(service.releaseTask(taskId, body.getWorkerId()));
    return ResponseEntity.ok(out);
  }

  static Assignment dto(AssignmentRow r) {
    var a = new Assignment();
    a.setAssignmentId(r.assignmentId());
    a.setMfcId(r.mfcId());
    a.setTaskId(r.taskId());
    a.setTaskType(r.taskType());
    a.setWorkerId(r.workerId());
    a.setAssignmentVersion(r.assignmentVersion());
    a.setState(Assignment.StateEnum.fromValue(r.state()));
    a.setPlanId(r.planId());
    a.setAssignedBy(r.assignedBy());
    a.setAssignedAt(r.assignedAt().atOffset(ZoneOffset.UTC));
    a.setSource(
        Assignment.SourceEnum.fromValue(
            r.planId() != null
                ? "PLAN"
                : com.pmart.mfc.capacity.application.AssignmentService.SYSTEM.equals(r.assignedBy())
                    ? "SYSTEM"
                    : "MANUAL"));
    return a;
  }
}
