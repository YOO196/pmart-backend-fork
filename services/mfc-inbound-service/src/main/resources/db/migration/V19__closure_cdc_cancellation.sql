-- 공급 요청 TRF 관리자 종결 = DC 미출발 취소 먼저(2026-11-27, 계약 0.27.0 — 요청 CO-3 ①, cdc-outbound 0.35.0, 사용자 "요청해"·"1만").
-- cdc-outbound POST /internal/transfer-intakes/{trfId}/cancellations 의 요청 키(조회 GET …/cancellations/{requestId}). DC 에 없던 TRF 면 null.
ALTER TABLE transit_closure ADD COLUMN cdc_cancel_request_id varchar(200);
