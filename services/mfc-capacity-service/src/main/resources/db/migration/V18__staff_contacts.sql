-- 센터 직원 연락처(2026-11-18 사용자 결정 — mfc-backoffice "내 정보"): 연락 수단 표시만(로그인·알림 발송에 안 씀).
-- 개인정보 — 로그·사건에 넣지 않는다.
CREATE TABLE mfc_staff_contact (
    mfc_id       varchar(32)  NOT NULL,
    operator_id  varchar(64)  NOT NULL,
    name         varchar(50),
    phone        varchar(20),
    email        varchar(254),
    version      integer      NOT NULL,
    updated_at   timestamptz  NOT NULL,
    CONSTRAINT pk_mfc_staff_contact PRIMARY KEY (mfc_id, operator_id)
);
