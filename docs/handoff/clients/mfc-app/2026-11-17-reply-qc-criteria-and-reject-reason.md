# 회신: 품질검사 판정 기준(BR-52 R18)·실적 거절 사유(E1~E3)·출고 조회 500(S1·S2)

- 보낸 영역: mfc (PC 3)
- 작성: pmart-backend, 2026-11-17
- 답하는 것: mfc-app 메시지(2026-11-17) "요청 2건" + `docs/handoff/mfc-app/2026-11-17-backend-pick-reject-reason.md`(E1~E3) + `…-backend-shipment-ready-at-npe.md`(S1·S2).
- 적용: PR 병합·LAN 배포 뒤(PC 1). 이 PR 은 앞 PR 들 위에 쌓였다(계약 버전).

## 1. 출고 조회 500 (S1·S2) — 이미 끝남
- PR #108 병합·**LAN 배포 완료**(mfc-fulfillment, 11-17 PC 1). PC 1 이 READY 전(PREPARING) 출고 두 건 `GET /shipments/{id}` 200·`readyAt` null 확인. 회신 `2026-11-17-reply-shipment-ready-at-npe.md`.

## 2. 품질검사 정상 판정 기준 (BR-52 R18) — **A안**
- `GET /inbound-tasks/{id}`·목록의 `InboundTask` 에 세 칸(mfc-inbound 0.11.0):

| 칸 | 뜻 |
|---|---|
| `storageType` | AMBIENT·CHILLED·FROZEN — 냉장·냉동이면 온도 확인 |
| `minRemainingDays` | 정상 최소 잔여일 = **상품 보장값, 없으면 보관 유형 기본값**(LAN CHILLED 2·FROZEN 30·AMBIENT 30 — 설정). 예약·출하 가능 판정과 **같은 값** |
| `maxDaysSinceManufacture` | 제조 후 최대 경과일 — 있으면 제조일 필수. 보장 없으면 null |

- 값이 없으면 **null**(0·무제한으로 바꾸지 않음) — PDA 는 "기준 미설정"으로 막으면 된다. `minRemainingDays` 가 null 인 건 상품 보장도 보관 유형 기본값도 없을 때뿐(보관 유형을 모르는 상품 등).
- 원천: mfc-inventory SKU 사본(`/internal/skus`, 0.25.0 `minRemainingDays` 새 칸) — mfc-inbound 가 상품명·바코드와 같은 조회로 채운다. 조회 실패면 상품명처럼 null(목록은 그대로).
- B안(PDA 가 product-service 직접)은 안 함 — 판정 값이 재고 예약과 어긋나지 않게 재고 쪽 규칙 하나로.

## 3. 실적 거절 사유 (E1~E3)
- **E1**: `GET /tasks/{taskId}/executions/{executionId}` 의 `TaskExecution` 에 `rejectCode`·`rejectReason`(REJECTED 일 때만, 아니면 null) — mfc-fulfillment 0.16.0. 서버가 저장해 두던 "코드: 설명"을 나눴다. 코드가 없던 옛 기록은 `rejectCode` REJECTED.
- **E2**: 안 함 — 실행 응답은 공용 `CommandResponse`(공통 스키마 `_common.yaml`, platform)라 칸을 더하지 않았다. 응답 `resultId` = executionId 로 실행 조회 한 번(요청서 "E1 만으로도 된다").
- **E3**: 코드 목록은 계약 `TaskExecution.rejectCode` 설명에 — `LOT_MISMATCH`·`PICK_QTY_EXCEEDED`·`INSUFFICIENT_STOCK`·`LOCATION_ZONE_MISMATCH`·`LOCATION_CLOSED`·`LOCATION_NOT_REGISTERED`·`LOCATION_ALLOCATED_TO_OTHER`·`SKU_ALREADY_SHELVED`·`TEMPERATURE_ZONE_MISMATCH`·`INVALID_STATE`·`VERSION_CONFLICT`·`CONFIG_REQUIRED`. 모르는 코드는 그대로.
- `rejectReason` 은 재고가 준 한국어 문장 그대로라 화면에 바로 보여도 된다.

## 시험(PC 3)
- inventory check 61·31(SKU 사본 `minRemainingDays`), inbound check 5·12(입고 작업 세 칸·사본 없으면 null), fulfillment check 20·28(거절 실적 `rejectCode` LOT_MISMATCH·설명, 나누기 단위 시험).
