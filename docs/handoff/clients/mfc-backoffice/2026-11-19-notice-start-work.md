# 알림: 작업 배정 화면 — "작업 시작" 한 번으로 배정, 바꾸기는 그 화면에서 (mfc-capacity 0.22.0)

- 보낸 영역: mfc (PC 3 백엔드)
- 작성: pmart-backend, 2026-11-19
- 대상: pmart-clients `web/mfc-backoffice`(배정 화면)
- 사용자 결정(2026-11-19): **배정 추천 화면을 따로 두지 않는다.** 백오피스에 "작업 시작" 단추가 있고, 누르면 추천대로 **바로 직원에게 배정**된다. 관리자는 그 화면에서 배정을 **직접 바꾸거나 새로 배정**할 수 있다.
- PR: 브랜치 `mfc/plan-start-work`(LAN 은 PC 1 병합·배포 뒤).

## 서버
- **작업 시작** = `POST /assignment-plans` 본문 `{mfcId, horizonMinutes, commit: true}`(그 센터 `MFC_SUPERVISOR`, Idempotency-Key — 같은 키 다시 보내기 = 같은 결과).
  - 계산 결과가 되면 바로 확정 → 201 `AssignmentPlan` `state: COMMITTED`, `assignments[]` = 생긴 배정(작업자에게 `AssignmentPublished` → PDA 다음 작업 받기·작업 목록에 바로 보임).
  - 계산과 확정 사이에 작업이 바뀌면 서버가 **한 번 다시 계산해 확정**한다. 그래도 안 되면 `state: STALE`(`staleReasons`) — "다시 시작" 안내.
  - 배정할 사람·작업이 없으면 `NO_SOLUTION`, 시간 설정이 없는 작업은 `unassigned[].reason`(CONFIG_REQUIRED 등) — 그대로 보여 준다.
  - `horizonMinutes`(10~480)는 "앞으로 몇 분 일감을 나눌지" — 화면 기본값 제안 60.
- **바꾸기·직접 배정** = 지금 있는 `POST /assignments` `{mfcId, taskId, taskType, workerId, reason}` + `X-Expected-Version`(지금 활성 배정 버전, 담당 없으면 0). 다른 사람으로 바꾸면 앞 배정은 SUPERSEDED, 진행 중 작업이면 새 담당이 처음부터 시작(fulfillment 규칙).
- **지금 배정 보기** = `GET /assignments?mfcId=`(ACTIVE 기본, 작업자별로 묶어 보이기 좋음). 담당 없는 작업은 fulfillment `GET /tasks?mfcId=&state=READY`.
- 추천만 보는 기존 흐름(`commit` 없이 → `POST /assignment-plans/{id}/commits`)과 지난 추천 목록(`GET /assignment-plans`)은 그대로 남는다 — 화면에서 안 써도 된다.

## 화면 제안(한 화면)
1. 위: **작업 시작** 단추(+ 몇 분치 선택) → 결과 요약 "N건 배정, M건 남음(사유)".
2. 아래: 작업자별 지금 배정 목록, 행마다 **담당 바꾸기**(작업자 고르기 → `POST /assignments`). 담당 없는 작업 목록에 **배정** 단추(같은 API).
3. 버전 충돌(409 VERSION_CONFLICT)이면 목록을 다시 읽고 다시.
