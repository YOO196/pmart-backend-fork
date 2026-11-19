-- 포장 인계 번호 4자리(2026-11-20, 계약 0.23.0 — MFC 시뮬레이터 R-26, 사용자 결정 "앞으로 발생하는 주문 인계 번호는 다 4자리").
-- 새 포장은 밀봉·재포장·재구성 때 앱이 센터 안에서 지금 쓰는(OPEN·SEALED) 번호와 겹치지 않는 4자리를 고른다(TaskStore.assignHandoverCode).
-- 기본값은 그 앞의 자리 채움(4자리 무작위) — 이미 6자리를 받은 포장은 그대로 두고 입력도 받는다.
ALTER TABLE package ALTER COLUMN handover_code SET DEFAULT lpad(floor(random() * 10000)::int::text, 4, '0');

CREATE INDEX ix_package_active_code ON package (shipment_id, handover_code) WHERE state IN ('OPEN', 'SEALED');

-- 번호 입력 실패 횟수(라이더·배송마다). 5번 틀리면 번호 입력을 막는다(409 HANDOVER_CODE_LOCKED — QR·점원 확인만).
CREATE TABLE handover_code_attempt (
    rider_id       varchar(64) NOT NULL,
    delivery_id    varchar(64) NOT NULL,
    failures       integer     NOT NULL,
    last_failed_at timestamptz NOT NULL,
    CONSTRAINT pk_handover_code_attempt PRIMARY KEY (rider_id, delivery_id)
);
