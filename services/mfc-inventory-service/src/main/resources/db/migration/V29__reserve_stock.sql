-- 보충재고 칸(0.44.0, R-30 — 사용자 결정 2026-11-20 "선반칸이 가득차면 남는 건 입고 창고에"·2026-11-21 보충 기본 규칙·피킹 마지막 순위).
-- mfc-location 0.9.0 MfcLocationStateChanged.reserveStock 사본. 예전 사건(필드 없음)은 false.
ALTER TABLE location_projection ADD COLUMN reserve_stock boolean NOT NULL DEFAULT false;
