package com.pmart.common.messaging;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * 도메인 이벤트 봉투 (contracts/events/_envelope.v1.json 과 1:1).
 *
 * <p>{@code (sourceSystem, eventId)} 가 멱등 키. 같은 aggregate 의 역순 수신은 {@code aggregateVersion} 으로 판정한다
 * — 단 버전은 발행 서비스마다 따로 세므로 비교는 {@code (sourceSystem, aggregateId)} 안에서만 한다(예: Delivery 는
 * delivery-dispatch·delivery-execution 이 각자 센다, events.md §7). payload 는 이벤트별 JSON
 * Schema(contracts/events/{eventType}.v{schemaVersion}.json)가 정의한다.
 */
public record EventEnvelope(
    UUID eventId,
    String eventType,
    int schemaVersion,
    String sourceSystem,
    String aggregateType,
    String aggregateId,
    long aggregateVersion,
    Instant occurredAt,
    String timezone,
    String traceId,
    @Nullable String correlationId,
    @Nullable String causationId,
    @Nullable Actor actor,
    EventSource source,
    JsonNode payload) {

  public static final String DEFAULT_TIMEZONE = "Asia/Seoul";

  /** 행위 주체. 개인정보가 아닌 식별자만. */
  public record Actor(ActorType type, String id) {}

  public enum ActorType {
    SYSTEM,
    OPERATOR,
    CUSTOMER,
    RIDER,
    DRIVER,
    WORKER
  }

  /** 실물 센서·차량·설비 없이 모의 입력한 사실이면 SIMULATED. */
  public enum EventSource {
    REAL,
    SIMULATED
  }

  public EventEnvelope {
    Objects.requireNonNull(eventId, "eventId");
    requireText(eventType, "eventType");
    requireText(sourceSystem, "sourceSystem");
    requireText(aggregateType, "aggregateType");
    requireText(aggregateId, "aggregateId");
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(payload, "payload");
    if (schemaVersion < 1) {
      throw new IllegalArgumentException("schemaVersion must be >= 1");
    }
    if (aggregateVersion < 0) {
      throw new IllegalArgumentException("aggregateVersion must be >= 0");
    }
    if (!eventType.matches("^[A-Z][A-Za-z0-9]+$")) {
      throw new IllegalArgumentException("eventType must be PascalCase: " + eventType);
    }
    timezone = (timezone == null || timezone.isBlank()) ? DEFAULT_TIMEZONE : timezone;
    traceId = (traceId == null || traceId.isBlank()) ? "" : traceId;
    source = source == null ? EventSource.REAL : source;
  }

  /** 토픽 {domain}.{aggregate}.v{n}. domain 은 소유 서비스의 도메인(order, mfc, cdc …). */
  public String topic(String domain) {
    return Topics.of(domain, aggregateType, schemaVersion);
  }

  private static void requireText(@Nullable String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
  }
}
