-- 사용자 결정 2026-11-20(계약 0.17.0): 격리품은 격리 구역으로(요청 IV-6 — 검수자·반입자·보고자에게 미리 배정), 이동·보충·적치 현장 예외는 PICK 결품처럼
-- 재고에 반영(분실 조사 보류·격리 — 작업 잔량에서 뺌), 해제된 격리품은 적치 작업. 적용된 파일은 수정하지 않는다.
ALTER TABLE inbound_task ALTER COLUMN task_type TYPE varchar(16);
ALTER TABLE inbound_task DROP CONSTRAINT ck_inbound_task_type;
ALTER TABLE inbound_task ADD CONSTRAINT ck_inbound_task_type
    CHECK (task_type IN ('INSPECT', 'PUTAWAY', 'QUARANTINE_MOVE'));
-- 현장 예외·품질 해제/폐기로 더는 할 필요가 없어진 양. 잔량 = planned − done − excepted
ALTER TABLE inbound_task ADD COLUMN excepted_qty integer NOT NULL DEFAULT 0;
ALTER TABLE inbound_task DROP CONSTRAINT ck_inbound_task_qty;
ALTER TABLE inbound_task ADD CONSTRAINT ck_inbound_task_qty
    CHECK (planned_qty > 0 AND done_qty >= 0 AND excepted_qty >= 0 AND done_qty + excepted_qty <= planned_qty);
-- 만든 까닭(INSPECTION·RETURN·EXCEPTION·RELEASE, 기존 작업 NULL)과 사건에서 만든 작업의 한 번 열쇠
ALTER TABLE inbound_task ADD COLUMN origin varchar(12);
ALTER TABLE inbound_task ADD COLUMN origin_ref varchar(200);
CREATE UNIQUE INDEX uq_inbound_task_origin_ref ON inbound_task (origin_ref) WHERE origin_ref IS NOT NULL;
CREATE INDEX ix_inbound_task_lot_open ON inbound_task (mfc_id, lot_id, location_code) WHERE state = 'READY';

-- 현장 예외의 재고 반영(0.15.0 은 기록만 — 그 행은 RECORDED 로 남는다)
ALTER TABLE task_exception ADD COLUMN apply_status varchar(16) NOT NULL DEFAULT 'RECORDED';
ALTER TABLE task_exception ADD COLUMN inventory_commit_id varchar(64);
ALTER TABLE task_exception ADD COLUMN reject_reason varchar(500);
ALTER TABLE task_exception ADD COLUMN quarantine_task_id varchar(64);
ALTER TABLE task_exception ADD COLUMN applied_qty integer;
ALTER TABLE task_exception ADD CONSTRAINT ck_task_exception_apply
    CHECK (apply_status IN ('RECORDED', 'PENDING', 'APPLIED', 'REJECTED', 'UNKNOWN_RESULT'));
