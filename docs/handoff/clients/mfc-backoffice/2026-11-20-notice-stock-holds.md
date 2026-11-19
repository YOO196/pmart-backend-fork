# 알림: 열린 분실 조사 보류 목록과 정정으로 풀기 — 센터 관리자 화면 지침 (mfc-inventory 0.38.0)

- 보낸 영역: mfc (PC 3 백엔드)
- 작성: pmart-backend, 2026-11-20
- 대상: pmart-clients `web/mfc-backoffice`(재고 > 분실 조사 화면)
- 배경: 0.37.0(사용자 결정 2026-11-20 B)으로 센터 관리자가 수령장(적치 전) 분실 조사 보류를 재고 정정 `holdResolutions[]` 로 푼다. 그런데 **지금 열린 보류를 알려 주는 조회가 없어** 화면이 보고 기록을 모아 맞춰야 했다. 0.38.0 이 목록을 준다 — 화면은 목록 한 줄을 그대로 정정 한 줄로 보내면 된다.
- 배포: 백엔드 PR 병합·배포 뒤(PC 1). 게이트웨이 라우트 `/api/v1/mfcs/*/stock-holds` 는 PC 1 이 pmart-config 에 넣어야 열린다.

## 1. 목록 — `GET /api/v1/mfcs/{mfcId}/stock-holds`
- 권한: 그 센터 운영자(역할 무관 — 센터 재고 `stock-units` 와 같다). 다른 센터 403.
- 거르기(모두 선택): `locationCode`(정확히), `sku`, `workState`(`UNSTOWED` = 수령장·반품 구역 등 적치 전, `STORED` = 선반). `size`(≤ 200, 기본 50), `cursor` = 앞 응답 `nextCursor` 그대로(null 이면 끝).
- 응답 `{ asOf, items[], nextCursor }`, 위치 → SKU → LOT 순. 한 줄 = 위치·SKU·LOT·품질·업무 상태 하나:
  - `locationCode`·`sku`·`productName`·`lotId`·`lotNo`·`expiryDate`·`qualityState`(지금은 늘 NORMAL)·`workState`·`zone`
  - `heldQty` — 열린 보류 합(못 찾은 것으로 보고된 양), `unitQty` — 그 칸 단위 행의 지금 수량(보류분 포함)
  - `resolution` — `CORRECTION`(적치 전 → 아래 2 의 정정) / `COUNT`(선반 → 순환 실사)
  - `firstHeldAt`, `sources[]` — 이 보류를 만든 보고(만든 순): `reportId`, `kind`(`PICK_SHORTAGE` 피킹 결품·`MOVE_EXCEPTION` 이동/보충 예외·`STOCK_EXCEPTION` 적치 등 재고 예외, 모르면 null), `reason`(SHORT·BLOCKED), `qty`, `heldAt`
- 들어 있지 않은 것: 파손·변질(격리 — `stock-units?qualityState=QUARANTINED`), 진행 중 이동이 출발 칸에 잡아 둔 양(보류 아님).

## 2. `resolution: CORRECTION` 줄 풀기 — `POST /api/v1/mfcs/{mfcId}/stock-corrections` (0.37.0, MFC_SUPERVISOR 만)
- 헤더 `Idempotency-Key`(누를 때마다 새로 — 본사 승인을 받은 뒤 다시 보낼 때만 **같은 키**), `X-Source-System`.
- 본문: `{ reason, approvalRef?, holdResolutions: [ { sku, lotId, locationCode, foundQty, lostQty } ] }` — 목록 줄의 `sku`·`lotId`·`locationCode` 를 그대로, **`foundQty + lostQty = heldQty`**. 여러 줄을 한 번에(최대 100, 같은 줄 두 번 400).
  - 찾음(foundQty) = 보류만 풀림 → 다시 적치할 수 있고, 입고가 적치 작업을 만든다(mfc-inbound, `StockHoldResolved`).
  - 못 찾음(lostQty) = 손실 처리(보유 −q). 손실 금액·수량이 본사 기준 이상이면 422 `APPROVAL_REQUIRED`(details 의 approvalRequest 로 본사 승인 요청 → 받은 번호를 `approvalRef` 로, 같은 키로 다시).
- 오류: 409 `HOLD_QTY_MISMATCH`(그 사이 보류가 바뀜 — details reason `openHoldQty=<n>`, 목록 다시 읽기), 409 `NO_OPEN_HOLD`(이미 풀림), 403(센터 관리자 아님).
- 풀린 줄은 목록에서 사라진다(다시 읽기).

## 3. `resolution: COUNT` 줄(선반)
- 정정으로는 풀 수 없다(정정 holdResolutions 는 적치 전 보류만 — 선반 줄을 보내면 409 `NO_OPEN_HOLD`). 그 칸 순환 실사(`count-plans`·`count-sessions`) 승인이 그 칸의 보류를 모두 정리한다. 화면은 "실사 계획 만들기"로 이어 주면 된다.
