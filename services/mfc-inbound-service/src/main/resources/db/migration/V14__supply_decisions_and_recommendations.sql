-- 센터 발주 승인·발주 수량 추천(요청 MI-4, 2026-11-24 — 사용자 결정 "B": SCM 전 임시로 센터 안에서). 적용된 파일은 수정하지 않는다.

-- ① 공급 요청 승인·거절(센터 MFC_SUPERVISOR). 승인 = 줄마다 승인 수량(부분 승인) + 입고 예정(initial_stock_load EXPECTED, approval_id = supply_request_id).
ALTER TABLE supply_request DROP CONSTRAINT ck_supply_request_state;
ALTER TABLE supply_request ADD CONSTRAINT ck_supply_request_state
    CHECK (state IN ('DRAFT', 'SUBMITTED', 'ACKNOWLEDGED', 'WITHDRAWN', 'APPROVED', 'REJECTED'));
ALTER TABLE supply_request
    ADD COLUMN approved_lines    jsonb,          -- [{sku, requestedQty, approvedQty}]
    ADD COLUMN decided_by        varchar(64),
    ADD COLUMN decided_at        timestamptz,
    ADD COLUMN decision_note     varchar(500),
    ADD COLUMN reject_reason     varchar(500),
    ADD COLUMN linked_receipt_id varchar(64);   -- 승인이 만든 initial_stock_load.receipt_id

-- ② 인계 실적 일별 합(센터 × SKU × 영업일 Asia/Seoul) — mfc-inventory PackageHandedOverPosted 투영. 판매 = 인계 실적(임시 예측의 입력).
CREATE TABLE handover_sale_daily (
    mfc_id        varchar(32) NOT NULL,
    sku           varchar(10) NOT NULL,
    business_date date        NOT NULL,
    qty           bigint      NOT NULL,
    updated_at    timestamptz NOT NULL,
    CONSTRAINT pk_handover_sale_daily PRIMARY KEY (mfc_id, sku, business_date),
    CONSTRAINT ck_handover_sale_daily_qty CHECK (qty >= 0)
);
CREATE INDEX ix_handover_sale_daily_mfc_date ON handover_sale_daily (mfc_id, business_date);

-- 같은 인계 전기(commitId)는 한 번만 센다(사건 재발행·재처리에도).
CREATE TABLE handover_sale_seen (
    commit_id     varchar(128) NOT NULL,
    mfc_id        varchar(32)  NOT NULL,
    business_date date         NOT NULL,
    created_at    timestamptz  NOT NULL,
    CONSTRAINT pk_handover_sale_seen PRIMARY KEY (commit_id)
);

-- ③ 공급 정책(원문 1장 6-3 — R 검토 주기·L 리드타임·SS 안전재고 일수·발주 배수·최소 발주량). 센터 줄이 전 센터 기본('*')보다 우선.
-- 빈 값(NULL)은 "정하지 않음" — 그 SKU 는 추천하지 않는다(0·무제한으로 채우지 않는다).
CREATE TABLE supply_policy (
    mfc_id              varchar(32)   NOT NULL,   -- '*' = 전 센터 기본
    storage_type        varchar(16)   NOT NULL,   -- AMBIENT / CHILLED / FROZEN
    review_days         numeric(6, 2),
    lead_days           numeric(6, 2),
    safety_days         numeric(6, 2),
    order_multiple      integer,
    min_order_qty       integer,
    average_window_days integer       NOT NULL DEFAULT 28,
    min_history_days    integer       NOT NULL DEFAULT 7,
    note                varchar(200),
    updated_at          timestamptz   NOT NULL,
    CONSTRAINT pk_supply_policy PRIMARY KEY (mfc_id, storage_type),
    CONSTRAINT ck_supply_policy_storage CHECK (storage_type IN ('AMBIENT', 'CHILLED', 'FROZEN')),
    CONSTRAINT ck_supply_policy_values CHECK (
        (review_days IS NULL OR review_days >= 0) AND (lead_days IS NULL OR lead_days >= 0)
        AND (safety_days IS NULL OR safety_days >= 0) AND (order_multiple IS NULL OR order_multiple >= 1)
        AND (min_order_qty IS NULL OR min_order_qty >= 1) AND average_window_days >= 1 AND min_history_days >= 1
        AND min_history_days <= average_window_days)
);

-- 시작값(사용자 확인 2026-11-24 — 데모 #411 기준 제안, 승인 전 시험값이지 운영값이 아니다). 박스 상품은 박스 SKU(unitType B)라 SKU 1개 = 1박스 → 배수 1.
INSERT INTO supply_policy (mfc_id, storage_type, review_days, lead_days, safety_days, order_multiple, min_order_qty,
                           average_window_days, min_history_days, note, updated_at)
VALUES ('*', 'AMBIENT', 1, 1, 1, 1, 1, 28, 7, '시작값 2026-11-24(MI-4, 사용자 확인) — 승인 전', now()),
       ('*', 'CHILLED', 1, 1, 1, 1, 1, 28, 7, '시작값 2026-11-24(MI-4, 사용자 확인) — 승인 전', now()),
       ('*', 'FROZEN',  1, 1, 1, 1, 1, 28, 7, '시작값 2026-11-24(MI-4, 사용자 확인) — 승인 전', now());
