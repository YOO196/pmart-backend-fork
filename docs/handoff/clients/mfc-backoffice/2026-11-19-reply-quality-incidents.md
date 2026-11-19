# 회신: 품질 사고(MB-2) 1차·상품 취급(R7·R8)

- 보낸 영역: mfc (PC 3 백엔드)
- 작성: pmart-backend, 2026-11-19
- 답하는 것: pmart-clients `docs/handoff/mfc-backoffice/2026-11-19-backend-quality-incidents.md`
- PR: 브랜치 `mfc/quality-incidents` — **mfc-quality 0.6.0**(V4)·**mfc-inventory 0.27.0**(V23). LAN 반영은 PC 1 병합·배포 + 설정(아래) 뒤.
- 범위: 사용자 "1차부터" — R1~R5·R7·R8. **R6(고객 영향 case 자동)은 2차**(아래).

## 1. 품질 사고 (mfc-quality 0.6.0)

### R1 조회
- `GET /quality-incidents?mfcId=&state=&kind=&lotId=&size=&cursor=` → `{items, nextCursor}`. `state` 반복, 비우면 OPEN·DECIDED. 오래된 순, 커서 = 마지막 사고 ID, size ≤ 200. 그 센터 운영자(역할 무관).
- `GET /quality-incidents/{id}` — 같은 모양. 다른 센터 403.
- 항목 추가 칸: `origin`(AUTO·MANUAL)·`description`·`openedBy`(자동은 null)·`closedBy`·`closeReason`·`version`·`affectedLots[].resolved`·`resolvedBy`(처리한 판정·폐기 ID)·`remainingLotCount`(남은 LOT 수 — 화면 "남은 LOT n개")·`affectedOrderCount`(2차 전엔 null)·`decisionIds`.
- 상태는 조회 때 판정에 맞춰 다시 정한다(OPEN ↔ DECIDED).

### R2 직접 등록 — `POST /quality-incidents`
- 본문 `{mfcId, kind, description(1~300), affectedLots[{lotId, qty}]}`(1~50 LOT). 그 센터 `MFC_SUPERVISOR` 만, Idempotency-Key·X-Source-System, 같은 키 = 같은 결과. 201 `QualityIncident`.
- kind: TEMPERATURE_EXCURSION·DAMAGE·**SPOILAGE(변질, 새 값)**·RECALL·EXPIRY·CONTAMINATION·OTHER — 직접 등록은 모두 받는다.
- LOT 은 **이 센터에 재고가 남은 LOT** 만(mfc-inventory `stock-units?lotId=` 로 확인 — 센터 관리자 토큰 그대로). 아니면 422 `LOT_NOT_IN_CENTER` + details(`field: lotId`, `reason: <lotId>`). 재고 조회가 안 되면 503.
- **자동 격리는 하지 않는다**(판단 바꿈): 격리 판정은 줄마다 선반 위치가 필수인데 사고 등록에는 LOT·수량만 있다. 사고 상세에서 지금의 품질 > 격리·해제 요청 흐름으로 보내 주세요(아래 R4 처럼 `incidentId` 를 실으면 사고와 이어진다).

### R3 자동
- mfc-fulfillment `LineShortageReported` 중 **reason DAMAGED 만**(SHORT·BLOCKED 는 열지 않음) → kind DAMAGE·origin AUTO·`sourceEvent` LineShortageReported·`sourceRef` = 사건 ID.
- 같은 센터·LOT 의 **닫히지 않은 자동 파손 사고가 있으면 거기에 수량을 더한다**(한 건으로 묶음).
- **변질(SPOILAGE) 자동은 아직 없다** — 결품 보고 사유가 SHORT·DAMAGED·BLOCKED 셋뿐이라, PDA 결품 사유·fulfillment 실적·결품 사건에 값을 더해야 한다(다음 단계로 따로 — 지금은 직접 등록으로).
- 온도 이탈·리콜은 발행자가 생기면.

### R4 상태
- OPEN → **DECIDED**: 영향 LOT 마다 **격리 해제(RELEASE) APPLIED** 또는 **폐기 승인(APPROVED·PARTIALLY_EXECUTED·COMPLETED)** 이 있을 때.
- 사고와 판정 잇기 — 둘 다:
  - 판정 요청 `POST /quality-decisions` 의 **`incidentId`**(이미 계약에 있는 칸)를 실으면 그 판정은 늘 이 사고 것.
  - 실지 않아도 **같은 센터·같은 LOT·사고가 열린 뒤** 만든 해제·폐기는 처리로 본다(폐기 요청에는 incidentId 칸이 없어 이것으로).
- `decisionIds` = 이 사고로 이어진 판정 + 처리로 본 판정·폐기.

### R5 닫기 — `POST /quality-incidents/{id}/closures`
- 본문 `{reason}`(1~300), `X-Expected-Version`(사고 `version`), 멱등키. 그 센터 `MFC_SUPERVISOR` 만.
- 남은 LOT → 409 `LOTS_NOT_RESOLVED` + details(`field: lotId`, `reason: <lotId>` 남은 것마다). 이미 닫힘 → 409 `INVALID_STATE`. 버전 다름 → 409 `VERSION_CONFLICT`.

### R6 (2차 — 다음)
- 영향 LOT 이 나간 고객 주문을 알아내는 조회(mfc-inventory)와 사고 사건 발행(mfc-quality)은 PC 3, case 자동 열기·센터 관리자 case 조회는 case-service(PC 2) 요청. `caseId`·`affectedOrderCount` 는 그때 채운다.

### 게이트웨이(R 4절)
- `/quality-incidents*` 는 이미 quality 라우트(`/api/v1/quality-incidents/**`)에 걸린다 — POST 둘 포함. 허용 목록 2줄(POST)은 PR 안.

## 2. 상품 취급 (mfc-inventory 0.27.0)

### R7 단종
- product-service `ProductSaleStateChanged`(commerce.product.v1) 의 **state `DISCONTINUED` = 단종**(새 값을 만들지 않음 — 이미 ON_SALE·TEMP_OUT·DISCONTINUED). 순서는 사건 `version`(없으면 시각).
- 단종이 오면 **그 상품을 켜 둔 모든 센터를 OFF**(이력·`SkuHandlingChanged`, `changedBy` = `product-service`, `reason` = "본사 단종"). 단종인 동안 켜기 → **409 `SKU_DISCONTINUED`**(일괄은 그 행 사유).
- **일시 품절(TEMP_OUT)·판매 재개(ON_SALE)는 센터 취급을 건드리지 않는다** — 재개 뒤 다시 켜는 것은 센터가.
- `SkuHandling` 추가 칸: `saleState`(사본, 모르면 null)·`offReason` — `DISCONTINUED`(본사 단종 — 켜기 단추 막기) / `CENTER`(센터가 끔) / null(정한 적 없음·ON).

### R8 신상품 배너
- `GET /mfcs/{mfcId}/sku-handlings?undecided=true&introducedSince=<시각>` — **이 센터가 한 번도 정하지 않은 상품만**(레코드 없음, **단종 제외**), `introducedSince` = 그 뒤 생긴 상품만. 응답 **`totalCount`**(거르기에 맞는 전체 — 배너 숫자, 쪽과 무관).
- `SkuHandling.introducedAt` = 상품이 생긴 시각(SkuIssued 의 `issuedAt`). **이번 배포 전에 생긴 상품은 null** — `introducedSince` 로 거르면 빠진다(배너에 예전 상품 1만 개가 뜨지 않게).
- ON 이든 OFF 든 한 번 정하면 레코드가 생겨 배너에서 빠진다.

## 3. 배포·설정(PC 1)
- mfc-quality 가 **mfc.fulfillment.v1 을 새로 구독**: pmart-config `mfc-quality-service.yml` 의 `spring.cloud.function.definition: inboundEvents;fulfillmentEvents`, 바인딩 `fulfillmentEvents-in-0.destination: mfc.fulfillment.v1`(시험 사본은 PR 안). 없으면 자동 사고만 안 열린다(나머지는 동작).
- mfc-inventory 는 원래 구독하던 commerce.product.v1 에서 `ProductSaleStateChanged` 를 더 읽는다(설정 변경 없음).

## 4. 시험(PC 3)
- mfc-quality check 통과 — 새 `IncidentIT` 2(직접 등록 권한·센터 밖 LOT 422·남은 LOT 409·해제+폐기로 DECIDED·버전 409·닫기·다시 닫기 INVALID_STATE·목록 거르기·다른 센터 403 / 결품 보고 DAMAGED 자동·같은 LOT 묶음·SHORT 안 엶).
- mfc-inventory check 통과 — `SkuHandlingFlowIT` 에 R7·R8(신상품 undecided·introducedAt·totalCount, 두 센터 켬 → 단종 → 모두 OFF·offReason·changedBy, 단종 신상품 배너 제외, 켜기 409).
- 게이트웨이 허용 목록 시험 통과.
