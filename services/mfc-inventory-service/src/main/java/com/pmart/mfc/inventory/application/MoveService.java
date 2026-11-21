package com.pmart.mfc.inventory.application;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.inventory.application.Ledger.HoldChange;
import com.pmart.mfc.inventory.domain.MoveRules;
import com.pmart.mfc.inventory.domain.ReserveCapacity;
import com.pmart.mfc.inventory.domain.StockUnit.Change;
import com.pmart.mfc.inventory.domain.StockUnit.Key;
import com.pmart.mfc.inventory.domain.StockUnit.Quality;
import com.pmart.mfc.inventory.domain.StockUnit.Work;
import com.pmart.mfc.inventory.domain.UnitSize;
import com.pmart.mfc.inventory.infrastructure.LocationProjectionStore.LocationRow;
import com.pmart.mfc.inventory.infrastructure.MoveStore;
import com.pmart.mfc.inventory.infrastructure.MoveStore.MoveRow;
import com.pmart.mfc.inventory.infrastructure.SkuProjectionStore;
import com.pmart.mfc.inventory.infrastructure.StockStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재고 위치 이동(IV-03, 2026-11-08 사용자 결정) — 두 단계: 출발 칸 → 카트(MOVING) → 도착 칸. H 불변.
 *
 * <ul>
 *   <li>배정: {@link MoveRules} 판정 뒤 출발 칸 NORMAL·STORED 에 MOVE 보류(E −q). 피킹 칸 배정·다른 이동은 보류를 빼고 보므로 같은
 *       물건을 못 가져간다. 도착 칸 공간은 들어올 예정량으로 잡는다.
 *   <li>집어 올리기: 출발 −q, 카트 MOVING +q, 보류 −q — E 변화 없음(배정 때 이미 뺐다).
 *   <li>내려놓기: 카트 −q, 도착(취소 뒤에는 출발도) STORED +q — E +q.
 *   <li>취소: 집지 않은 양만 보류를 풀어 E 복구. 카트 위 물건은 내려놓아야 끝난다.
 * </ul>
 *
 * 잠금 순서 이동 행 → 센터 요약 → LOT 요약 → 단위(Ledger). 실사 잠금 칸은 출발·도착 모두 안 된다(실사는 진행 중 이동이 있으면 시작을 거절).
 */
@Service
public class MoveService {

  public static final String MOVE_NOT_FOUND = "MOVE_NOT_FOUND";
  public static final String MOVE_QTY_EXCEEDED = "MOVE_QTY_EXCEEDED";
  public static final String CART_MISMATCH = "CART_MISMATCH";
  public static final String MOVE_ID_CONFLICT = "MOVE_ID_CONFLICT";

  private static final Logger log = LoggerFactory.getLogger(MoveService.class);

  public record CreateCommand(
      String moveId,
      String mfcId,
      String sku,
      String lotId,
      String fromLocation,
      String toLocation,
      int qty,
      String kind) {
    public CreateCommand(
        String moveId,
        String mfcId,
        String sku,
        String lotId,
        String fromLocation,
        String toLocation,
        int qty) {
      this(moveId, mfcId, sku, lotId, fromLocation, toLocation, qty, "MOVE");
    }
  }

  private final MoveStore moves;
  private final StockStore stock;
  private final Ledger ledger;
  private final Locations locations;
  private final SkuProjectionStore skus;
  private final LocationAllocationService allocations;
  private final InventoryProperties props;
  private final Clock clock;
  private final ReservationService reservations;

  private final com.pmart.mfc.inventory.infrastructure.LocationProjectionStore locationStore;

  public MoveService(
      MoveStore moves,
      StockStore stock,
      Ledger ledger,
      Locations locations,
      SkuProjectionStore skus,
      LocationAllocationService allocations,
      InventoryProperties props,
      Clock clock,
      ReservationService reservations,
      com.pmart.mfc.inventory.infrastructure.LocationProjectionStore locationStore) {
    this.locationStore = locationStore;
    this.reservations = reservations;
    this.moves = moves;
    this.stock = stock;
    this.ledger = ledger;
    this.locations = locations;
    this.skus = skus;
    this.allocations = allocations;
    this.props = props;
    this.clock = clock;
  }

  // ---------- 배정 ----------

  @Transactional
  public MoveRow create(String requestId, String sourceSystem, CreateCommand c) {
    String hash = hash(c);
    var existing = moves.find(c.moveId());
    if (existing.isPresent()) {
      if (!existing.get().contentHash().equals(hash)) {
        throw new PmartException(
            MOVE_ID_CONFLICT,
            HttpStatus.CONFLICT,
            "이동 " + c.moveId() + " 는 다른 내용으로 이미 있습니다.",
            false,
            List.of());
      }
      return existing.get();
    }
    if (c.qty() < 1) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "qty 는 1 이상입니다.");
    }
    locations.requireNotCountLocked(c.mfcId(), c.fromLocation());
    locations.requireNotCountLocked(c.mfcId(), c.toLocation());
    LocationRow from = locations.row(c.mfcId(), c.fromLocation());
    if (!Locations.STORE_ZONES.contains(from.zone())) {
      throw new PmartException(
          Locations.LOCATION_ZONE_MISMATCH,
          HttpStatus.CONFLICT,
          c.fromLocation() + " 는 선반·피킹 칸이 아닙니다(구역 " + from.zone() + ").",
          false,
          List.of());
    }
    var projection = skus.find(c.sku()).orElse(null);
    String storage = projection != null ? projection.storageType() : null;
    locations.requireStorage(c.mfcId(), c.toLocation(), storage);
    LocationRow to = locations.row(c.mfcId(), c.toLocation());
    // 상품 실물 치수 우선, 없으면 더미(설정 시) — 2026-11-16
    UnitSize size = UnitSize.resolve(c.sku(), projection, props.isDummySkuSize());
    if (size == null) {
      throw PmartException.configRequired(
          "상품 크기(" + c.sku() + " 치수 미등록 — 상품 마스터 dimensions 또는 pmart.inventory.dummy-sku-size)");
    }
    Integer capacity = capacityAt(c.mfcId(), to, c.sku(), size);
    if (capacity == null) {
      throw PmartException.configRequired("위치 치수(" + c.toLocation() + " 가로·세로·높이 — mfc-location)");
    }

    // 잠금: 센터 요약 → LOT 요약 (Ledger 와 같은 순서)
    stock.lockBalances(c.mfcId(), List.of(c.sku()));
    var lot =
        stock.lockLotBalances(c.mfcId(), List.of(c.sku())).stream()
            .filter(b -> b.lotId().equals(c.lotId()))
            .findFirst()
            .orElseThrow(
                () ->
                    PmartException.notFound(
                        "LOT_NOT_FOUND", "이 센터에 LOT " + c.lotId() + " 재고가 없습니다."));
    int pickable =
        Math.max(
            0, allocations.free(c.mfcId(), c.sku(), c.lotId()).getOrDefault(c.fromLocation(), 0));

    // 도착 칸의 이 상품(지금 + 들어올 예정) — 칸에 여러 상품 가능(2026-11-08)
    int destNow = 0;
    for (var u : stock.unitsAt(c.mfcId(), c.toLocation())) {
      if (u.key().sku().equals(c.sku())) {
        destNow += u.qty();
      }
    }
    int destIncoming = 0;
    for (MoveRow m : moves.openAt(c.mfcId(), c.toLocation())) {
      if (m.toLocation().equals(c.toLocation()) && m.sku().equals(c.sku())) {
        destIncoming += m.incoming();
      }
    }
    var lotRow = stock.lots(List.of(c.lotId())).get(c.lotId());
    Boolean shippable =
        lotRow == null
            ? null
            : reservations.shippableToday(
                c.sku(), c.lotId(), lotRow.expiryDate(), lotRow.manufactureDate(), clock.instant());
    // 이 상품이 있는(들어올) 선반·피킹 칸 → 고회전 여부
    Map<String, Boolean> shelves = new LinkedHashMap<>();
    int atFrom = 0;
    for (var u : stock.units(c.mfcId(), c.sku())) {
      String loc = u.key().locationCode();
      if (loc.equals(c.fromLocation())) {
        atFrom += u.qty();
      }
      shelf(c.mfcId(), loc, shelves);
    }
    for (MoveRow m : moves.openForSku(c.mfcId(), c.sku())) {
      shelf(c.mfcId(), m.toLocation(), shelves);
    }
    String reject =
        MoveRules.rejection(
            c.fromLocation(),
            c.toLocation(),
            from.placementExempt(),
            to.placementExempt(),
            to.reserveStock(),
            c.qty(),
            pickable,
            lot.available(),
            to.categoryId(),
            skus.categories(c.sku()).orElse(null),
            shippable,
            shelves,
            atFrom - c.qty(),
            destNow + destIncoming + c.qty(),
            capacity);
    if (reject != null) {
      throw new PmartException(
          reject,
          HttpStatus.CONFLICT,
          message(reject, c, pickable, lot.available(), capacity, destNow + destIncoming),
          false,
          List.of());
    }
    Instant at = clock.instant();
    Key fromKey = new Key(c.fromLocation(), c.sku(), c.lotId(), Quality.NORMAL, Work.STORED);
    ledger.postWithHolds(
        Ledger.newCommitId(),
        "MOVE_ALLOCATE",
        c.mfcId(),
        requestId,
        sourceSystem,
        c.moveId(),
        c.qty(),
        List.of(),
        List.of(),
        List.of(new HoldChange(fromKey, c.qty())),
        at);
    stock.insertHold("MVH-" + c.moveId(), c.mfcId(), fromKey, c.qty(), "MOVE", c.moveId(), at);
    MoveRow row =
        new MoveRow(
            c.moveId(),
            c.mfcId(),
            c.sku(),
            c.lotId(),
            c.fromLocation(),
            c.toLocation(),
            null,
            c.qty(),
            0,
            0,
            "ACTIVE",
            size.label(),
            capacity,
            hash,
            at,
            at,
            c.kind());
    moves.insert(row);
    log.info(
        "이동 배정 {} {} {}×{} {} → {} (크기 {} 용량 {})",
        c.moveId(),
        c.sku(),
        c.lotId(),
        c.qty(),
        c.fromLocation(),
        c.toLocation(),
        size.label(),
        capacity);
    return moves.find(c.moveId()).orElseThrow();
  }

  private void shelf(String mfcId, String loc, Map<String, Boolean> shelves) {
    if (shelves.containsKey(loc)) {
      return;
    }
    locations
        .projection(mfcId, loc)
        .filter(r -> Locations.STORE_ZONES.contains(r.zone()))
        .ifPresent(r -> shelves.put(loc, r.placementExempt()));
  }

  /**
   * 도착 칸의 이 상품 용량. 일반 칸은 상품마다 따로({@link UnitSize#capacity}), 보충재고 칸(R-30)은 다른 상품이 지금 차지하거나 들어올
   * 부피·무게를 뺀 나머지({@link ReserveCapacity}). 칸 치수가 없으면 null.
   */
  @Nullable Integer capacityAt(String mfcId, LocationRow to, String sku, UnitSize size) {
    if (!to.reserveStock()) {
      return size.capacity(to.widthMm(), to.depthMm(), to.heightMm(), to.maxWeightG());
    }
    Map<String, Integer> otherQty = new LinkedHashMap<>();
    for (var u : stock.unitsAt(mfcId, to.locationCode())) {
      if (!u.key().sku().equals(sku)) {
        otherQty.merge(u.key().sku(), u.qty(), Integer::sum);
      }
    }
    for (MoveRow m : moves.openAt(mfcId, to.locationCode())) {
      if (m.toLocation().equals(to.locationCode()) && !m.sku().equals(sku)) {
        otherQty.merge(m.sku(), m.incoming(), Integer::sum);
      }
    }
    List<ReserveCapacity.Occupant> others = new java.util.ArrayList<>();
    otherQty.forEach((s, q) -> others.add(new ReserveCapacity.Occupant(unitSize(s), q)));
    return ReserveCapacity.capacity(
        size, to.widthMm(), to.depthMm(), to.heightMm(), to.maxWeightG(), others);
  }

  // ---------- 집어 올리기 ----------

  @Transactional
  public MoveRow pickUp(
      String requestId,
      String sourceSystem,
      String moveId,
      String executionId,
      String cart,
      int qty) {
    MoveRow m = lockOrThrow(moveId);
    if (alreadyPosted(executionId, m.mfcId(), "MOVE_PICKUP:" + moveId + ":" + cart + ":" + qty)) {
      return moves.find(moveId).orElseThrow();
    }
    if (!"ACTIVE".equals(m.state())) {
      throw PmartException.invalidState("이동 " + moveId + " 는 " + m.state() + " 라 더 집지 않습니다.");
    }
    if (m.cartLocation() != null && !m.cartLocation().equals(cart)) {
      throw new PmartException(
          CART_MISMATCH,
          HttpStatus.CONFLICT,
          "이 이동은 카트 " + m.cartLocation() + " 로 옮기고 있습니다.",
          false,
          List.of());
    }
    if (qty < 1 || qty > m.unpicked()) {
      throw exceeded("집을 수 있는 남은 배정 " + m.unpicked() + "개");
    }
    locations.requireCart(m.mfcId(), cart);
    Instant at = clock.instant();
    Key fromKey = new Key(m.fromLocation(), m.sku(), m.lotId(), Quality.NORMAL, Work.STORED);
    Key cartKey = new Key(cart, m.sku(), m.lotId(), Quality.NORMAL, Work.MOVING);
    ledger.postWithHolds(
        Ledger.newCommitId(),
        "MOVE_PICKUP",
        m.mfcId(),
        requestId,
        sourceSystem,
        moveId,
        qty,
        List.of(new Change(fromKey, -qty), new Change(cartKey, qty)),
        List.of(),
        List.of(new HoldChange(fromKey, -qty)),
        at);
    stock.consumeMoveHold(moveId, qty, at);
    moves.update(moveId, m.qty(), m.pickedQty() + qty, m.droppedQty(), cart, "ACTIVE", at);
    log.info("이동 {} 집어 올림 {}개 → 카트 {}", moveId, qty, cart);
    return moves.find(moveId).orElseThrow();
  }

  // ---------- 내려놓기 ----------

  @Transactional
  public MoveRow drop(
      String requestId,
      String sourceSystem,
      String moveId,
      String executionId,
      String toLocation,
      int qty) {
    MoveRow m = lockOrThrow(moveId);
    if (alreadyPosted(
        executionId, m.mfcId(), "MOVE_DROP:" + moveId + ":" + toLocation + ":" + qty)) {
      return moves.find(moveId).orElseThrow();
    }
    if (!m.open()) {
      throw PmartException.invalidState("이동 " + moveId + " 는 이미 " + m.state() + " 입니다.");
    }
    boolean back = toLocation.equals(m.fromLocation());
    if (!toLocation.equals(m.toLocation()) && !(back && "CANCEL_PENDING".equals(m.state()))) {
      throw new PmartException(
          Locations.LOCATION_ZONE_MISMATCH,
          HttpStatus.CONFLICT,
          "이 이동의 도착 칸은 "
              + m.toLocation()
              + " 입니다(취소한 이동만 출발 칸 "
              + m.fromLocation()
              + " 으로 되돌릴 수 있다).",
          false,
          List.of());
    }
    if (qty < 1 || qty > m.onCart()) {
      throw exceeded("카트 위 " + m.onCart() + "개");
    }
    String storage = skus.find(m.sku()).map(s -> s.storageType()).orElse(null);
    locations.requireStorage(m.mfcId(), toLocation, storage);
    Instant at = clock.instant();
    Key cartKey =
        new Key(
            java.util.Objects.requireNonNull(m.cartLocation()),
            m.sku(),
            m.lotId(),
            Quality.NORMAL,
            Work.MOVING);
    Key toKey = new Key(toLocation, m.sku(), m.lotId(), Quality.NORMAL, Work.STORED);
    ledger.postWithHolds(
        Ledger.newCommitId(),
        "MOVE_DROP",
        m.mfcId(),
        requestId,
        sourceSystem,
        moveId,
        qty,
        List.of(new Change(cartKey, -qty), new Change(toKey, qty)),
        List.of(),
        List.of(),
        at);
    int dropped = m.droppedQty() + qty;
    String state = m.state();
    if (dropped == m.qty()) {
      state = "CANCEL_PENDING".equals(m.state()) ? "CANCELLED" : "COMPLETED";
    }
    moves.update(moveId, m.qty(), m.pickedQty(), dropped, m.cartLocation(), state, at);
    log.info("이동 {} 내려놓음 {}개 → {} ({})", moveId, qty, toLocation, state);
    return moves.find(moveId).orElseThrow();
  }

  // ---------- 취소 ----------

  @Transactional
  public MoveRow cancel(String requestId, String sourceSystem, String moveId) {
    MoveRow m = lockOrThrow(moveId);
    if (!"ACTIVE".equals(m.state())) {
      return m; // 이미 취소 중·끝남
    }
    Instant at = clock.instant();
    int unpicked = m.unpicked();
    if (unpicked > 0) {
      Key fromKey = new Key(m.fromLocation(), m.sku(), m.lotId(), Quality.NORMAL, Work.STORED);
      ledger.postWithHolds(
          Ledger.newCommitId(),
          "MOVE_CANCEL",
          m.mfcId(),
          requestId,
          sourceSystem,
          moveId,
          unpicked,
          List.of(),
          List.of(),
          List.of(new HoldChange(fromKey, -unpicked)),
          at);
      stock.consumeMoveHold(moveId, unpicked, at);
    }
    String state = m.onCart() == 0 ? "CANCELLED" : "CANCEL_PENDING";
    moves.update(moveId, m.pickedQty(), m.pickedQty(), m.droppedQty(), m.cartLocation(), state, at);
    log.info("이동 {} 취소 — 보류 {}개 풀고 카트 위 {}개 ({})", moveId, unpicked, m.onCart(), state);
    return moves.find(moveId).orElseThrow();
  }

  /** 용량 계산 크기: SKU 투영의 상품 실물 치수 우선, 없으면 더미(설정 시), 아니면 null(2026-11-16). */
  public @Nullable UnitSize unitSize(String sku) {
    return UnitSize.resolve(sku, skus.find(sku).orElse(null), props.isDummySkuSize());
  }

  @Transactional(readOnly = true)
  public MoveRow get(String moveId) {
    return moves
        .find(moveId)
        .orElseThrow(() -> PmartException.notFound(MOVE_NOT_FOUND, "이동이 없습니다: " + moveId));
  }

  /**
   * 입고 적치의 진열 규칙(2026-11-08 사용자 결정 — 이동과 같은 기준): 카테고리 칸은 그 카테고리 상품·출하 가능 LOT 만, 같은 상품은 한 자리(고회전
   * 제외). 용량은 칸 치수와 상품 크기를 알 때만 본다(적치는 치수 미등록으로 입고를 멈추지 않는다 — 전에는 용량을 아예 안 봤다). 상품 크기는 상품 실물 치수 우선,
   * 없으면 더미(설정 시) — 2026-11-16.
   */
  public void requirePlacement(String mfcId, String sku, String lotId, String toLocation, int qty) {
    Placement p = placement(mfcId, sku, lotId, toLocation);
    String reject = p.reject();
    if (reject == null && qty > p.room()) {
      reject = MoveRules.LOCATION_CAPACITY_EXCEEDED;
    }
    if (reject != null) {
      CreateCommand c = new CreateCommand("-", mfcId, sku, lotId, "(입고)", toLocation, qty);
      throw new PmartException(
          reject,
          HttpStatus.CONFLICT,
          message(reject, c, qty, qty, p.capacity() == null ? 0 : p.capacity(), p.destQty()),
          false,
          List.of());
    }
  }

  /**
   * 적치 판정 한 칸 — 수량과 무관한 규칙 거절({@code reject})과 들어갈 수 있는 남은 양({@code room}, 용량을 모르면 무제한).
   *
   * @param capacity 이 상품 기준 칸 용량(모르면 null)
   * @param destQty 도착 칸의 이 상품 지금 + 들어올 예정
   */
  record Placement(
      @Nullable String reject, int room, @Nullable Integer capacity, int destQty, boolean exempt) {}

  private Placement placement(String mfcId, String sku, String lotId, String toLocation) {
    LocationRow to = locations.row(mfcId, toLocation);
    UnitSize size = unitSize(sku);
    Integer capacity = size != null ? capacityAt(mfcId, to, sku, size) : null;
    int destNow = 0;
    for (var u : stock.unitsAt(mfcId, toLocation)) {
      if (u.key().sku().equals(sku)) {
        destNow += u.qty();
      }
    }
    int destIncoming = 0;
    for (MoveRow m : moves.openAt(mfcId, toLocation)) {
      if (m.toLocation().equals(toLocation) && m.sku().equals(sku)) {
        destIncoming += m.incoming();
      }
    }
    Map<String, Boolean> shelves = new LinkedHashMap<>();
    for (var u : stock.units(mfcId, sku)) {
      shelf(mfcId, u.key().locationCode(), shelves);
    }
    for (MoveRow m : moves.openForSku(mfcId, sku)) {
      shelf(mfcId, m.toLocation(), shelves);
    }
    var lotRow = stock.lots(List.of(lotId)).get(lotId);
    Boolean shippable =
        lotRow == null
            ? null
            : reservations.shippableToday(
                sku, lotId, lotRow.expiryDate(), lotRow.manufactureDate(), clock.instant());
    // 수량과 무관한 규칙만 먼저(수량 1, 용량 무제한) — 용량은 room 으로 따로
    String reject =
        MoveRules.rejection(
            "(입고)",
            toLocation,
            false,
            to.placementExempt(),
            to.reserveStock(),
            1,
            1,
            1,
            to.categoryId(),
            skus.categories(sku).orElse(null),
            shippable,
            shelves,
            0,
            0,
            Integer.MAX_VALUE);
    int destQty = destNow + destIncoming;
    int room = capacity == null ? Integer.MAX_VALUE : Math.max(0, capacity - destQty);
    return new Placement(reject, room, capacity, destQty, to.placementExempt());
  }

  /** 칸에 나눠 넣을 양(0.44.0, R-30). */
  public record PlacementShare(String locationCode, int qty) {}

  /** 나눠 넣기 추천 결과 — 다 못 넣으면 {@code unplacedQty} > 0(입고칸에 둔 채 작업을 열어 둔다). */
  public record PlacementPlan(List<PlacementShare> placements, int unplacedQty) {}

  /**
   * 복귀·적치 칸 후보 순서(#59, R-30): ① 그 상품이 이미 있는 일반 칸(고회전·보충재고 아님) ② 기타 칸(카테고리 없음, 보충재고 아님) ③ 그 온도대의
   * 보충재고 칸 — 그 상품이 이미 있는 보충재고 칸 먼저.
   */
  private List<String> placementCandidates(String mfcId, String sku) {
    java.util.LinkedHashSet<String> candidates = new java.util.LinkedHashSet<>();
    Map<String, Boolean> shelves = new LinkedHashMap<>();
    for (var u : stock.units(mfcId, sku)) {
      shelf(mfcId, u.key().locationCode(), shelves);
    }
    shelves.forEach(
        (loc, exempt) -> {
          if (!exempt) {
            candidates.add(loc);
          }
        });
    candidates.addAll(locationStore.miscShelves(mfcId, 50));
    String storage = skus.find(sku).map(s -> s.storageType()).orElse(null);
    if (storage != null) {
      List<String> reserves = locationStore.reserveLocations(mfcId, storage, 50);
      for (String loc : shelves.keySet()) {
        if (reserves.contains(loc)) {
          candidates.add(loc);
        }
      }
      candidates.addAll(reserves);
    }
    return new java.util.ArrayList<>(candidates);
  }

  /**
   * 복귀·적치 칸 추천(#59, 2026-11-08 사용자 결정): {@link #placementCandidates} 순서로 진열 규칙({@link
   * #requirePlacement})을 통과하고 전량이 들어가는 칸만 최대 {@code limit} 개. 통과하는 칸이 없으면 빈 목록. 나눠 넣기는 {@link
   * #planPlacement}.
   */
  @Transactional(readOnly = true)
  public List<String> suggestPlacement(String mfcId, String sku, String lotId, int qty, int limit) {
    List<String> out = new java.util.ArrayList<>();
    for (String loc : placementCandidates(mfcId, sku)) {
      try {
        Placement p = placement(mfcId, sku, lotId, loc);
        if (p.reject() == null && qty <= p.room()) {
          out.add(loc);
        }
      } catch (PmartException e) {
        // 규칙에 안 맞는 칸 — 다음 후보
      }
      if (out.size() >= limit) {
        break;
      }
    }
    return out;
  }

  /**
   * 나눠 넣기 추천(0.44.0, R-30 — 사용자 결정 2026-11-20 "선반칸이 가득차면 남는 건 입고 창고에"): {@link
   * #placementCandidates} 순서로 칸마다 들어가는 만큼. 일반 칸(규칙 예외 아님)은 한 곳만 — 한 상품 한 자리. 최대 {@code limit} 칸,
   * 남으면 {@code unplacedQty}.
   */
  @Transactional(readOnly = true)
  public PlacementPlan planPlacement(String mfcId, String sku, String lotId, int qty, int limit) {
    List<PlacementShare> out = new java.util.ArrayList<>();
    int left = qty;
    boolean normalUsed = false;
    for (String loc : placementCandidates(mfcId, sku)) {
      if (left <= 0 || out.size() >= limit) {
        break;
      }
      Placement p;
      try {
        p = placement(mfcId, sku, lotId, loc);
      } catch (PmartException e) {
        continue;
      }
      if (p.reject() != null || p.room() <= 0 || (!p.exempt() && normalUsed)) {
        continue;
      }
      int take = Math.min(left, p.room());
      out.add(new PlacementShare(loc, take));
      left -= take;
      normalUsed |= !p.exempt();
    }
    return new PlacementPlan(out, left);
  }

  /**
   * 격리품 놓을 칸 추천(0.35.0, IV-6): 구역 QUARANTINE·ACTIVE·온도대 = 상품 보관 유형, 위치 코드 순. 보관 유형을 모르면 빈 목록(참고값).
   */
  @Transactional(readOnly = true)
  public List<String> suggestQuarantine(String mfcId, String sku, int limit) {
    String storage = skus.find(sku).map(s -> s.storageType()).orElse(null);
    if (storage == null) {
      return List.of();
    }
    return locationStore.quarantineLocations(mfcId, storage, limit);
  }

  /** 출하 부적격 LOT 한 줄. */
  public record Unshippable(
      String sku,
      String lotId,
      java.time.@org.jspecify.annotations.Nullable LocalDate expiryDate,
      java.time.@org.jspecify.annotations.Nullable LocalDate manufactureDate,
      String locationCode,
      @org.jspecify.annotations.Nullable String locationCategoryId,
      int qty,
      boolean onReserve) {}

  /** 선반·피킹 칸의 정상·적치 재고 중 오늘 출하 기준을 못 맞추는 LOT(소비기한 빠른 순, 최대 500). 기준이 없는 상품은 뺀다. */
  @Transactional(readOnly = true)
  public List<Unshippable> unshippable(String mfcId) {
    Operator.requireCenter(mfcId);
    List<Unshippable> out = unshippableAt(mfcId);
    return out.size() > 500 ? out.subList(0, 500) : out;
  }

  /** {@link #unshippable} 의 계산(권한 검사·500 자르기 없음) — 정기 알림(UnshippableLotScan)이 쓴다. */
  @Transactional(readOnly = true)
  public List<Unshippable> unshippableAt(String mfcId) {
    Instant now = clock.instant();
    var units = stock.storedUnits(mfcId);
    var lots =
        stock.lots(
            units.stream().map(u -> u.key().lotId()).collect(java.util.stream.Collectors.toSet()));
    Map<String, Boolean> verdict = new LinkedHashMap<>();
    List<Unshippable> out = new java.util.ArrayList<>();
    for (var u : units) {
      var loc = locations.projection(mfcId, u.key().locationCode()).orElse(null);
      if (loc == null || !Locations.STORE_ZONES.contains(loc.zone())) {
        continue;
      }
      var lot = lots.get(u.key().lotId());
      if (lot == null) {
        continue;
      }
      Boolean ok =
          verdict.computeIfAbsent(
              u.key().lotId(),
              id -> {
                Boolean v =
                    reservations.shippableToday(
                        u.key().sku(), id, lot.expiryDate(), lot.manufactureDate(), now);
                return v == null ? Boolean.TRUE : v; // 기준 없음 — 판정 못 하니 목록에 넣지 않는다
              });
      if (!ok) {
        out.add(
            new Unshippable(
                u.key().sku(),
                u.key().lotId(),
                lot.expiryDate(),
                lot.manufactureDate(),
                u.key().locationCode(),
                loc.categoryId(),
                u.qty(),
                loc.reserveStock()));
      }
    }
    out.sort(
        java.util.Comparator.comparing(
                Unshippable::expiryDate,
                java.util.Comparator.nullsLast(java.util.Comparator.<LocalDate>naturalOrder()))
            .thenComparing(Unshippable::locationCode));
    return out;
  }

  /** 실사: 이 칸들에 진행 중 이동이 있는가. */
  public boolean busy(String mfcId, List<String> locations) {
    return locations.stream().anyMatch(l -> !moves.openAt(mfcId, l).isEmpty());
  }

  private boolean alreadyPosted(String executionId, String mfcId, String fingerprint) {
    String fp = sha(fingerprint);
    var done = stock.execution(executionId);
    if (done.isPresent()) {
      if (!done.get().contentHash().equals(fp)) {
        throw new PmartException(
            TaskExecutionService.EXECUTION_ALREADY_POSTED,
            HttpStatus.CONFLICT,
            "실행 " + executionId + " 는 다른 내용으로 이미 반영됐습니다.",
            false,
            List.of());
      }
      return true;
    }
    stock.claimExecution(executionId, mfcId, "MOVE", fp, "-", 0, clock.instant());
    return false;
  }

  private MoveRow lockOrThrow(String moveId) {
    return moves
        .lock(moveId)
        .orElseThrow(() -> PmartException.notFound(MOVE_NOT_FOUND, "이동이 없습니다: " + moveId));
  }

  private static PmartException exceeded(String what) {
    return new PmartException(
        MOVE_QTY_EXCEEDED, HttpStatus.CONFLICT, "수량이 " + what + "을 넘습니다.", false, List.of());
  }

  private static String message(
      String code, CreateCommand c, int pickable, int available, int capacity, int destQty) {
    return switch (code) {
      case MoveRules.SAME_LOCATION -> "출발과 도착이 같은 칸입니다.";
      case MoveRules.MOVE_QTY_EXCEEDS_PICKABLE ->
          c.fromLocation() + " 에서 옮길 수 있는 양은 " + pickable + "개입니다(다른 주문 배정·보류 제외).";
      case MoveRules.MOVE_QTY_EXCEEDS_AVAILABLE ->
          "LOT " + c.lotId() + " 의 가용이 " + available + "개라 그 이상 옮기면 예약이 판매 가능을 넘습니다.";
      case MoveRules.CATEGORY_MISMATCH ->
          c.toLocation() + " 은 다른 카테고리 칸입니다 — 이 상품의 카테고리 칸이나 기타 칸으로 옮깁니다.";
      case MoveRules.SKU_CATEGORY_UNKNOWN ->
          c.sku() + " 의 카테고리를 모릅니다(상품 정보 미수신) — 기타 칸으로만 옮길 수 있습니다.";
      case MoveRules.LOT_NOT_SHIPPABLE ->
          "LOT " + c.lotId() + " 은 출하 기준(소비기한)을 못 맞춥니다 — 카테고리 칸이 아니라 기타 칸으로 뺍니다.";
      case MoveRules.SKU_ALREADY_SHELVED -> c.sku() + " 는 이미 다른 칸에 있습니다(한 상품은 한 자리, 고회전 선반 제외).";
      case MoveRules.SKU_SPLIT_NOT_ALLOWED ->
          c.fromLocation() + " 에 " + c.sku() + " 가 남아 두 칸에 나뉩니다 — 전부 옮기거나 고회전 선반으로 옮깁니다.";
      case MoveRules.LOCATION_CAPACITY_EXCEEDED ->
          c.toLocation() + " 에는 이 상품이 " + capacity + "개까지 들어갑니다(지금·예정 " + destQty + "개).";
      default -> code;
    };
  }

  private static String hash(CreateCommand c) {
    return sha(
        String.join(
            "|",
            c.mfcId(),
            c.sku(),
            c.lotId(),
            c.fromLocation(),
            c.toLocation(),
            String.valueOf(c.qty())));
  }

  private static String sha(String s) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
