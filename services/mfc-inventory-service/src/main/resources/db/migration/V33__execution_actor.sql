-- 재고 이력 주문·처리한 사람(0.49.0) — 작업 실행의 예약(PICK·PACK)과 작업자를 남긴다. 예전 행은 NULL(소급 없음).
ALTER TABLE stock_execution ADD COLUMN reservation_id varchar(64);
ALTER TABLE stock_execution ADD COLUMN performed_by varchar(128);
CREATE INDEX ix_stock_execution_commit ON stock_execution (commit_id);
