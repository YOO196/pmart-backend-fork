보낸 영역: mfc (PC 3 — mfc-capacity)
받는 앱: mfc-app (PDA)
원 요청: pmart-clients `docs/handoff/mfc-app/2026-11-27-backend-worker-choose-task.md`

# 회신 — 대기 작업 목록에서 골라 받기 (mfc-capacity 0.37.0)

## 사용자 결정 (2026-11-27, PC 3 에서 받음)
- 목록 범위: **급한 순 위 20개**(줄마다 권장 순번).
- "그 사람만" 작업(나에게 정한 격리 이동·계획 실사, 내가 피킹한 PACK): **맨 위에 두고 먼저 처리** — 남아 있는 동안 다른 작업은 맡을 수 없다.
- 줄 내용: **가볍게** — 상품·칸 상세는 맡을 때 응답 detail 로.
- 기존 [다음 작업 받기](`next-tasks`)는 그대로(목록 맨 위 "권장 작업 받기"로 쓰면 됨 — recommendedOrder 1 과 같은 작업).

## MFC API
### `GET /mfcs/{mfcId}/workers/{workerId}/available-tasks?workArea=`
- 작업자 본인. 후보·순서·막는 규칙(OFF_SHIFT·ON_BREAK 409, WORKER_NOT_ACTIVE·WORK_AREA_NOT_ALLOWED 422)은 next-tasks 와 같다.
- 응답 `{ holding: Assignment|null, items: [{ taskId, taskType, recommendedOrder, since, mine }] }` (최대 20).
  - `since` = 순서 기준 시각 — 주문 작업은 출하 약속, 입고는 예정 도착(없으면 출발), 그 밖은 만든·도착·반입 시각. 대기 시간·기한 표시에.
  - `mine` true 줄은 맨 위 — 먼저 처리.
  - 다른 사람이 피킹한 PACK·다른 사람에게 정한 격리 이동·다른 사람이 들고 있는 작업은 나오지 않는다.
  - 들고 있는 작업이 있으면 `holding` 에 그 배정, `items` 는 빈 배열. 줄이 없으면 빈 items(409 아님).
- 화면 번호(주문 번호·TRF 번호)·상품 수·첫 상품명·구역은 **넣지 않았다**(사용자 "가볍게" — 줄마다 다른 서비스 조회가 필요). taskType 과 since 로 그리고, 상세는 맡은 뒤 detail 로.

### `POST /mfcs/{mfcId}/workers/{workerId}/task-claims` 본문 `{ taskId, workArea? }`, Idempotency-Key·X-Source-System
- 성공 201 = next-tasks 와 같은 `Assignment`(detail 포함, source SYSTEM).
- 들고 있는 작업이 바로 그 작업이면 200(다시 보내도 같다).
- 409: `ALREADY_HOLDING`(다른 작업을 들고 있음) · `OWN_TASK_FIRST`(먼저 처리할 내 작업이 있음 — 메시지에 그 taskId) · `TASK_TAKEN`(그새 다른 사람이 맡음 — 목록 새로고침) · `NOT_AVAILABLE`(끝났거나 업무 밖 등) · `OFF_SHIFT`·`ON_BREAK`.
- 422: `WORK_AREA_NOT_ALLOWED` 등 next-tasks 와 같음.

## DC
- `POST/GET /cdcs/{cdcId}/workers/{workerId}/…` 는 cdc-outbound(PC 1) 몫 — 같은 결정(위 20개·자기 작업 먼저·가볍게)으로 PC 1 에 전달했다.

## 상태
- 백엔드 PR(이 회신과 같은 PR) — PC 1 병합·배포 뒤 실서버. 그 전에는 목으로.
