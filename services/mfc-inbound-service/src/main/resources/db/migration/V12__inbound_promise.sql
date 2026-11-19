-- 입고 예정(InboundPromise, 2026-11-20 — 계약 0.16.0): CDC → MFC 이송(TRF)의 MFC 쪽 투영과 수령(TIN).
-- 원천 사건: replenishment TransferApproved·Changed·Cancelled(scm.replenishment.v1, trfVersion 으로 늦은 사건 무시),
-- cdc-inventory TransferDeparted(cdc.inventory.v1, 실물 사실 — departureId 한 번), cdc-outbound TransferShipmentManifestIssued
-- (cdc.outbound.v1, LOT·LPN 줄). 상태 EXPECTED → RECEIVING(도착 등록) → TIN_POSTED(수령 재고 반영) / CANCELLED(출발 전 취소).
-- 적용된 파일은 수정하지 않는다.

CREATE TABLE inbound_promise (
    trf_id              varchar(64)  NOT NULL,
    mfc_id              varchar(32)  NOT NULL,             -- 목적 센터(toMfc)
    from_cdc            varchar(32),
    trf_version         bigint,                            -- replenishment TRF 버전(Approved trfVersion, Changed·Cancelled toVersion). 출발만 먼저 오면 NULL
    state               varchar(16)  NOT NULL,
    row_version         integer      NOT NULL,             -- API version(X-Expected-Version) — 이 투영이 바뀔 때마다 +1
    deadline_meaning    varchar(16),                       -- ARRIVAL / HANDOVER / READY
    due_at              timestamptz,                       -- TRF 기한(plannedArrivalAt 으로 보인다)
    priority            varchar(32),
    approved_lines      jsonb        NOT NULL DEFAULT '[]', -- [{sku, qty, unit}] 승인 수량(취소분 뺀 값)
    departure_id        varchar(64),                       -- TOUT 출발 사실 ID(한 TRF 한 출발)
    departure_commit_id varchar(64),
    departed_lines      jsonb,                             -- [{sku, lot, lpn, qty, expiryDate}] 실제 출발 줄 = 예정 수량의 근거
    departed_at         timestamptz,
    trip_id             varchar(64),
    manifest_id         varchar(64),
    manifest_version    bigint,
    manifest_lines      jsonb,                             -- [{sku, lot, lpn, qty, expiryDate}] 출발 매니페스트(LPN 목록)
    arrived_at          timestamptz,
    arrival_trip_id     varchar(64),
    unloading_bay_reservation_id varchar(64),
    arrival_evidence_ref varchar(256),
    arrival_recorded_by varchar(128),
    cancel_reason       varchar(500),
    cancelled_at        timestamptz,
    source              varchar(16),                       -- 출발 사실 REAL / SIMULATED
    sources             jsonb        NOT NULL DEFAULT '{}', -- {발행 서비스: 마지막 반영 사건 occurredAt}
    sort_at             timestamptz  NOT NULL,             -- 목록 정렬 = due_at, 없으면 출발·생성 시각
    created_at          timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT pk_inbound_promise PRIMARY KEY (trf_id),
    CONSTRAINT ck_inbound_promise_state CHECK (state IN ('EXPECTED', 'RECEIVING', 'TIN_POSTED', 'CANCELLED'))
);
CREATE INDEX ix_inbound_promise_mfc ON inbound_promise (mfc_id, sort_at, trf_id);

-- 수령(TIN) — 현장 사실을 먼저 남기고(CAPTURED) mfc-inventory ConfirmReceipt(Idempotency-Key = receipt_id) → APPLIED(품질검사 작업)
-- / REJECTED / UNKNOWN_RESULT(같은 요청 키로 다시 — inventory 는 receipt_id 로 한 번만 계상).
CREATE TABLE promise_receipt (
    receipt_id          varchar(64)  NOT NULL,             -- RCV-TRF-…
    trf_id              varchar(64)  NOT NULL,
    mfc_id              varchar(32)  NOT NULL,
    departure_id        varchar(64)  NOT NULL,
    lines               jsonb        NOT NULL,             -- [{sku, lotId, expiryDate, normalQty, quarantinedQty, pendingQty, toLocationCode, discrepancyReason}]
    discrepancies       jsonb        NOT NULL DEFAULT '[]', -- [{sku, expectedQty, receivedQty, reason}]
    state               varchar(16)  NOT NULL,
    commit_id           varchar(64),
    reject_reason       varchar(1000),
    last_error          varchar(500),
    evidence_ref        varchar(256),
    received_by         varchar(128) NOT NULL,
    received_at         timestamptz  NOT NULL,             -- = ConfirmReceipt.receivedAt(다시 보내도 같은 본문)
    posted_at           timestamptz,
    created_at          timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT pk_promise_receipt PRIMARY KEY (receipt_id),
    CONSTRAINT fk_promise_receipt FOREIGN KEY (trf_id) REFERENCES inbound_promise (trf_id),
    CONSTRAINT ck_promise_receipt_state CHECK (state IN ('CAPTURED', 'APPLIED', 'REJECTED', 'UNKNOWN_RESULT'))
);
CREATE INDEX ix_promise_receipt_trf ON promise_receipt (trf_id);
CREATE INDEX ix_promise_receipt_operator ON promise_receipt (mfc_id, received_by, received_at);
