-- 실사 계획·서버 잠금(2026-11-20, 클라이언트 BR-52 R14 — 사용자 결정 "관리자가 계획·서버 잠금"), 목록 밖 상품(R19-6), 실사 예외(R19-7).
-- 계획 = 센터 관리자가 고른 칸 묶음. 칸마다 실사(count_session, plan_id) 하나를 만들고, 그 실사가 끝나지 않은 동안 이 서비스가 그 칸을 잠근다
-- (이동·적치·피킹 거절). 실사가 끝나거나(MATCHED·ADJUSTED·REJECTED) 취소(CANCELLED)되면 잠금은 저절로 풀린다 — 잠금 = 열린 계획 실사.
CREATE TABLE count_plan (
    plan_id        varchar(64)  NOT NULL,
    mfc_id         varchar(32)  NOT NULL,
    reason         varchar(200),
    created_by     varchar(64)  NOT NULL,
    created_at     timestamptz  NOT NULL,
    cancelled_by   varchar(64),
    cancelled_at   timestamptz,
    cancel_reason  varchar(200),
    CONSTRAINT pk_count_plan PRIMARY KEY (plan_id)
);
CREATE INDEX ix_count_plan_mfc ON count_plan (mfc_id, created_at);

-- first_unresolved / recount_unresolved = 목록 밖에서 찾았지만 SKU·LOT 을 정하지 못한 줄(조정하지 않고 관리자에게 보인다)
-- exceptions = 계수 중 예외 보고 [{exceptionId, reason, locationCode, sku, lotId, qty, note, reportedBy, reportedAt}]
ALTER TABLE count_session
    ADD COLUMN plan_id            varchar(64) REFERENCES count_plan (plan_id),
    ADD COLUMN first_unresolved   jsonb,
    ADD COLUMN recount_unresolved jsonb,
    ADD COLUMN exceptions         jsonb       NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN cancelled_by       varchar(64),
    ADD COLUMN cancel_reason      varchar(200);

ALTER TABLE count_session DROP CONSTRAINT ck_count_session_state;
ALTER TABLE count_session ADD CONSTRAINT ck_count_session_state
    CHECK (state IN ('COUNTING', 'RECOUNT_REQUIRED', 'PENDING_APPROVAL', 'MATCHED', 'ADJUSTED', 'REJECTED', 'CANCELLED'));

CREATE INDEX ix_count_session_plan ON count_session (plan_id) WHERE plan_id IS NOT NULL;
