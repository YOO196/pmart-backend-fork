-- 포장 아이스팩 수(2026-11-20, 계약 0.25.0 — mfc-app BR-52 R19-9, 사용자 결정 "아이스팩 수만 기록").
-- 밀봉 본문 icePackCount(선택) 그대로. NULL = 받지 않음. 재포장은 이전 포장 값을 복사, 재구성은 NULL.
ALTER TABLE package ADD COLUMN ice_pack_count INTEGER CHECK (ice_pack_count >= 0);
