package com.pmart.mfc.inbound.application;

import com.pmart.mfc.inbound.infrastructure.CdcTransferIntakeClient;
import com.pmart.mfc.inbound.infrastructure.SupplyRecommendationStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 자동 보충(ADR-0091, 2026-11-26 사용자 "자동보충 만들어" → "추천 자동 발주 + 승인").
 *
 * <ol>
 *   <li>자동 발주 초안 {@link #draftAll}: 매일(기본 06:00 Asia/Seoul) 인계 실적이 있는 센터마다 최근 28일에 판 SKU 의 발주
 *       추천(S·IP·Q — {@link SupplyRecommendations}, 사람이 보는 추천과 같은 계산)에서 추천 수량이 있는 줄로 공급 요청을
 *       SUBMITTED 로 낸다(제출자 SYSTEM, ref {@code AUTO-<센터>-<날짜>} 하루 한 번). 이미 미해결 요청에 있는 SKU 는 뺀다.
 *   <li>사람 승인은 그대로 — 그 센터 MFC_SUPERVISOR 가 승인함에서 승인·줄 고침·거절({@link SupplyRequestService#approve}).
 *   <li>승인되면 보급 DC 가 정해진 경우 이관(TRF) 요청 — {@link #sendDue} 가 cdc-outbound 에 보낸다(트랜잭션 밖, 실패하면 1분부터 두
 *       배·최대 30분, 내용 거절이면 멈추고 사유를 남김). DC 가 할당·피킹·출발하면 TransferDeparted 로 입고 예정에 출발 줄이 붙는다.
 * </ol>
 */
@Service
public class AutoSupply {

  static final int HISTORY_WINDOW_DAYS = 28;
  static final int MAX_SKUS = SupplyRecommendations.MAX_SKUS;
  static final Duration FIRST_RETRY = Duration.ofMinutes(1);
  static final Duration MAX_RETRY = Duration.ofMinutes(30);
  static final String REASON = "자동 발주 초안(판매 예상 기반 발주 추천)";

  private static final Logger log = LoggerFactory.getLogger(AutoSupply.class);

  private final SupplyRecommendationStore store;
  private final SupplyRecommendations recommendations;
  private final SupplyRequestService requests;
  private final CdcTransferIntakeClient cdc;
  private final InboundProperties props;
  private final Clock clock;
  private final com.pmart.mfc.inbound.infrastructure.InboundPromiseStore promiseStore;

  public AutoSupply(
      SupplyRecommendationStore store,
      SupplyRecommendations recommendations,
      SupplyRequestService requests,
      CdcTransferIntakeClient cdc,
      InboundProperties props,
      Clock clock,
      com.pmart.mfc.inbound.infrastructure.InboundPromiseStore promiseStore) {
    this.promiseStore = promiseStore;
    this.store = store;
    this.recommendations = recommendations;
    this.requests = requests;
    this.cdc = cdc;
    this.props = props;
    this.clock = clock;
  }

  /** 하루 한 번 — 꺼져 있으면 아무것도 하지 않는다. */
  @Scheduled(cron = "${pmart.inbound.auto-supply-cron:0 0 6 * * *}", zone = "Asia/Seoul")
  public void scheduled() {
    if (!props.autoSupplyEnabled()) {
      return;
    }
    draftAll();
  }

  /** 모든 센터 — 만든 요청 ID 목록. */
  public List<String> draftAll() {
    List<String> made = new ArrayList<>();
    for (String mfcId : store.salesCenters()) {
      try {
        String id = draft(mfcId);
        if (id != null) {
          made.add(id);
        }
      } catch (RuntimeException e) {
        log.warn("자동 발주 초안 {} 실패 — 다음 주기에: {}", mfcId, e.toString());
      }
    }
    return made;
  }

  /** 한 센터 — 만든 요청 ID 또는 null(추천 없음·이미 오늘 만듦). */
  public @Nullable String draft(String mfcId) {
    Instant now = clock.instant();
    LocalDate today = now.atZone(HandoverSales.SEOUL).toLocalDate();
    LocalDate through = today.minusDays(1);
    List<String> skus =
        new ArrayList<>(
            store.salesAll(mfcId, through.minusDays(HISTORY_WINDOW_DAYS - 1L), through).keySet());
    skus.sort(null);
    Map<String, Integer> lines = new LinkedHashMap<>();
    for (int i = 0; i < skus.size(); i += MAX_SKUS) {
      var view =
          recommendations.compute(
              mfcId, null, skus.subList(i, Math.min(skus.size(), i + MAX_SKUS)));
      for (var item : view.items()) {
        Integer q = item.result().recommendedQty();
        if (q != null && q > 0) {
          lines.put(item.sku(), q);
        }
      }
    }
    if (lines.isEmpty()) {
      log.info("자동 발주 초안 {} — 추천 수량 없음({} SKU 계산)", mfcId, skus.size());
      return null;
    }
    List<SupplyRequestService.Line> body = new ArrayList<>();
    lines.forEach((sku, q) -> body.add(new SupplyRequestService.Line(sku, q)));
    String ref = "AUTO-" + mfcId + "-" + today.format(DateTimeFormatter.BASIC_ISO_DATE);
    return requests.createSystem(mfcId, body, now.plus(props.supplyDueAfter()), ref, REASON);
  }

  /** 승인된 센터 발주의 이관(TRF) 요청을 보급 DC 에 보낸다 — 못 보낸 것 다시. */
  @Scheduled(
      fixedDelayString = "${pmart.inbound.supply-transfer-interval:PT15S}",
      initialDelayString = "${pmart.inbound.supply-transfer-initial-delay:PT20S}")
  public void sendDue() {
    for (String trfId : requests.dueTransfers(clock.instant(), 20)) {
      sendTransfer(trfId);
    }
  }

  /** 공급 요청 하나의 아직 안 보낸 TRF 전부(승인 직후). */
  public void send(String supplyRequestId) {
    requests
        .findRow(supplyRequestId)
        .ifPresent(
            r ->
                r.transfers().stream()
                    .filter(t -> t.sentAt() == null)
                    .forEach(t -> sendTransfer(t.trfId())));
  }

  void sendTransfer(String trfId) {
    var found = requests.findTransfer(trfId).orElse(null);
    if (found == null || found.getValue().sentAt() != null) {
      return;
    }
    String supplyRequestId = found.getKey();
    SupplyRequestService.Transfer t = found.getValue();
    SupplyRequestService.Row r = requests.findRow(supplyRequestId).orElse(null);
    if (r == null) {
      return;
    }
    var res =
        cdc.send(
            new CdcTransferIntakeClient.Transfer(
                t.trfId(),
                t.cdcId(),
                r.mfcId(),
                supplyRequestId,
                t.dueAt(),
                t.temperatureZone(),
                t.lines()));
    Instant now = clock.instant();
    switch (res) {
      case CdcTransferIntakeClient.Result.Accepted a -> {
        requests.transferSent(trfId, now);
        // (0.25.0, R-52) TRF 화면 번호 사본 — 입고 예정에(공급 요청 transfers[] 도 거기서 읽는다)
        cdc.numbers(trfId)
            .ifPresent(
                n -> promiseStore.setNumbers(trfId, n.trfNumber(), n.waveId(), n.waveNumber()));
        log.info(
            "공급 요청 {} → {} 이관 {}({}) 보냄", supplyRequestId, t.cdcId(), trfId, t.temperatureZone());
      }
      case CdcTransferIntakeClient.Result.Rejected no -> {
        requests.transferFailed(trfId, no.reason(), null);
        log.warn("공급 요청 {} 이관 {} 거절(다시 보내지 않음): {}", supplyRequestId, trfId, no.reason());
      }
      case CdcTransferIntakeClient.Result.Retry x -> {
        requests.transferFailed(trfId, x.error(), now.plus(TransitClosures.backoff(t.attempts())));
        log.warn("공급 요청 {} 이관 {} 보내기 실패 — 다시: {}", supplyRequestId, trfId, x.error());
      }
    }
  }
}
