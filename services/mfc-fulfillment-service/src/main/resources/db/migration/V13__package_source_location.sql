-- 2026-11-17 PDA 요청(피킹 도착지 = 장바구니): 포장이 집품분을 가져온 위치(장바구니). NULL = packing_location 과 같다(예전 흐름 — 포장 위치에 집품).
ALTER TABLE package ADD COLUMN source_location varchar(64);
