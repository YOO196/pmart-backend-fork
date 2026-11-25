package com.pmart.mfc.fulfillment.application;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.fulfillment.infrastructure.OrderLookupStore;
import com.pmart.mfc.fulfillment.infrastructure.OrderLookupStore.Package;
import com.pmart.mfc.fulfillment.infrastructure.OrderLookupStore.Shipment;
import com.pmart.mfc.fulfillment.infrastructure.OrderLookupStore.Shortage;
import com.pmart.mfc.fulfillment.infrastructure.OrderLookupStore.Stop;
import com.pmart.mfc.fulfillment.infrastructure.OrderLookupStore.Task;
import com.pmart.mfc.fulfillment.infrastructure.OrderLookupStore.WorkOrder;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * 주문 번호로 현장 상태 보기(0.10.0, 백오피스 통합 조회 MFC 칸 BO-11 — 요청 F-3). 이 서비스 원장만 읽는다. 본사
 * 직원(OPERATOR_ADMIN·CS_AGENT· RIDER_OPS)은 모든 센터, 그 밖 운영자는 토큰 센터의 작업 지시만.
 */
@Service
public class OrderLookupService {

  /** 모든 센터를 보는 본사 역할(order-service 직원용 주문 조회와 같은 셋). */
  static final Set<String> ALL_CENTER_ROLES = Set.of("OPERATOR_ADMIN", "CS_AGENT", "RIDER_OPS");

  public record ShipmentView(
      Shipment shipment, List<Task> tasks, List<Shortage> shortages, List<Package> packages) {}

  public record WorkOrderView(WorkOrder workOrder, List<ShipmentView> shipments) {}

  public record View(
      String orderId,
      @Nullable String orderNumber,
      List<WorkOrderView> workOrders,
      List<Stop> stops,
      Instant asOf) {}

  private final OrderLookupStore store;
  private final OrderNumbers orderNumbers;
  private final Clock clock;

  public OrderLookupService(OrderLookupStore store, OrderNumbers orderNumbers, Clock clock) {
    this.store = store;
    this.orderNumbers = orderNumbers;
    this.clock = clock;
  }

  /**
   * orderId 또는 화면용 주문번호(0.27.0, R-29) 중 정확히 하나로 찾는다. 번호는 order OrderConfirmed 사본에서 orderId 로 바꾸고,
   * 사본이 없으면 404(주문이 아직 MFC 에 오지 않은 것과 같게).
   */
  public View find(@Nullable String orderId, @Nullable String orderNumber) {
    boolean byId = orderId != null && !orderId.isBlank();
    boolean byNumber = orderNumber != null && !orderNumber.isBlank();
    if (byId == byNumber) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "orderId 와 orderNumber 중 하나만 주세요.");
    }
    if (byId) {
      return byOrder(orderId);
    }
    requireOperator();
    String id = orderNumbers.orderIdOf(OrderNumbers.normalize(orderNumber));
    if (id == null) {
      throw PmartException.notFound("WORK_ORDER_NOT_FOUND", "이 주문번호의 작업 지시가 없습니다.");
    }
    return byOrder(id);
  }

  private static PmartPrincipal requireOperator() {
    return PmartPrincipal.current()
        .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
        .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "직원 토큰만 볼 수 있습니다."));
  }

  public View byOrder(String orderId) {
    PmartPrincipal p = requireOperator();
    boolean allCenters = ALL_CENTER_ROLES.stream().anyMatch(p::hasRole);
    Instant asOf = clock.instant();
    List<WorkOrder> all = store.workOrders(orderId);
    List<Stop> stops = store.stops(orderId);
    if (all.isEmpty() && stops.isEmpty()) {
      throw PmartException.notFound("WORK_ORDER_NOT_FOUND", "이 주문의 작업 지시가 없습니다.");
    }
    List<WorkOrder> visible =
        allCenters ? all : all.stream().filter(w -> p.canAccessCenter(w.mfcId())).toList();
    if (!allCenters && visible.isEmpty()) {
      // 작업 지시가 없으면(작업 지시 전 중지만) 센터를 알 수 없어 본사 역할만 본다
      throw new PmartException(ErrorCode.FORBIDDEN, "이 주문의 센터에 대한 권한이 없습니다.");
    }
    List<String> fulfillmentIds = visible.stream().map(WorkOrder::fulfillmentId).toList();
    List<Shipment> shipments = store.shipments(fulfillmentIds);
    List<String> shipmentIds = shipments.stream().map(Shipment::shipmentId).toList();
    List<Task> tasks = store.tasks(shipmentIds);
    List<Shortage> shortages = store.shortages(shipmentIds);
    List<Package> packages = store.packages(shipmentIds);
    List<WorkOrderView> views =
        visible.stream()
            .map(
                w ->
                    new WorkOrderView(
                        w,
                        shipments.stream()
                            .filter(s -> s.fulfillmentId().equals(w.fulfillmentId()))
                            .map(
                                s ->
                                    new ShipmentView(
                                        s,
                                        tasks.stream()
                                            .filter(t -> t.shipmentId().equals(s.shipmentId()))
                                            .toList(),
                                        shortages.stream()
                                            .filter(x -> x.shipmentId().equals(s.shipmentId()))
                                            .toList(),
                                        packages.stream()
                                            .filter(x -> x.shipmentId().equals(s.shipmentId()))
                                            .toList()))
                            .toList()))
            .toList();
    return new View(orderId, orderNumbers.of(orderId), views, stops, asOf);
  }
}
