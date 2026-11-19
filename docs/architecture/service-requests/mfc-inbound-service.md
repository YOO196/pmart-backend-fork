# mfc-inbound-service 에 대한 요청

## MI-1. 오배송 회수품 MFC 반납 받기 (2026-11-16, 보낸 영역 delivery — DD-2 뒷단)
- **요청한 곳**: delivery-execution(오배송 회수 — `docs/architecture/service-requests/delivery-dispatch-service.md` DD-2 구현).
- **지금**: 회수 라이더가 MFC 에 도착해 `POST /deliveries/{id}/returns/store-deliveries` 를 누르면 회수 배송이 끝난다(RETURNED_TO_STORE, 수행료). **점원 수령 확인은 없다** — 사용자 2026-11-16 "추천대로": mfc-inbound 가 생기기 전까지 MFC 도착 = 반납.
- **사건(이미 나감)**: `ReturnDelivered`(delivery.delivery.v1, 계약 `contracts/events/ReturnDelivered.v1.json`) — purpose `MISDELIVERY_RECOVERY`, deliveryId(회수 배송), originDeliveryId(오배송된 원래 배송), caseId(오배송 확인 사건), orderId, mfcId, **lines = 원래 배송이 전달한 줄·수량**(회수 대상 — 실제 상태·수량은 검수가 정한다), deliveredAt. 앞서 `ReturnPickedUp`(회수지에서 받음)도 같은 모양.
- **mfc-inbound 가 할 것**:
  1. `ReturnDelivered`(MISDELIVERY_RECOVERY) 구독 → 회수품 입고 대기.
  2. 점원 수령 확인 → 검수: **신선·냉장·냉동은 폐기, 상온 미개봉품만 검수 후 재고 복구**(ADR-0052 결정 4). 회수품을 원래 고객에게 다시 보내지 않는다.
  3. (나중) 점원 수령 확인 참조를 라이더 반납과 잇기 — execution 은 `receivedByStaffRef` 를 이미 받는다. 확인을 반납 조건으로 바꾸려면 사용자 결정.
- **사용자 결정**: 검수 규칙은 ADR-0052 에 있음. 수령 확인을 반납 조건으로 할지는 나중.

## MI-2. 반납 도착 대기 목록 — 점원이 회수품이 온 줄 알게 (2026-11-17, 보낸 영역 delivery — 고객 반품 회수 DD-3·오배송 회수 DD-2)
- **지금 빈 곳**: 회수 라이더가 MFC 에 도착해 `returns/store-deliveries` 를 누르면 delivery 는 `ReturnDelivered`(delivery.delivery.v1)를 내고 배송을 끝낸다. 점원이 회수품을 격리 반입하는 `POST /return-receipts`(claimId·returnPickupId·줄)는 있지만, **mfc-inbound 가 `ReturnDelivered` 를 구독하지 않아 점원 화면에 "반납 도착"이 보이지 않는다** — 점원이 claimId·returnPickupId 를 알 길이 없어 반입·검수·환불(claim `ReturnItemReceived`→`ReturnInspected` 뒤)이 이어지지 않는다(2026-11-17 PC 5 점검).
- **사건 모양**(이미 나감): `ReturnDelivered` — purpose `CLAIM_RETURN`(claimId, lines = claim 이 요청한 줄 — orderLineId·qty·claimLineId·sku) 또는 `MISDELIVERY_RECOVERY`(caseId·originDeliveryId, lines = 원래 배송 전달 줄), deliveryId(회수 배송 = returnPickupId), orderId, mfcId, riderId 는 ReturnPickedUp 에, deliveredAt.
- **mfc-inbound 가 할 것(PC 3)**:
  1. `ReturnDelivered` 구독(두 purpose) → 그 MFC 의 "반납 도착" 대기 행(회수 배송 ID·claimId 또는 caseId·줄·도착 시각).
  2. 점원 화면(mfc-app)에 대기 목록 → 고르면 `POST /return-receipts` 를 그 값으로 채워 반입(격리). 반입하면 대기에서 빠짐.
  3. 오배송 회수(MISDELIVERY_RECOVERY)는 claim 이 없으니 MI-1 검수 규칙(신선·냉장·냉동 폐기, 상온 미개봉만 재고 복구)으로.
- delivery 는 바꿀 것 없음. 점원 수령 확인을 반납 조건으로 바꾸는 것(execution `receivedByStaffRef`)은 나중 사용자 결정.
- **보충(2026-11-17 PC 5)**: 세 번째 purpose `FAILED_DELIVERY_RETURN` — 지원센터가 전달 실패를 확정하고 상품이 라이더 손이면 같은 라이더가 MFC 에 반납한다(originDeliveryId = 실패한 배송, lines = 그 배송이 인계받은 줄, claim·case 없음). 대기 목록에 같이 올리고, 검수는 MI-1 규칙(신선·냉장·냉동 폐기, 상온 미개봉만 재고 복구) — 이 주문의 환불·재배송은 고객센터가 정한다(자동 환불 없음).

- **회신(PC 3, 2026-11-19 — 브랜치 `mfc/return-arrivals`, mfc-inbound 0.13.0 V10·mfc-quality 0.7.0 V5)**: MI-1·MI-2 구현.
  - `deliveryEvents-in-0` = delivery.delivery.v1 `ReturnDelivered`(세 목적 모두) → `return_arrival` 대기 건(arrivalId = 회수 배송 ID). `GET /return-arrivals?mfcId=&state=WAITING|RECEIVED|ALL`(그 센터 운영자, 상품명 표시용).
  - `POST /return-receipts` 에 `arrivalId` — claimId·returnPickupId·orderId·주문 줄 번호를 대기 건에서 채움(줄 생략 가능, 본문 값이 다르면 400, 이미 반입이면 409 `ARRIVAL_ALREADY_RECEIVED` — 재고가 거절한 반입이면 다시). claim 없는 반납(오배송·전달 실패)도 반입(재고 trfId = caseId → originDeliveryId → 회수 ID).
  - 검수는 quality 의 기존 규칙 그대로(신선·냉장·냉동·개봉 재판매 불가 — MI-1 규칙과 같음), claimId 선택. 자동 환불은 claim 이 있을 때만(claim 은 claimId 없는 사건을 무시).
  - 점원 수령 확인을 반납 조건으로 바꾸는 것(execution `receivedByStaffRef`)은 하지 않음 — 나중 사용자 결정. **PC 1**: pmart-config `mfc-inbound-service.yml` 바인딩 `deliveryEvents-in-0`(delivery.delivery.v1, definition 에 `deliveryEvents`), 게이트웨이 `/api/v1/return-arrivals` → inbound.
  - 화면: mfc-app 요청서 `docs/handoff/clients/mfc-app/2026-11-19-request-return-arrivals.md`.

## MI-3. [LAN 시험 전용] 승인 없이 시험 입고 예정 만들기 — 시뮬레이터가 재고 채우기 (2026-11-19, 보낸 영역 platform — MFC 시뮬레이터 R-23)
- **요청한 곳**: MFC 시뮬레이터(pmart-mfc-simulation `docs/backend-requests.md` R-23, 중간). 실서버로 주문·가상 작업자를 계속 돌리면 재고가 줄어 품절 — 지금 재고를 넣는 길은 PC 1 의 seed(`Seed.java --initial-stock`)뿐.
- **사용자 결정(시뮬레이터 쪽 2026-11-19)**: "정식 구현은 아니고 mfc 재고를 채우는 기능만". PC 1 사용자 "요청서 쓰고"(2026-11-19).
- **필요한 것**(이름·경로·한도는 PC 3 이 정한다):
  1. **시험 입고 예정 만들기** — 설정을 켠 LAN 에서만(예 `pmart.inbound.dev-arrivals.enabled`, 운영 설정에 없음 → 404). 본문 예 `{ mfcId, lines: [{ sku, qty }] }` → **INITIAL_STOCK 승인이 만드는 것과 같은 모양의 입고 예정(EXPECTED)** 하나(출처 표시 예 trfId `DEV-<id>`). 응답 `receiptId`. 조회는 지금처럼 `GET /initial-stock-loads?mfcId=&state=EXPECTED`.
     - 그 센터가 취급하는 SKU 만, 줄 수·수량 상한(시뮬레이터 예 40줄·SKU 당 500). **재고는 지금 수령 → ConfirmReceipt → INSPECT → PUTAWAY 그대로**(조정 `/internal/adjustments` 로 넣지 않는다 — seed 원칙 D2).
     - 호출자: 그 센터 운영자 토큰(시뮬레이터 관찰 계정 `claude-mfc-wrk`) 또는 내부 경로 — PC 3 이 정함.
  2. (선택) SKU 를 서버가 고르기 — `{ mfcId, auto: { maxLines, targetQty } }` 면 출하 가능 수량이 낮은 SKU 부터. 어려우면 1 만.
  3. 확인 — 그 입고 예정의 현장 수령 `POST /initial-stock-loads/{receiptId}/receipts` 를 가상 작업자 `mfcw-pp-01~10` 토큰으로 불러도 되는지. 안 되면 1 에 `receive: true`(수령 위치로 바로) 선택지.
- **하지 않는 것**: 정식 입고(`/inbound-promises`)·CDC·SCM 흉내. 정식 입고가 생기면 이 시험 입구는 없앤다.
- 회신: 시뮬레이터 handoff `docs/handoff/simulator/mfc/` 에(PC 3 이 써도 된다).
- **회신(PC 3, 2026-11-19 — 브랜치 `mfc/return-arrivals`, mfc-inbound 0.14.0)**: `POST /dev-initial-stock-loads`(설정 `pmart.inbound.dev-arrivals.enabled` 켠 곳만, 그 밖 404 — **PC 1: LAN `mfc-inbound-service-lan.yml` 에 켬, 라우트 `/api/v1/dev-initial-stock-loads`**). 그 센터 운영자, 줄 1~40·SKU 당 1~500, 모르는 SKU 400 UNKNOWN_SKU. EXPECTED 하나(approvalId `DEV-…`) → 현장 수령은 가상 작업자 토큰으로 됨(그래서 `receive: true` 없음). 선택 2(auto)는 안 함. 회신 `docs/handoff/simulator/mfc/2026-11-19-simulator-reply-r23-dev-stock.md`.
