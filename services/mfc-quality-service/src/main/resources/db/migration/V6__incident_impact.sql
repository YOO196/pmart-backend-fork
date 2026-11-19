-- 품질 사고 고객 영향(MB-2 2차 R6, 2026-11-19): 영향 LOT 이 라이더에게 인계된(고객에게 나간) 주문. mfc-inventory 조회로 다시 세고, 바뀌면 사건.
ALTER TABLE quality_incident
    ADD COLUMN affected_orders      jsonb,          -- [{orderId, lines:[{orderLineId, sku, lotId, qty}]}]
    ADD COLUMN affected_order_count integer,        -- 아직 안 셌으면 null
    ADD COLUMN affected_checked_at  timestamptz,    -- null = 곧 센다(새 사고·LOT 추가)
    ADD COLUMN impact_version       integer NOT NULL DEFAULT 0;
CREATE INDEX ix_quality_incident_impact ON quality_incident (affected_checked_at) WHERE state <> 'CLOSED';
