-- 실사 발견품 조사(2026-11-20, 계약 0.43.0 — BR-52 R19-6 남은 것, 사용자 "그대로 진행해")
-- 실사 승인 때 목록 밖에서 찾았지만 SKU·LOT 을 정하지 못한 줄(count_session.*_unresolved)을 조사 건으로 넘긴다.
-- 장부(stock_unit)는 늘리지 않는다 — MFC IV-09 "로트 불명 발견 = 판매 차단·조사", IV-11 "위치/로트 오기는 총량을 늘리는 ADJ 로
-- 해결하지 않음, 원인 불명은 조사 대기". 실제 처리(정정·이동·원사건 복구)는 기존 경로, 여기는 결론과 그 참조만.
CREATE TABLE count_found_item (
    found_id        varchar(64)  NOT NULL,
    mfc_id          varchar(64)  NOT NULL,
    session_id      varchar(64)  NOT NULL,  -- 처음 넘긴 실사
    line_no         integer      NOT NULL,  -- 그 실사의 정하지 못한 줄 순번(0부터) — 승인 재시도에 두 번 만들지 않게
    last_session_id varchar(64)  NOT NULL,  -- 같은 물건을 다시 본 마지막 실사
    seen_count      integer      NOT NULL DEFAULT 1,
    location_code   varchar(64)  NOT NULL,
    sku             varchar(32),
    barcode         varchar(64),
    expiry_date     date,
    qty             integer      NOT NULL,
    cause           varchar(16)  NOT NULL,  -- SKU_UNKNOWN·LOT_UNKNOWN·LOT_AMBIGUOUS
    state           varchar(16)  NOT NULL DEFAULT 'OPEN',
    outcome         varchar(24),
    outcome_ref     varchar(128),
    lot_id          varchar(64),
    note            varchar(500),
    resolved_by     varchar(64),
    resolved_at     timestamptz,
    created_at      timestamptz  NOT NULL,
    updated_at      timestamptz  NOT NULL,
    version         integer      NOT NULL DEFAULT 0,
    CONSTRAINT pk_count_found_item PRIMARY KEY (found_id),
    CONSTRAINT uq_count_found_item_line UNIQUE (session_id, line_no),
    CONSTRAINT ck_count_found_item_qty CHECK (qty > 0),
    CONSTRAINT ck_count_found_item_state CHECK (state IN ('OPEN', 'RESOLVED')),
    CONSTRAINT ck_count_found_item_outcome CHECK (
        (state = 'OPEN' AND outcome IS NULL)
        OR (state = 'RESOLVED' AND outcome IN ('EXISTING_LOT', 'SOURCE_RECOVERED', 'DISPOSED')))
);

CREATE INDEX ix_count_found_item_center ON count_found_item (mfc_id, state, found_id);
