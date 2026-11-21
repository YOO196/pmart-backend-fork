package com.pmart.mfc.inventory.domain;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * 보충재고 칸(R-30, 2026-11-21)의 상품 하나 용량 = 칸 부피 × 채움률 중 <b>다른 상품이 차지한 부피</b>를 뺀 나머지 ÷ 이 상품 부피(하중도 같게).
 * 순수 계산.
 *
 * <p>일반 선반은 상품마다 따로 센다({@link UnitSize#capacity}) — 보충재고 팔레트에는 여러 상품이 한꺼번에 쌓이므로 상품마다 따로 세면 각각은 들어가도
 * 합치면 넘친다(R-30 검토 ③). 크기를 모르는 다른 상품은 합계에서 뺀다 — 적치는 치수 미등록으로 입고를 멈추지 않는다는 기존 원칙(무게를 모르면 하중은 그 상품만큼 덜
 * 센다).
 */
public final class ReserveCapacity {

  private ReserveCapacity() {}

  /** 칸에 있거나 들어올 다른 상품 하나(크기 모르면 null). */
  public record Occupant(@Nullable UnitSize size, int qty) {}

  /**
   * @return 이 상품이 이 칸에 들어갈 수 있는 총량(이 상품의 지금·예정 양 포함 기준), 칸 치수가 없으면 null
   */
  public static @Nullable Integer capacity(
      UnitSize size,
      @Nullable Integer w,
      @Nullable Integer d,
      @Nullable Integer h,
      @Nullable Integer maxWeightG,
      List<Occupant> others) {
    if (w == null || d == null || h == null || w <= 0 || d <= 0 || h <= 0) {
      return null;
    }
    if (size.checkFit() && !size.fitsIn(w, d, h)) {
      return 0;
    }
    double usable = (double) w * d * h * SkuSize.FILL_RATE;
    double used = 0;
    long usedWeight = 0;
    for (Occupant o : others) {
      if (o.size() == null || o.qty() <= 0) {
        continue;
      }
      used += (double) o.size().widthMm() * o.size().depthMm() * o.size().heightMm() * o.qty();
      if (o.size().weightG() != null && o.size().weightG() > 0) {
        usedWeight += (long) o.size().weightG() * o.qty();
      }
    }
    double unit = (double) size.widthMm() * size.depthMm() * size.heightMm();
    int byVolume = (int) Math.max(0, Math.floor((usable - used) / unit));
    if (maxWeightG != null && maxWeightG > 0 && size.weightG() != null && size.weightG() > 0) {
      int byWeight = (int) Math.max(0, (maxWeightG - usedWeight) / size.weightG());
      return Math.min(byVolume, byWeight);
    }
    return byVolume;
  }
}
