# 회신: R-10 정기 입고(입고 예정) 수령 뒤에도 같은 INSPECT·PUTAWAY 작업이 생기는가 — 예

- 보낸 영역: mfc (PC 3)
- 작성: pmart-backend, 2026-11-20
- 답하는 것: pmart-mfc-simulation `docs/backend-requests.md` R-10 "남은 것" 첫 줄 — 정기 입고(`/inbound-promises/{trfId}/arrivals`·`receipts`) 수령 뒤에도 같은 INSPECT·PUTAWAY 작업이 생기는지.
- 계약: `mfc-inbound-service.yaml` **0.16.0**(브랜치 `mfc/inbound-promises`, PR 병합·LAN 배포 전). R-10 의 나머지(보충 이동 작업·긴급 보충 요청)는 이 회신 범위 밖 — 보충 이동은 이미 `/move-orders`(kind REPLENISH)로 있다.

## 1. 답 — 예, 같은 작업이 생긴다

입고 예정 수령은 초기 보유 반입(·`POST /dev-initial-stock-loads`)과 **같은 코드 경로**로 작업을 만든다.

| 단계 | 초기 보유 반입 | 입고 예정(0.16.0) |
|---|---|---|
| 수령 | `POST /initial-stock-loads/{receiptId}/receipts` | `POST /inbound-promises/{trfId}/arrivals` → `POST /inbound-promises/{trfId}/receipts` |
| 재고 반영 | mfc-inventory ConfirmReceipt(receiptId) | 같음(receiptId `RCV-TRF-…`, trfId·departureId = 실제 TRF·TOUT) |
| 품질검사 작업 | 수령 줄마다 `INSPECT` | **같음** — 정상 + 미판정 보류 수량(격리 수량은 inventory 에서 바로 격리라 작업 없음) |
| 적치 작업 | 품질검사에서 나뉜 정상 LOT 마다 `PUTAWAY` | **같음**(`POST /inbound-tasks/{id}/inspections` 그대로) |

- 작업 찾기: `GET /inbound-tasks?mfcId=&receiptId=<수령 응답 resultId>`(또는 `taskType=INSPECT`). 작업의 `receiptId` 가 수령 ID.
- RECEIVE 는 mfc-inbound 작업 행이 아니다 — mfc-capacity 배정의 RECEIVE `taskId` = `trfId`(클라이언트 BR-53 R9). 작업자 처리 내역(`/internal/work-activities`)에는 RECEIVE(taskId = trfId)로 잡힌다.

## 2. 흐름과 규칙(요약)

1. 입고 예정은 사건으로만 생긴다: replenishment `TransferApproved`(EXPECTED) → `TransferChanged`·`TransferCancelled`(출발 전만) → cdc-inventory `TransferDeparted`(출발 줄 = 예정 수량) → cdc-outbound `TransferShipmentManifestIssued`(LPN 목록).
2. `GET /inbound-promises?mfcId=`(기본 EXPECTED·RECEIVING) → 도착 등록(EXPECTED → RECEIVING) → 수령(줄마다 `toLocationCode` 필수, SKU 별 인수 합 ≤ 출발 수량, 출발 사건 전이면 409).
3. 수령 응답 status APPLIED → 예정 TIN_POSTED, 품질검사 작업. REJECTED(재고 거절) → 예정은 RECEIVING, 고쳐서 새 키로. UNKNOWN_RESULT → 같은 Idempotency-Key 로 다시.

## 3. 시뮬레이터가 알아 둘 것

- **LAN 에서는 입고 예정 목록이 비어 있다** — 원천 사건을 내는 서비스(replenishment·cdc-inventory·cdc-outbound)가 아직 없다. 재고 채우기는 계속 R-23 `POST /dev-initial-stock-loads` 를 쓰면 된다(그 뒤 작업 흐름은 위 표처럼 같다).
- 시뮬레이터가 입고 예정을 흉내 내려고 위 사건을 Kafka 에 직접 넣는 것은 하지 않는다(서비스 사건의 정본은 발행 서비스).

## 4. 남은 것

- 원천 서비스 구현(CDC·SCM) 전까지 실서버 확인 불가.
- 도착 예정 시각(eta — TMS `TransportEtaUpdated`)·차량 도착(`StopArrived`) 구독은 아직 없다(eta 는 늘 null).
