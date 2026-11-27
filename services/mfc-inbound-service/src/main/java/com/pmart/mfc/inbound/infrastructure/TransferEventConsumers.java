package com.pmart.mfc.inbound.infrastructure;

import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import com.pmart.mfc.inbound.application.InboundPromises;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 입고 예정 원천 사건 구독(0.16.0, ADR-0035 함수 바인딩 — 토픽은 설정 저장소). 세 토픽 모두 {@link
 * InboundPromises#onTransferEvent} 하나로 — 사건 종류로 나눈다. 내는
 * 서비스(replenishment·cdc-inventory·cdc-outbound)가 아직 없어 실서버에는 사건이 오지 않는다.
 */
@Configuration(proxyBeanMethods = false)
class TransferEventConsumers {

  /** scm.replenishment.v1 — TransferApproved·Changed·Cancelled. 바인딩 replenishmentEvents-in-0. */
  @Bean
  PmartEventConsumer replenishmentEvents(EventConsumers consumers, InboundPromises promises) {
    return consumers.of(promises::onTransferEvent);
  }

  /** cdc.inventory.v1 — TransferDeparted(TOUT). 바인딩 cdcInventoryEvents-in-0. */
  @Bean
  PmartEventConsumer cdcInventoryEvents(EventConsumers consumers, InboundPromises promises) {
    return consumers.of(promises::onTransferEvent);
  }

  /**
   * (2026-11-27) tms.trip.v1 — transport-execution StopArrived → 입고 예정 자동 도착. 바인딩
   * tmsTripEvents-in-0.
   */
  @Bean
  PmartEventConsumer tmsTripEvents(EventConsumers consumers, InboundPromises promises) {
    return consumers.of(promises::onTripEvent);
  }

  /** cdc.outbound.v1 — TransferShipmentManifestIssued. 바인딩 cdcOutboundEvents-in-0. */
  @Bean
  PmartEventConsumer cdcOutboundEvents(EventConsumers consumers, InboundPromises promises) {
    return consumers.of(promises::onTransferEvent);
  }
}
