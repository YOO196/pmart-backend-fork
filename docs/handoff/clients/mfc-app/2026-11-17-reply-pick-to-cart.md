# [pmart-backend → pmart-clients] 회신: 피킹 도착지 = 장바구니 (2026-11-17)

- 보낸 영역: mfc (PC 3)
- 원 요청: `docs/handoff/mfc-app/2026-11-17-backend-pick-to-cart.md` (P1~P4)
- 계약: `mfc-fulfillment-service.yaml` **0.8.0**, `mfc-inventory-service.yaml` **0.19.0**. LAN 반영은 PR 병합 뒤 PC 1 배포(시각은 PC 1 일지 `## PR` 절).
- 근거: 업무 원문 MFC §1 운영 원칙(장바구니를 패킹대로), §7-6 1·3단계(장바구니 바코드로 주문 호출), IV-07(지정 토트 일치, 토트/포장 구성과 예약 소유 연결).

## P1 PICK 도착지 = 장바구니 — 수용(포장 위치도 계속 받음)
- `POST /tasks/{taskId}/executions` PICK 의 `toLocation` 은 **구역 CART(장바구니)** 또는 PACKING(포장 위치 — 시뮬레이터·LAN 점검 스크립트가 쓰는 예전 흐름). 재고 반영: 선반 NORMAL·STORED −q → 장바구니 NORMAL·PICKED +q(예약 그대로). 온도대는 보지 않는다(재고 이동의 카트와 같게).
- 집은 양이 있는데 `toLocation` 이 없으면 **400 `TO_LOCATION_REQUIRED`**(예전 VALIDATION_FAILED 에서 코드만 바뀜).
- CART·PACKING 이 아닌 위치면 재고가 거절 → 실적 `REJECTED`, 사유 `LOCATION_ZONE_MISMATCH`.
- PDA 가 보낼 값은 요청서 §2 예시 그대로 맞다.

## P2 포장 = 장바구니 → 패킹대 — 수용
- `POST /shipments/{shipmentId}/packages` 의 `packingLocation`(찍은 패킹대 `PAK-…`)이 도착지, 출발지는 그 출고 PICK 실적의 도착지(장바구니). 재고 PACK: 장바구니 PICKED −q → 패킹대 PACKED +q.
- **장바구니로 집품했으면 `packingLocation` 은 필수로 보내 달라.** 빼면 집품분 위치(장바구니)를 포장 위치로 쓰게 되고 재고가 "장바구니 안에서 포장"을 거절한다 → 422 `PACK_REJECTED`(포장 VOIDED). 패킹대가 PACKING 구역이 아니어도 같은 거절.
- 응답·조회의 `Package.packingLocation` 은 패킹대 — 인계 화면 위치는 지금처럼 이 값.

## P3 계약에 판정 — 수용
- `TaskExecutionRequest.toLocation`·`recordTaskExecution` 설명에 위 규칙과 코드(400 TO_LOCATION_REQUIRED·409 CART_MISMATCH)를 적었다. `required` 배열은 그대로 — 결품 보고만(집은 양 0)이면 필요 없어서.
- `TaskDetail.packingLocations` 설명 = "집품분이 놓인 위치(장바구니 또는 포장 위치)". 장바구니로 집품했으면 여기 장바구니 코드가 온다(패킹대 아님).

## P4 한 출고 = 한 장바구니 — 수용
- 같은 출고의 반영됐거나(집은 양 > 0) 결과를 기다리는 PICK 실적과 다른 `toLocation` 이면 **409 `CART_MISMATCH`**(본문 detail 에 쓰고 있는 장바구니).
- 한 장바구니를 **여러 출고가 같이 쓰는 것은 막지 않는다**(재고는 예약으로 주문별로 나뉜다). LAN 평면도 장바구니는 지금 4개(`CRT-A-01`·`CRT-A-02`·`CRT-C-01`·`CRT-F-01`)뿐이라.
- 장바구니 온도대(CRT-C·CRT-F)와 상품 온도대는 보지 않는다 — 한 주문의 냉장·냉동을 다른 장바구니로 나누려면 "한 출고 = 한 장바구니"와 부딪친다. 필요하면 업무 결정으로 다시 요청해 달라.

## §3 같이 본 곳
- **복귀(RETURN)**: 포장 전에 취소되면 `picks[].locations` 에 **장바구니**, 포장분은 **패킹대**가 온다. PDA 는 받은 위치를 그대로 출발지로 보내면 된다.
- **집품 뒤 중지·인계**: `Package.packingLocation`·인계 화면 위치는 패킹대 그대로.
- **결품 보고만 있는 실적**: `toLocation` 필요 없음(그대로).

## 시험
- inventory 통합 `StockReservationFlowIT.pickIntoCartThenPackMovesCartToPackingStation` — 장바구니로 집품, 장바구니 안 포장·선반으로 포장 거절, 장바구니 → 패킹대, 취소 복귀가 장바구니 PICKED·패킹대 PACKED.
- fulfillment 통합 `TaskFlowIT.pickIntoCartThenPackAtStationAndStopReturnsFromWhereUnitsAre` — 400 TO_LOCATION_REQUIRED, 409 CART_MISMATCH, 재고 PACK from 장바구니·to 패킹대, 중지 복귀 위치.
- fulfillment 단위 `CancelReturnRulesTest.cartPickPackedAtStationSplitsByWhereUnitsAre`.
