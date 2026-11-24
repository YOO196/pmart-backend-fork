package com.pmart.mfc.inbound.application;

import com.pmart.common.messaging.EventEnvelope;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 인계 실적 일별 합(요청 MI-4 — 발주 수량 추천의 임시 예측 입력, 판매 = 인계 실적). mfc-inventory {@code
 * PackageHandedOverPosted} (mfc.inventory.v1, inbox 트랜잭션 안)를 센터 × SKU × 영업일(Asia/Seoul, 전기 시각
 * postedAt — 없으면 사건 시각)로 더한다. 같은 commitId 는 한 번만(사건이 다시 와도). demand-planning 이 생기면 그 예측으로 바꾸고 이 투영은
 * 없앤다.
 */
@Service
public class HandoverSales {

  static final String TYPE = "PackageHandedOverPosted";
  static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
  private static final Logger log = LoggerFactory.getLogger(HandoverSales.class);

  private final JdbcTemplate jdbc;
  private final Clock clock;

  public HandoverSales(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  public void onInventoryEvent(EventEnvelope e) {
    if (!TYPE.equals(e.eventType())) {
      return;
    }
    JsonNode p = e.payload();
    String commitId = text(p, "commitId");
    String mfcId = text(p, "mfcId");
    if (commitId == null || mfcId == null) {
      log.warn("인계 전기 사건에 commitId·mfcId 가 없음 — 실적에서 뺌: {}", e.eventId());
      return;
    }
    String posted = text(p, "postedAt");
    Instant at = posted == null ? e.occurredAt() : Instant.parse(posted);
    LocalDate day = at.atZone(SEOUL).toLocalDate();
    Instant now = clock.instant();
    int fresh =
        jdbc.update(
            "INSERT INTO handover_sale_seen (commit_id, mfc_id, business_date, created_at)"
                + " VALUES (?,?,?,?) ON CONFLICT (commit_id) DO NOTHING",
            commitId,
            mfcId,
            Date.valueOf(day),
            Timestamp.from(now));
    if (fresh == 0) {
      return;
    }
    for (JsonNode l : p.path("lines")) {
      String sku = text(l, "sku");
      long qty = l.path("qty").asLong(0);
      if (sku == null || qty <= 0) {
        continue;
      }
      jdbc.update(
          "INSERT INTO handover_sale_daily (mfc_id, sku, business_date, qty, updated_at)"
              + " VALUES (?,?,?,?,?) ON CONFLICT (mfc_id, sku, business_date)"
              + " DO UPDATE SET qty = handover_sale_daily.qty + EXCLUDED.qty, updated_at ="
              + " EXCLUDED.updated_at",
          mfcId,
          sku,
          Date.valueOf(day),
          qty,
          Timestamp.from(now));
    }
  }

  private static @Nullable String text(JsonNode n, String f) {
    JsonNode v = n.path(f);
    return v.isMissingNode() || v.isNull() || v.asString().isBlank() ? null : v.asString();
  }
}
