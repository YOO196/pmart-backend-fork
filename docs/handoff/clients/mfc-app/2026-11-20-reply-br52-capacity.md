# 회신: BR-52 배정 쪽 — R2 나머지(재고 실사·회수품 반입·회수품 검수)·R5(메뉴)·R6(작업 가능 신호)

- 보낸 영역: mfc
- 작성: pmart-backend, 2026-11-20
- 답하는 것: pmart-clients `docs/handoff/mfc-app/2026-11-10-backend-pda-work-areas.md` R2(앞 회신 `2026-11-14-reply-br52-r2-r19-1.md` §2 의 남은 것 — 회수품 반입·회수품 검사·재고 실사)·R4(묶음)·R5·R6·R12 1·R13(배정 부분)·R14 ②.
- 계약: `mfc-capacity-service.yaml` **0.24.0**(PR 병합 뒤 main 에서 `gen:api`). 새 경로 없음(게이트웨이 변경 없음). LAN 반영은 PC 1 배포 뒤.
- 기대는 것: 실사는 mfc-inventory 0.34.0(`GET /internal/count-work`, 회신 `2026-11-20-reply-br52-inventory.md`), 회수품은 mfc-inbound 0.13.0(반납 도착 대기 `GET /return-arrivals`)·0.12.0(`GET /return-receipts`).
- R16(documentNo)은 이번에 하지 않았다.

## 0. 요약

| # | 답 |
|---|---|
| R2 재고 실사 | **구현.** 업무 `STOCK_COUNT` → 작업 유형 `CYCLE_COUNT`, taskId = 실사 `sessionId` |
| R2 회수품 반입 | **구현.** 업무 `STOCK_MOVE` 에 `RETURN_RECEIPT`, taskId = 반납 도착 `arrivalId` |
| R2 회수품 검사 | **구현.** 업무 `INSPECTION` 에 `RETURN_INSPECT`, taskId = `returnReceiptId` |
| 순서 | 같은 업무의 기존 유형 **뒤**(임시 — 사용자 결정 필요, §4) |
| R5 메뉴 | **사용자 결정(2026-11-20): 인계 확인·프린터 설정 메뉴는 작업자(MFC_WORKER)도 본다.** 백엔드 변경 없음 — §2 |
| R6 작업 가능 신호 | **API 를 만들지 않는다.** 다음 작업 받기 호출이 그 신호다 — §3 |

## 1. R2 — 다음 작업 받기에 실사·회수품 반입·회수품 검수 (mfc-capacity 0.24.0)

### 1-1. 작업 유형·업무 묶음
| 업무(`workArea`) | 작업 유형 | taskId | 어디서 오나 |
|---|---|---|---|
| `INSPECTION` | INSPECT, **RETURN_INSPECT** | `returnReceiptId` | mfc-inbound 반입 중 재고 반영(APPLIED)·검수 대기(`inspectionState` PENDING), 반입 오래된 순 |
| `STOCK_MOVE` | PUTAWAY·REPLENISH·MOVE·RETURN, **RETURN_RECEIPT** | `arrivalId`(= 회수 배송 ID) | mfc-inbound 반납 도착 대기(WAITING), 도착 오래된 순 |
| `STOCK_COUNT` | **CYCLE_COUNT** | `sessionId` | mfc-inventory 계획 실사(계수 단계·예외 보고 없음), 기준 시각 오래된 순 |

- `TaskType` 에 세 값이 늘었다. 작업자 응답 `workAreas` 는 지금처럼 "묶음이 유효 업무와 겹치는 업무" — 그래서 **세 유형을 업무·자격에 가진 작업자에게만** `STOCK_COUNT` 등이 나온다.
- **기존 작업자에게 자동으로 더하지 않았다.** 센터 관리자가 자격(`qualification-changes`)·업무(`duty-changes`)에 `CYCLE_COUNT`·`RETURN_RECEIPT`·`RETURN_INSPECT` 를 넣어야 받는다(백오피스 작업자 화면의 작업 유형 목록에 세 값이 필요 — 생성 클라이언트 enum). 자동으로 넣을지는 §4 미결 2.

### 1-2. 받은 작업으로 할 일(실행은 각 서비스 — 지금 계약 그대로)
| 유형 | 앱이 부를 것 |
|---|---|
| RETURN_RECEIPT | mfc-inbound `POST /return-receipts` 본문 `arrivalId` = taskId(+ `toLocationCode` 반품 칸) — 줄 생략 가능(대기 건 줄 그대로) |
| RETURN_INSPECT | 반입 조회 `GET /return-receipts/{taskId}` → mfc-quality `POST /return-inspections`(`returnReceiptId` = taskId) |
| CYCLE_COUNT | mfc-inventory `GET /mfcs/{mfcId}/count-sessions/{taskId}`(계수표) → `POST …/counts`(예외는 `…/exceptions`) |

### 1-3. `detail`
- `CYCLE_COUNT`: 새 필드 **`locationCodes`**(셀 칸 — 계획 실사는 칸 하나), `lines` 는 빈 배열(계수표는 실사 조회로).
- `RETURN_RECEIPT`: 대기 건 줄마다 `sku`·`productName`·`qty`. **`sku` 는 null 일 수 있다**(배송 사건에 상품 코드가 없던 예전 반납 — DE-8 전). 반품 칸(`toLocation`)은 없다(현장에서 고름).
- `RETURN_INSPECT`: 반입 줄마다 `sku`·`lotId`(재고 반영 LOT)·`qty`, `fromLocation` = 반입한 반품 칸, `notice` = 반입 때 적은 상태 메모(`conditionNote`). 상품명은 없다(반입 조회에도 아직 없음).
- 조회가 안 되면 지금처럼 `available=false`(배정은 그대로).

### 1-4. 한 사람에게만 · 끝남 판정
- 세 정본(mfc-inbound 반납 대기·반입, mfc-inventory 실사)은 **담당을 모른다** — 이 서비스의 배정이 "한 번에 한 사람"을 지킨다: 누가 들고 있으면(ACTIVE 배정) 다른 사람에게 주지 않는다. 대신 실행 서비스가 담당 아닌 사람의 실행을 막지는 않는다(입고 작업·이동의 409 `NOT_ASSIGNEE` 같은 검사 없음).
- 들고 있는 작업 판정(다시 부르면 200): 반납 대기가 아직 WAITING / 반입이 아직 검수 대기 / 실사가 아직 계수 단계이고 **내가 셀 수 있는 사람**일 때. 반입·검수·계수를 내서 목록에서 빠지면 끝난 것 → 다음 호출에 새 작업.
  - 검수 결과는 사건(`ReturnInspected`)으로 mfc-inbound 에 닿는다 — 닿기 전 잠깐은 검수한 반입이 아직 들고 있는 작업으로 돌아올 수 있다(다시 검수하면 mfc-quality 가 거절). 짧게 기다렸다 다시 부르면 된다.
  - 반입이 재고에서 거절되면 그 대기 건은 WAITING 으로 돌아오고, 들고 있던 사람에게 그대로 남는다.
- **실사 재계수**: 첫 계수가 장부와 다르면(RECOUNT_REQUIRED) 첫 계수자는 `excludedOperatorIds` 에 든다 → 그 사람에게는 들고 있는 작업으로도, 새 작업으로도 주지 않고, 다른 사람이 받을 때 첫 계수자의 배정을 닫는다(RELEASED). 그래서 같은 사람에게 409 `SAME_COUNTER` 가 나지 않는다.
- 실사 주소(`pmart.capacity.activities.mfc-inventory-base-url`)가 없거나 조회가 안 되면 실사는 건너뛴다(업무 `STOCK_COUNT` 만 골랐으면 409 `NO_TASK_AVAILABLE`). 회수품도 조회가 안 되면 그 종류만 건너뛴다.

### 1-5. 한계
- **검수 대기 순서**: mfc-inbound 가 `inspectionState=PENDING` 거르기(오래된 순)를 아직 배포하지 않았다(별도 PR 진행 중). 그 전에는 **최근 반입 200건 안에서만** 검수 대기를 찾는다 — 그보다 오래된 검수 대기는 다음 작업 받기에 안 나온다(센터 관리자 화면에서는 보임). 거르기가 들어가면 자동으로 오래된 순 전체(앞 200건)가 된다(capacity 코드 변경 없음).
- 반납 대기·검수 대기는 앞 200건, 실사는 mfc-inventory 가 주는 최대 500건 안에서 고른다.
- 수동 배정(`POST /assignments`)도 세 유형을 받는다(taskId 존재는 확인하지 않음 — 지금 규칙 그대로).
- 배정 추천(OR-Tools)에는 넣지 않았다(입력은 주문 작업·이동 그대로 — 입력 지문 불변).

## 2. R5 — 인계 확인·프린터 설정 메뉴 (사용자 결정 2026-11-20)
- 결정: **두 메뉴는 작업자(MFC_WORKER)에게도 보인다**(센터 관리자 MFC_SUPERVISOR 와 함께). 앱 가정("둘 다 PACK 자격")은 버리고 역할로 — 그 센터 운영자면 보이게.
- mfc-capacity 에는 메뉴 권한 개념이 없다(작업자 응답은 업무 `workAreas` 만). 그래서 **백엔드 변경 없음** — 앱이 메뉴를 늘 보이면 된다.
- 쓰는 API 가 작업자를 받는지 확인함:
  - 인계 확인 — mfc-fulfillment `GET /packages?mfcId=&checkRequired=true`·`POST /packages/{packageId}/handover-checks`: **그 센터 MFC_WORKER 또는 MFC_SUPERVISOR**(0.17.0 MB-3). `GET /packages/{packageId}/handover-resolution` 은 운영자·라이더.
  - 프린터 설정 — 백엔드 API 가 없다(단말 설정). 할 일 없음.

## 3. R6 — "작업 가능" 신호
- **만들지 않는다.** 지금 계약으로 이미 되는 것:
  - "업무 설명 확인 → 작업 가능" = **다음 작업 받기 호출**(`workArea` 포함). 작업이 있으면 바로 배정되고, 없으면 409 `NO_TASK_AVAILABLE`(재시도 가능) — 앱은 몇 초 간격으로 다시 부르면 그것이 "대기" 상태다.
  - 일할 수 없는 상태는 서버가 이미 안다: 휴게(`onBreak`, 409 `ON_BREAK`), 근무 밖(409 `OFF_SHIFT`), 정지·퇴사·일일크루 미등록(422).
- 서버에 "대기 중" 상태를 따로 남기는 것(배정 추천의 작업자 후보·관리자 감시 화면에서 "지금 노는 사람")은 요청에 쓰임새가 정해지지 않았다 — 배정 추천은 지금 ACTIVE·근무 안 작업자를 쓰고, 감시는 작업자 오늘 내역(`/activities`)과 배정 목록으로 본다. 관리자 화면에 "대기 중" 표시가 필요하면 화면·기준(마지막 호출 몇 분 안 등)을 정해 다시 요청해 주세요.

## 4. 미결(사용자 결정 필요)
1. **순서**: 지금 `주문(PICK·PACK) → 보충·긴급 이동 → 품질검사 → 적치 → 관리자 이동 → 복귀 → 회수품 반입 → 회수품 검수 → 재고 실사`. 새 셋은 같은 업무 안에서 기존 유형 뒤(품질검사 업무: 입고 품질검사 → 회수품 검수, 재고 이동 업무: … 복귀 → 회수품 반입). 업무를 안 골랐을 때만 셋 사이 순서가 의미 있다. Q-08 전 임시.
2. **기존 작업자에게 자동으로 줄지**: 지금은 센터 관리자가 자격·업무에 넣어야 한다. 예) INSPECT 자격이 있으면 RETURN_INSPECT 도, PUTAWAY 가 있으면 RETURN_RECEIPT 도 자동으로 — 원하면 결정.
3. **실행 서비스의 담당 검사**: 회수품 반입·검수·실사는 담당 아닌 사람의 실행을 막지 않는다(입고 작업·이동은 409 NOT_ASSIGNEE). 막으려면 mfc-inbound·mfc-quality·mfc-inventory 가 배정 사건을 투영해야 한다.

## 5. 시험(PC 3, 2026-11-20)
- mfc-capacity 단위 58 통과(Docker `tools/ortools-test-docker.sh` — OR-Tools 포함). 같은 단위를 Windows 에서 돌리면 CpSatPlanner 4건이 `ortools.dll` 차단(알려진 제약)으로 실패.
- 통합 40 중 36 통과 — 새 시험 `nextTaskGivesReturnWorkAndCycleCountsToOnePersonEach`(회수품 반입 한 사람만·검수 대기만·상세, 실사 첫 계수자 제외 → 재계수는 다른 사람·첫 계수자 배정 RELEASED) 통과. 실패 4건은 배정 추천(OR-Tools) 시험으로 같은 DLL 차단 — PC 1 이 병합 전에 돌린다.

## 6. 덧붙임(2026-11-20 저녁) — 입고(RECEIVE)도 다음 작업 받기에 (mfc-capacity 0.25.0)

- 기대는 것: mfc-inbound **0.16.0 입고 예정 구현**(`/inbound-promises/*` 501 해제 — 별도 PR). 그 전(0.15.0)에는 목록이 501 이라 입고만 건너뛴다(다른 작업은 그대로).
- 계약 `mfc-capacity-service.yaml` 0.25.0 — 새 경로·필드 없음(설명만). LAN 반영은 PC 1 배포 뒤. **원천 사건(replenishment·cdc)을 내는 서비스가 아직 없어 실서버 입고 예정은 비어 있다** — 실제로 RECEIVE 가 나오는 것은 그 뒤.

### 6-1. 무엇을 주나
| 업무(`workArea`) | 작업 유형 | taskId | 어디서 오나 |
|---|---|---|---|
| `INBOUND` | RECEIVE | `trfId`(BR-53 R9 그대로) | mfc-inbound `GET /inbound-promises?mfcId=`(끝나지 않은 EXPECTED·RECEIVING) |

- **받을 수 있는 입고**(보수적으로): 출발(TOUT, `departureId`)을 받은 예정 중
  1. `EXPECTED` — 차가 떠났다. 도착 등록부터 한다.
  2. `RECEIVING` — 도착 등록됨. 단 **재고 반영 전 수령**(`receipts[].inventoryApplyStatus` 가 CAPTURED·SUBMITTED·PROCESSING·UNKNOWN_RESULT)이 있으면 주지 않는다(그 수령을 낸 사람이 같은 Idempotency-Key 로 마저 — mfc-inbound 도 새 수령은 409). 재고 거절(REJECTED) 수령만 있으면 고쳐서 다시 — 다시 준다.
  - 출발 전(승인만, `departureId` null) 예정은 주지 않는다 — 실물이 없고 mfc-inbound 도 출발 전 수령은 409.
- **한 사람에게만**: 입고 예정도 담당을 모른다 — 이 서비스 배정(ACTIVE 하나)이 지킨다(회수품과 같음). mfc-inbound 도착·수령 등록은 담당을 검사하지 않는다.
- **들고 있음** = 그 예정이 아직 EXPECTED·RECEIVING 목록에 있음. 수령이 재고에 반영(TIN_POSTED)되거나 취소(CANCELLED)되면 끝난 것 → 다음 호출에 새 작업(이어서 생기는 품질검사·적치는 따로 후보).
- 조회가 안 되면(주소 없음·실패·501) 입고만 건너뛴다. 업무 `INBOUND` 만 골랐으면 409 `NO_TASK_AVAILABLE`.

### 6-2. 순서
- 같은 유형은 **예정 도착(`plannedArrivalAt`) 이른 순**(없으면 출발 시각), 같으면 trfId.
- 업무를 안 골랐을 때: `주문(PICK·PACK) → 보충·긴급 이동 → **입고** → 품질검사 → 적치 → 관리자 이동 → 복귀 → 회수품 반입 → 회수품 검수 → 재고 실사`. 입고 흐름(수령 → 품질검사 → 적치, BR-52 R2) 순서대로 품질검사 앞에 두었다(임시 — §4 미결 1 에 같이).

### 6-3. 받은 작업으로 할 일(실행은 mfc-inbound — 지금 계약 그대로)
1. `GET /inbound-promises/{taskId}` — 상태·줄·LPN(`packages`)·version.
2. `EXPECTED` 면 `POST /inbound-promises/{taskId}/arrivals`(송장 라벨 `TRF:{trfId}:{version}` 은 trfId 만 비교, `X-Expected-Version`).
3. `POST /inbound-promises/{taskId}/receipts`(줄마다 `toLocationCode` 입고대 필수, 차이 사유 규칙 BR-53 R11) → APPLIED 면 끝(품질검사 작업이 생긴다), REJECTED 면 고쳐서 새 키로, UNKNOWN_RESULT 면 같은 키로 다시.

### 6-4. `detail`
- RECEIVE: 예정 줄마다 `sku`·`productName`·`lotId`(CDC LOT 표기 — inventory lotId 아님)·`expiresOn`(소비기한)·`qty`(= 예정 − 재고 반영된 인수, 다 받은 줄은 뺌). 수령 위치(`fromLocation`·`toLocation`)는 없다(현장에서 입고대).
- 바코드는 상세에 없다 — 입고 예정 조회의 `lines[].barcodes`.

### 6-5. 미결(사용자 결정 필요)
4. **EXPECTED(출발했지만 아직 도착 전)도 줄지**: 지금은 준다 — 차가 오기 전에 받은 사람은 도착까지 그 작업을 들고 기다린다(한 번에 하나). 도착 등록된 것(RECEIVING)만 주려면 도착 등록을 누가 하는지(하차 담당·센터 관리자)를 정해야 한다 — 그렇게 바꾸면 이 서비스 판정 한 줄.
5. **도착한 차 먼저**: 지금은 상태와 상관없이 예정 도착 순. 하차 중인 차(RECEIVING)를 EXPECTED 보다 먼저 줄지.

### 6-6. 시험(PC 3, 2026-11-20)
- 단위 61 통과(Docker `tools/ortools-test-docker.sh` — 새 `InboundReceiveWorkTest`·`NextTaskDetailsTest.receiveDetailListsRemainingPromiseLines` 포함).
- 통합: 새 시험 `AssignmentPlanIT.nextTaskGivesDepartedInboundPromisesAsReceiveBeforeInspection`(출발 전·진행 중 수령 건너뜀, 예정 도착 순, 한 사람만, 품질검사 앞, TIN_POSTED 뒤 다음 작업) 통과. 통합 41 중 37 통과 — 실패 4건은 배정 추천(OR-Tools) 시험으로 Windows `ortools.dll` 차단(알려진 제약, PC 1 Linux 에서 확인).
