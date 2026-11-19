package com.pmart.mfc.inventory.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.web.CommandResponse;
import com.pmart.common.web.CommandStatus;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.inventory.domain.PickRules;
import com.pmart.mfc.inventory.domain.ReservationState;
import com.pmart.mfc.inventory.domain.StockRules;
import com.pmart.mfc.inventory.domain.StockUnit.Change;
import com.pmart.mfc.inventory.domain.StockUnit.Key;
import com.pmart.mfc.inventory.domain.StockUnit.Quality;
import com.pmart.mfc.inventory.domain.StockUnit.Work;
import com.pmart.mfc.inventory.infrastructure.ReservationStore;
import com.pmart.mfc.inventory.infrastructure.ReservationStore.PickPart;
import com.pmart.mfc.inventory.infrastructure.ReservationStore.ReservationRow;
import com.pmart.mfc.inventory.infrastructure.SkuProjectionStore;
import com.pmart.mfc.inventory.infrastructure.StockStore;
import com.pmart.mfc.inventory.infrastructure.StockStore.LotRow;
import com.pmart.mfc.inventory.infrastructure.StockStore.ReceiptRow;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * CommitTaskExecution(IV-02·IV-03·IV-13, ADR-0040 결정 3). 현장 실행 사실을 단위 행 이동으로 반영한다.
 *
 * <ul>
 *   <li>INSPECT: 같은 위치에서 PENDING_INSPECTION/UNSTOWED → NORMAL 또는 QUARANTINED(UNSTOWED). 격리면 Q+q. E
 *       변화 없음.
 *   <li>PUTAWAY: fromLocation 의 NORMAL/UNSTOWED → toLocation 의 NORMAL/STORED. E+q(이때 처음 판매 가능).
 *   <li>PICK: 선반 NORMAL·STORED → toLocation(장바구니 CART 또는 포장 위치 PACKING)의 PICKED.
 *   <li>PACK: fromLocation(집품 도착지)의 PICKED → toLocation(포장 위치 PACKING, 없으면 fromLocation)의 PACKED.
 *       최종 위치는 늘 포장 구역(장바구니 안에서 PACKED 금지).
 *   <li>MOVE: 취소 복귀(CANCEL_RETURN_PENDING → 선반).
 *   <li>같은 executionId 는 요청 ID 가 달라도 한 번만(내용 지문이 같으면 처음 commitId, 다르면 409).
 * </ul>
 *
 * 위치는 mfc-location 투영으로 검사한다({@link Locations}).
 */
@Service
public class TaskExecutionService {

  public static final String EXECUTION_ALREADY_POSTED = "EXECUTION_ALREADY_POSTED";
  static final String OP = "commitTaskExecution";

  private static final Logger log = LoggerFactory.getLogger(TaskExecutionService.class);

  public enum Type {
    INSPECT,
    PUTAWAY,
    PICK,
    PACK,
    MOVE,
    /** 포장분 → 출하대기 랙(0.29.0, F-12): PACKED 위치만, 예약·총량 불변. */
    STAGE
  }

  /** 실행 줄. resultQuality 는 INSPECT 전용. */
  public record ExecLine(
      String sku,
      String lotId,
      int qty,
      @Nullable Quality resultQuality,
      @Nullable String sourceLotRef,
      @Nullable LocalDate expiryDate,
      @Nullable LocalDate manufactureDate,
      @Nullable String orderLineId) {

    public ExecLine(String sku, String lotId, int qty, @Nullable Quality resultQuality) {
      this(sku, lotId, qty, resultQuality, null, null, null, null);
    }

    /** INSPECT 에서 LOT 을 나누는가(제조 LOT 표기·소비기한·제조일을 검수에서 확인). */
    boolean splitsLot() {
      return sourceLotRef != null || expiryDate != null || manufactureDate != null;
    }
  }

  public record ExecCommand(
      Type type,
      String mfcId,
      String taskId,
      String executionId,
      @Nullable String fromLocation,
      @Nullable String toLocation,
      List<ExecLine> lines,
      @Nullable String reservationId) {}

  private final StockStore stock;
  private final Ledger ledger;
  private final Locations locations;
  private final SkuProjectionStore skus;
  private final ReservationStore reservations;
  private final RequestIdempotency idempotency;
  private final Clock clock;
  private final LocationAllocationService allocations;
  private final MoveService moves;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;

  public TaskExecutionService(
      StockStore stock,
      Ledger ledger,
      Locations locations,
      SkuProjectionStore skus,
      ReservationStore reservations,
      RequestIdempotency idempotency,
      Clock clock,
      LocationAllocationService allocations,
      MoveService moves,
      EventPublisher events,
      EventEnvelopeCodec codec) {
    this.moves = moves;
    this.events = events;
    this.codec = codec;
    this.stock = stock;
    this.ledger = ledger;
    this.locations = locations;
    this.skus = skus;
    this.reservations = reservations;
    this.idempotency = idempotency;
    this.clock = clock;
    this.allocations = allocations;
  }

  public CommandResponse commit(String requestId, String sourceSystem, ExecCommand cmd) {
    validate(cmd);
    int total = cmd.lines().stream().mapToInt(ExecLine::qty).sum();
    String fingerprint = fingerprint(cmd);
    return idempotency
        .execute(
            Callers.key(sourceSystem, requestId, OP),
            cmd,
            CommandResponse.class,
            () -> {
              Instant at = clock.instant();
              checkLots(cmd);
              checkLocations(cmd);
              String commitId = Ledger.newCommitId();
              if (!stock.claimExecution(
                  cmd.executionId(),
                  cmd.mfcId(),
                  cmd.type().name(),
                  fingerprint,
                  commitId,
                  total,
                  at)) {
                return already(requestId, cmd.executionId(), fingerprint);
              }
              if (cmd.type() == Type.PICK || cmd.type() == Type.PACK) {
                linkReservation(cmd, at); // 예약 행 → 조각 잠금 뒤 잔량(센터 → LOT → 단위) — 예약 명령과 같은 잠금 순서
              }
              ledger.postAs(
                  commitId,
                  "TASK_EXECUTION",
                  cmd.mfcId(),
                  requestId,
                  sourceSystem,
                  cmd.taskId(),
                  total,
                  changes(cmd, at),
                  List.of(),
                  at);
              publishApplied(requestId, commitId, cmd, total, at);
              log.info(
                  "{} {} (task {}) posted to {} as {} ({} units)",
                  cmd.type(),
                  cmd.executionId(),
                  cmd.taskId(),
                  cmd.mfcId(),
                  commitId,
                  total);
              return new CommandResponse(
                  CommandStatus.APPLIED, requestId, commitId, total, null, false, null, at);
            })
        .value();
  }

  /**
   * {@code InventoryExecutionApplied}(mfc.inventory.v1, 사건 카탈로그 — 구독 mfc-inbound 등). 같은 트랜잭션
   * outbox. 성공한 반영만 알린다(거절은 호출자가 응답으로 안다). mfc-inbound 는 이것으로 자동 보충을 바로 점검한다(2026-11-08 남은 일 15).
   */
  private void publishApplied(
      String requestId, String commitId, ExecCommand cmd, int total, Instant at) {
    Map<String, Object> p = new java.util.LinkedHashMap<>();
    p.put("requestId", requestId);
    p.put("commitId", commitId);
    p.put("executionType", cmd.type().name());
    p.put("mfcId", cmd.mfcId());
    p.put("appliedQty", total);
    p.put(
        "lines",
        cmd.lines().stream()
            .map(l -> Map.<String, Object>of("sku", l.sku(), "lot", l.lotId(), "qty", l.qty()))
            .toList());
    p.put("result", "SUCCESS");
    p.put("appliedAt", at.toString());
    events.publish(
        codec.create(
            events.sourceSystem(),
            "InventoryExecutionApplied",
            1,
            "InventoryExecution",
            cmd.executionId(),
            1,
            new EventEnvelope.Actor(EventEnvelope.ActorType.SYSTEM, events.sourceSystem()),
            EventEnvelope.EventSource.REAL,
            p),
        HandoverPostingService.TOPIC);
  }

  private static void validate(ExecCommand cmd) {
    if (cmd.type() == Type.MOVE) {
      // 지금은 취소 복귀만(ADR-0046): 포장 위치의 CANCEL_RETURN_PENDING → 선반 STORED
      if (blank(cmd.toLocation())) {
        throw new PmartException(
            ErrorCode.VALIDATION_FAILED, "MOVE(취소 복귀)는 toLocation(선반)이 필요합니다.");
      }
      for (ExecLine l : cmd.lines()) {
        if (l.resultQuality() != null
            && l.resultQuality() != Quality.NORMAL
            && l.resultQuality() != Quality.QUARANTINED) {
          throw new PmartException(
              ErrorCode.VALIDATION_FAILED, "복귀 판정은 NORMAL/QUARANTINED 입니다: " + l.sku());
        }
      }
    }
    if (cmd.type() == Type.PICK || cmd.type() == Type.PACK) {
      if (blank(cmd.reservationId())) {
        throw new PmartException(
            ErrorCode.VALIDATION_FAILED, cmd.type() + " 는 reservationId 가 필요합니다.");
      }
      if (cmd.type() == Type.PICK && blank(cmd.toLocation())) {
        throw new PmartException(
            ErrorCode.VALIDATION_FAILED, "PICK 은 toLocation(장바구니 또는 포장 위치)이 필요합니다.");
      }
      for (ExecLine l : cmd.lines()) {
        if (blank(l.orderLineId())) {
          throw new PmartException(
              ErrorCode.VALIDATION_FAILED, cmd.type() + " 줄은 orderLineId 가 필요합니다: " + l.sku());
        }
      }
    }
    if (cmd.executionId() == null || cmd.executionId().isBlank()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "executionId 가 필요합니다.");
    }
    if (blank(cmd.fromLocation())) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "fromLocation 이 필요합니다.");
    }
    if (cmd.type() == Type.PUTAWAY && blank(cmd.toLocation())) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "PUTAWAY 는 toLocation 이 필요합니다.");
    }
    if (cmd.type() == Type.STAGE && blank(cmd.toLocation())) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "STAGE 는 toLocation(출하대기 랙)이 필요합니다.");
    }
    for (ExecLine l : cmd.lines()) {
      if (l.qty() < 1) {
        throw new PmartException(ErrorCode.VALIDATION_FAILED, "수량은 1 이상이어야 합니다: " + l.sku());
      }
      if (blank(l.lotId())) {
        throw new PmartException(ErrorCode.VALIDATION_FAILED, "lotId 가 필요합니다: " + l.sku());
      }
      if (cmd.type() == Type.INSPECT
          && l.resultQuality() != Quality.NORMAL
          && l.resultQuality() != Quality.QUARANTINED) {
        throw new PmartException(
            ErrorCode.VALIDATION_FAILED,
            "INSPECT 는 resultQuality NORMAL/QUARANTINED 가 필요합니다: " + l.sku());
      }
    }
  }

  /** 출발 위치 실사 잠금 아님, 적치 목적지는 등록·ACTIVE·선반 구역·온도대 = SKU 보관 유형. */
  private void checkLocations(ExecCommand cmd) {
    locations.requireNotCountLocked(cmd.mfcId(), cmd.fromLocation());
    if (cmd.type() == Type.PUTAWAY) {
      var projections =
          skus.findAll(cmd.lines().stream().map(ExecLine::sku).collect(Collectors.toSet()));
      for (String sku : cmd.lines().stream().map(ExecLine::sku).distinct().toList()) {
        var p = projections.get(sku);
        locations.requireStorage(cmd.mfcId(), cmd.toLocation(), p == null ? null : p.storageType());
      }
      // 진열 규칙(카테고리·출하 부적격·같은 상품 한 자리·용량) — 이동과 같은 기준(2026-11-08)
      for (ExecLine l : cmd.lines()) {
        moves.requirePlacement(
            cmd.mfcId(),
            l.sku(),
            l.lotId(),
            java.util.Objects.requireNonNull(cmd.toLocation()),
            l.qty());
      }
    }
    if (cmd.type() == Type.PICK) {
      locations.requirePickDestination(cmd.mfcId(), cmd.toLocation());
    }
    if (cmd.type() == Type.PACK) {
      // 장바구니 → 패킹대(2026-11-17) 또는 같은 포장 위치(예전 흐름). 포장된 물건은 늘 포장 구역에 있다.
      locations.requirePacking(cmd.mfcId(), packedAt(cmd));
    }
    if (cmd.type() == Type.STAGE) {
      locations.requireStaging(cmd.mfcId(), java.util.Objects.requireNonNull(cmd.toLocation()));
    }
    if (cmd.type() == Type.MOVE) {
      var projections =
          skus.findAll(cmd.lines().stream().map(ExecLine::sku).collect(Collectors.toSet()));
      for (String sku : cmd.lines().stream().map(ExecLine::sku).distinct().toList()) {
        var p = projections.get(sku);
        locations.requireStorage(cmd.mfcId(), cmd.toLocation(), p == null ? null : p.storageType());
      }
      // 취소품 복귀도 진열 규칙(#59, 2026-11-08 사용자 결정) — 정상 판정분만(격리분은 판매 칸 규칙 대상 아님). 추천 칸은
      // placement-suggestions
      for (ExecLine l : cmd.lines()) {
        if (l.resultQuality() == null || l.resultQuality() == Quality.NORMAL) {
          moves.requirePlacement(
              cmd.mfcId(),
              l.sku(),
              l.lotId(),
              java.util.Objects.requireNonNull(cmd.toLocation()),
              l.qty());
        }
      }
    }
  }

  /**
   * PICK·PACK 실적을 예약 조각에 붙인다(IV-07). 예약은 이 센터의 CONFIRMED 여야 하고, 줄은 같은 주문 줄·LOT 의 CONFIRMED 조각의 남은 양
   * 안에서만.
   */
  private void linkReservation(ExecCommand cmd, Instant at) {
    String reservationId = java.util.Objects.requireNonNull(cmd.reservationId());
    ReservationRow r =
        reservations
            .lock(reservationId)
            .orElseThrow(
                () ->
                    PmartException.notFound(
                        ReservationService.RESERVATION_NOT_FOUND, "예약이 없습니다: " + reservationId));
    if (!r.mfcId().equals(cmd.mfcId())) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED, "예약 " + reservationId + " 는 센터 " + r.mfcId() + " 의 예약입니다.");
    }
    if (r.state() != ReservationState.CONFIRMED) {
      throw PmartException.invalidState(
          "예약 " + reservationId + " 는 " + r.state() + " 상태라 집품·포장할 수 없습니다(확정 예약만).");
    }
    List<PickPart> parts = reservations.pickParts(reservationId, true);
    List<PickRules.Part> view =
        parts.stream()
            .map(
                p ->
                    new PickRules.Part(
                        p.partId(),
                        p.orderLineId(),
                        p.lotId(),
                        p.state() == ReservationState.CONFIRMED,
                        p.qty(),
                        p.pickedQty(),
                        p.packedQty()))
            .toList();
    PickRules.Step step = cmd.type() == Type.PICK ? PickRules.Step.PICK : PickRules.Step.PACK;
    Map<String, Integer> used = new HashMap<>();
    List<PickRules.Take> takes = new ArrayList<>();
    for (ExecLine l : cmd.lines()) {
      PickRules.Result res =
          PickRules.allocate(
              step,
              java.util.Objects.requireNonNull(l.orderLineId()),
              l.lotId(),
              l.qty(),
              view,
              used);
      if (res.error() != null) {
        throw new PmartException(
            res.error(),
            HttpStatus.UNPROCESSABLE_CONTENT,
            java.util.Objects.requireNonNullElse(res.detail(), res.error()),
            false,
            List.of(new PmartException.Detail(l.sku(), res.error())));
      }
      takes.addAll(res.takes());
    }
    for (PickRules.Take t : takes) {
      reservations.addPicked(t.partId(), t.qty(), step == PickRules.Step.PICK, at);
    }
    if (step == PickRules.Step.PICK) {
      Map<String, Integer> byPart = new java.util.LinkedHashMap<>();
      takes.forEach(t -> byPart.merge(t.partId(), t.qty(), Integer::sum));
      allocations.onPick(
          cmd.mfcId(),
          reservationId,
          java.util.Objects.requireNonNull(cmd.fromLocation()),
          byPart,
          parts,
          at); // 위치 배정 소진·다른 칸 집품 기록(V11)
    }
  }

  /** 줄의 lotId 가 그 SKU 의 LOT 인지. */
  private void checkLots(ExecCommand cmd) {
    Map<String, LotRow> lots =
        stock.lots(cmd.lines().stream().map(ExecLine::lotId).collect(Collectors.toSet()));
    for (ExecLine l : cmd.lines()) {
      LotRow lot = lots.get(l.lotId());
      if (lot == null || !lot.sku().equals(l.sku())) {
        throw new PmartException(
            ErrorCode.VALIDATION_FAILED,
            "lotId " + l.lotId() + " 는 SKU " + l.sku() + " 의 LOT 이 아닙니다.");
      }
    }
  }

  private List<Change> changes(ExecCommand cmd, Instant at) {
    List<Change> out = new ArrayList<>();
    for (ExecLine l : cmd.lines()) {
      switch (cmd.type()) {
        case INSPECT -> {
          Key from =
              new Key(
                  cmd.fromLocation(),
                  l.sku(),
                  l.lotId(),
                  Quality.PENDING_INSPECTION,
                  Work.UNSTOWED);
          out.add(new Change(from, -l.qty()));
          // LOT 분리: 검수에서 확인한 제조 LOT 표기·소비기한의 추적 LOT 으로 옮긴다(같은 위치, 총량 불변)
          String target =
              l.splitsLot()
                  ? stock.trackedLot(
                      l.sku(),
                      l.sourceLotRef(),
                      l.expiryDate(),
                      l.manufactureDate(),
                      "LOT-" + java.util.UUID.randomUUID(),
                      cmd.executionId(),
                      at)
                  : l.lotId();
          Key to = new Key(cmd.fromLocation(), l.sku(), target, l.resultQuality(), Work.UNSTOWED);
          out.add(new Change(to, l.qty()));
        }
        case PUTAWAY -> {
          Key from = new Key(cmd.fromLocation(), l.sku(), l.lotId(), Quality.NORMAL, Work.UNSTOWED);
          out.add(new Change(from, -l.qty()));
          out.add(new Change(from.at(cmd.toLocation(), Work.STORED), l.qty()));
        }
        case PICK -> {
          // 선반 NORMAL·STORED → 포장 위치 NORMAL·PICKED(E 유지, 위치·업무 상태만)
          Key from = new Key(cmd.fromLocation(), l.sku(), l.lotId(), Quality.NORMAL, Work.STORED);
          out.add(new Change(from, -l.qty()));
          out.add(new Change(from.at(cmd.toLocation(), Work.PICKED), l.qty()));
        }
        case MOVE -> {
          // 취소 복귀(IV-08): 복귀·적격 확인 뒤 선반으로 — NORMAL 이면 E+q, 이상품이면 격리(Q+q)
          Key from =
              new Key(
                  cmd.fromLocation(),
                  l.sku(),
                  l.lotId(),
                  Quality.NORMAL,
                  Work.CANCEL_RETURN_PENDING);
          out.add(new Change(from, -l.qty()));
          Quality q = l.resultQuality() == null ? Quality.NORMAL : l.resultQuality();
          out.add(
              new Change(new Key(cmd.toLocation(), l.sku(), l.lotId(), q, Work.STORED), l.qty()));
        }
        case PACK -> {
          // 집품 도착지(장바구니·포장 위치) PICKED → 포장 위치 PACKED(E 유지)
          Key from = new Key(cmd.fromLocation(), l.sku(), l.lotId(), Quality.NORMAL, Work.PICKED);
          out.add(new Change(from, -l.qty()));
          out.add(new Change(from.at(packedAt(cmd), Work.PACKED), l.qty()));
        }
        case STAGE -> {
          // 포장분 → 출하대기 랙(F-12): 위치만, PACKED 그대로(E·R 불변 — 인계 전기가 이 위치에서 뺀다)
          Key from = new Key(cmd.fromLocation(), l.sku(), l.lotId(), Quality.NORMAL, Work.PACKED);
          out.add(new Change(from, -l.qty()));
          out.add(new Change(from.at(cmd.toLocation(), Work.PACKED), l.qty()));
        }
        default -> throw new IllegalStateException(cmd.type().name());
      }
    }
    return out;
  }

  private static String fingerprint(ExecCommand cmd) {
    StringBuilder sb = new StringBuilder();
    sb.append(cmd.type())
        .append('|')
        .append(cmd.mfcId())
        .append('|')
        .append(cmd.taskId())
        .append('|')
        .append(cmd.fromLocation())
        .append('|')
        .append(cmd.toLocation())
        .append('|')
        .append(cmd.reservationId());
    for (ExecLine l : cmd.lines()) {
      sb.append('|')
          .append(l.sku())
          .append(':')
          .append(l.lotId())
          .append(':')
          .append(l.qty())
          .append(':')
          .append(l.resultQuality())
          .append(':')
          .append(l.sourceLotRef())
          .append(':')
          .append(l.expiryDate())
          .append(':')
          .append(l.manufactureDate())
          .append(':')
          .append(l.orderLineId());
    }
    return StockRules.sha256(sb.toString());
  }

  private CommandResponse already(String requestId, String executionId, String fingerprint) {
    Optional<ReceiptRow> row = stock.execution(executionId);
    if (row.isEmpty() || !row.get().contentHash().equals(fingerprint)) {
      throw new PmartException(
          EXECUTION_ALREADY_POSTED,
          HttpStatus.CONFLICT,
          "executionId " + executionId + " 는 다른 내용으로 이미 반영되었습니다.",
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

  /** PACK 의 도착 위치: toLocation, 없으면 fromLocation(같은 포장 위치에서 포장). */
  private static String packedAt(ExecCommand cmd) {
    return blank(cmd.toLocation())
        ? java.util.Objects.requireNonNull(cmd.fromLocation())
        : java.util.Objects.requireNonNull(cmd.toLocation());
  }

  private static boolean blank(@Nullable String s) {
    return s == null || s.isBlank();
  }
}
