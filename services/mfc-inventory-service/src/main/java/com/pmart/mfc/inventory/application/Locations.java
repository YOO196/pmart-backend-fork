package com.pmart.mfc.inventory.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.inventory.infrastructure.LocationProjectionStore;
import com.pmart.mfc.inventory.infrastructure.LocationProjectionStore.LocationRow;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 위치 투영과 목적지 검증(ADR-0040 2단계, IV-02·IV-03). 위치 정본은 mfc-location 이고, 여기서는 MfcLocationStateChanged
 * 사본으로 수령·적치 목적지를 판정한다.
 *
 * <ul>
 *   <li>수령 위치: 등록·ACTIVE·구역 RECEIVING(또는 회수품 RETURN).
 *   <li>적치 위치: 등록·ACTIVE·구역 STORAGE/PICKING, 온도대 = SKU 보관 유형. 보관 유형을 모르면 CONFIG_REQUIRED.
 *   <li>출발 위치가 실사 잠금(COUNT_LOCKED)이면 거절(IV-11). 출발 위치 존재·잔량은 단위 행으로 검사된다.
 * </ul>
 */
@Service
public class Locations {

  public static final String LOCATION_NOT_REGISTERED = "LOCATION_NOT_REGISTERED";
  public static final String LOCATION_CLOSED = "LOCATION_CLOSED";
  public static final String LOCATION_ZONE_MISMATCH = "LOCATION_ZONE_MISMATCH";
  public static final String TEMPERATURE_ZONE_MISMATCH = "TEMPERATURE_ZONE_MISMATCH";

  static final Set<String> RECEIVE_ZONES = Set.of("RECEIVING", "RETURN");
  static final Set<String> STORE_ZONES = Set.of("STORAGE", "PICKING");
  static final String QUARANTINE_ZONE = "QUARANTINE";

  private static final Logger log = LoggerFactory.getLogger(Locations.class);

  private final LocationProjectionStore store;
  private final com.pmart.mfc.inventory.infrastructure.CountStore counts;
  private final Clock clock;

  public Locations(
      LocationProjectionStore store,
      com.pmart.mfc.inventory.infrastructure.CountStore counts,
      Clock clock) {
    this.store = store;
    this.counts = counts;
    this.clock = clock;
  }

  /**
   * 실사 계획 잠금(0.34.0, BR-52 R14 — 사용자 결정 "관리자가 계획·서버 잠금"): 끝나지 않은 계획 실사의 칸은 이 서비스가 잠근다. mfc-location
   * 의 COUNT_LOCKED 와 같은 효과(409 LOCATION_CLOSED). 실사가 끝나거나 취소되면 저절로 풀린다.
   */
  public void requireNotPlanLocked(String mfcId, String locationCode) {
    if (counts.planLocked(mfcId, locationCode)) {
      throw new PmartException(
          LOCATION_CLOSED,
          HttpStatus.CONFLICT,
          locationCode + " 는 실사 계획으로 잠긴 칸입니다(실사가 끝나면 풀립니다).",
          true,
          List.of());
    }
  }

  private static @Nullable Integer intOrNull(JsonNode p, String f) {
    return p.hasNonNull(f) ? p.path(f).asInt() : null;
  }

  /** 카트(재고 이동 바구니, IV-03): 등록·ACTIVE·구역 CART. */
  public void requireCart(String mfcId, String locationCode) {
    LocationRow r = active(mfcId, locationCode);
    if (!"CART".equals(r.zone())) {
      throw mismatch(LOCATION_ZONE_MISMATCH, locationCode + " 는 카트가 아닙니다(구역 " + r.zone() + ").");
    }
  }

  public java.util.Optional<LocationRow> projection(String mfcId, String locationCode) {
    return store.byCode(mfcId, locationCode);
  }

  /** 투영 한 칸(없으면 404 — 등록 안 된 위치). */
  public LocationRow row(String mfcId, String locationCode) {
    return store
        .byCode(mfcId, locationCode)
        .orElseThrow(
            () ->
                PmartException.notFound(LOCATION_NOT_REGISTERED, "등록되지 않은 위치입니다: " + locationCode));
  }

  /** MfcLocationStateChanged → 투영(InboxGuard 트랜잭션 안). */
  public void apply(EventEnvelope e) {
    if (!"MfcLocationStateChanged".equals(e.eventType())) {
      return;
    }
    JsonNode p = e.payload();
    if (!p.hasNonNull("locationCode")
        || !p.hasNonNull("zone")
        || !p.hasNonNull("temperatureZone")) {
      log.warn("MfcLocationStateChanged {} without attributes ignored", e.eventId());
      return;
    }
    int version = p.path("version").asInt((int) e.aggregateVersion());
    int n =
        store.upsert(
            new LocationRow(
                p.path("locationId").asString(),
                p.path("mfcId").asString(),
                p.path("locationCode").asString(),
                p.path("zone").asString(),
                p.path("temperatureZone").asString(),
                p.path("state").asString(),
                version,
                p.path("highTurnover").asBoolean(false),
                intOrNull(p, "widthMm"),
                intOrNull(p, "depthMm"),
                intOrNull(p, "heightMm"),
                intOrNull(p, "maxWeightG"),
                p.hasNonNull("categoryId") ? p.path("categoryId").asString() : null),
            clock.instant());
    if (n == 0) {
      log.debug("location {} v{} ignored (older)", p.path("locationId").asString(), version);
    }
  }

  public void requireReceiving(String mfcId, String locationCode) {
    LocationRow r = active(mfcId, locationCode);
    if (!RECEIVE_ZONES.contains(r.zone())) {
      throw mismatch(LOCATION_ZONE_MISMATCH, locationCode + " 는 수령 구역이 아닙니다(구역 " + r.zone() + ").");
    }
  }

  public void requireStorage(String mfcId, String locationCode, @Nullable String storageType) {
    LocationRow r = active(mfcId, locationCode);
    if (!STORE_ZONES.contains(r.zone())) {
      throw mismatch(
          LOCATION_ZONE_MISMATCH, locationCode + " 는 선반·피킹 구역이 아닙니다(구역 " + r.zone() + ").");
    }
    if (storageType == null) {
      throw PmartException.configRequired("SKU 보관 유형(상품 정보 storageType)");
    }
    if (!storageType.equals(r.temperatureZone())) {
      throw mismatch(
          TEMPERATURE_ZONE_MISMATCH,
          locationCode + " 의 온도대 " + r.temperatureZone() + " ≠ 상품 보관 유형 " + storageType + ".");
    }
  }

  /**
   * 격리 구역 칸(0.35.0, IV-6 — MFC 업무 문서 §5 "냉장·냉동 격리상품은 각각 별도 4평"): 등록·ACTIVE·구역 QUARANTINE·온도대 = 상품
   * 보관 유형. 보관 유형을 모르면 CONFIG_REQUIRED.
   */
  public void requireQuarantine(String mfcId, String locationCode, @Nullable String storageType) {
    LocationRow r = active(mfcId, locationCode);
    if (!QUARANTINE_ZONE.equals(r.zone())) {
      throw mismatch(LOCATION_ZONE_MISMATCH, locationCode + " 는 격리 구역이 아닙니다(구역 " + r.zone() + ").");
    }
    if (storageType == null) {
      throw PmartException.configRequired("SKU 보관 유형(상품 정보 storageType)");
    }
    if (!storageType.equals(r.temperatureZone())) {
      throw mismatch(
          TEMPERATURE_ZONE_MISMATCH,
          locationCode + " 의 온도대 " + r.temperatureZone() + " ≠ 상품 보관 유형 " + storageType + ".");
    }
  }

  /** 피킹 도착 위치(IV-07): 등록·ACTIVE·구역 PACKING. 온도대는 보지 않는다(포장대는 짧게 머무는 작업 위치). */
  public void requirePacking(String mfcId, String locationCode) {
    LocationRow r = active(mfcId, locationCode);
    if (!"PACKING".equals(r.zone())) {
      throw mismatch(LOCATION_ZONE_MISMATCH, locationCode + " 는 포장 구역이 아닙니다(구역 " + r.zone() + ").");
    }
  }

  /** 출하대기 랙(0.29.0, F-12): 등록·ACTIVE·구역 DISPATCH_STAGING. 온도대는 보지 않는다(봉투가 잠깐 머무는 곳). */
  public void requireStaging(String mfcId, String locationCode) {
    LocationRow r = active(mfcId, locationCode);
    if (!"DISPATCH_STAGING".equals(r.zone())) {
      throw mismatch(
          LOCATION_ZONE_MISMATCH, locationCode + " 는 출하대기 구역이 아닙니다(구역 " + r.zone() + ").");
    }
  }

  /**
   * 피킹 도착지(IV-07 "지정 토트", 2026-11-17 PDA 요청): 등록·ACTIVE·구역 CART(장바구니) 또는 PACKING(포장 위치 — 예전
   * 흐름·시뮬레이터). 온도대는 보지 않는다(카트·포장대 모두 짧게 머무는 작업 위치 — 이동의 카트와 같게).
   */
  public void requirePickDestination(String mfcId, String locationCode) {
    LocationRow r = active(mfcId, locationCode);
    if (!"CART".equals(r.zone()) && !"PACKING".equals(r.zone())) {
      throw mismatch(
          LOCATION_ZONE_MISMATCH, locationCode + " 는 장바구니·포장 구역이 아닙니다(구역 " + r.zone() + ").");
    }
  }

  /**
   * 실사로 잠긴 칸인가 — 계획 잠금(이 서비스) 또는 mfc-location COUNT_LOCKED. {@link #requireNotCountLocked} 가 거절하는
   * 칸과 같다(피킹 위치 배정이 이런 칸을 고르지 않게, 0.36.0).
   */
  public boolean countLocked(String mfcId, String locationCode) {
    return counts.planLocked(mfcId, locationCode)
        || store
            .byCode(mfcId, locationCode)
            .filter(r -> "COUNT_LOCKED".equals(r.state()))
            .isPresent();
  }

  /** 출발 위치: 투영에 있고 실사 잠금이면 거절. 투영에 없는 위치(이관 위치 MIGRATION 등)는 단위 행 잔량으로만 판정. */
  public void requireNotCountLocked(String mfcId, String locationCode) {
    requireNotPlanLocked(mfcId, locationCode);
    store
        .byCode(mfcId, locationCode)
        .filter(r -> "COUNT_LOCKED".equals(r.state()))
        .ifPresent(
            r -> {
              throw new PmartException(
                  LOCATION_CLOSED,
                  HttpStatus.CONFLICT,
                  locationCode + " 는 실사 잠금 중입니다.",
                  true,
                  List.of());
            });
  }

  /** 실사 대상 위치(IV-11): 등록·실사 잠금(COUNT_LOCKED) 이어야 계수를 시작·반영한다. */
  public void requireCountLocked(String mfcId, String locationCode) {
    LocationRow r =
        store
            .byCode(mfcId, locationCode)
            .orElseThrow(
                () ->
                    new PmartException(
                        LOCATION_NOT_REGISTERED,
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "등록되지 않은 위치입니다: " + mfcId + "/" + locationCode,
                        false,
                        List.of()));
    if (!"COUNT_LOCKED".equals(r.state())) {
      throw new PmartException(
          com.pmart.mfc.inventory.domain.CountRules.LOCATION_NOT_LOCKED,
          HttpStatus.CONFLICT,
          locationCode + " 는 실사 잠금 상태가 아닙니다(지금 " + r.state() + ") — mfc-location 에서 먼저 잠근다.",
          false,
          List.of());
    }
  }

  private LocationRow active(String mfcId, String locationCode) {
    LocationRow r =
        store
            .byCode(mfcId, locationCode)
            .orElseThrow(
                () ->
                    new PmartException(
                        LOCATION_NOT_REGISTERED,
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "등록되지 않은 위치입니다: " + mfcId + "/" + locationCode + " (mfc-location 등록·전파 확인)",
                        true,
                        List.of()));
    if (!"ACTIVE".equals(r.state())) {
      throw new PmartException(
          LOCATION_CLOSED,
          HttpStatus.CONFLICT,
          locationCode + " 는 " + r.state() + " 상태입니다.",
          false,
          List.of());
    }
    requireNotPlanLocked(mfcId, locationCode);
    return r;
  }

  private static PmartException mismatch(String code, String message) {
    return new PmartException(code, HttpStatus.CONFLICT, message, false, List.of());
  }
}
