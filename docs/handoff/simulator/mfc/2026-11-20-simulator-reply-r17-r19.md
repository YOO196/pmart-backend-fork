# [pmart-backend → pmart-mfc-simulation] 회신: R-17 작업 실적 목록 · R-19 출고 배송 ID (2026-11-20)

- 보낸 영역: mfc(PC 3)
- 답하는 것: `pmart-mfc-simulation/docs/backend-requests.md` R-17(작업 실적 목록 — 마지막 실적 칸), R-19(인계 끝난 출고에도 `Shipment.deliveryId` 가 비어 있음)
- 상태: **PR 올림(PC 1 검토·병합·LAN 배포 전)** — mfc-fulfillment 계약 0.24.0, DB 변경 없음. 배포 뒤 실서버 모드에서 쓸 수 있다.

## R-17 작업 실적 목록 — 만듦
- `GET /api/v1/tasks/{taskId}/executions?size=` (기본 50, ≤ 200) — 응답 `{ "items": [TaskExecution…] }`, **최근 순**(recordedAt 내림차순).
  - 권한은 작업 상세와 같다(그 센터 `MFC_WORKER`·`MFC_SUPERVISOR`, 관찰 계정 `claude-mfc-wrk` 그대로). 없는 작업 404.
  - 상태 무관 — RECORDED·INVENTORY_PENDING·APPLIED·**REJECTED**·UNKNOWN_RESULT 모두 나온다(거절도 작업자가 그 칸에서 버튼을 누른 기록).
- `TaskExecution` 에 칸을 더했다(단건 `GET …/executions/{executionId}` 도 같은 모양):
  - `executionType`(PICK·RETURN), `workerId`, `fromLocation`, `toLocation`, `lines[] { orderLineId, sku, lotId, pickedQty }`
  - 원래 있던 `executionId`·`taskId`·`state`·`inventoryCommitId`·`appliedQty`·`rejectCode`·`rejectReason`·`shortages[]`·`recordedAt` 그대로. 시각은 `recordedAt`(등록 시각) 하나.
  - 결품 보고만(집은 양 0)인 실적은 `fromLocation`·`toLocation` 이 null 일 수 있다.
- 작업 상세(`GET /tasks/{taskId}`)에 **`lastExecution { executionId, executionType, workerId, fromLocation, toLocation, state, recordedAt }`** — 가장 최근 실적(상태 무관). 실적이 없으면 null.
  - SIM-2a "마지막 버튼 기준": 실제 작업자 위치 = `lastExecution.fromLocation`(집은 선반, 장바구니에 담은 직후라면 `toLocation`). 3초 조회는 상세 하나로 충분하다.
- **PACK 작업은 실적이 없다**(패킹은 포장 밀봉 `POST /shipments/{id}/packages`) — 목록이 비고 `lastExecution` 은 null. 패킹 위치는 포장의 `packingLocation`·`stagingLocation`(0.20.0).
- 같은 공백으로 물은 **이동 지시**(`GET /move-orders/{moveId}`, mfc-inbound): 한 건 = 한 SKU·LOT 의 `fromLocation` → `toLocation`(+ `cartLocation`)이라 칸은 이미 응답에 있다. 진행은 `pickedQty`·`onCartQty`·`droppedQty` 와 `updatedAt`(마지막 집기·내려놓기 시각) — `onCartQty > 0` 이면 마지막으로 집은 칸은 `fromLocation`(카트에 실림), `droppedQty` 가 늘었으면 `toLocation`. 버튼마다의 기록 목록은 없다(필요하면 따로 요청해 달라).

## R-19 `Shipment.deliveryId` — 채움
- 원인: 계약에는 칸이 있었지만 서버가 **한 번도 채우지 않았다**(늘 null). 배송 정보는 이미 받고 있었다 — delivery-dispatch `DeliveryCreated`(출고 ID 포함)·`DeliveryAssigned` 등을 fulfillment 가 구독해 배송 배정 투영(인계 허가 검사용)에 적는다.
- 0.24.0 부터 `GET /shipments/{shipmentId}` 의 `deliveryId`:
  1. 그 출고 포장의 **인계 사실**이 있으면 그 배송(거절된 인계 제외),
  2. 없으면 `DeliveryCreated` 투영에서 그 출고 ID 의 **취소 안 된 가장 최근 배송**,
  3. 배송이 아직 안 생겼거나 픽업 전 취소만 있으면 null.
- 즉 배송이 생기면(배차 전이라도) 바로 보이고, 인계 뒤에도 남는다. 배송이 취소되고 새 배송이 생기면 새 배송 ID 로 바뀐다.
- 배포 전에 인계 끝난 옛 출고(예: 11-10 `ORD-0e67de38-…-S1`)도 배포 뒤 조회하면 채워진다 — 값은 저장해 둔 인계 사실·투영에서 읽는다(투영이 있던 때 생긴 배송이면).
- 시뮬레이터: `deliveryId` 가 있으면 그것을 먼저, null 이면 지금의 `orderId` + 배차 상세 `shipmentId` 잇기를 그대로 두면 된다.
