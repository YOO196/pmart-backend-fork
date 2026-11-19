package com.pmart.mfc.inventory.application;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.inventory.application.Ledger.HoldChange;
import com.pmart.mfc.inventory.domain.StockRules;
import com.pmart.mfc.inventory.domain.StockUnit.Change;
import com.pmart.mfc.inventory.domain.StockUnit.Key;
import com.pmart.mfc.inventory.domain.StockUnit.Quality;
import com.pmart.mfc.inventory.domain.StockUnit.Work;
import com.pmart.mfc.inventory.infrastructure.MoveStore;
import com.pmart.mfc.inventory.infrastructure.MoveStore.MoveRow;
import com.pmart.mfc.inventory.infrastructure.StockStore;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이동·보충·적치 현장 예외(사용자 결정 2026-11-20 "PICK 결품처럼", 계약 0.35.0). 못 찾음(SHORT·BLOCKED) = 분실 조사
 * 보류(stock_hold — 실사가 해소), 이상품(DAMAGED·SPOILED) = 격리(QUARANTINED — mfc-quality 가 해제·폐기). PICK 결품
 * 보고({@link ShortageService})는 확정 예약이 있어야 해서 이동·적치 재고에는 못 쓴다 — 여기는 예약 없이 위치·LOT 에 건다.
 *
 * <ul>
 *   <li>이동 예외: 진행 중 이동의 아직 집지 않은 양 안에서 이동 수량 −q·MOVE 보류 −q 하고 출발 칸 NORMAL·STORED 에 보류·격리. 배정 때 이미 E
 *       를 뺐으므로 E 순변화 없음 — 예약은 그대로.
 *   <li>재고 예외: 위치·LOT·업무 상태(STORED/UNSTOWED)의 NORMAL 에 같은 규칙. STORED 면 E−q 라 품질 격리와 같이 넘는 예약을
 *       BLOCKED → 같은 SKU 적격 LOT 재확보 → 남은 것만 ReservationBlocked. UNSTOWED 분실 보류는 E 밖이라 E 변화 없이 적치만
 *       막는다.
 * </ul>
 *
 * 같은 reportId 는 한 번(같은 내용이면 처음 결과, 다르면 409).
 */
@Service
public class StockExceptionService {

  static final Set<String> REASONS = Set.of("SHORT", "DAMAGED", "SPOILED", "BLOCKED");

  private static final Logger log = LoggerFactory.getLogger(StockExceptionService.class);

  public record StockReport(
      String reportId,
      String mfcId,
      String sku,
      String lotId,
      String locationCode,
      @Nullable String workState,
      String reason,
      int qty,
      @Nullable String sourceRef) {}

  public record Result(
      String reportId,
      String commitId,
      String reason,
      int reportedQty,
      int quarantinedQty,
      int heldQty,
      int blockedQty,
      String locationCode,
      @Nullable MoveRow move) {}

  private final StockStore stock;
  private final MoveStore moves;
  private final Ledger ledger;
  private final ReservationBlocker blocker;
  private final Locations locations;
  private final JdbcTemplate jdbc;
  private final Clock clock;

  public StockExceptionService(
      StockStore stock,
      MoveStore moves,
      Ledger ledger,
      ReservationBlocker blocker,
      Locations locations,
      JdbcTemplate jdbc,
      Clock clock) {
    this.stock = stock;
    this.moves = moves;
    this.ledger = ledger;
    this.blocker = blocker;
    this.locations = locations;
    this.jdbc = jdbc;
    this.clock = clock;
  }

  // ---------- 이동·보충 ----------

  @Transactional
  public Result reportMove(
      String requestId,
      String sourceSystem,
      String moveId,
      String reportId,
      String reason,
      int qty) {
    validate(reportId, reason, qty);
    String hash = StockRules.sha256("MOVE|" + moveId + "|" + reason + "|" + qty);
    Optional<Result> done = find(reportId, hash);
    if (done.isPresent()) {
      return done.get();
    }
    MoveRow m =
        moves
            .lock(moveId)
            .orElseThrow(
                () -> PmartException.notFound(MoveService.MOVE_NOT_FOUND, "이동이 없습니다: " + moveId));
    done = find(reportId, hash); // 잠금 뒤 다시(경합한 같은 보고)
    if (done.isPresent()) {
      return done.get();
    }
    if (!"ACTIVE".equals(m.state())) {
      throw PmartException.invalidState("이동 " + moveId + " 는 " + m.state() + " 라 예외를 반영하지 않습니다.");
    }
    if (qty > m.unpicked()) {
      throw new PmartException(
          MoveService.MOVE_QTY_EXCEEDED,
          HttpStatus.CONFLICT,
          "아직 집지 않은 배정 " + m.unpicked() + "개를 넘습니다(카트 위 물건은 내려놓은 뒤 품질 격리로).",
          false,
          List.of());
    }
    Instant at = clock.instant();
    Key from = new Key(m.fromLocation(), m.sku(), m.lotId(), Quality.NORMAL, Work.STORED);
    boolean damaged = damaged(reason);
    // MOVE 보류 −q(E+q) 와 보류(E−q) 또는 격리(E−q) — E 순변화 없음
    List<HoldChange> holds =
        damaged
            ? List.of(new HoldChange(from, -qty))
            : List.of(new HoldChange(from, -qty), new HoldChange(from, qty));
    List<Change> units =
        damaged
            ? List.of(
                new Change(from, -qty),
                new Change(from.with(Quality.QUARANTINED, Work.STORED), qty))
            : List.of();
    String commitId = Ledger.newCommitId();
    ledger.postWithHolds(
        commitId,
        "MOVE_EXCEPTION",
        m.mfcId(),
        requestId,
        sourceSystem,
        reportId,
        qty,
        units,
        List.of(),
        holds,
        at);
    stock.consumeMoveHold(moveId, qty, at);
    if (!damaged) {
      stock.insertHold("HLD-" + UUID.randomUUID(), m.mfcId(), from, qty, reason, reportId, at);
    }
    int newQty = m.qty() - qty;
    String state = m.state();
    if (newQty == m.droppedQty() && m.onCart() == 0) {
      state = m.droppedQty() == 0 ? "CANCELLED" : "COMPLETED";
    }
    moves.update(moveId, newQty, m.pickedQty(), m.droppedQty(), m.cartLocation(), state, at);
    Result r =
        new Result(
            reportId,
            commitId,
            reason,
            qty,
            damaged ? qty : 0,
            damaged ? 0 : qty,
            0,
            m.fromLocation(),
            moves.find(moveId).orElseThrow());
    save(r, hash, m.mfcId(), "MOVE", moveId, m.sku(), m.lotId(), Work.STORED, null, at);
    log.info(
        "이동 예외 {} {} {} {}개({}) @{} → 이동 {}/{} {}",
        reportId,
        moveId,
        m.lotId(),
        qty,
        reason,
        m.fromLocation(),
        newQty,
        m.qty(),
        state);
    return r;
  }

  // ---------- 예약 없는 재고(입고 적치 등) ----------

  @Transactional
  public Result reportStock(String requestId, String sourceSystem, StockReport in) {
    validate(in.reportId(), in.reason(), in.qty());
    Work work = in.workState() == null ? Work.STORED : Work.valueOf(in.workState());
    if (work != Work.STORED && work != Work.UNSTOWED) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "workState 는 STORED/UNSTOWED 입니다.");
    }
    String hash =
        StockRules.sha256(
            String.join(
                "|",
                "STOCK",
                in.mfcId(),
                in.sku(),
                in.lotId(),
                in.locationCode(),
                work.name(),
                in.reason(),
                String.valueOf(in.qty()),
                String.valueOf(in.sourceRef())));
    Optional<Result> done = find(in.reportId(), hash);
    if (done.isPresent()) {
      return done.get();
    }
    var lot = stock.lots(List.of(in.lotId())).get(in.lotId());
    if (lot == null || !lot.sku().equals(in.sku())) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED,
          "lotId " + in.lotId() + " 는 SKU " + in.sku() + " 의 LOT 이 아닙니다.");
    }
    locations.requireNotCountLocked(in.mfcId(), in.locationCode());
    Instant at = clock.instant();
    Key key = new Key(in.locationCode(), in.sku(), in.lotId(), Quality.NORMAL, work);
    boolean damaged = damaged(in.reason());
    List<Change> units =
        damaged
            ? List.of(
                new Change(key, -in.qty()),
                new Change(key.with(Quality.QUARANTINED, work), in.qty()))
            : List.of();
    List<HoldChange> holds = damaged ? List.of() : List.of(new HoldChange(key, in.qty()));
    // STORED 면 E−q — 품질 격리와 같은 규칙으로 넘는 예약만 BLOCKED(사유 = 보고 사유)
    Map<String, Integer> drop = new TreeMap<>();
    Map<String, Set<String>> dropAt = new TreeMap<>();
    if (key.sellable()) {
      drop.put(in.sku() + "|" + in.lotId(), in.qty());
      dropAt.put(in.sku() + "|" + in.lotId(), new java.util.TreeSet<>(Set.of(in.locationCode())));
    }
    ReservationBlocker.Result held =
        blocker.block(in.mfcId(), drop, dropAt, in.reason(), in.reportId(), at);
    String commitId = Ledger.newCommitId();
    ledger.postWithHolds(
        commitId,
        "STOCK_EXCEPTION",
        in.mfcId(),
        requestId,
        sourceSystem,
        in.reportId(),
        in.qty(),
        units,
        held.reserved(),
        holds,
        at);
    if (!damaged) {
      stock.insertHold(
          "HLD-" + UUID.randomUUID(), in.mfcId(), key, in.qty(), in.reason(), in.reportId(), at);
    }
    held = blocker.resecure(held, requestId, sourceSystem, at);
    blocker.publish(held, in.reason(), at);
    int blocked = held.blocked().stream().mapToInt(ReservationBlocker.Blocked::qty).sum();
    Result r =
        new Result(
            in.reportId(),
            commitId,
            in.reason(),
            in.qty(),
            damaged ? in.qty() : 0,
            damaged ? 0 : in.qty(),
            blocked,
            in.locationCode(),
            null);
    save(r, hash, in.mfcId(), "STOCK", null, in.sku(), in.lotId(), work, in.sourceRef(), at);
    log.info(
        "재고 예외 {} {} {} {}개({}) @{} {} — 예약 보류 {}",
        in.reportId(),
        in.sku(),
        in.lotId(),
        in.qty(),
        in.reason(),
        in.locationCode(),
        work,
        blocked);
    return r;
  }

  private static boolean damaged(String reason) {
    return "DAMAGED".equals(reason) || "SPOILED".equals(reason);
  }

  private static void validate(String reportId, String reason, int qty) {
    if (reportId == null || reportId.isBlank() || reportId.length() > 128) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "reportId 는 1~128자입니다.");
    }
    if (!REASONS.contains(reason)) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED, "reason 은 SHORT/DAMAGED/SPOILED/BLOCKED.");
    }
    if (qty < 1) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "qty 는 1 이상입니다.");
    }
  }

  private void save(
      Result r,
      String hash,
      String mfcId,
      String targetType,
      @Nullable String moveId,
      String sku,
      String lotId,
      Work work,
      @Nullable String sourceRef,
      Instant at) {
    jdbc.update(
        "INSERT INTO stock_exception_report (report_id, content_hash, mfc_id, target_type, move_id,"
            + " sku, lot_id, location_code, work_state, reason, qty, quarantined_qty, held_qty,"
            + " blocked_qty, source_ref, commit_id, created_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        r.reportId(),
        hash,
        mfcId,
        targetType,
        moveId,
        sku,
        lotId,
        r.locationCode(),
        work.name(),
        r.reason(),
        r.reportedQty(),
        r.quarantinedQty(),
        r.heldQty(),
        r.blockedQty(),
        sourceRef,
        r.commitId(),
        Timestamp.from(at));
  }

  private Optional<Result> find(String reportId, String hash) {
    return jdbc
        .query(
            "SELECT * FROM stock_exception_report WHERE report_id = ?",
            (rs, i) -> {
              if (!hash.equals(rs.getString("content_hash"))) {
                throw PmartException.idempotencyConflict("같은 예외 보고 ID 로 다른 내용이 왔습니다: " + reportId);
              }
              String moveId = rs.getString("move_id");
              return new Result(
                  reportId,
                  rs.getString("commit_id"),
                  rs.getString("reason"),
                  rs.getInt("qty"),
                  rs.getInt("quarantined_qty"),
                  rs.getInt("held_qty"),
                  rs.getInt("blocked_qty"),
                  rs.getString("location_code"),
                  moveId == null ? null : moves.find(moveId).orElse(null));
            },
            reportId)
        .stream()
        .findFirst();
  }
}
