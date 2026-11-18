# 회신: MFC 센터 관리자 웹 — 목록 API(MB-1)·권한(MB-3)·품질 사고(MB-2 질문)

- 보낸 영역: mfc (PC 3 백엔드)
- 작성: pmart-backend, 2026-11-18
- 답하는 것: pmart-clients `docs/handoff/mfc-backoffice/2026-11-17-backend-lists-and-roles.md`(MB-1 9개·MB-2·MB-3).
- PR: 브랜치 `mfc/backoffice-lists` — quality 0.5.0·inventory 0.26.0·inbound 0.12.0·capacity 0.18.0·location 0.8.0·fulfillment 0.17.0. LAN 반영은 PC 1 병합·배포 뒤.

## 1. 목록 (MB-1) — 9개 모두

공통: `size`(기본 50, ≤ 200)·`cursor`(응답 `nextCursor` 를 그대로 — 다음 쪽 없으면 null), 권한 = 그 센터 운영자(단건 조회와 같다), **항목은 단건 응답과 같은 모양**(요약이 아니라 전체). 상태를 비우면 아래 "기본".

| 화면 | 경로(실제) | 기본(state 비우면) | 정렬 |
|---|---|---|---|
| 품질 > 해제 승인 대기 | `GET /quality-decisions?mfcId=&state=` | 끝나지 않은 것 REQUESTED·SUBMITTED·UNKNOWN_RESULT — **해제 승인 대기 = `REQUESTED`**(요청서의 PENDING_APPROVAL 은 없는 값) | 오래된 순 |
| 품질 > 폐기 | `GET /disposal-approvals?mfcId=&state=` | REQUESTED·APPROVED·PARTIALLY_EXECUTED | 오래된 순(요청 시각) |
| 품질 > 회수품 검수 | `GET /return-inspections?mfcId=&state=` | state = 재고 반영 상태, 기본 SUBMITTED·UNKNOWN_RESULT(끝난 검수는 `state=APPLIED`). **검수를 기다리는 반입**은 아래 회수품 반입 목록의 `inspectionState=PENDING` | 오래된 순 |
| 재고 > 순환 실사 | `GET /mfcs/{mfcId}/count-sessions?state=` | COUNTING·RECOUNT_REQUIRED·PENDING_APPROVAL(승인 대기 = `PENDING_APPROVAL`) | 시작(baselineAt) 오래된 순 |
| 입고 > 회수품 반입 | `GET /return-receipts?mfcId=&from=&to=&state=` | 전부(state = 재고 반영 상태) | **최근 순** |
| 입고 > 공급 요청 | `GET /supply-requests?mfcId=&state=` | DRAFT·SUBMITTED·ACKNOWLEDGED(철회는 state 로) | **최근 순**(제출 시각) |
| 입고 > 초기 보유 수령 대기 | `GET /initial-stock-loads?mfcId=&state=` — `approvalId` 는 이제 선택(둘 중 하나 필수, 없으면 400) | EXPECTED·PENDING | 오래된 순. approvalId 로 찾으면 예전대로(쪽 없음) |
| 재고 > 센터 재고 | **새 경로 `GET /mfcs/{mfcId}/stock-units`**(`/stock` 은 상품 하나 기준이라 그대로) — `sku`·`locationCode`·`lotId`·`qualityState`·`workState` 모두 선택 | 수량 > 0 인 단위 행 전부 | 위치 → SKU → LOT. 커서는 불투명 값 |
| 배정 > 지난 추천 | `GET /assignment-plans?mfcId=&date=&state=` | 전부(date = 만든 날 Asia/Seoul) | **최근 순** |

- 센터 재고 행: `locationCode`·`sku`·`productName`·`lotId`·`lotNo`·`expiryDate`·`qualityState`·`workState`·`qty`. LOT 요약(H/Q/E/R)은 상품별 `/stock?sku=`.
- 회수품 반입 단건 응답 스키마에 이름을 붙였다(`ReturnReceipt` — 모양은 그대로). 생성 타입 이름이 바뀔 수 있다(`gen:api` 뒤 확인).
- **게이트웨이**: `/mfcs/*/stock-units` 만 새 라우트가 필요하다(PC 1). 나머지는 기존 라우트에 걸린다.

## 2. 권한 (MB-3) — 요청서 결정 그대로

| # | 결정 | 구현 |
|---|---|---|
| 1 | 확인 필요 포장 목록·확인을 `MFC_SUPERVISOR` 도 | fulfillment 0.17.0 — `GET /packages?checkRequired=true`·`POST /packages/{id}/handover-checks` = 그 센터 MFC_WORKER **또는** MFC_SUPERVISOR. 작업 현황의 "현장 작업자(PDA)만" 안내를 목록·확인 단추로 바꿔도 된다 |
| 2 | 위치·LOT 정정, 칸 등록·수정·닫기·열기·폐지 = `MFC_SUPERVISOR` 만 | inventory 0.26.0 `stock-corrections`, location 0.8.0 `/locations*` 변경 — 아니면 403 `FORBIDDEN`. 조회는 그대로 |
| 3 | 상품 취급 변경 = `MFC_SUPERVISOR` + MD | inventory 0.26.0 `sku-handlings`(단건·일괄) — **그 센터 권한이 있는** MFC_SUPERVISOR 또는 MD. 센터 권한이 없는 본사 MD 는 지금처럼 403(넓히지 않음 — 아래 질문 1) |

## 3. 품질 사고 (MB-2) — 구현 전에 정할 것(사용자 질문)

지금 계약(`/quality-incidents*`, 501)은 **사건으로 자동으로 열리는 조회 전용**이다 — 원천 `sourceEvent` = 온도 이탈(TemperatureExcursion)·결품 보고(LineShortageReported)·리콜(RecallIssued), 상태 OPEN → DECIDED → CLOSED, 영향 LOT·연결된 품질 판정·사건(case) ID. 화면이 요청한 **등록·종결**은 계약에 없고, 업무 문서(`MFC-business-logic.md`)에도 규칙이 없다. 정해야 할 것:

1. 품질 사고를 **센터 관리자가 직접 등록**하나, 사건으로만 자동으로 여나(둘 다면 직접 등록의 종류는)?
2. 온도 이탈·리콜 사건의 **발행자가 아직 없다** — 지금 쓸 수 있는 원천은 결품 보고뿐. 결품 보고마다 사고를 여나, 파손·분실(DAMAGED·SHORT) 같은 일부만인가?
3. 상태 기준: DECIDED = 영향 LOT 에 격리·해제·폐기 판정이 붙으면? CLOSED = 누가(센터 관리자) · 언제(모든 영향 LOT 처리 뒤만)?
4. 고객 영향이 있으면 case-service 사건을 함께 여나(지금 `caseId` 칸만 있음)?
5. (MB-3 3번 관련) 본사 MD 가 **센터 권한 없이** 모든 센터의 상품 취급을 바꿀 수 있어야 하나?

입고 예정(TRF, `/inbound-promises*`)은 요청서대로 나중(간선 입고가 생길 때).

## 4. 시험(PC 3, 2026-11-18)

- quality check 단위 14·통합 4(판정·폐기·검수 목록 — 기본·끝난 것·다른 센터 403)
- inventory check 단위 61·통합 31(실사 목록, 센터 재고 쪽 나누기·거르기·틀린 커서 400·다른 센터 403, 정정 작업자 403, 상품 취급 작업자 403)
- inbound check 단위 5·통합 12(공급 요청·회수품 반입 기간·초기 보유 센터로·둘 다 없으면 400)
- capacity `AssignmentsIT` 4(지난 추천 — 최근 순·상태·쪽·403·400), `AssignmentPlanIT` 에도 단언(OR-Tools 라 PC 3 에서는 안 돎 — PC 1 Linux)
- location check 단위 6·통합 5(작업자 칸 등록 403), fulfillment check 단위 20·통합 28(관리자 확인 필요 목록, 역할 없는 운영자 403)
- 게이트웨이 허용 목록(GET 8줄)

## 5. 병합·배포(PC 1)

- 한 PR. 배포 순서 상관없음(서로 부르지 않음). 게이트웨이: 허용 목록 + `/api/v1/mfcs/*/stock-units` 라우트(inventory).
- MB-3 는 좁히는 변경이다 — LAN 에서 역할 없는 시험 계정으로 정정·칸 변경·상품 취급을 부르던 스크립트가 있으면 403 이 난다(PC 3 `seed/mfc_layout.py` 는 `claude-mfc-sup` 라 괜찮음).
