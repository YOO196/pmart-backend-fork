-- 0.9.0(2026-11-17, 백오피스 처리 대기 BO-12 — 요청 F-4): 멈춘 중지·거절된 인계 목록과 운영자 재시도 기록.

-- 집품 뒤 중지가 실패로 멈춘 시각(last_error 를 남길 때). 이 전에 실패한 것은 null — 목록은 decided_at 으로 대신 정렬한다.
ALTER TABLE order_work_stop ADD COLUMN failed_at timestamptz;
CREATE INDEX ix_order_work_stop_failed ON order_work_stop (COALESCE(failed_at, decided_at), stop_request_id)
    WHERE state = 'PENDING' AND last_error IS NOT NULL;

-- 재고가 거절한 인계(REJECTED 일 때 updated_at = 거절 시각).
CREATE INDEX ix_package_handover_rejected ON package_handover (updated_at, handover_id) WHERE state = 'REJECTED';

-- 운영자 재시도 기록 — 실제로 다시 시도했을 때만(중지: PENDING + 실패 사유, 인계: REJECTED). 지우지 않는다.
CREATE TABLE manual_retry (
    retry_id      bigserial    NOT NULL,
    target_type   varchar(16)  NOT NULL,   -- STOP_REQUEST / HANDOVER
    target_id     varchar(128) NOT NULL,
    operator_id   varchar(64),
    reason        varchar(500),
    source_system varchar(64)  NOT NULL,
    retried_at    timestamptz  NOT NULL,
    CONSTRAINT pk_manual_retry PRIMARY KEY (retry_id),
    CONSTRAINT ck_manual_retry_target CHECK (target_type IN ('STOP_REQUEST', 'HANDOVER'))
);
CREATE INDEX ix_manual_retry_target ON manual_retry (target_type, target_id, retried_at DESC);
