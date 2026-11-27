-- TRF·Wave 화면 번호 사본(2026-11-27, 계약 0.25.0 — mfc 시뮬 R-52, 사용자 "TRF·WAVE 식별값 말고 T286575 W6307 로").
-- 정본은 cdc-outbound(ADR-0070·0085). 인수 조회·출발 매니페스트에서 받아 둔다 — 표시용(못 받으면 null), 예정 version 은 올리지 않는다.
ALTER TABLE inbound_promise ADD COLUMN trf_number varchar(16);
ALTER TABLE inbound_promise ADD COLUMN wave_id varchar(64);
ALTER TABLE inbound_promise ADD COLUMN wave_number varchar(16);
