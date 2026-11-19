-- 결품률 지표(F-10, 2026-11-19 — 백오피스 대시보드): 결품 보고(LineShortageReported)마다 한 줄. 실적(task_execution)에 없는
-- 예약 보류분 결품(BLOCKED)까지 줄 단위·사유별로 세려고 둔다. 개인정보 없음.
CREATE TABLE line_shortage (
    shortage_id    bigint       GENERATED ALWAYS AS IDENTITY,
    task_id        varchar(96)  NOT NULL,
    order_line_id  varchar(80)  NOT NULL,
    mfc_id         varchar(32)  NOT NULL,
    reason         varchar(16)  NOT NULL,
    qty            integer      NOT NULL,
    reported_at    timestamptz  NOT NULL,
    CONSTRAINT pk_line_shortage PRIMARY KEY (shortage_id)
);
CREATE INDEX ix_line_shortage_line ON line_shortage (task_id, order_line_id);

-- 지금까지의 실적 결품(SHORT·DAMAGED)을 옮긴다 — 예전 BLOCKED(예약 보류분)는 기록이 없어 빠진다
INSERT INTO line_shortage (task_id, order_line_id, mfc_id, reason, qty, reported_at)
SELECT e.task_id, l->>'orderLineId', w.mfc_id, l->'shortage'->>'reason',
       COALESCE((l->'shortage'->>'qty')::integer, 0), e.recorded_at
FROM task_execution e
JOIN task t ON t.task_id = e.task_id
JOIN shipment s ON s.shipment_id = t.shipment_id
JOIN work_order w ON w.fulfillment_id = s.fulfillment_id,
     jsonb_array_elements(e.lines) l
WHERE e.execution_type = 'PICK' AND e.state <> 'REJECTED'
  AND l ? 'shortage' AND l->'shortage'->>'reason' IS NOT NULL AND l->>'orderLineId' IS NOT NULL;
