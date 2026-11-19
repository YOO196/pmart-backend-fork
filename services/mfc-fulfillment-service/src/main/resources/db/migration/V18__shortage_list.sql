-- 센터 결품 목록(2026-11-19, MFC 백오피스 OE-1): 결품 보고 줄에 상품·LOT·위치·보고자. 개인정보 없음(보고자 = 운영자 ID).
ALTER TABLE line_shortage
    ADD COLUMN sku           varchar(32),
    ADD COLUMN lot_id        varchar(40),
    ADD COLUMN location_code varchar(32),
    ADD COLUMN reported_by   varchar(64);
-- 예전 줄은 상품만 출고 줄에서 채운다(LOT·위치·보고자는 기록이 없어 null)
UPDATE line_shortage z SET sku = sl.sku
  FROM task t JOIN shipment_line sl ON sl.shipment_id = t.shipment_id
 WHERE t.task_id = z.task_id AND sl.order_line_id = z.order_line_id AND z.sku IS NULL;
CREATE INDEX ix_line_shortage_center ON line_shortage (mfc_id, shortage_id DESC);
