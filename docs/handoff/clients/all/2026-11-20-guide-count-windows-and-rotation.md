# 프론트엔드 지침: 재고 실사 시간대 · 순환 실사 시작 (mfc-capacity 0.33.0 · mfc-inventory 0.42.0)

- 보낸 영역: mfc (PC 3 백엔드)
- 작성: pmart-backend, 2026-11-20
- 대상: pmart-clients `web/mfc-backoffice`(센터 관리자 웹 — §2·§3), `apps/mfc_app`(PDA — §4)
- 배경: 사용자 결정(2026-11-20) "재고실사는 하루 두 번 아침·저녁, 아침 7시~8시·저녁 8시~9시 디폴트이되 변경 가능하게, 관리자 권한으로 재고실사 시작, 20칸, 추천대로".
- 배포: 백엔드 PR #301(#288 → #290 → #301 순서로 병합) 병합·배포 뒤(PC 1). 게이트웨이 경로는 기존 `/mfcs/**` 그대로 — 게이트웨이 변경 없음.
- 같은 내용의 짧은 요청서: `mfc-backoffice/2026-11-20-request-count-windows-and-rotation.md`(이 문서가 자세한 판).

## 1. 한눈에

| 하는 일 | 누가 | 어디서 | API |
|---|---|---|---|
| 실사 시간대 보기 | 그 센터 직원 누구나(작업자 포함) | 웹 설정·PDA 표시 | `GET /api/v1/mfcs/{mfcId}/count-windows` |
| 실사 시간대 바꾸기 | 그 센터 `MFC_SUPERVISOR` | 웹 설정 | `PUT /api/v1/mfcs/{mfcId}/count-windows` |
| 순환 실사 시작(서버가 칸 고름) | 그 센터 `MFC_SUPERVISOR` | 웹 실사 화면 | `POST /api/v1/mfcs/{mfcId}/count-plans` `{ "rotationSize": 20 }` |
| 칸 골라 실사 시작(기존) | 그 센터 `MFC_SUPERVISOR` | 웹 실사 화면 | 같은 경로 `{ "locationCodes": [...] }` |
| 실사 받기 | 실사 업무가 있는 작업자 | PDA 다음 작업 받기 | 기존 `POST /mfcs/{mfcId}/workers/{workerId}/next-tasks` |

- **시간대가 돼도 실사는 저절로 열리지 않는다.** 관리자가 시작(§3)해야 작업자에게 실사가 생긴다. 시간대는 "그 시간엔 실사를 피킹보다 먼저 준다"는 순서 규칙이다.
- 권장 운영: 시간대 시작 즈음 관리자가 "순환 실사 시작"을 누른다 → 시간대 동안 작업자들이 다음 작업 받기로 실사를 받는다.

## 2. [웹] 실사 시간대 설정

### 조회
`GET /api/v1/mfcs/{mfcId}/count-windows`
```json
{
  "mfcId": "MFC-SEED-01",
  "windows": [ { "startTime": "07:00", "endTime": "08:00" }, { "startTime": "20:00", "endTime": "21:00" } ],
  "source": "DEFAULT",
  "version": 0,
  "updatedBy": null,
  "updatedAt": null,
  "activeNow": false
}
```
- 시각은 **서울 시각 "HH:mm"**, 구간은 [startTime, endTime) — 07:00~08:00 이면 07:59 까지.
- `source`: `DEFAULT` = 이 센터가 바꾼 적 없음(기본 07:00–08:00·20:00–21:00), `CENTER` = 바꾼 값.
- `activeNow`: 지금 시간대 안인가(서버 시계 기준 — 화면 시계로 계산하지 말고 이 값을 쓴다).
- 403: 그 센터 직원이 아님.

### 바꾸기
`PUT /api/v1/mfcs/{mfcId}/count-windows`
- 헤더: `Idempotency-Key`(요청마다 새 UUID, 같은 요청 재시도면 같은 값), `X-Source-System: mfc-backoffice`, `X-Expected-Version: <조회한 version>`(기본값이면 0).
- 본문 — **목록 통째로** 보낸다(한 줄만 고칠 때도 전체):
```json
{ "windows": [ { "startTime": "06:30", "endTime": "07:30" }, { "startTime": "20:00", "endTime": "21:00" } ] }
```
- 응답 200 = 조회와 같은 모양(새 `version`). 화면은 응답으로 다시 그린다.
- 규칙(어기면 **400 VALIDATION_FAILED**, `detail` 에 이유): "HH:mm" 형식, 시작 < 끝(**자정을 넘길 수 없음** — 23:30~00:30 은 23:30~23:59 로), 서로 겹치면 안 됨, 최대 6개. 순서는 서버가 시작 순으로 정렬.
- 빈 목록 `{ "windows": [] }` = 실사 시간대 없음(실사는 순서 맨 뒤 — 담당 지정 실사만 먼저). "끄기" 버튼으로 쓸 수 있다. 다시 기본값으로 돌리는 전용 API 는 없다 — 07:00–08:00·20:00–21:00 을 넣어 저장.
- 403: 센터 관리자가 아님(작업자 토큰 등) → 편집 칸을 아예 숨기거나 읽기 전용.
- 409 VERSION_CONFLICT: 다른 관리자가 먼저 바꿈 → "다른 곳에서 먼저 바뀌었습니다" + 다시 조회해 보여 주기.

### 화면 제안
- 센터 설정(또는 실사 화면 위)에 "재고 실사 시간대" 카드: 줄마다 시작·끝 시각 고르기(10분 단위면 충분), [추가]·[삭제], [저장].
- `source = DEFAULT` 면 "기본값" 꼬리표. `activeNow = true` 면 "지금 실사 시간" 표시.
- 저장 전 클라이언트에서도 같은 규칙(시작 < 끝, 겹침)을 미리 검사하면 좋다(서버가 최종 판단).

## 3. [웹] 실사 시작 — 순환 실사

`POST /api/v1/mfcs/{mfcId}/count-plans` (그 센터 `MFC_SUPERVISOR`, 헤더 `Idempotency-Key`·`X-Source-System`)

| 본문 | 뜻 |
|---|---|
| `{}` 또는 `{ "rotationSize": 20 }` | **순환 실사** — 서버가 칸 20개(생략 시 20, 1~200)를 고른다 |
| `{ "rotationSize": 10, "assigneeId": "OP-…", "reason": "저녁 실사" }` | 칸 수·담당·사유를 같이(담당·사유는 선택) |
| `{ "locationCodes": ["A-01-01", …] }` | 기존 방식 — 칸 직접 고르기 |

- 서버가 고르는 칸: 선반·피킹 구역의 사용 중(ACTIVE) 칸 중 **이미 실사 중이거나 재고 이동 중인 칸은 빼고**, **마지막으로 실사를 끝낸 지 오래된 칸부터**(한 번도 안 센 칸이 맨 먼저). 칸이 모자라면 있는 만큼만 연다(20칸을 요청해도 12칸만 열릴 수 있다 — 응답의 `sessions` 개수로 보여 준다).
- 사유를 비우면 `reason` = "순환 실사".
- 응답 201 = 기존 `CountPlan`(`planId`, `state: OPEN`, `sessions[]{sessionId, locationCode, state}`, `lockedLocationCodes`, `assigneeId`). 고른 칸은 `sessions[].locationCode` 로 표시.
- 오류:
  - **422 NO_COUNT_LOCATIONS** — 고를 칸이 없음(모든 칸이 실사·이동 중이거나 칸이 없음) → "지금 실사할 칸이 없습니다".
  - **400 VALIDATION_FAILED** — `locationCodes` 와 `rotationSize` 를 같이 줌, `rotationSize` 가 1~200 밖.
  - 403 — 센터 관리자가 아님.
- 주의(화면 안내 문구로): **고른 칸은 그 실사가 끝나거나 취소될 때까지 피킹·이동·적치가 막힌다.** 시작 전 확인 대화상자에 "칸 N개가 실사 동안 잠깁니다" 를 보여 주길 권한다. 잘못 열었으면 기존 계획 취소 `POST …/count-plans/{planId}/cancellation`.
- 화면 제안: 실사 화면 위에 [순환 실사 시작] 버튼 → 작은 창(칸 수 기본 20, 담당 작업자 선택(선택), 사유(선택)) → 시작. 옆에 기존 [칸 골라 계획]. 그 아래 열린 계획 목록.
- 같은 버튼을 두 번 눌러도 같은 `Idempotency-Key` 로 재시도하면 한 계획만 생긴다(버튼을 누를 때 키를 만들고 응답까지 버튼을 잠근다).

## 4. [PDA] 작업자 화면

- **API 를 새로 부를 필요는 없다.** 다음 작업 받기(`next-tasks`)가 시간대 안에서 실사를 먼저 돌려준다 — 받은 작업의 `taskType` 이 `CYCLE_COUNT` 면 기존 실사 화면으로.
- 순서(시간대 안): 들고 있는 작업 → 나에게 정해진 격리 이동 → 나를 담당으로 정한 실사 → **자기가 피킹한 주문의 포장** → **실사(시간대)** → 피킹·포장 → … (시간대 밖에서는 실사가 맨 뒤 — 예전과 같다).
- **업무(workArea)를 고르면 그 업무 안에서만 준다**(기존 규칙 그대로). 피킹·포장(`PICK_PACK`)을 고른 사람은 시간대여도 실사를 받지 않고, `STOCK_COUNT`(재고 실사)를 고르거나 업무를 고르지 않았을 때 받는다. 실사 업무가 없는 사람에게는 가지 않는다(담당으로 정해진 실사는 예외 — 0.29.0).
- 표시 제안(선택): 앱 시작·업무 고르기 화면에서 `GET /mfcs/{mfcId}/count-windows` 를 한 번 불러 `activeNow = true` 이고 작업자 업무에 `CYCLE_COUNT` 가 있으면 "지금은 재고 실사 시간입니다 — 재고 실사 업무를 고르세요" 띠를 보여 준다. 작업자 토큰으로 조회 가능(바꾸기는 403).
- 시간대에 실사가 다 끝났거나 아직 관리자가 시작하지 않았으면 평소 일이 나온다 — 따로 처리할 것 없음.

## 5. 바뀌지 않은 것
- 실사 계수·재계수·승인 화면과 API 는 그대로. 계획 담당(`assigneeId`, 0.40.0)도 그대로.
- 다음 작업 순서 확정·오래 밀린 복귀/회수품 검수 앞당기기(0.29.0) 그대로 — `docs/handoff/clients/all/2026-11-20-notice-count-assignee-and-order.md`.
