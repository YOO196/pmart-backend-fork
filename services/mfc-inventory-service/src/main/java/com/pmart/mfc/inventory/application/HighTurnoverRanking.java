package com.pmart.mfc.inventory.application;

import com.pmart.mfc.inventory.infrastructure.HighTurnoverSkuStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 고회전 상품 순위(2026-11-23, MFC 시뮬레이터 R-40 — 사용자 결정): 센터마다 <b>최근 7일 주문 줄 수</b> 상위 <b>20</b> 상온 SKU 를
 * <b>하루 한 번</b> 다시 계산한다. 업무 문서 2-1 "F 상온 고회전 — 카테고리 무관 인기 SKU". 적치·복귀 칸 추천이 이 상품에 F 구역 고회전 칸을 먼저
 * 내놓는다({@link MoveService}). 이미 다른 칸에 있는 재고는 옮기지 않는다("다음 적치부터만").
 *
 * <p>값(7일·20·하루)은 사용자 결정이라 코드 상수다. 주기만 {@code pmart.inventory.high-turnover-rank-interval}(기본
 * PT24H)로 바꿀 수 있다(시험·시뮬레이터 환경).
 */
@Component
public class HighTurnoverRanking {

  private static final Logger log = LoggerFactory.getLogger(HighTurnoverRanking.class);

  /** 상위 몇 개(사용자 "top20"). */
  public static final int TOP_N = 20;

  /** 집계 기간(사용자 "최근 7일 주문 줄 수"). */
  public static final Duration WINDOW = Duration.ofDays(7);

  /** 고회전 구역 F 는 상온만(업무 문서 2-1). */
  public static final String STORAGE_TYPE = "AMBIENT";

  public record Result(String mfcId, List<String> skus) {}

  private final HighTurnoverSkuStore store;
  private final TransactionTemplate tx;
  private final Clock clock;

  public HighTurnoverRanking(
      HighTurnoverSkuStore store, PlatformTransactionManager txm, Clock clock) {
    this.store = store;
    this.tx = new TransactionTemplate(txm);
    this.clock = clock;
  }

  @Scheduled(
      fixedDelayString = "${pmart.inventory.high-turnover-rank-interval:PT24H}",
      initialDelayString = "${pmart.inventory.high-turnover-rank-initial-delay:PT2M}")
  void scheduled() {
    try {
      rankAll();
    } catch (RuntimeException e) {
      log.warn("고회전 상품 순위 계산 실패(다음 주기에 다시): {}", e.toString());
    }
  }

  /** 모든 센터 다시 계산. */
  public List<Result> rankAll() {
    List<Result> out = new ArrayList<>();
    for (String mfcId : store.centers()) {
      out.add(rank(mfcId));
    }
    return out;
  }

  /** 한 센터 다시 계산(그 센터 순위를 통째로 바꾼다). */
  public Result rank(String mfcId) {
    Instant now = clock.instant();
    Instant since = now.minus(WINDOW);
    List<HighTurnoverSkuStore.Ranked> ranked =
        tx.execute(
            s -> {
              var r = store.rank(mfcId, STORAGE_TYPE, since, TOP_N);
              store.replace(mfcId, r, since, now);
              return r;
            });
    List<String> skus =
        ranked == null ? List.of() : ranked.stream().map(HighTurnoverSkuStore.Ranked::sku).toList();
    log.info("고회전 상품 순위 {}: {}개 {}", mfcId, skus.size(), skus);
    return new Result(mfcId, skus);
  }
}
