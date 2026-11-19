# 회신: BR-52 R19·R20 중 mfc-fulfillment 몫 — 패킹 온도대·장바구니로 주문 불러오기·아이스팩 수·냉장/냉동 취소품·복귀 예외 보고

- 보낸 영역: mfc (PC 3 백엔드)
- 작성: pmart-backend, 2026-11-20
- 답하는 것: PDA 요청 `pmart-clients` `docs/handoff/mfc-app/2026-11-10-backend-pda-work-areas.md` R19-2·R19-7(복귀만)·R19-8·R19-9·R20-5
- 서버: mfc-fulfillment **0.25.0**(V20, 브랜치 `mfc/br52-fulfillment` — #267(0.24.0) 위에 쌓음. LAN 은 PC 1 병합·배포 뒤)
- 사용자 결정(2026-11-20): R19-9 "아이스팩 수만 기록", R19-8 "항상 품질 검사"

## 한눈에

| 요청 | 결과 | PDA 가 할 일 |
|------|------|-------------|
| R19-2 패킹 줄 온도대 | **만듦** — 작업 상세 `lines[].temperatureZone` | 봉투 온도대를 이 값으로, null 이면 지금처럼 작업자가 고름 |
| R20-5 장바구니 찍어 주문 불러오기 | **만듦** — `GET /tasks?mfcId=&type=PACK&cartCode=CRT-…` | 패킹대에서 장바구니 스캔 → 이 목록 |
| R19-9 아이스팩 | **만듦(수만)** — 밀봉 `icePackCount`, 포장 응답 `icePackCount` | 수 입력칸(선택) |
| R19-8 냉장·냉동 취소품 복귀 | **만듦** — 서버가 늘 격리(QUARANTINED)로 복귀 | 확인 질문 없애도 됨, "격리로 복귀 — 품질 검사 대기" 표시 |
| R19-7 예외 보고(복귀) | **만듦(0.26.0)** — 파손·변질은 격리로 선반에, 없음·막힘은 처리한 양으로 세고 관리자 재고 정정 | 복귀 화면 예외 버튼 → 아래 본문 |
| R19-7 이동·보충·적치·실사 예외 | 안 함(mfc-inbound 몫) | — |

## 1. R19-2 줄 온도대
- `GET /tasks/{taskId}` → `lines[].temperatureZone`: `AMBIENT`·`CHILLED`·`FROZEN` 또는 null. PICK·PACK·RETURN 모두.
- 값은 mfc-inventory 상품(SKU) 사본의 보관 유형을 **조회 때** 읽는다(바코드와 같은 조회 — 이미 만든 작업도 맞다).
- null = 재고 조회 실패·상품 정보 없음. 그때는 지금처럼 작업자가 고르고 "온도대 정보 없음" 경고.

## 2. R20-5 장바구니로 주문 불러오기
- `GET /api/v1/tasks?mfcId=<센터>&type=PACK&cartCode=<찍은 CRT-…>` — 그 장바구니로 **집품한**(PICK 실적 `toLocation`, 반영됐거나 결과 대기) 출고의 작업.
- `state` 를 주지 않으면 이때만 기본값이 **끝나지 않은 작업**(WAITING·READY·ASSIGNED·IN_PROGRESS·BLOCKED). 장바구니는 다시 쓰이므로 끝난 예전 주문은 안 나온다.
- 보통 한 건. 0건 = 그 장바구니로 집품한 열린 주문이 없음("장바구니를 다시 확인"). 여러 건이면 목록에서 고르게.
- 권한·응답 모양은 작업 목록과 같다(`items[]` TaskSummary — `taskId`·`shipmentId`·`orderNumber`·`pickedBy`). 고른 PACK 은 지금처럼 시작·상세.
- 새 경로가 아니라 기존 `/tasks` 의 조회 칸이라 게이트웨이 변경 없음.

## 3. R19-9 아이스팩 수
- 밀봉 `POST /shipments/{id}/packages` 본문에 `icePackCount`(0 이상 정수, **선택**). 안 보내면 기록 안 함(null).
- `Package.icePackCount`(밀봉 응답·`GET /packages/{id}`·출고 조회). 재포장은 이전 값 그대로, 포장 사이 재구성은 null.
- 신선 기한 확인·포장재 스캔은 받지 않는다(사용자 결정 — 수만).

## 4. R19-8 냉장·냉동 취소품 → 늘 품질 검사
- 복귀(RETURN) 실적에서 상품 온도대가 `CHILLED`·`FROZEN` 인 줄은 PDA 가 보낸 `resultQuality` 와 상관없이 서버가 **`QUARANTINED`** 로 기록·반영한다.
  - 재고: 도착 선반에 **격리**(판매 불가)로 놓인다. 센터 관리자가 mfc-quality 해제 판정(요청 → 다른 관리자 승인)을 해야 판매로, 아니면 폐기.
  - 격리 재고는 센터 재고 목록(`GET /mfcs/{mfcId}/stock-units?qualityState=QUARANTINED`)에 보인다.
- 서버가 온도대를 확인하지 못하면(재고 조회 실패) 실적을 기록하지 않고 **503** — 같은 Idempotency-Key 로 다시.
- PDA: 지금 목으로 둔 "되돌려도 되나요?" 질문은 없애도 된다. 복귀 줄 `temperatureZone` 이 CHILLED·FROZEN 이면 "격리로 복귀 — 품질 검사 대기"를 보여 주면 된다. 도착 칸은 지금처럼 `suggestedToLocations`(같은 온도대 선반).
- 상온 줄은 그대로(기본 NORMAL, 이상품이면 PDA 가 QUARANTINED).

## 5. R19-7 복귀 예외 보고 — 처리까지 (0.26.0, 사용자 결정 2026-11-20 "응")
> 0.25.0 에서는 기록만 했다. 2026-11-20 사용자 결정으로 사유별로 처리한다(브랜치 `mfc/return-exceptions`, mfc-fulfillment **0.26.0**).

- `POST /tasks/{RETURN 작업}/executions` 에 피킹 결품과 같은 모양 — **옮긴 줄과 섞지 않은 한 줄**만 따로:
  ```json
  { "executionType": "RETURN", "workerId": "w2", "fromLocation": "CRT-A-01",
    "lines": [ { "orderLineId": "L2", "sku": "E-…", "lotId": "LOT-…", "pickedQty": 0,
                 "shortage": { "qty": 1, "reason": "SHORT", "note": "장바구니에 없음" } } ] }
  ```
  - `fromLocation`·`lotId` 필수. qty ≤ 그 줄·LOT·위치의 남은 복귀량(아니면 409 `QTY_EXCEEDS_REMAINING`). 옮긴 줄과 섞으면 400.
  - 사유: `SHORT` 출발 칸에 없음·모자람 / `DAMAGED` 파손 / `SPOILED` 변질 / `BLOCKED` 도착 칸을 못 씀(가득·막힘·접근 불가).

| 사유 | 작업자가 줄 것 | 서버가 하는 일 | 결과 |
|------|---------------|---------------|------|
| `DAMAGED`·`SPOILED` | **`toLocation`(격리로 둘 선반) 필수** — 없으면 400 `TO_LOCATION_REQUIRED` | 일반 복귀와 같은 재고 MOVE 를 `resultQuality: QUARANTINED` 로(냉장·냉동 R19-8 과 같은 길) | 상품은 그 선반에 **격리·보관**(판매 불가) → 센터 관리자가 mfc-quality 해제·폐기. 실적 APPLIED·`appliedQty` = qty, 남은 복귀량이 준다. 재고가 거절하면 실적 REJECTED(다른 칸으로 다른 키로 다시) |
| `SHORT`·`BLOCKED` | `toLocation` 선택 | 재고 호출 없음 — 그 양을 **처리한 것으로 센다**(작업 줄 취소량) | 실적 APPLIED·`appliedQty` = qty, 남은 복귀량이 준다. 포장·장바구니 위치에 `CANCEL_RETURN_PENDING` 으로 남은 재고는 **센터 관리자가 기존 재고 정정(승인)** 으로 맞춘다 |

- 모든 줄이 옮겨지거나 예외로 처리되면 복귀 작업이 **COMPLETED**(예외 때문에 작업을 내놓을 필요 없음).
- 관리자 화면: 두 경우 모두 센터 결품 목록 `GET /shortages?mfcId=` 에 **`taskType: RETURN`** 줄로 남는다(`locationCode` = 복귀 대상이 놓인 위치, `lotId`·`qty`·`reportedBy`). SHORT·BLOCKED 줄이 재고 정정할 대상이다. 결품률 지표에는 넣지 않는다(PICK 작업 줄만).
- 고객 결품 사건(`LineShortageReported`)은 내지 않는다 — 취소된 주문의 복귀분이라 결품 사가 대상이 아니다.
- 같은 Idempotency-Key 로 다시 보내면 같은 실행(두 번 세지 않음).
- 0.25.0 때 기록만 한 예외 보고(appliedQty 0)는 처리한 양으로 세지 않는다 — 그 양은 아직 남은 복귀량이다.
- PDA: 복귀 화면 예외 버튼에서 파손·변질이면 격리 선반을 찍게(추천 목록 `suggestedToLocations` 를 써도 됨), 없음·도착 칸 막힘이면 칸 없이 보낸다. 도착 칸이 막혔지만 다른 칸에 둘 수 있으면 예외 대신 그 칸으로 일반 복귀하는 것이 낫다(BLOCKED 는 재고 정정 대상이 된다).

## 아직 정하지 않은 것 (서버가 안 함)
- 이동·보충·적치·실사 예외(R19-7 나머지)는 mfc-inbound 몫이라 이번에 안 함.
- 격리 구역(QRT)으로 옮기는 복귀는 하지 않았다 — 재고 MOVE 가 선반·피킹 구역만 받는다. 선반에 격리로 두는 지금 방식으로 "판매 전 품질 검사"는 지켜진다.
