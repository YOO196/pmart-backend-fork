-- 반납 도착 대기(2026-11-19, 요청 MI-1·MQ-1): 오배송 회수·전달 실패 반납은 claim 이 없다. 목적·오배송 사건·원래 배송을 이어 싣는다.
ALTER TABLE return_receipt_projection ALTER COLUMN claim_id DROP NOT NULL;
ALTER TABLE return_receipt_projection
    ADD COLUMN purpose            varchar(32) NOT NULL DEFAULT 'CLAIM_RETURN',
    ADD COLUMN case_id            varchar(64),
    ADD COLUMN origin_delivery_id varchar(64);
ALTER TABLE return_inspection ALTER COLUMN claim_id DROP NOT NULL;
ALTER TABLE return_inspection
    ADD COLUMN purpose            varchar(32) NOT NULL DEFAULT 'CLAIM_RETURN',
    ADD COLUMN case_id            varchar(64),
    ADD COLUMN origin_delivery_id varchar(64);
