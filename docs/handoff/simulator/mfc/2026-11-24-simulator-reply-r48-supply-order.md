# 시뮬레이터 회신 — R-48 센터 발주·승인·입고 예정, 발주 수량 추천 (2026-11-24)

보낸 영역: mfc (PC 3). 요청: 시뮬레이터 R-48 → 백엔드 요청 MI-4(`docs/architecture/service-requests/mfc-inbound-service.md`, 사용자 "B"). mfc-inbound 0.21.0(V14) — **PR 병합·LAN 배포 전**.

## 1. 발주(여러 줄) — 지금 API 그대로
`POST /api/v1/supply-requests` (그 센터 MFC_SUPERVISOR, `Idempotency-Key`·`X-Source-System`)
```json
{ "mfcId": "MFC-BJG-01", "requestedReadyAt": "2026-11-25T00:00:00Z", "urgency": "NORMAL",
  "reason": "추천값으로 채움", "lines": [ { "sku": "E-00000071", "qty": 20 }, { "sku": "B-00000006", "qty": 3 } ] }
```
→ 201, `state: SUBMITTED`. 줄 1~200, 같은 센터·SKU 미해결 요청이 있으면 409 `DUPLICATE_OPEN_REQUEST`. 바꾸기 `PATCH`(SUBMITTED 일 때만), 철회 `…/withdrawals`.

## 2. 승인·거절 (센터 관리자 — 센터 안에서 끝냄)
- `POST /api/v1/supply-requests/{id}/approvals` `{ "lines": [ { "sku": "E-00000071", "approvedQty": 15 } ], "note": "적재 한도" }`
  - `lines` 에 없는 줄 = 요청 수량 그대로. 0 ~ 요청 수량. **줄였으면 `note` 필수**, 전부 0 이면 400(거절로).
  - → 200 `state: APPROVED`, `approvedLines[{sku, requestedQty, approvedQty}]`, `decidedBy`, `decisionNote`, `linkedReceiptId`.
- `POST /api/v1/supply-requests/{id}/rejections` `{ "reason": "냉장 칸 부족" }` → `state: REJECTED`, `rejectReason`.
- SUBMITTED 만(그 밖 409 INVALID_STATE). 요청한 사람이 승인해도 된다. approval-service 는 거치지 않는다.

## 3. 입고 예정(수령 전 수량) → 수령
승인하면 승인 수량 0 초과 줄로 입고 예정 하나(receiptId `RCV-SR-…`, `approvalId` = 발주 ID):
- 조회 `GET /api/v1/initial-stock-loads?mfcId=MFC-BJG-01&state=EXPECTED` 또는 `?approvalId=<발주 ID>` — 줄마다 sku·qty·상품명.
- 수령·품질검사·적치는 지금(R-23 시험 입고 예정과 같은) 흐름: `POST /initial-stock-loads/{receiptId}/receipts` → INSPECT → PUTAWAY.

## 4. 발주 수량 추천 — "추천값으로 채우기"
`GET /api/v1/supply-recommendations?mfcId=MFC-BJG-01&storageType=CHILLED&sku=E-00000071&sku=…` (그 센터 운영자, SKU 1~100, `storageType` 선택)
- 줄마다 `recommendedQty`(0 = 지금 필요 없음) + 근거: `forecastDailyQty`(d)·`historyDays`·`availableQty`(A)·`expectedQty`(입고 예정)·`pendingRequestedQty`(승인 전 발주 — IP 에 안 넣음)·`inventoryPosition`·`safetyStockQty`·`targetQty`(S)·`shortfallQty`·`reviewDays`·`leadDays`·`safetyDays`·`orderMultiple`·`minOrderQty`·`policySource`, 머리 `calculatedAt`·`availabilityAsOf`·`salesThrough`(어제).
- 산식: S = d × (R + L) + d × SS일, IP = A + 입고 예정, Q = max(0, S − IP) 를 배수로 올림(0 보다 크면 최소 이상).
- 추천하지 않으면 `recommendedQty: null` + `reason`: `INSUFFICIENT_HISTORY`(인계 실적 7일 미만) · `POLICY_MISSING`(`missingPolicyFields`) · `STORAGE_TYPE_UNKNOWN` · `AVAILABILITY_UNKNOWN` · `SKU_NOT_HANDLED`. **null 을 0 으로 채우지 말 것.**
- d = 그 센터 인계 실적(mfc-inventory `PackageHandedOverPosted`) 28일 이동평균(임시). **배포 뒤부터 쌓이므로 처음 7일은 모든 SKU 가 INSUFFICIENT_HISTORY.** 품절 날은 빼지 않는다.
- 시작값(사용자 확인 2026-11-24, 승인 전 시험값): R 1일·L 1일·SS 1일치·배수 1(박스 상품은 박스 SKU)·최소 1.
