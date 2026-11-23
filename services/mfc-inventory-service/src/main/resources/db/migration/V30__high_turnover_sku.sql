-- 고회전 상품 순위(2026-11-23, MFC 시뮬레이터 R-40 — 사용자 결정 "진짜 고회전 상품(주문율 높은 상품 top20)은 거기[F 구역]에 적치",
-- 기준 "최근 7일 주문 줄 수"·"하루 한 번"·"다음 적치부터만"). 센터마다 상온 상위 20 SKU 를 하루 한 번 다시 계산해 통째로 바꾼다.
-- 적치·복귀 칸 추천(MoveService.placementCandidates)이 이 상품에 F 구역 고회전 칸을 먼저 내놓는다. 이미 다른 칸에 있는 재고는 옮기지 않는다.
CREATE TABLE high_turnover_sku (
    mfc_id       varchar(32)  NOT NULL,
    sku          varchar(10)  NOT NULL,
    rank         integer      NOT NULL,
    order_lines  integer      NOT NULL,   -- 집계 기간 안의 주문 줄 수(확정·인계된 예약)
    window_from  timestamptz  NOT NULL,
    computed_at  timestamptz  NOT NULL,
    CONSTRAINT pk_high_turnover_sku PRIMARY KEY (mfc_id, sku),
    CONSTRAINT ck_high_turnover_sku_rank CHECK (rank > 0)
);
