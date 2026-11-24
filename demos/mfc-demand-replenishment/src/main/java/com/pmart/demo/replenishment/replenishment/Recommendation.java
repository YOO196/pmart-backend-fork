package com.pmart.demo.replenishment.replenishment;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 보충 추천 결과(명세 4장 "추천 결과"). 입력이 모자라 못 구한 값은 null(0 으로 채우지 않는다).
 *
 * @param onHand H(고객 예약 빼기 전 적격 현물)
 * @param expiringQty 보호기간 안에 적격을 잃는 H 안의 양(IP 에서 뺌)
 * @param commitments C
 * @param incoming I
 * @param pendingReady P(보호기간 끝까지 준비)
 * @param newDemand F(예측 소비 뒤)
 * @param target S
 * @param inventoryPosition IP = H − 적격상실 − C + I + P (음수 보존)
 * @param shortage N
 * @param multiple m
 * @param wanted W
 * @param bottleneck B
 * @param capByMultiple K
 * @param quantity Q
 */
public record Recommendation(
    LocalDateTime asOf,
    String mfcId,
    String skuId,
    LocalDateTime proposedReadyAt,
    LocalDateTime protectionEnd,
    String protectionMethod,
    String granularity,
    String policyVersion,
    String targetMethod,
    String forecastModel,
    long onHand,
    long expiringQty,
    long commitments,
    long incoming,
    long pendingReady,
    Double newDemand,
    Double target,
    double inventoryPosition,
    Double shortage,
    Integer multiple,
    Long wanted,
    Long bottleneck,
    Long capByMultiple,
    Long quantity,
    Double unmetQty,
    Double excessQty,
    LocalDateTime firstShortageAt,
    double maxShortage,
    LocalDateTime recoveryAt,
    double shortageBeforeProposedReady,
    double residualShortageAfterReplenishment,
    String sharedCapGroup,
    CalcStatus status,
    List<String> reasons,
    String dataMode) {

  public String reason() {
    return String.join("; ", reasons);
  }

  /** 사유 하나를 더한 사본(여러 SKU 공유 상한 검사 등). */
  public Recommendation withReason(String extra) {
    List<String> more = new java.util.ArrayList<>(reasons);
    more.add(extra);
    return new Recommendation(
        asOf, mfcId, skuId, proposedReadyAt, protectionEnd, protectionMethod, granularity, policyVersion,
        targetMethod, forecastModel, onHand, expiringQty, commitments, incoming, pendingReady, newDemand, target,
        inventoryPosition, shortage, multiple, wanted, bottleneck, capByMultiple, quantity, unmetQty, excessQty,
        firstShortageAt, maxShortage, recoveryAt, shortageBeforeProposedReady, residualShortageAfterReplenishment,
        sharedCapGroup, status, List.copyOf(more), dataMode);
  }
}
