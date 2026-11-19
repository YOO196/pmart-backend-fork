-- 품질 사고(MB-2 1차, 2026-11-19 사용자 결정): 자동(결품 보고 DAMAGED) + 센터 관리자 직접 등록, OPEN → DECIDED(영향 LOT 모두 처리) → CLOSED(관리자, 사유).
-- 격리·해제·폐기는 기존 판정·폐기 흐름 — 사고는 그 결과를 LOT 으로 본다(판정의 incident_id 또는 같은 센터·LOT·사고 뒤).
CREATE TABLE quality_incident (
    incident_id    varchar(64)  NOT NULL,
    mfc_id         varchar(40)  NOT NULL,
    kind           varchar(32)  NOT NULL,
    origin         varchar(8)   NOT NULL,
    state          varchar(8)   NOT NULL,
    source_event   varchar(64),
    source_ref     varchar(128),
    description    varchar(300),
    opened_by      varchar(64),
    opened_at      timestamptz  NOT NULL,
    closed_by      varchar(64),
    closed_at      timestamptz,
    close_reason   varchar(300),
    case_id        varchar(64),
    version        integer      NOT NULL DEFAULT 1,
    updated_at     timestamptz  NOT NULL,
    CONSTRAINT pk_quality_incident PRIMARY KEY (incident_id),
    CONSTRAINT ck_quality_incident_origin CHECK (origin IN ('AUTO', 'MANUAL')),
    CONSTRAINT ck_quality_incident_state CHECK (state IN ('OPEN', 'DECIDED', 'CLOSED'))
);
CREATE INDEX ix_quality_incident_center ON quality_incident (mfc_id, state, opened_at, incident_id);

CREATE TABLE quality_incident_lot (
    incident_id  varchar(64) NOT NULL,
    lot_id       varchar(40) NOT NULL,
    sku          varchar(40),
    qty          integer     NOT NULL,
    CONSTRAINT pk_quality_incident_lot PRIMARY KEY (incident_id, lot_id),
    CONSTRAINT fk_quality_incident_lot FOREIGN KEY (incident_id) REFERENCES quality_incident (incident_id),
    CONSTRAINT ck_quality_incident_lot_qty CHECK (qty > 0)
);
CREATE INDEX ix_quality_incident_lot_lot ON quality_incident_lot (lot_id);
