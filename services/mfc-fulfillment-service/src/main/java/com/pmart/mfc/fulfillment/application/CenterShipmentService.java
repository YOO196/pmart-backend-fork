package com.pmart.mfc.fulfillment.application;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.fulfillment.infrastructure.CenterShipmentStore;
import com.pmart.mfc.fulfillment.infrastructure.CenterShipmentStore.PackageRow;
import com.pmart.mfc.fulfillment.infrastructure.CenterShipmentStore.ShipmentRow;
import com.pmart.mfc.fulfillment.infrastructure.CenterShipmentStore.TaskRow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 센터 출고 목록(요청 F-9, 2026-11-17 — PC 2 포장 인계 현황, 업무 원문 Delivery §10.1·§3.1·§3.4·§3.7). 출고마다 PICK·PACK
 * 지금 상태와 살아 있는 포장(인계 허가·인계 시각·라이더)을 원천 값 그대로 준다 — 단계 표시(출고요청·피킹중·…)는 bff 가 정한다. 권한은 F-3 과 같다:
 * OPERATOR_ADMIN·CS_AGENT·RIDER_OPS 는 모든 센터, 그 밖 운영자는 그 센터({@code canAccessCenter}). 개인정보·메모·QR 없음.
 */
@Service
public class CenterShipmentService {

  /** 상태를 안 주면 끝나지 않은 출고. */
  static final List<String> OPEN = List.of("PREPARING", "READY", "PARTIALLY_HANDED_OVER");

  static final Set<String> STATES =
      Set.of(
          "PREPARING",
          "READY",
          "PARTIALLY_HANDED_OVER",
          "HANDED_OVER",
          "CANCELLED",
          "CLOSED_PARTIAL");

  public record Board(
      ShipmentRow shipment,
      @Nullable String orderNumber,
      @Nullable TaskRow pick,
      @Nullable TaskRow pack,
      List<PackageRow> packages) {}

  private final CenterShipmentStore store;
  private final OrderNumbers orderNumbers;

  public CenterShipmentService(CenterShipmentStore store, OrderNumbers orderNumbers) {
    this.store = store;
    this.orderNumbers = orderNumbers;
  }

  @Transactional(readOnly = true)
  public List<Board> list(
      String mfcId, @Nullable List<String> states, int size, @Nullable String cursor) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "운영자만 볼 수 있습니다."));
    boolean allCenters = OrderLookupService.ALL_CENTER_ROLES.stream().anyMatch(p::hasRole);
    if (!allCenters && !p.canAccessCenter(mfcId)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "이 센터의 출고를 볼 권한이 없습니다.");
    }
    List<String> s = states == null || states.isEmpty() ? OPEN : states;
    for (String x : s) {
      if (!STATES.contains(x)) {
        throw new PmartException(ErrorCode.VALIDATION_FAILED, "모르는 출고 상태: " + x);
      }
    }
    List<ShipmentRow> rows = store.shipments(mfcId, s, cursor, Math.max(1, Math.min(size, 200)));
    List<String> ids = rows.stream().map(ShipmentRow::shipmentId).toList();
    Map<String, TaskRow> picks = new LinkedHashMap<>();
    Map<String, TaskRow> packs = new LinkedHashMap<>();
    for (TaskRow t : store.tasks(ids)) {
      ("PICK".equals(t.taskType()) ? picks : packs).put(t.shipmentId(), t);
    }
    Map<String, List<PackageRow>> pkgs = new LinkedHashMap<>();
    for (PackageRow r : store.packages(ids)) {
      pkgs.computeIfAbsent(r.shipmentId(), k -> new ArrayList<>()).add(r);
    }
    Map<String, String> numbers =
        orderNumbers.of(rows.stream().map(ShipmentRow::orderId).distinct().toList());
    return rows.stream()
        .map(
            r ->
                new Board(
                    r,
                    numbers.get(r.orderId()),
                    picks.get(r.shipmentId()),
                    packs.get(r.shipmentId()),
                    pkgs.getOrDefault(r.shipmentId(), List.of())))
        .toList();
  }
}
