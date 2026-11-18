-- 휴게 번호·자동 휴게(2026-11-18 사용자 결정 — mfc-app 요청 B1~B3, 계약 0.20.0).
-- 센터 휴게 번호: 4자리, 해시만(PBKDF2). 번호 자체는 어디에도 남기지 않는다.
CREATE TABLE mfc_break_pin (
    mfc_id      varchar(32)  NOT NULL,
    pin_hash    varchar(200) NOT NULL,
    updated_at  timestamptz  NOT NULL,
    updated_by  varchar(64)  NOT NULL,
    CONSTRAINT pk_mfc_break_pin PRIMARY KEY (mfc_id)
);

-- 작업자별 연속 틀린 횟수·잠금(5번 → 15분). 맞히거나 번호가 바뀌면 지운다.
CREATE TABLE mfc_break_pin_failure (
    mfc_id        varchar(32) NOT NULL,
    worker_id     varchar(64) NOT NULL,
    failed_count  integer     NOT NULL,
    locked_until  timestamptz,
    updated_at    timestamptz NOT NULL,
    CONSTRAINT pk_mfc_break_pin_failure PRIMARY KEY (mfc_id, worker_id)
);

-- 근무의 휴게 구간(둘 다 있거나 둘 다 없음, 근무 안)
ALTER TABLE mfc_worker_shift ADD COLUMN break_start_at timestamptz;
ALTER TABLE mfc_worker_shift ADD COLUMN break_end_at   timestamptz;
ALTER TABLE mfc_worker_shift ADD CONSTRAINT ck_mfc_worker_shift_break CHECK (
    (break_start_at IS NULL AND break_end_at IS NULL)
    OR (break_start_at IS NOT NULL AND break_end_at IS NOT NULL
        AND break_start_at < break_end_at AND break_start_at >= start_at AND break_end_at <= end_at));
CREATE INDEX ix_mfc_worker_shift_break ON mfc_worker_shift (break_start_at) WHERE state = 'PLANNED' AND break_start_at IS NOT NULL;

-- 휴게 출처: MANUAL(본인·관리자) / AUTO(근무 휴게 구간). 자동 휴게는 근무마다 한 번
ALTER TABLE mfc_worker_break ADD COLUMN source varchar(10) NOT NULL DEFAULT 'MANUAL';
ALTER TABLE mfc_worker_break ADD COLUMN shift_id varchar(40);
ALTER TABLE mfc_worker_break ADD COLUMN planned_end_at timestamptz;
ALTER TABLE mfc_worker_break ADD CONSTRAINT ck_mfc_worker_break_source CHECK (source IN ('MANUAL', 'AUTO'));
CREATE UNIQUE INDEX uq_mfc_worker_break_auto_shift ON mfc_worker_break (shift_id) WHERE source = 'AUTO';
