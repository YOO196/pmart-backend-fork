package com.pmart.mfc.fulfillment.infrastructure;

import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import com.pmart.mfc.fulfillment.application.OrderNumbers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 주문 사건(order.order.v1) 중 OrderConfirmed 만 — 화면용 주문번호 사본(F-5, 2026-11-17). 바인딩 {@code
 * orderEvents-in-0}.
 */
@Configuration(proxyBeanMethods = false)
class OrderEventConsumers {

  @Bean
  PmartEventConsumer orderEvents(EventConsumers consumers, OrderNumbers numbers) {
    return consumers.of(e -> "OrderConfirmed".equals(e.eventType()), numbers::onOrderConfirmed);
  }
}
