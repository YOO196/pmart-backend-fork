package com.pmart.mfc.inventory.domain;

import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * 실사 발견품 조사 결론(0.43.0). 결론은 기록만 — 장부는 결론에 적은 기존 경로(정정·이동·입고/반품 복구)로만 바뀐다.
 *
 * <ul>
 *   <li>EXISTING_LOT: 기존 LOT 의 물건(위치·LOT 오기) — 재고 정정·이동 참조(outcomeRef)와 그 LOT(lotId) 필수. 총량을 늘리는
 *       조정으로 풀지 않는다(IV-11).
 *   <li>SOURCE_RECOVERED: 빠졌던 원사건(입고·반품 등록 누락)을 복구함 — 복구한 원사건 참조 필수.
 *   <li>DISPOSED: 원인 불명 — 팔지 않고 폐기, 장부는 늘리지 않음(LOT 근거 없는 식품은 정상 운영에 넣지 않는다, IV-09). 참조 선택.
 * </ul>
 */
public final class FoundItemRules {

  private FoundItemRules() {}

  public static final String EXISTING_LOT = "EXISTING_LOT";
  public static final String SOURCE_RECOVERED = "SOURCE_RECOVERED";
  public static final String DISPOSED = "DISPOSED";
  public static final Set<String> OUTCOMES = Set.of(EXISTING_LOT, SOURCE_RECOVERED, DISPOSED);

  /** 결론 본문 검사 — 문제가 있으면 사유 문장, 없으면 null. */
  public static @Nullable String rejection(
      String outcome, @Nullable String outcomeRef, @Nullable String lotId, @Nullable String note) {
    if (!OUTCOMES.contains(outcome)) {
      return "outcome 은 EXISTING_LOT·SOURCE_RECOVERED·DISPOSED 입니다.";
    }
    if (note == null || note.isBlank()) {
      return "note(확인한 내용)가 필요합니다.";
    }
    boolean hasRef = outcomeRef != null && !outcomeRef.isBlank();
    if (!DISPOSED.equals(outcome) && !hasRef) {
      return outcome + " 는 처리한 정정·이동·원사건 참조(outcomeRef)가 필요합니다.";
    }
    if (EXISTING_LOT.equals(outcome) && (lotId == null || lotId.isBlank())) {
      return "EXISTING_LOT 은 확인한 lotId 가 필요합니다.";
    }
    if (!EXISTING_LOT.equals(outcome) && lotId != null) {
      return "lotId 는 EXISTING_LOT 일 때만 줍니다.";
    }
    return null;
  }
}
