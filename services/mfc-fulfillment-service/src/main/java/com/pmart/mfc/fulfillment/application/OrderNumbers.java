package com.pmart.mfc.fulfillment.application;

import com.pmart.common.messaging.EventEnvelope;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 화면용 주문번호(요청 F-5, 2026-11-17 — 클라이언트 all "배달앱의 주문 번호가 고객앱에도 똑같이"). order {@code
 * OrderConfirmed.orderNumber} (숫자 8자리) 사본 {@code order_display_number}. 이 필드 전 주문은 사본이 없다 — 화면은
 * orderId 를 쓴다. 번호는 주문마다 한 번 정해져 바뀌지 않아 늦게 온 사건도 같은 값(덮어써도 같다).
 */
@Service
public class OrderNumbers {

  private final NamedParameterJdbcTemplate jdbc;
  private final Clock clock;

  public OrderNumbers(NamedParameterJdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  public void onOrderConfirmed(EventEnvelope e) {
    String orderId = e.payload().path("orderId").asText(null);
    String number = e.payload().path("orderNumber").asText(null);
    if (orderId == null || number == null || number.isBlank()) {
      return; // 번호 전 주문
    }
    jdbc.update(
        "INSERT INTO order_display_number (order_id, order_number, updated_at)"
            + " VALUES (:o, :n, :at) ON CONFLICT (order_id) DO UPDATE SET order_number ="
            + " EXCLUDED.order_number, updated_at = EXCLUDED.updated_at",
        Map.of("o", orderId, "n", number, "at", Timestamp.from(clock.instant())));
  }

  public @Nullable String of(String orderId) {
    return of(List.of(orderId)).get(orderId);
  }

  public Map<String, String> of(Collection<String> orderIds) {
    Map<String, String> out = new HashMap<>();
    if (orderIds.isEmpty()) {
      return out;
    }
    jdbc.query(
        "SELECT order_id, order_number FROM order_display_number WHERE order_id IN (:ids)",
        Map.of("ids", orderIds),
        rs -> {
          out.put(rs.getString(1), rs.getString(2));
        });
    return out;
  }
}
