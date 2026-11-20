# 요청: 작업자 목록에 "업무 미지정" 표시 (서버 변경 없음)

- 보낸 영역: mfc (PC 3 백엔드)
- 작성: pmart-backend, 2026-11-20
- 대상: pmart-clients `web/mfc-backoffice`(작업자 화면)
- 배경: 사용자 결정(2026-11-20, PC 3 추천 4번). 인사·권한으로 자동 생성된 **정규직·알바 작업자는 자격·업무가 빈 채**로 만들어진다(센터 관리자가 정해야 배정 — 2026-11-08 결정 "정규 = 관리자가 업무"). 권한을 받았는데 왜 일을 못 받는지 관리자가 바로 알 수 있게 한다.

## 표시 규칙(이미 있는 값으로)
- `GET /api/v1/mfcs/{mfcId}/workers` 항목의 `effectiveTaskTypes` 가 비어 있으면 → **"업무 미지정"** 배지 + 업무 정하기(`POST …/workers/{workerId}/duty-changes`·`qualification-changes`)로 바로 가는 단추.
- 일일크루(`employmentType: DAILY`)는 기본 자격(피킹·포장)이 있어 해당 없음 — 다만 그날 등록(`todayEnrollment`)이 없으면 그날은 받지 못한다(기존 표시 그대로).
- 목록 맨 위에 "업무 미지정 N명" 요약이 있으면 좋다(선택).
