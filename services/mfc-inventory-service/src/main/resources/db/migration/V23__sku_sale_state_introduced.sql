-- 상품 취급 R7·R8(2026-11-19 사용자 결정): 본사 판매 상태 사본(단종이면 모든 센터 OFF·켜기 막음)과 상품이 생긴 시각(신상품 배너).
-- 예전 상품은 생긴 시각을 모른다(NULL — 신상품 거르기에서 빠진다).
ALTER TABLE sku_projection ADD COLUMN sale_state varchar(16);
ALTER TABLE sku_projection ADD COLUMN sale_state_at timestamptz;
ALTER TABLE sku_projection ADD COLUMN sale_state_version integer;   -- ProductSaleStateChanged.version(있으면 이것으로 순서 판정)
ALTER TABLE sku_projection ADD COLUMN introduced_at timestamptz;
ALTER TABLE sku_projection ADD CONSTRAINT ck_sku_projection_sale_state
    CHECK (sale_state IS NULL OR sale_state IN ('ON_SALE', 'TEMP_OUT', 'DISCONTINUED'));
CREATE INDEX ix_sku_projection_introduced ON sku_projection (introduced_at) WHERE introduced_at IS NOT NULL;
