-- 출하 약속 준수율 지표(0.35.0) — 배차 요청 예상 준비 시각으로 기간 찾기
CREATE INDEX ix_shipment_dispatch_estimated ON shipment (dispatch_estimated_ready_at)
    WHERE dispatch_estimated_ready_at IS NOT NULL;
