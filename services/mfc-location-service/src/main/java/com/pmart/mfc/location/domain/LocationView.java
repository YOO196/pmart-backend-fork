package com.pmart.mfc.location.domain;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** 위치 한 건(저장 사본·멱등 결과·이벤트 내용). */
public record LocationView(
    String locationId,
    String mfcId,
    String locationCode,
    @Nullable String areaCode,
    String zone,
    String temperatureZone,
    String state,
    @Nullable String stateReason,
    @Nullable Integer widthMm,
    @Nullable Integer depthMm,
    @Nullable Integer heightMm,
    @Nullable Integer slotCount,
    @Nullable Integer maxWeightG,
    @Nullable Integer pickSequence,
    boolean highTurnover,
    boolean reserveStock,
    @Nullable String categoryId,
    @Nullable Double posXM,
    @Nullable Double posZM,
    int version,
    Instant updatedAt) {}
