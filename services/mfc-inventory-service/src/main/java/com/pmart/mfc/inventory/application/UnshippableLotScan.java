package com.pmart.mfc.inventory.application;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 출하 부적격 LOT 정기 알림(2026-11-08 남은 일 16 — 지금까지는 요청할 때만 계산). 주기({@code
 * pmart.inventory.unshippable-scan-interval}, 기본 1시간)마다 센터별로 {@link MoveService#unshippableAt} 를 돌려
 * <b>카테고리 칸</b>에 남은 부적격 LOT 을 {@code unshippable_lot_alert} OPEN 으로 남기고(처음 본 시각 유지), 기타 칸으로 회수됐거나
 * 없어진 것은 CLEARED. 기타 칸(세일·기타 품목, 사용자 결정)에 있는 부적격 LOT 은 알림 대상이 아니다.
 *
 * <p>알리는 곳: 지표 {@code pmart.inventory.unshippable.lots}·{@code
 * pmart.inventory.unshippable.oldest.age}(센터별) + 새로 생기면 WARN 로그. Alertmanager 규칙은 넣지 않았다 — 받는 곳이
 * Slack 이라 사용자 요청 없이 발송하지 않는다.
 */
@Component
public class UnshippableLotScan {

  private static final Logger log = LoggerFactory.getLogger(UnshippableLotScan.class);

  public record Result(String mfcId, int open, int added, int cleared) {}

  private final MoveService moves;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final @Nullable MultiGauge openGauge;
  private final @Nullable MultiGauge ageGauge;

  public UnshippableLotScan(
      MoveService moves,
      JdbcTemplate jdbc,
      PlatformTransactionManager txm,
      Clock clock,
      ObjectProvider<MeterRegistry> meters) {
    this.moves = moves;
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(txm);
    this.clock = clock;
    MeterRegistry m = meters.getIfAvailable();
    this.openGauge =
        m == null
            ? null
            : MultiGauge.builder("pmart.inventory.unshippable.lots")
                .description("카테고리 칸에 남은 출하 부적격 LOT 칸 수(회수 필요)")
                .register(m);
    this.ageGauge =
        m == null
            ? null
            : MultiGauge.builder("pmart.inventory.unshippable.oldest.age")
                .description("가장 오래 안 거둔 출하 부적격 LOT 이 처음 보인 뒤 지난 시간")
                .baseUnit("seconds")
                .register(m);
  }

  @Scheduled(
      fixedDelayString = "${pmart.inventory.unshippable-scan-interval:PT1H}",
      initialDelayString = "${pmart.inventory.unshippable-scan-initial-delay:PT1M}")
  void scheduled() {
    try {
      scanAll();
    } catch (RuntimeException e) {
      log.warn("출하 부적격 LOT 점검 실패(다음 주기에 다시): {}", e.toString());
    }
  }

  public List<Result> scanAll() {
    List<String> centers =
        jdbc.queryForList(
            "SELECT mfc_id FROM stock_unit WHERE qty > 0 UNION"
                + " SELECT mfc_id FROM unshippable_lot_alert WHERE state = 'OPEN'",
            String.class);
    List<Result> out = new ArrayList<>();
    for (String mfcId : centers) {
      try {
        out.add(scan(mfcId));
      } catch (RuntimeException e) {
        log.warn("출하 부적격 LOT 점검 실패 {}: {}", mfcId, e.toString());
      }
    }
    refreshGauges();
    return out;
  }

  public Result scan(String mfcId) {
    Result r =
        tx.execute(
            s -> {
              Timestamp at = Timestamp.from(clock.instant());
              int added = 0;
              for (MoveService.Unshippable u : moves.unshippableAt(mfcId)) {
                if (u.locationCategoryId() == null && !u.onReserve()) {
                  continue; // 기타 칸 = 회수된 곳(세일·기타 품목). 보충재고 칸(R-30)은 팔 물건 자리라 알린다
                }
                int updated =
                    jdbc.update(
                        "UPDATE unshippable_lot_alert SET qty = ?, last_seen_at = ?, category_id = ?"
                            + " WHERE mfc_id = ? AND sku = ? AND lot_id = ? AND location_code = ?"
                            + " AND state = 'OPEN'",
                        u.qty(),
                        at,
                        u.locationCategoryId(),
                        mfcId,
                        u.sku(),
                        u.lotId(),
                        u.locationCode());
                if (updated == 0) {
                  jdbc.update(
                      "INSERT INTO unshippable_lot_alert (mfc_id, sku, lot_id, location_code,"
                          + " category_id, expiry_date, qty, state, first_seen_at, last_seen_at)"
                          + " VALUES (?,?,?,?,?,?,?,'OPEN',?,?)",
                      mfcId,
                      u.sku(),
                      u.lotId(),
                      u.locationCode(),
                      u.locationCategoryId(),
                      u.expiryDate() == null ? null : java.sql.Date.valueOf(u.expiryDate()),
                      u.qty(),
                      at,
                      at);
                  added++;
                }
              }
              int cleared =
                  jdbc.update(
                      "UPDATE unshippable_lot_alert SET state = 'CLEARED', cleared_at = ?"
                          + " WHERE mfc_id = ? AND state = 'OPEN' AND last_seen_at < ?",
                      at,
                      mfcId,
                      at);
              Integer open =
                  jdbc.queryForObject(
                      "SELECT COUNT(*) FROM unshippable_lot_alert WHERE mfc_id = ? AND state = 'OPEN'",
                      Integer.class,
                      mfcId);
              return new Result(mfcId, open == null ? 0 : open, added, cleared);
            });
    if (r == null) {
      throw new IllegalStateException("scan returned nothing");
    }
    if (r.added() > 0) {
      log.warn(
          "출하 부적격 LOT {}건 새로 — 카테고리 칸에서 거둬야 함({}, 남은 것 {}건, GET /mfcs/{}/unshippable-lots)",
          r.added(),
          mfcId,
          r.open(),
          mfcId);
    }
    return r;
  }

  private void refreshGauges() {
    if (openGauge == null || ageGauge == null) {
      return;
    }
    Instant now = clock.instant();
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT mfc_id, COUNT(*) AS n, MIN(first_seen_at) AS oldest FROM unshippable_lot_alert"
                + " WHERE state = 'OPEN' GROUP BY mfc_id");
    List<MultiGauge.Row<?>> open = new ArrayList<>();
    List<MultiGauge.Row<?>> age = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      Tags t = Tags.of("mfcId", (String) row.get("mfc_id"));
      open.add(MultiGauge.Row.of(t, ((Number) row.get("n")).doubleValue()));
      Instant oldest = ((Timestamp) row.get("oldest")).toInstant();
      age.add(MultiGauge.Row.of(t, (double) Duration.between(oldest, now).toSeconds()));
    }
    openGauge.register(open, true);
    ageGauge.register(age, true);
  }
}
