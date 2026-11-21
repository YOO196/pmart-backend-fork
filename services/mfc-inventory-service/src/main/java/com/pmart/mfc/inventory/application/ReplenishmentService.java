package com.pmart.mfc.inventory.application;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.inventory.domain.ReplenishmentRules;
import com.pmart.mfc.inventory.domain.StockUnit.Quality;
import com.pmart.mfc.inventory.domain.StockUnit.Work;
import com.pmart.mfc.inventory.infrastructure.MoveStore;
import com.pmart.mfc.inventory.infrastructure.MoveStore.MoveRow;
import com.pmart.mfc.inventory.infrastructure.StockStore;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 자동 선반 보충(IV-04, 2026-11-08 사용자 결정: 시스템이 만든다, 재배치는 센터 관리자). 기준(최소·목표·단위, Q-07 값)은 센터 관리자가 선반·상품별로
 * 등록하고, 점검은 mfc-inbound 가 주기적으로 부른다. 만든 보충은 일반 이동 배정(kind REPLENISH)과 같은 규칙({@link MoveService})으로
 * 검사한다 — 출발 LOT 가용(A)이 모자라면(이동 중은 판매 가능에서 빠지므로) 재고가 확보될 때까지 보충하지 않는다(사용자 결정, 예외 없음).
 *
 * <p>보충재고 → 일반 선반(0.44.0, R-30 — 사용자 결정 2026-11-21 "기본 규칙 + 관리자 수정"): 보충재고 칸에 정상·적치 재고가 있는 상품은 그
 * 상품의 일반 선반(고회전·보충재고 아닌 선반·피킹 칸 — 지금 있는 칸, 비었으면 마지막으로 있던 칸)에 기준이 없어도 <b>칸 용량의 25% 이하이면 칸 용량까지</b>
 * 보충한다({@link #DEFAULT_MIN_PERCENT}). 관리자가 그 칸·상품에 기준을 넣으면 그 값이 우선. 일반 선반으로의 보충은 보충재고 칸에서만 가져온다. 일반
 * 선반이 한 번도 없던 상품은 건너뛴다({@code NO_HOME_SHELF} — 관리자가 이동으로 자리를 정한다).
 */
@Service
public class ReplenishmentService {

  /** 기준을 둘 수 없는 칸(보충재고·선반 밖) — 0.44.0 전에는 "고회전 선반만"이라 이름이 남았다. */
  public static final String REPLENISH_ONLY_HIGH_TURNOVER = "REPLENISH_ONLY_HIGH_TURNOVER";

  /** 기준 없는 일반 선반의 기본 최소 = 칸 용량의 이 비율(%) 이하(사용자 결정 2026-11-21), 목표 = 칸 용량, 단위 1. */
  static final int DEFAULT_MIN_PERCENT = 25;

  static final String SUPERVISOR = "MFC_SUPERVISOR";
  private static final Logger log = LoggerFactory.getLogger(ReplenishmentService.class);

  public record Policy(
      String mfcId,
      String locationCode,
      String sku,
      int minQty,
      int targetQty,
      int unitQty,
      String updatedBy,
      Instant updatedAt,
      boolean derived) {}

  public record Skipped(String mfcId, String locationCode, String sku, String reason) {}

  public record Evaluation(int created, List<MoveRow> moves, List<Skipped> skipped) {}

  private final JdbcTemplate jdbc;
  private final StockStore stock;
  private final MoveStore moves;
  private final MoveService moveService;
  private final Locations locations;
  private final LocationAllocationService allocations;
  private final ReservationService reservations;
  private final TransactionTemplate tx;
  private final Clock clock;

  public ReplenishmentService(
      JdbcTemplate jdbc,
      StockStore stock,
      MoveStore moves,
      MoveService moveService,
      Locations locations,
      LocationAllocationService allocations,
      ReservationService reservations,
      TransactionTemplate tx,
      Clock clock) {
    this.jdbc = jdbc;
    this.stock = stock;
    this.moves = moves;
    this.moveService = moveService;
    this.locations = locations;
    this.allocations = allocations;
    this.reservations = reservations;
    this.tx = tx;
    this.clock = clock;
  }

  // ---------- 기준 ----------

  public Policy put(String mfcId, String locationCode, String sku, int min, int target, int unit) {
    String me = supervisor(mfcId);
    if (min < 0 || target < min || unit < 1) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED,
          "최소 ≤ 목표, 단위 ≥ 1 이어야 합니다(최소 " + min + " 목표 " + target + ").");
    }
    var loc = locations.row(mfcId, locationCode);
    if (loc.reserveStock() || !Locations.STORE_ZONES.contains(loc.zone())) {
      throw new PmartException(
          REPLENISH_ONLY_HIGH_TURNOVER,
          HttpStatus.UNPROCESSABLE_CONTENT,
          locationCode + " 에는 보충 기준을 둘 수 없습니다 — 선반·피킹 칸만(보충재고 칸은 보충의 출발지, R-30).",
          false,
          List.of());
    }
    Instant at = clock.instant();
    jdbc.update(
        "INSERT INTO replenishment_policy (mfc_id, location_code, sku, min_qty, target_qty, unit_qty,"
            + " updated_by, updated_at) VALUES (?,?,?,?,?,?,?,?) ON CONFLICT (mfc_id, location_code, sku)"
            + " DO UPDATE SET min_qty = EXCLUDED.min_qty, target_qty = EXCLUDED.target_qty,"
            + " unit_qty = EXCLUDED.unit_qty, updated_by = EXCLUDED.updated_by,"
            + " updated_at = EXCLUDED.updated_at",
        mfcId,
        locationCode,
        sku,
        min,
        target,
        unit,
        me,
        Timestamp.from(at));
    log.info(
        "보충 기준 {} {} {} min {} target {} unit {} by {}",
        mfcId,
        locationCode,
        sku,
        min,
        target,
        unit,
        me);
    return policies(mfcId).stream()
        .filter(p -> p.locationCode().equals(locationCode) && p.sku().equals(sku))
        .findFirst()
        .orElseThrow();
  }

  public void delete(String mfcId, String locationCode, String sku) {
    supervisor(mfcId);
    jdbc.update(
        "DELETE FROM replenishment_policy WHERE mfc_id = ? AND location_code = ? AND sku = ?",
        mfcId,
        locationCode,
        sku);
  }

  public List<Policy> list(String mfcId) {
    Operator.requireCenter(mfcId);
    return policies(mfcId);
  }

  private List<Policy> policies(@Nullable String mfcId) {
    return jdbc.query(
        "SELECT * FROM replenishment_policy"
            + (mfcId == null ? "" : " WHERE mfc_id = ?")
            + " ORDER BY mfc_id, location_code, sku",
        (rs, i) ->
            new Policy(
                rs.getString("mfc_id"),
                rs.getString("location_code"),
                rs.getString("sku"),
                rs.getInt("min_qty"),
                rs.getInt("target_qty"),
                rs.getInt("unit_qty"),
                rs.getString("updated_by"),
                rs.getTimestamp("updated_at").toInstant(),
                false),
        mfcId == null ? new Object[] {} : new Object[] {mfcId});
  }

  // ---------- 점검 ----------

  /** 기준마다 따로(한 기준의 거절이 다른 기준을 막지 않게) 필요한 보충 이동을 만들고, 진행 중 보충 이동 전부를 돌려준다. */
  public Evaluation evaluate(String requestId, String sourceSystem, @Nullable String mfcId) {
    int created = 0;
    List<Skipped> skipped = new ArrayList<>();
    List<Policy> all = new ArrayList<>(policies(mfcId));
    all.addAll(derivedPolicies(mfcId, all, skipped));
    for (Policy p : all) {
      try {
        Integer n = tx.execute(s -> evaluateOne(requestId, sourceSystem, p, skipped));
        created += n == null ? 0 : n;
      } catch (PmartException e) {
        skipped.add(new Skipped(p.mfcId(), p.locationCode(), p.sku(), e.code()));
        log.info("보충 {} {} {} 건너뜀: {}", p.mfcId(), p.locationCode(), p.sku(), e.getMessage());
      }
    }
    return new Evaluation(created, moves.openReplenishments(mfcId), skipped);
  }

  /**
   * 기준 없는 일반 선반의 기본 보충 대상(R-30): 보충재고 칸에 정상·적치 재고가 있는 (센터, 상품)마다 그 상품의 일반 선반 하나 — 관리자 기준이 있는 칸·상품은
   * 뺀다. 최소·목표는 칸 용량을 알아야 해서 {@link #evaluateOne} 이 채운다.
   */
  private List<Policy> derivedPolicies(
      @Nullable String mfcId, List<Policy> explicit, List<Skipped> skipped) {
    String latest =
        "(SELECT DISTINCT ON (mfc_id, location_code) mfc_id, location_code, zone, state,"
            + " high_turnover, reserve_stock FROM location_projection"
            + " ORDER BY mfc_id, location_code, location_version DESC)";
    var pairs =
        jdbc.queryForList(
            "SELECT DISTINCT u.mfc_id, u.sku FROM stock_unit u JOIN "
                + latest
                + " p ON p.mfc_id = u.mfc_id AND p.location_code = u.location_code"
                + " WHERE p.reserve_stock AND u.qty > 0 AND u.quality_state = 'NORMAL'"
                + " AND u.work_state = 'STORED' AND (CAST(? AS varchar) IS NULL OR u.mfc_id = ?)"
                + " ORDER BY u.mfc_id, u.sku",
            mfcId,
            mfcId);
    Instant now = clock.instant();
    List<Policy> out = new ArrayList<>();
    for (var row : pairs) {
      String mfc = (String) row.get("mfc_id");
      String sku = (String) row.get("sku");
      List<String> home =
          jdbc.queryForList(
              "SELECT u.location_code FROM stock_unit u JOIN "
                  + latest
                  + " p ON p.mfc_id = u.mfc_id AND p.location_code = u.location_code"
                  + " WHERE u.mfc_id = ? AND u.sku = ? AND p.zone IN ('STORAGE', 'PICKING')"
                  + " AND p.state = 'ACTIVE' AND NOT p.high_turnover AND NOT p.reserve_stock"
                  + " ORDER BY (u.qty > 0) DESC, u.updated_at DESC, u.location_code LIMIT 1",
              String.class,
              mfc,
              sku);
      if (home.isEmpty()) {
        skipped.add(new Skipped(mfc, "-", sku, "NO_HOME_SHELF"));
        continue;
      }
      String loc = home.get(0);
      boolean set =
          explicit.stream()
              .anyMatch(
                  e ->
                      e.mfcId().equals(mfc) && e.locationCode().equals(loc) && e.sku().equals(sku));
      if (!set) {
        out.add(new Policy(mfc, loc, sku, 0, 0, 1, "SYSTEM", now, true));
      }
    }
    return out;
  }

  private int evaluateOne(String requestId, String sourceSystem, Policy p, List<Skipped> skipped) {
    // 같은 칸·상품 점검을 줄 세운다(동시 점검이 진행 중 U 를 못 보고 둘 다 만들지 않게)
    jdbc.queryForList(
        "SELECT pg_advisory_xact_lock(hashtext(?))",
        "replenish:" + p.mfcId() + ":" + p.locationCode() + ":" + p.sku());
    var dest = locations.row(p.mfcId(), p.locationCode());
    var size = moveService.unitSize(p.sku()); // 상품 실물 치수 우선, 없으면 더미(설정 시)
    if (size == null) {
      skipped.add(new Skipped(p.mfcId(), p.locationCode(), p.sku(), "CONFIG_REQUIRED:SKU_SIZE"));
      return 0;
    }
    Integer capacity =
        size.capacity(dest.widthMm(), dest.depthMm(), dest.heightMm(), dest.maxWeightG());
    if (capacity == null) {
      skipped.add(
          new Skipped(p.mfcId(), p.locationCode(), p.sku(), "CONFIG_REQUIRED:LOCATION_DIMENSIONS"));
      return 0;
    }
    // 기준 없는 일반 선반(R-30): 칸 용량의 25% 이하이면 칸 용량까지, 단위 1
    int minQty = p.derived() ? capacity * DEFAULT_MIN_PERCENT / 100 : p.minQty();
    int targetQty = p.derived() ? capacity : p.targetQty();
    // 일반 선반(한 상품 한 자리)은 보충재고 칸에서만 채운다 — 다른 일반 칸에는 그 상품이 없다
    boolean reserveOnly = !dest.highTurnover();
    var holds = stock.openHolds(p.mfcId(), p.sku());
    int current = 0;
    int destAll = 0;
    var units = stock.units(p.mfcId(), p.sku());
    for (var u : units) {
      if (!u.key().locationCode().equals(p.locationCode())) {
        continue;
      }
      destAll += u.qty();
      if (u.key().quality() == Quality.NORMAL && u.key().work() == Work.STORED) {
        current += u.qty() - holds.getOrDefault(u.key(), 0);
      }
    }
    int incoming = 0;
    for (MoveRow m : moves.openAt(p.mfcId(), p.locationCode())) {
      if (m.toLocation().equals(p.locationCode()) && m.sku().equals(p.sku())) {
        incoming += m.incoming();
      }
    }
    // 출발 후보: 같은 상품의 다른 선반·피킹 칸, 소비기한 빠른 출하 가능 LOT 부터
    Instant now = clock.instant();
    var lotIds = units.stream().map(u -> u.key().lotId()).distinct().toList();
    var lots = stock.lots(lotIds);
    var lotBalances = stock.lotBalances(p.mfcId(), p.sku());
    record Source(String lotId, @Nullable LocalDate expiry, String location, int movable) {}
    List<Source> sources = new ArrayList<>();
    for (String lotId : lotIds) {
      var lot = lots.get(lotId);
      if (lot == null) {
        continue;
      }
      Boolean ok =
          reservations.shippableToday(p.sku(), lotId, lot.expiryDate(), lot.manufactureDate(), now);
      if (Boolean.FALSE.equals(ok)) {
        continue; // 부적격 LOT 은 피킹면에 채우지 않는다
      }
      int lotAvailable =
          lotBalances.stream()
              .filter(b -> b.lotId().equals(lotId))
              .mapToInt(b -> b.available())
              .findFirst()
              .orElse(0);
      for (Map.Entry<String, Integer> e : allocations.free(p.mfcId(), p.sku(), lotId).entrySet()) {
        if (e.getKey().equals(p.locationCode()) || e.getValue() <= 0) {
          continue;
        }
        var src = locations.projection(p.mfcId(), e.getKey()).orElse(null);
        if (src == null
            || !Locations.STORE_ZONES.contains(src.zone())
            || (reserveOnly && !src.reserveStock())) {
          continue;
        }
        sources.add(
            new Source(lotId, lot.expiryDate(), e.getKey(), Math.min(e.getValue(), lotAvailable)));
      }
    }
    sources.sort(
        Comparator.comparing(
                Source::expiry, Comparator.nullsLast(Comparator.<LocalDate>naturalOrder()))
            .thenComparing(Source::location));
    // LOT 가용은 LOT 단위라 같은 LOT 의 여러 칸에 겹쳐 세지 않게 깎으며 합한다
    Map<String, Integer> lotLeft = new java.util.HashMap<>();
    for (String lotId : lotIds) {
      lotLeft.put(
          lotId,
          lotBalances.stream()
              .filter(b -> b.lotId().equals(lotId))
              .mapToInt(b -> b.available())
              .findFirst()
              .orElse(0));
    }
    int s = 0;
    List<int[]> takes = new ArrayList<>();
    for (int i = 0; i < sources.size(); i++) {
      Source src = sources.get(i);
      int t = Math.min(src.movable(), lotLeft.getOrDefault(src.lotId(), 0));
      if (t > 0) {
        lotLeft.merge(src.lotId(), -t, Integer::sum);
        s += t;
        takes.add(new int[] {i, t});
      }
    }
    int v = capacity - destAll - incoming;
    int qty = ReplenishmentRules.orderQty(current, minQty, targetQty, incoming, s, v, p.unitQty());
    if (qty <= 0) {
      if (!p.derived() && current <= minQty && s < p.unitQty()) {
        skipped.add(new Skipped(p.mfcId(), p.locationCode(), p.sku(), "WAITING_FOR_STOCK"));
      }
      return 0;
    }
    int left = qty;
    int n = 0;
    for (int[] tk : takes) {
      if (left == 0) {
        break;
      }
      Source src = sources.get(tk[0]);
      int chunk = Math.min(left, tk[1]);
      String moveId = "RPL-" + UUID.randomUUID();
      moveService.create(
          requestId + ":" + moveId,
          sourceSystem,
          new MoveService.CreateCommand(
              moveId,
              p.mfcId(),
              p.sku(),
              src.lotId(),
              src.location(),
              p.locationCode(),
              chunk,
              "REPLENISH"));
      left -= chunk;
      n++;
    }
    log.info(
        "보충 {} {} {}{} — 현재 {} 최소 {} 목표 {} 진행 {} → 지시 {}개({}건)",
        p.mfcId(),
        p.locationCode(),
        p.sku(),
        p.derived() ? "(기본 규칙)" : "",
        current,
        minQty,
        targetQty,
        incoming,
        qty,
        n);
    return n;
  }

  private static String supervisor(String mfcId) {
    String me = Operator.requireCenter(mfcId);
    if (!PmartPrincipal.current().orElseThrow().hasRole(SUPERVISOR)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "보충 기준은 이 센터의 MFC_SUPERVISOR 가 정합니다.");
    }
    return me;
  }
}
