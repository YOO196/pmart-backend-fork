-- 반납 도착 대기(2026-11-19, 요청 MI-1·MI-2·MQ-1): delivery ReturnDelivered → 센터별 대기 건. 점원이 고르면 반입(return_receipt)을 채운다.
CREATE TABLE return_arrival (
    arrival_id          varchar(64)  NOT NULL,             -- = 회수 배송 ID(returnPickupId)
    mfc_id              varchar(32)  NOT NULL,
    purpose             varchar(32)  NOT NULL,             -- CLAIM_RETURN / MISDELIVERY_RECOVERY / FAILED_DELIVERY_RETURN
    claim_id            varchar(64),
    case_id             varchar(64),
    origin_delivery_id  varchar(64),
    order_id            varchar(64),
    rider_id            varchar(64),
    lines               jsonb        NOT NULL,             -- [{orderLineId, sku?, qty}]
    arrived_at          timestamptz  NOT NULL,
    return_receipt_id   varchar(48),                       -- 반입하면(재고 거절된 반입이면 다시 받을 수 있다)
    created_at          timestamptz  NOT NULL,
    CONSTRAINT pk_return_arrival PRIMARY KEY (arrival_id),
    CONSTRAINT ck_return_arrival_purpose CHECK (purpose IN ('CLAIM_RETURN', 'MISDELIVERY_RECOVERY', 'FAILED_DELIVERY_RETURN'))
);
CREATE INDEX ix_return_arrival_mfc ON return_arrival (mfc_id, arrived_at);

-- 오배송 회수·전달 실패 반납은 claim 이 없다
ALTER TABLE return_receipt ALTER COLUMN claim_id DROP NOT NULL;
ALTER TABLE return_receipt
    ADD COLUMN purpose            varchar(32) NOT NULL DEFAULT 'CLAIM_RETURN',
    ADD COLUMN case_id            varchar(64),
    ADD COLUMN origin_delivery_id varchar(64),
    ADD COLUMN arrival_id         varchar(64);
