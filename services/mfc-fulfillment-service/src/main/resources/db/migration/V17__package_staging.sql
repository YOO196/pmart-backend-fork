-- 출하대기 랙(F-12, 2026-11-19 — 시뮬레이터 R-20·PDA BR-52 R20): 밀봉 포장을 올린 랙. NULL = 아직 패킹대(packing_location).
-- 포장분이 지금 있는 곳 = COALESCE(staging_location, packing_location) — 인계 전기·중지 복귀가 이 위치를 쓴다.
ALTER TABLE package ADD COLUMN staging_location varchar(40);
ALTER TABLE package ADD COLUMN staged_at        timestamptz;
ALTER TABLE package ADD COLUMN staged_by        varchar(64);
