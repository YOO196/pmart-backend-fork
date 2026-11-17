-- 포장 라벨 번호(2026-11-17, 계약 0.13.0 — 요청 F-5·F-8).
-- F-8: 라이더가 QR 을 못 찍을 때 입력하는 숫자 6자리. 무작위(라벨을 봐야 안다 — 실물 확인), 그 배송의 출고 안에서 찾는다.
--      DB 기본값이라 밀봉·재포장·재구성 어느 경로로 만든 포장이든 채워진다. 같은 출고 안 겹침은 매우 드물고, 겹치면 숫자 인계를 거절(QR 로).
ALTER TABLE package ADD COLUMN handover_code varchar(6);
UPDATE package SET handover_code = lpad(floor(random() * 1000000)::int::text, 6, '0');
ALTER TABLE package
    ALTER COLUMN handover_code SET DEFAULT lpad(floor(random() * 1000000)::int::text, 6, '0'),
    ALTER COLUMN handover_code SET NOT NULL;

-- F-5: 화면용 주문번호(order OrderConfirmed.orderNumber, 숫자 8자리) 사본 — 포장 라벨·인계 화면·작업 응답에. 이 필드 전 주문은 없다(orderId 를 쓴다).
-- 작업 지시보다 먼저 와도 되게 주문 ID 로 따로 둔다.
CREATE TABLE order_display_number (
    order_id      varchar(64) NOT NULL,
    order_number  varchar(8)  NOT NULL,
    updated_at    timestamptz NOT NULL,
    CONSTRAINT pk_order_display_number PRIMARY KEY (order_id)
);
