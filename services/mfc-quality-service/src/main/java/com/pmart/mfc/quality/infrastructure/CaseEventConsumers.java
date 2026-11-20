package com.pmart.mfc.quality.infrastructure;

import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import com.pmart.mfc.quality.application.IncidentService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * case 사건(platform.case.v1, 바인딩 caseEvents-in-0) — CaseOpened MFC_QUALITY_IMPACT → 품질 사고
 * caseId(MQ-2, 2026-11-20).
 */
@Configuration(proxyBeanMethods = false)
class CaseEventConsumers {

  @Bean
  PmartEventConsumer caseEvents(EventConsumers consumers, IncidentService incidents) {
    return consumers.of(incidents::onCaseEvent);
  }
}
