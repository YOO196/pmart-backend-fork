-- 센터 관리자 배정 풀기(2026-11-27, 계약 0.36.0): 해제를 AssignmentReleased 로 알릴 때 쓴 버전. 투영(mfc-inbound)은 더 새 버전만 받으므로
-- 다음 배정 버전은 배정·해제 버전 중 큰 값 + 1 이어야 한다(AssignmentStore.maxVersion).
ALTER TABLE task_assignment ADD COLUMN release_version integer;
