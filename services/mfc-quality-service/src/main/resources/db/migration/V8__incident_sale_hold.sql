-- 품질 사고 LOT 판매 보류(계약 0.10.0, 사용자 결정 2026-11-20 — mfc-inventory 0.39.0 lot-sale-holds).
-- NULL = 보류 안 함(자동 사고·saleHold false·이전 사고). HOLD_PENDING → HELD → RELEASE_PENDING → RELEASED. 주기 작업이 재고 서비스에 맞춘다.
ALTER TABLE quality_incident_lot ADD COLUMN sale_hold varchar(16);
ALTER TABLE quality_incident_lot ADD CONSTRAINT ck_quality_incident_lot_sale_hold
    CHECK (sale_hold IS NULL OR sale_hold IN ('HOLD_PENDING', 'HELD', 'RELEASE_PENDING', 'RELEASED'));
CREATE INDEX ix_quality_incident_lot_sale_hold ON quality_incident_lot (incident_id)
    WHERE sale_hold IN ('HOLD_PENDING', 'HELD', 'RELEASE_PENDING');
