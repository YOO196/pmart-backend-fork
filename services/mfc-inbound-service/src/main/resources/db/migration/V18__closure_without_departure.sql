-- 출발 없는 예정의 관리자 종결(2026-11-27, 계약 0.26.0 — mfc 시뮬 R-54, 사용자 "종결처리하고 백오피스에도 다 연동되게").
-- CDC 출발(TOUT)을 받지 못한 예정(시험 송장·출발 전 멈춘 TRF)도 센터 관리자가 사유와 함께 CLOSED 로 — 출발이 없으니 cdc-inventory 에는 알리지 않는다
-- (from_cdc·departure_id 가 비고 next_try_at 도 비어 sendDue 가 집지 않는다).
ALTER TABLE transit_closure ALTER COLUMN from_cdc DROP NOT NULL;
ALTER TABLE transit_closure ALTER COLUMN departure_id DROP NOT NULL;
