-- 곧 품절 기준(0.47.0, 2026-11-25 — 통합 백오피스 LS-1~3, 사용자 결정 "하루 판매량 기준").
-- 정본은 mfc-inbound(인계 실적 이동평균 d·공급 정책 표) — PUT /internal/mfcs/{mfcId}/low-stock-basis 로 센터마다 통째로 바꿔 넣는다.
CREATE TABLE low_stock_basis (
    mfc_id          varchar(32)  NOT NULL,
    calculated_at   timestamptz  NOT NULL,
    sales_through   date         NOT NULL,
    received_at     timestamptz  NOT NULL,
    CONSTRAINT pk_low_stock_basis PRIMARY KEY (mfc_id)
);

-- 온도대별 적용 정책(센터 줄 우선, 없으면 전 센터 기본 — inbound 가 골라서 보냄). 줄 없음 = 정책 없음.
CREATE TABLE low_stock_policy (
    mfc_id              varchar(32)   NOT NULL,
    storage_type        varchar(16)   NOT NULL,
    safety_days         numeric(6, 2),
    history_days        integer       NOT NULL,
    min_history_days    integer       NOT NULL,
    average_window_days integer       NOT NULL,
    CONSTRAINT pk_low_stock_policy PRIMARY KEY (mfc_id, storage_type)
);

-- 하루 판매량 d(판 SKU 만 — 없으면 0).
CREATE TABLE low_stock_daily (
    mfc_id      varchar(32)    NOT NULL,
    sku         varchar(10)    NOT NULL,
    daily_qty   numeric(14, 4) NOT NULL,
    CONSTRAINT pk_low_stock_daily PRIMARY KEY (mfc_id, sku)
);
