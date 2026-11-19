-- 현장 예외 보고(PDA BR-52 R19-7, 2026-11-20): 이동·보충·적치 중 "출발 칸에 없음·모자람·파손·변질·칸 못 씀"을 기록만 한다.
-- 사유 값은 PICK 결품 보고(mfc-fulfillment shortage.reason)와 같다. 재고·작업 상태는 바꾸지 않는다(남은 양 처리 규칙은 아직 정하지 않음).
CREATE TABLE task_exception (
    exception_id   varchar(48)  NOT NULL,
    request_key    varchar(200) NOT NULL,             -- 출처 + Idempotency-Key + 대상 — 같은 키는 같은 기록
    mfc_id         varchar(32)  NOT NULL,
    target_type    varchar(12)  NOT NULL,             -- MOVE / REPLENISH / PUTAWAY
    target_id      varchar(64)  NOT NULL,             -- moveId 또는 입고 작업 taskId
    sku            varchar(10),
    lot_id         varchar(40),
    reason         varchar(12)  NOT NULL,
    qty            integer,
    location_code  varchar(64),
    note           varchar(200),
    evidence_ref   varchar(64),
    reported_by    varchar(128) NOT NULL,
    reported_at    timestamptz  NOT NULL,
    CONSTRAINT pk_task_exception PRIMARY KEY (exception_id),
    CONSTRAINT uq_task_exception_request UNIQUE (request_key),
    CONSTRAINT ck_task_exception_target CHECK (target_type IN ('MOVE', 'REPLENISH', 'PUTAWAY')),
    CONSTRAINT ck_task_exception_reason CHECK (reason IN ('SHORT', 'DAMAGED', 'SPOILED', 'BLOCKED')),
    CONSTRAINT ck_task_exception_qty CHECK (qty IS NULL OR qty > 0)
);
CREATE INDEX ix_task_exception_mfc ON task_exception (mfc_id, reported_at DESC, exception_id DESC);
CREATE INDEX ix_task_exception_target ON task_exception (target_id);

-- 회수품 검수 대기 큐(R12): 센터·검수 상태·반입 오래된 순
CREATE INDEX ix_return_receipt_inspection ON return_receipt (mfc_id, inspection_state, received_at, return_receipt_id);
