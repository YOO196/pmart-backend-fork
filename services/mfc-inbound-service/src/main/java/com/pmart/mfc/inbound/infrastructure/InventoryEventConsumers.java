package com.pmart.mfc.inbound.infrastructure;

import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import com.pmart.mfc.inbound.application.ReplenishmentTrigger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * mfc-inventory 사건 구독(mfc.inventory.v1) — 재고 변동 때 자동 보충 점검 표시. 바인딩 inventoryEvents-in-0(설정 저장소,
 * ADR-0035).
 */
@Configuration(proxyBeanMethods = false)
class InventoryEventConsumers {

  /**
   * mfc-quality 사건(mfc.quality.v1, 바인딩 qualityEvents-in-0) — ReturnInspected 의 재판매 분 적치
   * 작업(IV-10)·폐기 분 격리 이동 작업, (0.17.0) QualityDecisionMade RELEASE → 해제분 적치 작업, DisposalApproved →
   * 격리 이동 작업 정리.
   */
  @Bean
  PmartEventConsumer qualityEvents(
      EventConsumers consumers,
      com.pmart.mfc.inbound.application.ReturnReceiptService returns,
      com.pmart.mfc.inbound.application.QuarantineReleases releases) {
    return consumers.of(
        e -> {
          returns.onQualityEvent(e);
          releases.onQualityEvent(e);
        });
  }

  /**
   * mfc-capacity 사건(mfc.capacity.v1, 바인딩 capacityEvents-in-0) — 이동 지시(작업자 쪽 4단계)·입고 작업(0.9.0, BR-52
   * R2) 담당 투영. taskId 가 어느 쪽에도 없으면 아무것도 안 바뀐다.
   */
  @Bean
  PmartEventConsumer capacityEvents(
      EventConsumers consumers,
      com.pmart.mfc.inbound.application.MoveOrderService moves,
      com.pmart.mfc.inbound.application.InboundTaskService tasks) {
    return consumers.of(
        e -> {
          moves.onCapacityEvent(e);
          tasks.onCapacityEvent(e);
        });
  }

  /**
   * delivery 사건(delivery.delivery.v1, 바인딩 deliveryEvents-in-0) — ReturnDelivered → 반납 도착 대기(MI-2,
   * 0.13.0).
   */
  @Bean
  PmartEventConsumer deliveryEvents(
      EventConsumers consumers, com.pmart.mfc.inbound.application.ReturnArrivals arrivals) {
    return consumers.of(arrivals::onDeliveryEvent);
  }

  /**
   * mfc.inventory.v1 — 재고 변동 → 보충 점검 표시, (0.18.0) StockHoldResolved → 수령장 분실 조사 보류에서 찾은 양 적치 작업.
   */
  @Bean
  PmartEventConsumer inventoryEvents(
      EventConsumers consumers,
      ReplenishmentTrigger trigger,
      com.pmart.mfc.inbound.application.DockHoldResolutions holds) {
    return consumers.of(
        e -> {
          trigger.onInventoryEvent(e);
          holds.onInventoryEvent(e);
        });
  }
}
