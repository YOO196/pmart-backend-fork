# 회신: BR-52 재고 쪽 — R14(실사 계획·서버 잠금)·R19-6(실사 목록 밖 상품)·R19-7(실사 예외)·R15(LOT 표기 없음)

- 보낸 영역: mfc
- 작성: pmart-backend, 2026-11-20
- 답하는 것: pmart-clients `docs/handoff/mfc-app/2026-11-10-backend-pda-work-areas.md` R14·R15·R19-6·R19-7(앞 회신 `2026-11-14-reply-br52-r2-r19-1.md` §2·§6 의 남은 것).
- 계약: `mfc-inventory-service.yaml` **0.34.0**(PR 병합 뒤 main 에서 `gen:api`). LAN 반영은 PC 1 배포 뒤 — 새 경로 `/mfcs/{mfcId}/count-plans/**` 는 게이트웨이 라우트(pmart-config)가 들어가야 LAN 에서 404 가 아니다.
- R16(documentNo)은 이번에 하지 않았다.

## 0. 요약

| # | 답 |
|---|---|
| R14 | **사용자 결정(2026-11-20) "관리자가 계획·서버 잠금"대로 구현.** 센터 관리자가 칸을 고르면 서버가 칸마다 실사를 만들고 그 칸을 잠근다(mfc-location 잠금 필요 없음). 끝나거나 취소되면 잠금이 저절로 풀린다. **작업자에게 실사를 주는 것(다음 작업 받기 `CYCLE_COUNT`)은 아직** — mfc-capacity 몫으로 따로(§1-4) |
| R19-6 | 계수 제출에 `unexpectedLines[]` 추가 — 바코드/SKU·소비기한(또는 LOT 라벨)·수량 |
| R19-7 | 실사 예외 보고 `POST …/count-sessions/{id}/exceptions` — `LOCATION_INACCESSIBLE`·`DAMAGE_FOUND`. 이동·보충·적치·복귀의 예외는 이번에 안 함(mfc-inbound 몫, §3) |
| R15 | 코드 변경 없음. 지금 규칙은 §4 — **표기 없이 온 정상 판정은 막지 않는다**(판매 가능으로 간다). 식품 LOT 불명 차단을 MFC 에 적용할지는 사용자 결정 필요 |

## 1. R14 실사 계획·서버 잠금 (mfc-inventory 0.34.0)

### 1-1. 흐름
1. **계획** `POST /mfcs/{mfcId}/count-plans` — 그 센터 `MFC_SUPERVISOR` 만(작업자 403). 본문 `locationCodes`(1~200)·`reason`. 응답 `CountPlan`(201).
   - 칸마다 실사(`CountSession`, 상태 COUNTING) 하나. 기준 시각에 그 칸의 판매 단위 장부·조사 보류를 고정한다(지금 수동 실사와 같다 — 계수표에 수량 없음).
   - 모두 또는 전무: 모르는 칸 422 `LOCATION_NOT_REGISTERED`, 폐지 칸 409 `LOCATION_CLOSED`, 진행 중 재고 이동 409 `MOVE_IN_PROGRESS`, 열린 실사에 이미 있는 칸 409 `COUNT_IN_PROGRESS`.
2. **서버 잠금** — 계획 실사가 끝나지 않은 동안(COUNTING·RECOUNT_REQUIRED·PENDING_APPROVAL) 그 칸을 출발·도착으로 하는 재고 이동·보충, 적치·피킹 등 작업 실행은 **409 `LOCATION_CLOSED`**(mfc-location `COUNT_LOCKED` 와 같은 효과). 잠금 순서 꼬임(R14 ③ — 세션 전에 잠가야 하는데 잠금이 세션 ID 를 받음)은 서버가 한 번에 하므로 없어졌다.
3. **계수** — 지금처럼 `POST …/count-sessions/{sessionId}/counts`. 첫 계수가 다르면 **다른 작업자**가 재계수(같은 사람 409 `SAME_COUNTER`).
4. **승인** — 지금처럼 `…/approvals`(MFC_SUPERVISOR). **계획한 사람은 그 실사의 시작자(`createdBy`)라 승인할 수 없다**(422 `SELF_APPROVAL` — 요청자·승인자 분리 규칙 그대로). 다른 관리자가 승인한다(§5 미결 1).
5. **잠금 해제는 자동** — 실사가 MATCHED·ADJUSTED·REJECTED·CANCELLED 가 되는 순간 그 칸이 풀린다. 시간 만료 해제는 없다(업무 문서 IV-11 "잠금 시간 만료로 무조건 해제 금지").
6. **취소** — `POST /mfcs/{mfcId}/count-plans/{planId}/cancellation`(끝나지 않은 실사를 모두 CANCELLED, 재고 변화 없음 → 잠금 풀림), 실사 하나만 `POST …/count-sessions/{sessionId}/cancellation`. 둘 다 MFC_SUPERVISOR. 끝난 실사의 취소는 409 `INVALID_STATE`.

### 1-2. 조회
- `GET /mfcs/{mfcId}/count-plans/{planId}` — `state`(OPEN = 끝나지 않은 실사 있음 / COMPLETED / CANCELLED), `lockedLocationCodes`(지금 잠근 칸), `sessions[]`(칸·실사·상태·예외 수).
- `CountSession` 에 `planId`(수동 시작은 null)·`exceptions[]`·`unresolvedLines`·`cancelReason`, 상태에 **`CANCELLED`** 추가 — 상태를 enum 으로 받는 화면은 새 값을 처리해야 한다.
- 실사 목록 `GET …/count-sessions?state=CANCELLED` 도 된다.

### 1-3. 수동 실사(예전 흐름)
- `POST …/count-sessions`(작업자·관리자가 칸을 골라 시작, mfc-location `COUNT_LOCKED` 필요)는 그대로 둔다. 앱은 R14 결정대로 **계획 실사만** 쓰면 된다(작업자가 칸을 고르지 않음).

### 1-4. 작업자에게 주기 — 아직(mfc-capacity)
- 서버 쪽 준비: `GET /internal/count-work?mfcId=` — 계획 실사 중 계수 단계이고 예외 보고가 없는 것, 기준 시각 오래된 순. `excludedOperatorIds` = 셀 수 없는 사람(재계수면 첫 계수자).
- 다음 작업 받기에 `CYCLE_COUNT`(taskId = sessionId, 업무 `STOCK_COUNT`)를 넣는 일은 mfc-capacity 요청 **MC-1**(`docs/architecture/service-requests/mfc-capacity-service.md`)로 따로 한다. 그 전까지 `STOCK_COUNT` 는 workAreas 에 없다.
- 그때까지 PDA 시험은 관리자 계획 → `GET /mfcs/{mfcId}/count-plans/{planId}` 의 sessionId 로 계수 화면을 여는 방식이면 된다.

## 2. R19-6 실사 목록 밖 상품 (`unexpectedLines[]`)

`POST …/count-sessions/{sessionId}/counts` 본문:

```json
{
  "lines": [{ "locationCode": "A-01-01", "sku": "E-…", "lotId": "LOT-…", "countedQty": 7 }],
  "unexpectedLines": [
    { "locationCode": "A-01-01", "barcode": "8801115114154", "expiryDate": "2027-01-31", "qty": 2 },
    { "locationCode": "A-01-01", "sku": "E-…", "lotId": "LOT-…", "qty": 1 }
  ]
}
```

- 줄마다 `locationCode`(이 실사의 칸)·`qty`(1 이상), `sku` 또는 `barcode` 하나는 필수(없으면 400). LOT 은 LOT 라벨을 찍었으면 `lotId`, 아니면 `expiryDate`.
- 서버 판정:
  - SKU: `sku` 가 있으면 그것, 없으면 바코드로 SKU 사본에서 찾는다(선행 0 그대로 비교).
  - LOT: `lotId` 가 있으면 그것(그 SKU 의 LOT 이 아니면 400). 없으면 그 SKU 에서 **소비기한이 같은 LOT 이 하나뿐일 때만** 그 LOT.
  - 정해지면 `lines` 의 줄과 똑같이 센다(장부 0 → 찾은 수량만큼 차이, 승인되면 조정).
  - 정하지 못하면(모르는 바코드 `SKU_UNKNOWN` / 그 기한 LOT 없음·기한 없음 `LOT_UNKNOWN` / 같은 기한 LOT 여럿 `LOT_AMBIGUOUS`) **조정하지 않고** `unresolvedLines` 로 남긴다. 그런 줄이 있으면 장부와 같아도 MATCHED 가 아니다(첫 계수 → 재계수, 재계수 → 승인 대기) — 관리자가 보게.
- `unresolvedLines` 는 `differences` 처럼 계수가 끝난 뒤에만 보인다(재계수자는 첫 계수를 보지 않는다).
- 정하지 못한 물건을 재고로 만드는 방법(입고·LOT 등록)은 정해지지 않았다(§5 미결 2) — 지금은 관리자 확인용 기록.

## 3. R19-7 실사 예외 보고

`POST /mfcs/{mfcId}/count-sessions/{sessionId}/exceptions`(MFC_WORKER·MFC_SUPERVISOR, Idempotency-Key 한 번):

```json
{ "reason": "LOCATION_INACCESSIBLE", "locationCode": "A-01-01", "note": "카트가 막음" }
{ "reason": "DAMAGE_FOUND", "locationCode": "A-01-01", "sku": "E-…", "lotId": "LOT-…", "qty": 2 }
```

- 계수 단계(COUNTING·RECOUNT_REQUIRED)만(아니면 409 `INVALID_STATE`), 칸은 이 실사의 칸(아니면 400).
- **재고는 바꾸지 않는다.** 기록(`CountSession.exceptions[]`)하고 센터 관리자에게 넘긴다 — 예외가 있는 실사는 작업 후보(`/internal/count-work`)에서 빠진다. 관리자는 실사를 취소(잠금 풀림)하거나, 막힘이 풀린 뒤 누가 계수를 내면 그대로 이어진다.
- 파손품 격리는 품질 절차(mfc-quality)로 따로. 실사 중 격리하면 장부가 바뀌어 승인이 409 `BOOK_CHANGED` 가 된다 — 실사를 취소하고 격리한 뒤 다시 계획하는 순서.
- 요청 원문의 "출발 칸에 없음·모자람"은 실사에서는 센 수량(0 포함)으로 들어오므로 예외 사유를 따로 두지 않았다. "도착 칸 못 씀(가득·막힘)"은 실사에 해당 없음.
- **이동·보충·적치·복귀의 예외 보고는 이번에 안 했다** — 그 작업들의 주인은 mfc-inbound(이동 지시·입고 작업)라 따로 한다(§6).

## 4. R15 — LOT 표기(sourceLotRef) 없이 온 정상 판정 (확인 ①·②)

지금 코드·계약 그대로의 규칙(바꾸지 않음):

1. 품질검사(INSPECT) 줄에 `sourceLotRef`·`expiryDate`·`manufactureDate` 가 **하나라도 있으면** 그 값의 추적 LOT 으로 나눈다(같은 SKU·표기·기한·제조일 = 같은 LOT). 하나도 없으면 **수령 때의 LOT 그대로**(수령도 표기·기한이 없었으면 추적 미확인 `UNTRACKED` LOT — 수령마다 하나).
2. 서버는 표기 없는 정상 판정을 **막지 않는다** — 정상(NORMAL)이면 적치 뒤 판매 가능 수량(E)에 들어간다. 소비기한만 보내면 기한으로 LOT 이 나뉘고 LOT 번호는 서버가 매긴다(`L`+날짜+순번, 작업자가 만들지 않음).
3. 예약(주문 배정) 때: **소비기한 없는 LOT** 은 상품이 잔여일을 보장하면(상품 정보 `shelfLifeGuarantee`) 배정하지 않고, 보장이 없으면 배정한다. 제조 후 경과일 보장이 있으면 제조일 모르는 LOT 은 배정하지 않는다(ADR-0041). 즉 지금 "LOT 불명"으로 막히는 것은 **기한을 판정할 수 없는 경우**뿐이고, **표기(sourceLotRef)가 없다는 것만으로는 막히지 않는다.**
4. ② 제조사·공급사 값: inventory 에는 그런 칸이 없다(LOT 라벨 회신 2026-11-17 과 같음). 수령(TRF) 쪽에서 채울 계획도 아직 없다.

- "식품의 LOT 불명 수량은 판매 가능으로 전환하지 않는다"는 근거 문구는 CDC 업무 문서(`WMS-business-logic.md` 16.4.1 "식품인데 LOT 불명 → 정상 출고 차단")에 있고 MFC 문서에는 없다. 그리고 inventory 는 **상품이 식품인지 모른다**(상품 사건에 식품 구분이 없음). 그래서 이번에 바꾸지 않았다 — §5 미결 3.
- PDA 는 개정안대로(표기는 스캔, 없으면 "LOT 표기 없음" 확인 뒤 `sourceLotRef` 없이 + 소비기한) 보내면 된다. 소비기한을 보내면 3번 판정이 된다.

## 5. 미결(사용자 결정 필요 — 추측해 구현하지 않음)

1. **계획한 관리자의 승인**: 지금 규칙(시작자·계수자는 승인 불가)을 그대로 적용해 계획한 관리자는 그 실사의 차이를 승인할 수 없다. 관리자가 한 명인 센터는 다른 센터 관리자·본사가 승인해야 한다. 계획은 "요청"이 아니라고 보고 계획한 사람의 승인을 허용할지.
2. **목록 밖에서 찾은, LOT 을 정하지 못한 물건**의 처리(입고·LOT 등록·격리 중 무엇으로, 누가).
3. **식품 LOT 불명 차단을 MFC 에 적용할지**(적용하면 상품에 식품 구분이 필요 — product-service 요청).
4. 실사 예외 뒤 다시 세는 방법 — 지금은 관리자가 취소 후 다시 계획하거나 그대로 계수. 예외를 "해결됨"으로 닫아 다시 작업 후보로 돌리는 경로가 필요한지.
5. 다음 작업 순서에서 실사의 자리(MC-1).

## 6. 남은 것

- MC-1(mfc-capacity): 다음 작업 받기에 `CYCLE_COUNT`.
- 이동·보충·적치·복귀 예외 보고(R19-7 나머지, mfc-inbound).
- 피킹 위치 배정(`location-allocations`)이 잠긴 칸을 아직 고를 수 있다(피킹 실행은 409 로 막힘 — mfc-location COUNT_LOCKED 와 같은 지금 동작). 잠긴 칸을 배정에서 빼는 것은 다음에.
- 칸 지도에 계획 잠금 표시(ML-1, `mfc-location-service.md`) — 화면 요청이 오면.
- R5~R7·R12·R13·R16~R18·R19 2·8·9·R20 은 각 회신·요청서 참고.

## 7. 시험(PC 3, 2026-11-20)

- mfc-inventory `check` + `integrationTest`: 단위 62·통합 34 모두 통과. api-gateway `test` 41 통과(허용 목록 다시 만듦).
- 새 통합 시험 `StockReservationFlowIT.countPlanLocksLocationsUntilSessionsEndAndTakesUnexpectedLines` — 작업자 계획 403·모르는 칸 422·mfc-location 잠금 없이 계획·겹침 409·잠긴 칸 이동 409 LOCATION_CLOSED·capacity 후보·예외 보고 뒤 후보 제외·목록 밖 줄(바코드+기한 → LOT, 모르는 바코드 → unresolved)·재계수 후보 첫 계수자 제외·계획자 승인 거절·다른 관리자 승인 반영·잠금 풀림·계획 취소.
