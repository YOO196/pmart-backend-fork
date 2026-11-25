# 회신 — 재고 이력에 주문·처리한 사람 (mfc-inventory 0.49.0)

보낸 영역: mfc
받는 앱: mfc-backoffice
요청: pmart-clients `docs/handoff/mfc-backoffice/2026-11-25-backend-stock-ledger-order-actor.md`(LOT 추적)
작성: pmart-backend PC 3, 2026-11-25
계약: **mfc-inventory-service 0.49.0** — `StockLedgerCommit` 에 선택 칸 둘 + 거르기 하나(추가만)

## 답
- **재고 이력에 바로 실었다**(예약 → 주문 운영자 조회는 열지 않음 — 한 번의 조회로 끝나게).
  - `orderId`: 예약에 딸린 확정 — 예약·풀림·만료·결품·취소 복귀(출처 = 예약 번호), 인계(포장의 예약), PICK·PACK(실적의 예약). 그 밖은 null.
  - `actorOperatorId`: 작업 실행(검수·적치·격리 이동·피킹·포장·랙 올리기·복귀)은 그 작업자, 직원이 직접 부른 확정(정정·조정 등)은 그 직원. 만료·자동 보충·서비스 사건은 null.
  - 거르기 `orderId=` — 그 주문에 딸린 확정만.
- **`orderNumber` 는 싣지 않았다** — 화면용 8자리 번호는 mfc-inventory 에 없다(mfc-fulfillment 가 가짐). 주문이 몇 개 안 되면 `GET /work-orders?orderId=`(fulfillment, 그 센터 운영자)로 한 번씩 바꿔 보인다.
- **예전 기록**: PICK·PACK 의 주문과 작업 실행의 작업자는 이번 배포 뒤 기록부터 채워진다(예전 행은 null — 소급 없음). 예약·풀림·만료·결품·인계 줄의 주문은 예전 것도 나온다.
- 이동 지시(MOVE_* — 선반 간 이동·보충)는 아직 작업자를 싣지 않는다(null).
- **다른 센터·간선**: 맞다. 이 원장은 그 센터 안만이다 — MFC 재고가 센터 밖으로 나가는 길은 고객 인계(HANDOVER)뿐이고, 입고는 CDC(TRF)에서 온다(CDC 쪽 이력은 cdc-inventory, 이 화면 밖).
