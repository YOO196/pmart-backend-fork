-- 입고 종결(2026-11-26, 계약 0.22.0 — 요청 MI-5): TRF 입고 예정이 "더 받을 것 없음"이 된 시각·종류. TransferReceivingClosed 를 한 번 낸다.
-- 자동(ALL_RECEIVED — 전량 수령·품질검사 끝) 또는 센터 관리자(SHORT_CLOSED·NOT_RECEIVED_CLOSED). 받은 것 없이 닫으면 state CLOSED.
ALTER TABLE inbound_promise ADD COLUMN closed_at timestamptz;
ALTER TABLE inbound_promise ADD COLUMN close_kind varchar(24);
ALTER TABLE inbound_promise ADD COLUMN closed_by varchar(128);
ALTER TABLE inbound_promise ADD COLUMN close_reason varchar(500);
ALTER TABLE inbound_promise DROP CONSTRAINT ck_inbound_promise_state;
ALTER TABLE inbound_promise ADD CONSTRAINT ck_inbound_promise_state
    CHECK (state IN ('EXPECTED', 'RECEIVING', 'TIN_POSTED', 'CANCELLED', 'CLOSED'));
ALTER TABLE inbound_promise ADD CONSTRAINT ck_inbound_promise_close_kind
    CHECK (close_kind IS NULL OR close_kind IN ('ALL_RECEIVED', 'SHORT_CLOSED', 'NOT_RECEIVED_CLOSED'));
