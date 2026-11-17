-- 실사 계수 시각(2026-11-17, 요청 F-7 R3 — 작업자 "오늘 업무 내역"). 지금까지는 updated_at 하나라 승인 시각에 덮였다.
-- 기존 행은 알 수 없어 updated_at 으로 채운다(근사 — 승인된 실사는 승인 시각).
ALTER TABLE count_session
    ADD COLUMN first_counted_at timestamptz,
    ADD COLUMN recounted_at     timestamptz;

UPDATE count_session SET first_counted_at = updated_at WHERE first_counter IS NOT NULL;
UPDATE count_session SET recounted_at = updated_at WHERE recounter IS NOT NULL;

CREATE INDEX ix_count_session_first_counter ON count_session (mfc_id, first_counter, first_counted_at);
CREATE INDEX ix_count_session_recounter ON count_session (mfc_id, recounter, recounted_at);
