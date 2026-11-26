-- IV-9 마감세일(ADR-0090 B2, 2026-11-26): 예약 줄의 LOT 지정. 지정 줄은 처음 배정·재확보(결품·막힘·품질 보류 뒤)를 모두 그 LOT 안에서만 한다.
-- 줄 키 = 예약·주문 줄·SKU(지정 줄은 orderLineId 가 꼭 있다).
CREATE TABLE reservation_line_lot_pin (
    reservation_id  varchar(40)  NOT NULL,
    order_line_id   varchar(64)  NOT NULL,
    sku             varchar(10)  NOT NULL,
    lot_id          varchar(40)  NOT NULL,
    created_at      timestamptz  NOT NULL,
    CONSTRAINT pk_reservation_line_lot_pin PRIMARY KEY (reservation_id, order_line_id, sku),
    CONSTRAINT fk_reservation_line_lot_pin FOREIGN KEY (reservation_id) REFERENCES reservation (reservation_id),
    CONSTRAINT fk_reservation_line_lot_pin_lot FOREIGN KEY (lot_id) REFERENCES stock_lot (lot_id)
);
