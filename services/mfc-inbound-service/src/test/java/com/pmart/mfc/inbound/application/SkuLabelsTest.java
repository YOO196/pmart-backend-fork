package com.pmart.mfc.inbound.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 회수품 줄 신선 여부(0.17.0, 사용자 결정 2026-11-20): 온도대 CHILLED·FROZEN = 신선. */
class SkuLabelsTest {

  @Test
  void freshMeansChilledOrFrozen() {
    assertThat(SkuLabels.fresh("CHILLED")).isTrue();
    assertThat(SkuLabels.fresh("FROZEN")).isTrue();
    assertThat(SkuLabels.fresh("AMBIENT")).isFalse();
    assertThat(SkuLabels.fresh(null)).isNull();
  }
}
