# 회신: 송장(TRF) 입고 예정 서버 구현 — BR-53 남은 것(입고 예정 501 해제·R17 일부)

- 보낸 영역: mfc
- 작성: pmart-backend, 2026-11-20 (PC 3)
- 답하는 것: pmart-clients `docs/handoff/mfc-app/2026-11-10-backend-pda-work-areas.md` 의 BR-53 남은 것 — `/inbound-promises/*` 서버 구현(501), R17(차량·박스 단위).
- 계약: `mfc-inbound-service.yaml` **0.16.0**(브랜치 `mfc/inbound-promises`, PR 병합 뒤 main 에서 `gen:api`). LAN 반영은 PC 1 배포 뒤. 앞 회신 `2026-11-13-reply-br53-inbound-receiving.md`(R8~R11)의 규칙은 그대로다.

## 0. 먼저 — LAN 에서는 목록이 비어 있다

사용자 결정(2026-11-20 "먼저 만들어")으로 서버를 먼저 만들었다. 입고 예정은 **replenishment·cdc-inventory·cdc-outbound 사건으로만 생기는데 그 서비스들이 아직 없다.** 그래서 실서버 `GET /inbound-promises` 는 빈 목록이다.
송장 입고 화면은 **목을 유지**하되, 실서버로 바꿀 때 아래 모양을 그대로 쓰면 된다. 재고를 넣어 볼 일이 있으면 지금처럼 초기 보유 반입(`/initial-stock-loads`)으로.

## 1. API (모두 그 센터 운영자 — 다른 센터 403)

| 일 | API | 결과 |
|---|---|---|
| 목록 | `GET /inbound-promises?mfcId=&state=&date=&size=&cursor=` | state 비우면 **EXPECTED·RECEIVING**. date = 예정 도착(없으면 출발 시각)의 서울 날짜. 예정 도착이 이른 순, `page.nextCursor` |
| 상세 | `GET /inbound-promises/{trfId}` | 줄·LPN 목록·수령들 |
| 도착 등록 | `POST /inbound-promises/{trfId}/arrivals` `{arrivedAt, tripId?, unloadingBayReservationId?, evidenceRef?}` | 202, EXPECTED → RECEIVING. EXPECTED 가 아니면 409 INVALID_STATE. 기록자는 토큰 사용자 |
| 수령 등록 | `POST /inbound-promises/{trfId}/receipts` | 202 `status` APPLIED / REJECTED / UNKNOWN_RESULT, `resultId` = receiptId |
| 수령 결과 | `GET /inbound-promises/{trfId}/receipts/{receiptId}` | inventoryApplyStatus·commitId·차이·rejectReason |

`X-Expected-Version` 을 보내면 예정 `version` 과 다를 때 409 VERSION_CONFLICT(선택).

## 2. 수령 규칙

- RECEIVING(도착 등록 뒤)이고 **출발(TOUT) 사실을 받은 예정만** 수령 — 아니면 409 INVALID_STATE. 재고 반영 전 수령이 하나 있으면 다른 수령도 409(그 수령을 같은 키로 다시).
- 줄 = `{sku, lotId?, expiryDate?, normalQty, quarantinedQty, pendingQty, toLocationCode, discrepancyReason?}` — **`toLocationCode`(수령 위치) 필수**(없으면 400).
- SKU 는 그 예정(출발 줄)에 있는 것만, SKU 별 인수 합(정상 + 격리 + 보류) ≤ 출발 수량. 넘으면 400(초과분은 정상 수령에 넣지 않는다 — MFC IV-02). 초과 실물의 예외 보관 경로는 아직 없다.
- `lotId` 는 **CDC LOT 표기**(줄 `lotId` 와 같은 값)다. 비우면 그 SKU 의 출발 LOT 이 하나일 때 서버가 그 LOT·소비기한으로 채운다.
- 차이 사유는 앞 회신 R11 그대로(수량 차이 우선). 서버는 SKU 별 출발 수량 ≠ 인수 합인 줄을 `discrepancies` 로 남기고, 사유를 안 보내면 인수 0 → NOT_RECEIVED, 적으면 SHORT.
- APPLIED 면 예정 TIN_POSTED, 수령 줄마다 **품질검사 작업**(정상 + 보류 수량) → 지금 쓰는 품질검사·적치 화면 그대로(`GET /inbound-tasks?mfcId=&receiptId=`).
- REJECTED(재고 거절 — 예: 수령 위치가 입고 구역이 아님)면 예정은 RECEIVING 그대로 — 고쳐서 **새 키**로. UNKNOWN_RESULT 면 **같은 키**로 다시.

## 3. 응답 모양(0.16.0 에 더한 것)

- `InboundPromise`: `fromCdc`, `trfVersion`(replenishment 버전), `plannedArrivalAt`(= TRF 기한, null 가능) + `deadlineMeaning`(ARRIVAL·HANDOVER·READY), `departedAt`, `shipmentManifestId`, `cancelReason`, `packages[]`.
- 줄: `lotId`(CDC LOT, 출발 전이면 null), `expiryDate`, `temperatureZone`(SKU 사본 보관 유형), `expectedQty`(출발 수량, 출발 전이면 승인 수량), `receivedQty`(**재고 반영된 인수 합 = 정상 + 격리 + 보류**), `quarantinedQty`·`pendingQty`(그중). `productName`·`barcodes` 는 R8 그대로.
- `eta` 는 늘 null(TMS 사건 구독 전). `tripId` 는 도착 등록 값, 없으면 출발 사건 값.
- `Receipt.rejectReason`.

## 4. R17 박스 단위 — 일부만

- 원천 사건(출발·매니페스트 줄)에는 `sku·lot·lpn·qty·expiryDate` 만 있다. 그래서 **`packages[]` = LPN 별 목록(`lpn`, sku, lotId, qty, expiryDate)** 만 넣었다 — `lpn` 은 CDC 가 준 값 그대로.
- 줄 `temperatureZone` 은 SKU 사본으로 채운다(예정 단위 온도대는 줄에서 모아 보면 된다).
- **넣지 않은 것**: 롤테이너 수, 박스 수·입수(`unitsPerBox`·`boxCount`), 박스 바코드 `BOX-…` — 백엔드에 정한 형식·원천 값이 없다. 형식을 만들어 넣지 않고 cdc-outbound 요청 **CO-2**(`docs/architecture/service-requests/cdc-outbound-service.md`)로 남겼다. 그 전까지 "박스째 +12" 버튼은 실서버에서 숨겨 주세요.

## 5. 앱에서 바꿀 것

- 송장 입고 화면: 계속 목. 실서버 전환 때 §1~3 모양으로(빈 목록이면 "입고 예정 없음").
- 수령 줄에 수령 위치를 반드시 넣기, LOT 는 줄의 `lotId`(CDC 표기)를 그대로 보내거나 비우기.
- R17 박스 표시는 `packages` 의 LPN 목록까지만.

## 6. 남은 것

- 원천 서비스(replenishment·cdc-inventory·cdc-outbound) 구현 — 그 전엔 실서버 확인 불가.
- 다음 작업 받기의 RECEIVE 후보(mfc-capacity) — 입고 예정이 실제로 생길 때.
- 박스 형식·입수(CO-2), ETA(TMS), 초과·불명 실물 예외 보관 경로.
