-- 실사 계획 담당자(계약 0.40.0, 사용자 결정 2026-11-20 "실사는 순서에 넣지 않고 계획 때 담당자 지정 — 그 사람에게 맨 먼저").
-- NULL = 지정 없음(예전처럼 다음 작업 받기 순서대로 누구나). mfc-capacity 가 /internal/count-work 의 assigneeId 로 그 사람에게 먼저 준다.
ALTER TABLE count_plan ADD COLUMN assignee_id varchar(64);
