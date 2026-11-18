# 회신: 휴게는 관리자 번호로만 켜고, 정한 휴게 시간에는 자동 휴게 (B1·B2·B3)

- 보낸 영역: mfc (PC 3 백엔드)
- 작성: pmart-backend, 2026-11-18
- 답하는 것: pmart-clients `docs/handoff/mfc-app/2026-11-18-backend-break-supervisor-pin.md`
- PR: 브랜치 `mfc/break-pin-auto-break` — **mfc-capacity 0.20.0**(V17). LAN 반영은 PC 1 병합·배포 + 게이트웨이 라우트(아래) 뒤.

## 사용자 결정(2026-11-18, 회신 전에 받음)
- 잠금: **같은 작업자가 연속 5번 틀리면 15분**. 시간이 지나면 풀리고, **센터 관리자가 번호를 새로 정하면 바로 풀린다**(그 센터 모두).
- 휴게 시간: **근무(shift)마다 휴게 구간**을 센터 관리자가 근무 등록 때 함께 적는다. 구간이 없으면 자동 휴게 없음.
- 자동 휴게: **들고 있던 작업은 끝낼 수 있고**(다음 작업 받기부터 409 ON_BREAK — 지금 규칙), **일찍 끝내기는 센터 관리자만**.

## B1. 센터 휴게 번호
- `PUT /mfcs/{mfcId}/break-pin` `{pin}`(숫자 4자리, 그 센터 `MFC_SUPERVISOR`, Idempotency-Key·X-Source-System) → 200 `BreakPinStatus`.
- `GET /mfcs/{mfcId}/break-pin`(그 센터 운영자) → `{mfcId, set, updatedAt, updatedBy}`. **번호는 어떤 응답에도 없다.**
- 저장은 PBKDF2 해시만, 로그·사건·멱등 기록에도 번호를 넣지 않는다.

## B2. 작업자 본인이 휴게를 켤 때
- `POST /mfcs/{mfcId}/workers/{workerId}/breaks` 본문 `{supervisorPin}`(선택 필드). **작업자 본인 토큰이면 필수**, 센터 관리자는 번호 없이.
- 번호가 빠지거나 4자리가 아니면 **400 VALIDATION_FAILED**(틀린 횟수에 넣지 않는다).
- 422 코드(요청서 이름 그대로):
  - `BREAK_PIN_NOT_SET` — 센터가 번호를 안 정함.
  - `BREAK_PIN_INVALID` — 틀림. `details` 에 `{field: "remainingAttempts", reason: "<남은 횟수>"}`(4 → 1).
  - `BREAK_PIN_LOCKED` — 5번째 틀림부터, 잠긴 동안은 맞는 번호도. `details` 에 `{field: "lockedUntil", reason: "<UTC 시각>"}`.
- 거절도 확정 결과 — **같은 Idempotency-Key 면 같은 판정**(번호가 달라도 첫 판정). **번호를 다시 받아 보낼 때는 새 키**로.
- 휴게 끝(`…/breaks/current/end`)은 번호 없이 그대로 — 단 아래 자동 휴게는 예외.

## B3. 자동 휴게
- 근무 등록 `POST /mfcs/{mfcId}/workers/{workerId}/shifts` 에 `breakStartAt`·`breakEndAt`(둘 다, 근무 안 — 아니면 400). 근무 조회에도 같은 칸.
- 서버가 30초마다: 휴게 구간에 들어선 근무의 작업자를 휴게로(`WorkerBreak.source = AUTO`, `startedBy = SYSTEM`, `shiftId`, `plannedEndAt`), 끝 시각이 지나면(또는 근무 취소) 끝낸다.
  - 그 근무의 자동 휴게는 한 번만 — 관리자가 일찍 끝냈으면 다시 켜지 않는다.
  - 이미 직접 켠 휴게 중이면 그대로 두고, 그 휴게가 끝난 뒤 구간이 남아 있으면 그때 자동 휴게.
- 자동 휴게 중 작업자 본인의 "휴게 끝" → **409 `AUTO_BREAK_SUPERVISOR_ONLY`**. 센터 관리자는 끝낼 수 있다.
- PDA 표시는 지금처럼 `getWorker.onBreak`. 휴게 응답의 `source` 로 "정해진 휴게 — 관리자만 끝냄" 안내를 할 수 있다.

## 게이트웨이(PC 1)
- 허용 목록 2줄(GET·PUT `/api/v1/mfcs/{mfcId}/break-pin`)은 PR 에 있다.
- **라우트**: mfc-capacity 경로에 `/api/v1/mfcs/*/break-pin` 추가가 필요(pmart-config `api-gateway.yml` — 지금은 `/mfcs/*/workers/**` 만).

## 시험(PC 3)
- `BreakPinIT` 3(번호 없음 400·미설정·틀림 4번 남은 횟수·같은 키 같은 판정·5번째 잠금·잠금 중 맞는 번호도 막힘·번호 바꾸면 풀림·해시만 저장, 관리자는 번호 없이·남의 휴게 403, 근무 휴게 구간 검사·자동 휴게 시작·본인 끝 409·관리자 끝·다시 안 켬·끝 시각에 서버가 끝냄).
- `BreakPinsTest` 3(해시·형식·구간). capacity 통합 33/36 — 실패 3 은 이 PC 앱 제어가 OR-Tools DLL 을 막는 `AssignmentPlanIT` 추천 시험(바뀐 휴게 호출은 번호를 싣도록 고침, PC 1 Linux 확인).

## 클라이언트 할 일
- mfc-backoffice: 번호 설정 화면(B1), 근무 등록에 휴게 구간 칸(B3), 작업자 화면 휴게 단추는 지금처럼(관리자 = 번호 없이).
- PDA: 휴게 켜기에 `supervisorPin`, 코드 셋 처리(이미 만든 창), 번호를 다시 받을 때 새 Idempotency-Key, 자동 휴게면 끝 단추 대신 안내.
