package com.pmart.mfc.inventory.application;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.inventory.application.Ledger.HoldChange;
import com.pmart.mfc.inventory.application.Ledger.ReservedChange;
import com.pmart.mfc.inventory.domain.LotAllocator.Allocation;
import com.pmart.mfc.inventory.domain.PickRules;
import com.pmart.mfc.inventory.domain.ReservationState;
import com.pmart.mfc.inventory.domain.StockRules;
import com.pmart.mfc.inventory.domain.StockUnit.Change;
import com.pmart.mfc.inventory.domain.StockUnit.Key;
import com.pmart.mfc.inventory.domain.StockUnit.Quality;
import com.pmart.mfc.inventory.domain.StockUnit.Work;
import com.pmart.mfc.inventory.infrastructure.ReservationStore;
import com.pmart.mfc.inventory.infrastructure.ReservationStore.PartRow;
import com.pmart.mfc.inventory.infrastructure.ReservationStore.PickPart;
import com.pmart.mfc.inventory.infrastructure.ReservationStore.ReservationRow;
import com.pmart.mfc.inventory.infrastructure.StockStore;
import java.sql.Timestamp;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 피킹 결품·파손 보고(IV-09, ADR-0044). 한 트랜잭션: 예약 행 → 조각 잠금 → 판매 제외(파손 = 격리 Q, 결품·차단 = 분실 조사 보류) + 예약 R−q
 * → 같은 SKU 적격 LOT 재확보(R+r, ADR-0042 결정 1) → 못 잡은 양은 BLOCKED 조각(이행부족 D) + ReservationBlocked. 같은
 * reportId 는 한 번만.
 */
@Service
public class ShortageService {

  private static final Logger log = LoggerFactory.getLogger(ShortageService.class);
  private static final TypeReference<List<Map<String, Object>>> PARTS = new TypeReference<>() {};

  public record Report(
      String reportId,
      String mfcId,
      String reservationId,
      String orderLineId,
      String sku,
      String lotId,
      String locationCode,
      int qty,
      String reason,
      @Nullable String taskId) {}

  public record Result(
      String reportId,
      String commitId,
      int reportedQty,
      int quarantinedQty,
      int heldQty,
      int resecuredQty,
      int blockedQty,
      List<Map<String, Object>> resecuredParts) {}

  private final ReservationStore reservations;
  private final ReservationService reservationService;
  private final StockStore stock;
  private final Ledger ledger;
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final com.pmart.common.messaging.RequestIdempotency idempotency;
  private final Clock clock;

  public ShortageService(
      ReservationStore reservations,
      ReservationService reservationService,
      StockStore stock,
      Ledger ledger,
      JdbcTemplate jdbc,
      ObjectMapper mapper,
      com.pmart.common.messaging.RequestIdempotency idempotency,
      Clock clock) {
    this.reservations = reservations;
    this.reservationService = reservationService;
    this.stock = stock;
    this.ledger = ledger;
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  @Transactional
  public Result report(String requestId, String sourceSystem, Report in) {
    if (in.qty() < 1) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "qty 는 1 이상입니다.");
    }
    if (!List.of("SHORT", "DAMAGED", "SPOILED", "BLOCKED").contains(in.reason())) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED, "reason 은 SHORT/DAMAGED/SPOILED/BLOCKED.");
    }
    String hash = StockRules.sha256(mapper.writeValueAsString(in));
    Optional<Result> done = find(in.reportId(), hash);
    if (done.isPresent()) {
      return done.get();
    }
    ReservationRow r =
        reservations
            .lock(in.reservationId())
            .orElseThrow(
                () ->
                    PmartException.notFound(
                        ReservationService.RESERVATION_NOT_FOUND,
                        "예약이 없습니다: " + in.reservationId()));
    done = find(in.reportId(), hash); // 잠금 뒤 다시(경합한 같은 보고)
    if (done.isPresent()) {
      return done.get();
    }
    if (!r.mfcId().equals(in.mfcId())) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "다른 센터의 예약입니다.");
    }
    if (r.state() != ReservationState.CONFIRMED) {
      throw PmartException.invalidState("확정 예약만 결품을 보고할 수 있습니다(지금 " + r.state() + ").");
    }
    // 그 줄·LOT 의 확정 조각 중 아직 집지 않은 양
    List<PickPart> parts = reservations.pickParts(in.reservationId(), true);
    List<PickPart> line =
        parts.stream().filter(p -> in.orderLineId().equals(p.orderLineId())).toList();
    if (line.isEmpty()) {
      throw unprocessable(
          PickRules.LINE_NOT_IN_RESERVATION, "주문 줄 " + in.orderLineId() + " 은 이 예약에 없습니다.");
    }
    List<PickPart> lot =
        line.stream()
            .filter(p -> in.lotId().equals(p.lotId()) && p.state() == ReservationState.CONFIRMED)
            .toList();
    if (lot.isEmpty()) {
      throw unprocessable(
          PickRules.LOT_MISMATCH,
          "주문 줄 " + in.orderLineId() + " 의 확정 예약 LOT 이 아닙니다: " + in.lotId());
    }
    int room = lot.stream().mapToInt(p -> p.qty() - p.pickedQty()).sum();
    if (in.qty() > room) {
      throw unprocessable(
          PickRules.PICK_QTY_EXCEEDED,
          "보고 " + in.qty() + " > 아직 집지 않은 예약 " + room + "(LOT " + in.lotId() + ")");
    }
    Instant at = clock.instant();
    // 1. 판매 제외 + R−q
    Key shelf = new Key(in.locationCode(), in.sku(), in.lotId(), Quality.NORMAL, Work.STORED);
    // 실물이 있는 이상품(파손·변질 — 0.32.0 SPOILED)은 격리, 못 찾음(SHORT·BLOCKED)은 분실 조사 보류
    boolean damaged = "DAMAGED".equals(in.reason()) || "SPOILED".equals(in.reason());
    List<Change> units =
        damaged
            ? List.of(
                new Change(shelf, -in.qty()),
                new Change(shelf.with(Quality.QUARANTINED, Work.STORED), in.qty()))
            : List.of();
    List<HoldChange> holds = damaged ? List.of() : List.of(new HoldChange(shelf, in.qty()));
    String commitId = Ledger.newCommitId();
    ledger.postWithHolds(
        commitId,
        "SHORTAGE",
        in.mfcId(),
        requestId,
        sourceSystem,
        in.reportId(),
        in.qty(),
        units,
        List.of(new ReservedChange(in.sku(), in.lotId(), -in.qty())),
        holds,
        at);
    if (!damaged) {
      stock.insertHold(
          "HLD-" + UUID.randomUUID(), in.mfcId(), shelf, in.qty(), in.reason(), in.reportId(), at);
    }
    // 조각에서 빼기(아직 집지 않은 양에서, part_id 순)
    int left = in.qty();
    for (PickPart p : lot) {
      int take = Math.min(left, p.qty() - p.pickedQty());
      if (take <= 0) {
        continue;
      }
      if (p.qty() - take > 0) {
        reservations.shrinkPart(p.partId(), p.qty() - take, at);
      } else {
        reservations.setPartState(
            p.partId(), ReservationState.RELEASED, "SHORTAGE:" + in.reportId(), at);
      }
      left -= take;
      if (left == 0) {
        break;
      }
    }
    // 2. 재확보
    List<Allocation> again =
        reservationService.resecure(
            r, in.orderLineId(), in.sku(), in.qty(), requestId, sourceSystem, at);
    int resecured = again.stream().mapToInt(Allocation::qty).sum();
    int blocked = in.qty() - resecured;
    // 3. 못 잡은 양 = 이행부족 D
    if (blocked > 0) {
      reservations.insertPart(
          r.reservationId(),
          new PartRow(
              r.reservationId() + ":B" + UUID.randomUUID().toString().substring(0, 8),
              in.orderLineId(),
              in.sku(),
              in.lotId(),
              "HAND",
              blocked,
              ReservationState.BLOCKED,
              in.reason()),
          at);
      reservationService.publishBlocked(r, in.sku(), in.lotId(), blocked, in.reason(), at);
    }
    List<Map<String, Object>> parts2 = new ArrayList<>();
    for (Allocation a : again) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("lotId", a.lotId());
      m.put("qty", a.qty());
      parts2.add(m);
    }
    Result res =
        new Result(
            in.reportId(),
            commitId,
            in.qty(),
            damaged ? in.qty() : 0,
            damaged ? 0 : in.qty(),
            resecured,
            blocked,
            parts2);
    jdbc.update(
        "INSERT INTO shortage_report (report_id, content_hash, mfc_id, reservation_id, order_line_id,"
            + " sku, lot_id, location_code, reason, qty, quarantined_qty, held_qty, resecured_qty,"
            + " blocked_qty, resecured_parts, commit_id, created_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?)",
        in.reportId(),
        hash,
        in.mfcId(),
        in.reservationId(),
        in.orderLineId(),
        in.sku(),
        in.lotId(),
        in.locationCode(),
        in.reason(),
        in.qty(),
        res.quarantinedQty(),
        res.heldQty(),
        resecured,
        blocked,
        mapper.writeValueAsString(parts2),
        commitId,
        Timestamp.from(at));
    log.info(
        "결품 보고 {} {} {} {}개({}) → 재확보 {}, 이행부족 {}",
        in.reportId(),
        in.orderLineId(),
        in.lotId(),
        in.qty(),
        in.reason(),
        resecured,
        blocked);
    return res;
  }

  /** 이행부족 해제 요청. */
  public record ShortfallRelease(
      String reservationId, String orderLineId, int qty, String reason) {}

  /**
   * 이행부족(BLOCKED 조각) 해제 — 결품 수량을 주문에서 취소했을 때(ADR-0045). BLOCKED 는 R 이 아니라 D 라 현물·요약 변화 없음. 같은 요청은
   * 재생.
   */
  public com.pmart.common.web.CommandResponse releaseShortfall(
      String requestId, String sourceSystem, ShortfallRelease in) {
    return idempotency
        .execute(
            Callers.key(sourceSystem, requestId, "releaseShortfall"),
            in,
            com.pmart.common.web.CommandResponse.class,
            () -> {
              ReservationRow r =
                  reservations
                      .lock(in.reservationId())
                      .orElseThrow(
                          () ->
                              PmartException.notFound(
                                  ReservationService.RESERVATION_NOT_FOUND,
                                  "예약이 없습니다: " + in.reservationId()));
              List<PickPart> blocked =
                  reservations.pickParts(in.reservationId(), true).stream()
                      .filter(
                          p ->
                              in.orderLineId().equals(p.orderLineId())
                                  && p.state() == ReservationState.BLOCKED)
                      .toList();
              int total = blocked.stream().mapToInt(PickPart::qty).sum();
              if (in.qty() > total) {
                throw PmartException.invalidState(
                    "주문 줄 "
                        + in.orderLineId()
                        + " 의 이행부족은 "
                        + total
                        + " 입니다(요청 "
                        + in.qty()
                        + ").");
              }
              Instant at = clock.instant();
              int left = in.qty();
              for (PickPart p : blocked) {
                int take = Math.min(left, p.qty());
                if (take == p.qty()) {
                  reservations.setPartState(p.partId(), ReservationState.RELEASED, in.reason(), at);
                } else {
                  reservations.shrinkPart(p.partId(), p.qty() - take, at);
                }
                left -= take;
                if (left == 0) {
                  break;
                }
              }
              log.info(
                  "예약 {} 줄 {} 이행부족 {} 해제({})",
                  r.reservationId(),
                  in.orderLineId(),
                  in.qty(),
                  in.reason());
              return new com.pmart.common.web.CommandResponse(
                  com.pmart.common.web.CommandStatus.APPLIED,
                  requestId,
                  r.reservationId(),
                  in.qty(),
                  r.version(),
                  false,
                  null,
                  at);
            })
        .value();
  }

  private Optional<Result> find(String reportId, String hash) {
    return jdbc
        .query(
            "SELECT * FROM shortage_report WHERE report_id = ?",
            (rs, i) -> {
              if (!hash.equals(rs.getString("content_hash"))) {
                throw PmartException.idempotencyConflict("같은 결품 보고 ID 로 다른 내용이 왔습니다: " + reportId);
              }
              return new Result(
                  reportId,
                  rs.getString("commit_id"),
                  rs.getInt("qty"),
                  rs.getInt("quarantined_qty"),
                  rs.getInt("held_qty"),
                  rs.getInt("resecured_qty"),
                  rs.getInt("blocked_qty"),
                  mapper.readValue(rs.getString("resecured_parts"), PARTS));
            },
            reportId)
        .stream()
        .findFirst();
  }

  private static PmartException unprocessable(String code, String message) {
    return new PmartException(code, HttpStatus.UNPROCESSABLE_CONTENT, message, false, List.of());
  }
}
