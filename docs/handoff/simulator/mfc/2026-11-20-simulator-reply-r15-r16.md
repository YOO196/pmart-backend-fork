# [pmart-backend → pmart-mfc-simulation] 회신: R-15 칸 단위 재고 · R-16 SKU 여러 개 재고 — 만듦 (2026-11-20)

- 보낸 영역: mfc(PC 3)
- 답하는 것: `pmart-mfc-simulation/docs/backend-requests.md` R-15(칸 단위 재고 조회)·R-16(SKU 여러 개 재고 한 번에)
- 서버: mfc-inventory **0.33.0**(계약 추가만 — 하위 호환, Flyway 없음), 브랜치 `mfc/sim-r15-r16`. LAN 은 PC 1 병합·배포 뒤.

## R-15 칸 단위 재고 — 새 경로 대신 기존 `stock-units` 를 넓힘
- `GET /api/v1/mfcs/{mfcId}/stock-units`(0.26.0 부터 있던 센터 재고 목록 — 센터 관리자 웹이 씀)에 거르기를 더했다. 경로·권한(그 센터 운영자, 역할 무관 — `/stock` 과 같다) 그대로라 게이트웨이 라우트도 이미 있다.
- 요청에 적은 `location-stock` 은 만들지 않았다 — 같은 행(위치·SKU·LOT·품질·업무 상태·수량)을 이미 위치 순으로 주는 경로가 있어서.
- query(모두 선택, 함께 쓰면 AND):
  - `locationCode`(정확히), **`locationPrefix`**(앞부분 — 예 `C-B1-01` 이면 그 선반 줄 전체, 대소문자 구분)
  - **`zone`**(mfc-location ZoneKind — STORAGE·PICKING·PACKING·CART·DISPATCH_STAGING 등)·**`temperatureZone`**(AMBIENT·CHILLED·FROZEN)
  - `sku`·`lotId`·`qualityState`·`workState`, `size`(1~200, 기본 50)·`cursor`(응답 `nextCursor` 그대로)
- 응답: `{ asOf, items: [{ locationCode, sku, productName, lotId, lotNo, expiryDate, qualityState, workState, qty, zone, temperatureZone }], nextCursor }`
  - 수량 > 0 인 행만, **위치 → SKU → LOT → 품질 → 업무 순**. 한 칸의 행은 이어서 나온다(쪽 경계에서 나뉠 수는 있음).
  - `zone`·`temperatureZone` 은 mfc-inventory 가 mfc-location 사건(`MfcLocationStateChanged`)으로 받아 둔 **위치 사본** 값이다(다른 서비스 DB 를 읽지 않음). 사본에 없는 위치(예 옛 `MIGRATION`)는 항목에서 null 이고, `zone`·`temperatureZone` 으로 거르면 빠진다 — 센터 전체를 그릴 때는 이 둘 없이 읽는 것을 권한다.
  - `asOf` = 그 쪽을 읽은 시각. 쪽마다 따로 읽으므로 센터 전체가 한 순간의 사진은 아니다.
- 쓰는 법(SIM-5 주기 조회와 맞춤): 처음 한 번 거르기 없이(또는 `zone=STORAGE` 등) `size=200` 으로 `nextCursor` 가 null 일 때까지. 이후 3초 조회는 지금처럼 바뀐 칸·주문 SKU 만(`locationCode`·`locationPrefix` 또는 아래 R-16). 14,559칸 중 재고 있는 행만 오므로 쪽 수는 재고 행 수 ÷ 200.
- 주의: 이 목록에 LOT 요약(H/Q/E/R·가용)은 없다 — 그것은 상품별 `/stock`·`/stocks`.

## R-16 SKU 여러 개 — 형제 경로 `GET /mfcs/{mfcId}/stocks`
- `GET /api/v1/mfcs/{mfcId}/stocks?sku=A&sku=B…` — `sku` 반복 **1~100**(101개 이상이면 400, 없으면 400, 같은 값은 한 번).
- 응답 `{ mfcId, asOf, items: [CenterStock …] }` — **요청 순서대로** 지금 `/stock?sku=` 와 같은 `CenterStock`(productName·barcodes·lots·units). 재고가 없거나 모르는 SKU 도 빈 `lots`·`units` 로 하나씩 온다.
- 권한은 `/stock` 과 같다(그 센터 운영자, 다른 센터 403).
- 기존 `/stock?sku=` 를 배열로 바꾸지 않은 이유: PDA(mfc-app)·백오피스 생성 클라이언트가 단건 객체로 쓴다 — 응답 모양을 바꾸면 깨진다.
- **LAN 게이트웨이 라우트**: `/api/v1/mfcs/*/stocks` 를 PC 1 이 pmart-config 에 더해야 열린다(병합·배포 때). 그 전에는 게이트웨이에서 404 — 그때까지 지금처럼 SKU 마다 `/stock`.

## 시뮬레이터가 할 것(제안)
- 실서버 화면 전체 선반: `stock-units` 를 처음 한 번 끝까지 읽어 칸마다 수량을 그리고 "수량 모름"(회색)을 없앤다. 행이 없는 칸 = 재고 0.
- 주문 SKU 조회는 `stocks` 한 번(10개 제한을 100으로). 라우트가 열리기 전에는 404 를 보고 예전 방식으로.
