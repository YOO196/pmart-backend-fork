package com.pmart.mfc.quality.infrastructure;

import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import com.pmart.mfc.quality.application.IncidentService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * mfc-fulfillment 사건(mfc.fulfillment.v1, 바인딩 fulfillmentEvents-in-0) — LineShortageReported DAMAGED
 * → 품질 사고(0.6.0).
 */
@Configuration(proxyBeanMethods = false)
class FulfillmentEventConsumers {

  @Bean
  PmartEventConsumer fulfillmentEvents(EventConsumers consumers, IncidentService incidents) {
    return consumers.of(incidents::onFulfillmentEvent);
  }
}
