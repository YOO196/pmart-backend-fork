-- 이동·보충·적치 현장 예외(사용자 결정 2026-11-20 "PICK 결품처럼", 계약 0.35.0): 못 찾음(SHORT·BLOCKED) = 분실 조사 보류,
-- 이상품(DAMAGED·SPOILED) = 격리. 이동 예외는 아직 집지 않은 몫(이동 수량·MOVE 보류를 줄임), 예약 없는 재고 예외는 위치·LOT·업무 상태의 NORMAL.
-- 같은 report_id 는 한 번(멱등·감사). 적용된 파일은 수정하지 않는다.
CREATE TABLE stock_exception_report (
    report_id       varchar(128) NOT NULL,
    content_hash    varchar(64)  NOT NULL,
    mfc_id          varchar(32)  NOT NULL,
    target_type     varchar(8)   NOT NULL,             -- MOVE / STOCK
    move_id         varchar(64),
    sku             varchar(10)  NOT NULL,
    lot_id          varchar(40)  NOT NULL,
    location_code   varchar(64)  NOT NULL,
    work_state      varchar(24)  NOT NULL,
    reason          varchar(16)  NOT NULL,
    qty             integer      NOT NULL,
    quarantined_qty integer      NOT NULL,
    held_qty        integer      NOT NULL,
    blocked_qty     integer      NOT NULL,
    source_ref      varchar(128),
    commit_id       varchar(64)  NOT NULL,
    created_at      timestamptz  NOT NULL,
    CONSTRAINT pk_stock_exception_report PRIMARY KEY (report_id),
    CONSTRAINT ck_stock_exception_target CHECK (target_type IN ('MOVE', 'STOCK')),
    CONSTRAINT ck_stock_exception_reason CHECK (reason IN ('SHORT', 'DAMAGED', 'SPOILED', 'BLOCKED')),
    CONSTRAINT ck_stock_exception_qty CHECK (qty > 0)
);
CREATE INDEX ix_stock_exception_move ON stock_exception_report (move_id) WHERE move_id IS NOT NULL;
