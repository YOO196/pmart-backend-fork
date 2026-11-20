-- 품질 사고 LOT 판매 보류(IV-09 "범위 불명 보류", 사용자 결정 2026-11-20 — 0.39.0).
-- 사고가 열리면 그 센터의 그 LOT 정상 재고(선반 STORED·적치 전 UNSTOWED)를 모두 stock_hold(reason QUALITY)로 막는다 — 격리(Q)가 아니라
-- 판매 가능(E)에서만 빼고 집품·이동·적치를 막는다. 보류가 걸린 동안 새로 들어온 같은 LOT 도 주기 작업이 막는다.
-- 같은 LOT 에 여러 사고가 걸리면 마지막 사고가 풀 때 풀린다.
CREATE TABLE lot_sale_hold (
    mfc_id       varchar(32)  NOT NULL,
    lot_id       varchar(40)  NOT NULL,
    source_ref   varchar(128) NOT NULL,    -- 품질 사고 ID
    sku          varchar(10)  NOT NULL,
    state        varchar(10)  NOT NULL,    -- ACTIVE / RELEASED
    created_at   timestamptz  NOT NULL,
    released_at  timestamptz,
    CONSTRAINT pk_lot_sale_hold PRIMARY KEY (mfc_id, lot_id, source_ref),
    CONSTRAINT ck_lot_sale_hold_state CHECK (state IN ('ACTIVE', 'RELEASED'))
);
CREATE INDEX ix_lot_sale_hold_active ON lot_sale_hold (mfc_id, lot_id) WHERE state = 'ACTIVE';
