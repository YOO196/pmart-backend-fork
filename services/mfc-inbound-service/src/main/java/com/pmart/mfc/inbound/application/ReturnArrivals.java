package com.pmart.mfc.inbound.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 반납 도착 대기(2026-11-19, 요청 MI-1·MI-2·MQ-1). delivery `ReturnDelivered`(delivery.delivery.v1) → 센터별
 * 대기 건 → 점원이 고르면 {@link ReturnReceiptService} 가 반입 본문을 채우고 대기에서 뺀다(재고가 거절한 반입이면 다시 받을 수 있다).
 *
 * <p>목적 CLAIM_RETURN(고객 반품) · MISDELIVERY_RECOVERY(오배송 회수, caseId) · FAILED_DELIVERY_RETURN(전달 실패
 * 반납) — 뒤의 둘은 claim 이 없어 자동 환불이 없고, 검수 규칙은 같다(신선·냉장·냉동 폐기, 상온 미개봉만 재고 복구 — quality).
 */
@Service
public class ReturnArrivals {

  public static final String ALREADY_RECEIVED = "ARRIVAL_ALREADY_RECEIVED";
  public static final String NOT_FOUND = "RETURN_ARRIVAL_NOT_FOUND";
  private static final Logger log = LoggerFactory.getLogger(ReturnArrivals.class);
  private static final TypeReference<List<ArrivalLine>> LINES = new TypeReference<>() {};
  private static final List<String> PURPOSES =
      List.of("CLAIM_RETURN", "MISDELIVERY_RECOVERY", "FAILED_DELIVERY_RETURN");

  public record ArrivalLine(@Nullable String orderLineId, @Nullable String sku, int qty) {}

  public record Arrival(
      String arrivalId,
      String mfcId,
      String purpose,
      @Nullable String claimId,
      @Nullable String caseId,
      @Nullable String originDeliveryId,
      @Nullable String orderId,
      @Nullable String riderId,
      List<ArrivalLine> lines,
      Instant arrivedAt,
      @Nullable String returnReceiptId,
      boolean waiting) {}

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final Clock clock;

  public ReturnArrivals(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.clock = clock;
  }

  /** delivery.delivery.v1(inbox 트랜잭션 안) — ReturnDelivered 만, 같은 회수 배송은 한 번. */
  public void onDeliveryEvent(EventEnvelope e) {
    if (!"ReturnDelivered".equals(e.eventType())) {
      return;
    }
    JsonNode p = e.payload();
    String id = text(p, "returnPickupId");
    if (id == null) {
      id = text(p, "deliveryId");
    }
    String mfc = text(p, "mfcId");
    if (id == null || mfc == null) {
      log.warn("반납 사건 {} — 회수 ID·센터가 없어 대기에 올리지 않음", e.eventId());
      return;
    }
    String purpose = text(p, "purpose");
    if (purpose == null) {
      purpose = "CLAIM_RETURN";
    }
    if (!PURPOSES.contains(purpose)) {
      log.warn("반납 {} 모르는 목적 {} — 대기에 올리지 않음", id, purpose);
      return;
    }
    List<ArrivalLine> lines = new ArrayList<>();
    for (JsonNode l : p.path("lines")) {
      int qty = l.path("qty").asInt();
      if (qty > 0) {
        lines.add(new ArrivalLine(text(l, "orderLineId"), text(l, "sku"), qty));
      }
    }
    String at = text(p, "deliveredAt");
    Instant now = clock.instant();
    int n =
        jdbc.update(
            "INSERT INTO return_arrival (arrival_id, mfc_id, purpose, claim_id, case_id, origin_delivery_id,"
                + " order_id, rider_id, lines, arrived_at, created_at) VALUES (?,?,?,?,?,?,?,?,?::jsonb,?,?)"
                + " ON CONFLICT (arrival_id) DO NOTHING",
            id,
            mfc,
            purpose,
            text(p, "claimId"),
            text(p, "caseId"),
            text(p, "originDeliveryId"),
            text(p, "orderId"),
            text(p, "riderId") != null
                ? text(p, "riderId")
                : e.actor() != null && e.actor().type() == EventEnvelope.ActorType.RIDER
                    ? e.actor().id()
                    : null,
            mapper.writeValueAsString(lines),
            Timestamp.from(at == null ? now : Instant.parse(at)),
            Timestamp.from(now));
    if (n == 1) {
      log.info("반납 도착 {} — 센터 {} {} {}줄", id, mfc, purpose, lines.size());
    }
  }

  /** 목록 — 그 센터 운영자. state WAITING(기본)·RECEIVED·ALL, 오래된 순. */
  public List<Arrival> list(String mfcId, @Nullable String state, int size) {
    InboundTaskService.requireOperator(mfcId);
    String st = state == null ? "WAITING" : state;
    String waiting = "(a.return_receipt_id IS NULL OR r.state = 'REJECTED')";
    String where =
        switch (st) {
          case "WAITING" -> " AND " + waiting;
          case "RECEIVED" -> " AND NOT " + waiting;
          case "ALL" -> "";
          default ->
              throw new PmartException(
                  ErrorCode.VALIDATION_FAILED, "state 는 WAITING·RECEIVED·ALL 입니다.");
        };
    return jdbc.query(
        "SELECT a.*, "
            + waiting
            + " AS waiting FROM return_arrival a"
            + " LEFT JOIN return_receipt r ON r.return_receipt_id = a.return_receipt_id"
            + " WHERE a.mfc_id = ?"
            + where
            + " ORDER BY a.arrived_at, a.arrival_id LIMIT ?",
        (rs, i) -> row(rs),
        mfcId,
        Math.max(1, Math.min(size, 200)));
  }

  /** 반입이 가져간다(반입 기록 트랜잭션 안) — 그 센터의 대기 건을 잠그고, 이미 반입된(재고 거절이 아닌) 건이면 409. 연결은 {@link #link}. */
  Arrival lockWaiting(String mfcId, String arrivalId) {
    Arrival a =
        jdbc
            .query(
                "SELECT a.*, (a.return_receipt_id IS NULL OR r.state = 'REJECTED') AS waiting"
                    + " FROM return_arrival a LEFT JOIN return_receipt r ON r.return_receipt_id = a.return_receipt_id"
                    + " WHERE a.arrival_id = ? FOR UPDATE OF a",
                (rs, i) -> row(rs),
                arrivalId)
            .stream()
            .findFirst()
            .orElse(null);
    if (a == null || !a.mfcId().equals(mfcId)) {
      throw PmartException.notFound(NOT_FOUND, "이 센터의 반납 도착 건이 없습니다: " + arrivalId);
    }
    if (!a.waiting()) {
      throw new PmartException(
          ALREADY_RECEIVED,
          HttpStatus.CONFLICT,
          "이미 반입한 반납입니다(반입 " + a.returnReceiptId() + ").",
          false,
          List.of());
    }
    return a;
  }

  void link(String arrivalId, String returnReceiptId) {
    jdbc.update(
        "UPDATE return_arrival SET return_receipt_id = ? WHERE arrival_id = ?",
        returnReceiptId,
        arrivalId);
  }

  /**
   * 반입 줄에 주문 줄 번호 채우기(MQ-1) — orderLineId 가 없는 줄은 같은 상품의 대기 줄에서 차례로(수량이 대기 줄보다 많으면 나눈다, 남는 수량은 번호
   * 없이). 이미 번호가 있는 줄은 그대로, 그 줄 수량만큼 대기 줄에서 먼저 뺀다.
   */
  static List<ReturnReceiptService.Line> withOrderLines(
      List<ReturnReceiptService.Line> lines, List<ArrivalLine> arrived) {
    Map<Integer, Integer> left = new LinkedHashMap<>();
    for (int k = 0; k < arrived.size(); k++) {
      left.put(k, arrived.get(k).qty());
    }
    for (ReturnReceiptService.Line l : lines) {
      if (l.orderLineId() == null) {
        continue;
      }
      for (int k = 0; k < arrived.size(); k++) {
        if (l.orderLineId().equals(arrived.get(k).orderLineId())) {
          left.merge(k, -l.qty(), Integer::sum);
        }
      }
    }
    List<ReturnReceiptService.Line> out = new ArrayList<>();
    for (ReturnReceiptService.Line l : lines) {
      if (l.orderLineId() != null) {
        out.add(l);
        continue;
      }
      int need = l.qty();
      for (int k = 0; k < arrived.size() && need > 0; k++) {
        ArrivalLine a = arrived.get(k);
        int have = left.get(k);
        if (a.orderLineId() == null || have <= 0 || !Objects.equals(a.sku(), l.sku())) {
          continue;
        }
        int take = Math.min(have, need);
        left.put(k, have - take);
        need -= take;
        out.add(l.with(a.orderLineId(), take));
      }
      if (need > 0) {
        out.add(l.with(null, need));
      }
    }
    return out;
  }

  /** 줄 생략 — 대기 건 줄 그대로. 상품 코드가 없는 줄이 있으면 400. */
  static List<ReturnReceiptService.Line> linesOf(Arrival a) {
    List<ReturnReceiptService.Line> out = new ArrayList<>();
    for (ArrivalLine l : a.lines()) {
      if (l.sku() == null) {
        throw new PmartException(
            ErrorCode.VALIDATION_FAILED, "반납 사건에 상품 코드가 없는 줄이 있습니다 — 반입 줄(sku·qty)을 보내 주세요.");
      }
      out.add(
          new ReturnReceiptService.Line(
              l.orderLineId(), l.sku(), l.qty(), null, null, null, null, null));
    }
    if (out.isEmpty()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "반납 사건에 줄이 없습니다 — 반입 줄을 보내 주세요.");
    }
    return out;
  }

  private Arrival row(ResultSet rs) throws SQLException {
    return new Arrival(
        rs.getString("arrival_id"),
        rs.getString("mfc_id"),
        rs.getString("purpose"),
        rs.getString("claim_id"),
        rs.getString("case_id"),
        rs.getString("origin_delivery_id"),
        rs.getString("order_id"),
        rs.getString("rider_id"),
        mapper.readValue(rs.getString("lines"), LINES),
        rs.getTimestamp("arrived_at").toInstant(),
        rs.getString("return_receipt_id"),
        rs.getBoolean("waiting"));
  }

  private static @Nullable String text(JsonNode n, String f) {
    JsonNode v = n.path(f);
    return v.isMissingNode() || v.isNull() || v.asString().isBlank() ? null : v.asString();
  }
}
