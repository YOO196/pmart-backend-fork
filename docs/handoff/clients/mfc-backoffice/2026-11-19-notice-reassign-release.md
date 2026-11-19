# 알림: 작업 재배정(빼앗기)·내려놓기 — 센터 관리자 화면 지침 (서버 변경 없음, 이미 있음)

- 보낸 영역: mfc (PC 3 백엔드)
- 작성: pmart-backend, 2026-11-19
- 대상: pmart-clients `web/mfc-backoffice`(작업 현황·배정 화면)
- 사용자(2026-11-19): 센터 관리자가 작업자의 작업을 **빼앗아 다른 사람에게 재배정**하거나 **중간에 멈추게** 하는 기능. 업무에 맞는 두 가지는 서버에 이미 있다 — 화면만 붙이면 된다.
- 주문 자체를 취소하는 것은 이 문서 범위가 아니다(claim — 고객센터·시스템, 센터 관리자 취소는 PC 5 "배차 불가로 취소" CL-5 와 같이 별도).

## 1. 다른 작업자에게 재배정(빼앗기)
- `POST /api/v1/assignments`(mfc-capacity) — 그 센터 `MFC_SUPERVISOR` 만.
  - 헤더: `Idempotency-Key`(누를 때마다 새로), `X-Source-System: mfc-backoffice`, **`X-Expected-Version` = 지금 활성 배정 버전**(담당이 없으면 `0`).
  - 본문: `{ mfcId, taskId, taskType, workerId, reason }` — `taskType` 은 작업 목록 값 그대로(PICK·PACK·RETURN·MOVE 등), `reason` 은 200자 이하(화면에서 받기 — 예 "교대", "지연").
  - 결과: 201 새 배정(앞 배정은 SUPERSEDED), 200 = 이미 그 사람이 담당(변화 없음). 응답 `warnings` 에 `OFF_SHIFT` 가 오면 "근무 시간 밖 작업자입니다" 안내만(막지 않음).
- **지금 버전 얻기**: 작업 목록 `GET /api/v1/tasks?mfcId=&state=ASSIGNED&state=IN_PROGRESS`(fulfillment)의 `assignment.assignmentVersion`, 또는 `GET /api/v1/assignments?mfcId=&taskId=`(ACTIVE)의 `assignmentVersion`.
- **고를 작업자 목록**: `GET /api/v1/mfcs/{mfcId}/workers` — `state` ACTIVE·`effectiveTaskTypes` 에 그 작업 유형이 있는 사람만 고르게, `onBreak` 면 "휴게 중" 표시.
- 오류(그대로 안내):
  - 409 `VERSION_CONFLICT` — 그 사이 다른 배정이 생김 → 목록 다시 읽고 다시.
  - 422 `WORKER_NOT_REGISTERED`·`WORKER_NOT_ACTIVE`·`DUTY_NOT_ALLOWED`(그 작업 유형 업무 없음).
  - 403 — 센터 관리자 아님.
- **진행 중 작업을 넘기면**(현장 주의 문구로):
  - 새 담당은 PDA 에서 **다시 "시작"부터**(앞 사람 시작은 지워짐). **이미 집은 수량은 그대로** 남고 남은 양만 이어서 한다.
  - 피킹은 **한 출고 = 한 장바구니** — 앞 사람이 담던 장바구니를 넘겨받아 그 장바구니로 이어서(다른 장바구니면 서버가 409 CART_MISMATCH).
  - 포장(PACK)은 원래 "피킹한 사람만" 자동으로 받는다 — **관리자 재배정은 막지 않는다**(장바구니를 넘겨받는 것은 현장에서 확인).
  - 앞 사람 PDA 는 다음 작업 받기·작업 조회에서 그 작업이 더 이상 자기 것이 아님을 보게 된다(실적을 보내면 서버가 담당 아님으로 거절).

## 2. 작업 내려놓기(중간에 멈추고 대기로)
- `POST /api/v1/tasks/{taskId}/releases`(mfc-fulfillment) — **담당 본인 또는 그 센터 관리자**.
  - 헤더: `Idempotency-Key`, `X-Source-System`.
  - 본문: `{ workerId: <지금 담당>, reason }`(reason 200자 이하).
  - 결과: 200 `TaskSummary` — 작업이 **READY(담당 없음)** 로 돌아가 다음 작업 받기로 다른 사람에게 간다. 완료한 수량은 그대로(다음 사람이 남은 양).
- 오류:
  - 409 `EXECUTION_IN_FLIGHT` — 결과를 기다리는 실적·열린 포장이 있음 → "처리 중인 실적이 끝난 뒤 다시"(잠시 뒤 재시도).
  - 409 `INVALID_STATE` — ASSIGNED·IN_PROGRESS 가 아님(이미 끝났거나 대기).
  - 409 `NOT_ASSIGNEE`(capacity) — `workerId` 가 지금 담당이 아님 → 목록 다시 읽기.
  - 503 — 배정 서비스 응답 없음, 바뀐 것 없음 → 다시.

## 3. 화면 제안
- 작업 현황 목록(진행 중·배정됨)에서 행마다 **[담당 바꾸기]**(작업자 고르기 → 1번) · **[내려놓기]**(사유 → 2번).
- 늦음·멈춤 표시는 작업 목록의 `late`(예상 시간 2배 넘김)·`idle`(30분 실적 없음)·`lastActivityAt` 으로 — 이 행들을 위로 올리면 관리자가 빼앗을 대상을 찾기 쉽다.
- 바꾸기·내려놓기 뒤에는 목록을 다시 읽는다(배정 사건이 fulfillment 에 반영되기까지 잠깐 걸릴 수 있음 — 1~2초 뒤 한 번 더).
