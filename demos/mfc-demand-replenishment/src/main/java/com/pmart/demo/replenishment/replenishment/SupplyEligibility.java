package com.pmart.demo.replenishment.replenishment;

import com.pmart.demo.replenishment.replenishment.Model.Supply;
import com.pmart.demo.replenishment.replenishment.Model.SupplyStage;
import com.pmart.demo.replenishment.replenishment.Model.SupplyStatus;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 기존 공급 I 판정(SCM 7.1, 명세 2장 표). 같은 supplyId 의 여러 줄(승인 24·확보 24·출발 24)은 가장 진행된 단계 한 줄만 본다.
 * 보호기간 끝 시각에 준비되는 공급은 포함한다(≤ protectionEnd).
 */
public final class SupplyEligibility {

  /** 공급 한 건의 판정. counted 면 I 에 넣는다. */
  public record Decision(String supplyId, SupplyStage stage, long qty, boolean counted, String reason) {}

  /** I 합계, 판정 목록, 진행 중인 요청(중복 요청 방지용 진행 의도) 여부. */
  public record Result(long incoming, List<Decision> decisions, boolean openIntent, List<Supply> counted) {}

  private SupplyEligibility() {}

  public static Result evaluate(List<Supply> supplies, LocalDateTime protectionEnd, boolean acceptSecured) {
    Map<String, Supply> lineage = new LinkedHashMap<>();
    for (Supply s : supplies) {
      if (s.remainingQty() < 0) {
        throw new IllegalArgumentException("공급 잔량은 음수일 수 없음: " + s.supplyId());
      }
      lineage.merge(s.supplyId(), s, (a, b) -> b.stage().ordinal() > a.stage().ordinal() ? b : a);
    }
    long incoming = 0;
    boolean intent = false;
    List<Decision> decisions = new ArrayList<>();
    List<Supply> counted = new ArrayList<>();
    for (Supply s : lineage.values()) {
      String reason;
      boolean count = false;
      if (s.status() == SupplyStatus.CANCELLED) {
        reason = "취소";
      } else {
        switch (s.stage()) {
          case RECOMMENDED, REQUESTED -> {
            reason = "미승인 — 진행 의도만 표시";
            intent = true;
          }
          case APPROVED -> {
            reason = "승인 미이행(미확보·운송 미확인)";
            intent = true;
          }
          case SECURED, DEPARTED -> {
            if (s.status() == SupplyStatus.ETA_OR_QUALITY_UNKNOWN) {
              reason = "ETA·품질 불명 — 이 기간 제외(손실 처리 안 함)";
            } else if (s.readyEta() == null) {
              reason = "준비시각 근거 없음";
            } else if (s.readyEta().isAfter(protectionEnd)) {
              reason = "보호기간 밖 준비 — 제외";
            } else if (s.stage() == SupplyStage.SECURED && !acceptSecured) {
              reason = "확보 단계 인정 정책 꺼짐";
            } else {
              reason = s.stage() == SupplyStage.DEPARTED ? "출발분 미수령 잔량" : "확보·운송 가능 미수령량";
              count = true;
            }
          }
          case RECEIVED_READY -> reason = "수령·준비 완료 — H 에 포함, I 아님";
          case RECEIVED_PENDING -> reason = "수령·준비 대기 — I 아님, 확인된 범위만 P";
          default -> throw new IllegalStateException(s.stage().name());
        }
      }
      if (count) {
        incoming += s.remainingQty();
        counted.add(s);
      }
      decisions.add(new Decision(s.supplyId(), s.stage(), s.remainingQty(), count, reason));
    }
    return new Result(incoming, decisions, intent, counted);
  }
}
