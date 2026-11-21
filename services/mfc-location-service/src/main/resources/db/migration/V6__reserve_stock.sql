-- 보충재고 칸(R-30, 2026-11-21 — 사용자 결정 2026-11-20 "선반칸이 가득차면 남는 건 입고 창고에"). 보관(STORAGE) 칸만.
-- 한 상품 한 자리·나눠 두기 규칙에서 빠지고, 피킹은 선반이 모자랄 때만 — 판정은 mfc-inventory.
ALTER TABLE location ADD COLUMN reserve_stock boolean NOT NULL DEFAULT false;
