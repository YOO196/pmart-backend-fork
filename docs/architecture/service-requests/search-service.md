# search-service 에 대한 요청

받는 영역: platform(PC 1). 요청한 쪽이 지금 어떻게 버티는지와 생기면 바꿀 것을 같이 적는다.

## SR-MFC-1. 재고·LOT 운영자 검색 — 색인 `mfc-lots` + 운영자 검색 경로 (2026-11-25, 보낸 영역 mfc — PC 3)
- **근거**: MFC 백오피스 요청 pmart-clients `docs/handoff/mfc-backoffice/2026-11-25-backend-inventory-search.md`(R1~R4). 사용자 2026-11-25 "웹에 쓰이는 검색도 고객앱 참조해서 엘라스틱서치로 처리해줘"·"지난 LOT 도 넣어".
- **사용자 결정(2026-11-25, PC 3 이 물음 — 추천안 "그렇게 해")**: ① 색인은 **search-service 쪽**(ADR-0031 — search 소유 Logstash 가 사건을 색인, 서비스는 조회만; 고객앱과 같은 분석기). mfc-inventory 에 ES 를 넣지 않는다. ② 다 나간 **지난 LOT 은 마지막 움직임 뒤 1년**까지 검색에 남긴다(원장은 지우지 않음 — 그 뒤는 재고 이력 `stock-ledger?sku=` 로만).
- **mfc 쪽에서 한 것(PC 3, mfc-inventory 0.50.0)**:
  - 사건 `MfcLotStockChanged`(`contracts/events/MfcLotStockChanged.v1.json`, 토픽 **`mfc.lot-stock.v1`**, 집계 키 `mfcId:lotId`) — LOT 하나의 지금 모습: lotId·lotNo·sourceLotRef·tracking·expiryDate·manufactureDate·sku·productId·productName·barcodes[]·storageType·H/Q/E/R·locations[{locationCode, qualityState, workState, qty, temperatureZone}]·depleted·firstReceivedAt·lastMovedAt·retainUntil·version. H·Q·E·칸이 바뀐 확정 단위마다(예약 R 만 바뀐 것은 안 냄).
  - 재발행 `POST /api/v1/internal/lot-stock-republications?mfcId=`(수량 있는 LOT + 1년 안 지난 LOT, republished=true) — 처음 색인 채우기·복구·상품명 바뀐 뒤.
- **필요한 것(search-service, PC 1)**:
  1. **Kafka 토픽 `mfc.lot-stock.v1`** 만들기(LAN `lan.sh deploy pc2 kafka` 쪽 — 재색인 재생을 하려면 `mfc.sku-handling.v1` 처럼 무제한 보관, 아니면 재발행으로 채움). 토픽이 없으면 outbox → Debezium 라우팅이 막히니 **mfc-inventory 0.50.0 배포 전에** 만든다.
  2. Logstash 파이프라인에 `mfc.lot-stock.v1` → 색인 `mfc-lots`(문서 ID `mfcId:lotId`, `version` 외부 버전 — 큰 것만). 상품명은 `products` 와 **같은 분석기**(형태소·동의어·오타 교정·초성 `nameChosung`)로, sku·바코드·lotNo 는 앞부분 일치(edge n-gram 또는 prefix), sourceLotRef 는 keyword+text. **`retainUntil` 이 지난 문서는 지운다**(주기 delete-by-query, 예 하루 한 번).
  3. 운영자 경로(경로·모양은 PC 1 이 정해도 됨 — 화면은 맞춘다):
     - `GET /api/v1/search/mfc-lots?mfcId=&q=&state=IN_STOCK|DEPLETED|ALL&qualityState=&temperatureZone=&expiryBefore=&sort=EXPIRY|RECENT|RELEVANCE&size=&cursor=` → `{items[{lotId, lotNo, sku, productName, expiryDate, tracking, onHandQty, quarantinedQty, sellableQty, locationCount, firstLocationCode, firstReceivedAt, lastMovedAt, temperatureZones[]}], nextCursor, total, correctedQuery, asOf}`(요청서 R1·R3). 지난 LOT 은 수량 0.
     - (있으면 좋음) `GET /api/v1/search/mfc-sku-suggestions?mfcId=&q=` → sku·productName 최대 10개(그 센터 색인에 있는 상품, R4).
     - 권한: 그 센터 운영자(토큰 `centers` 에 mfcId), OPERATOR_ADMIN 모든 센터. 판매 중지·미취급 상품도 찾는다(이 색인은 재고 기준).
  4. 게이트웨이 라우트·허용 목록(OPERATOR).
  5. 처음 채우기: 토픽·파이프라인이 선 뒤 mfc-inventory 재발행을 센터마다 부른다(PC 1 — 운영 도구 호출).
- **지금(임시)**: 화면은 `GET /mfcs/{mfcId}/stock-units` 를 받아 화면 안에서 글자 그대로 거른다(수량 > 0 LOT 만, 4,000줄 상한).
- **요청한 쪽에서 바꿀 것**: MFC 프론트가 LOT 추적 첫 화면을 새 검색 경로로, 재고 이력 찾기 칸에 자동완성. R2(`stock-units?q=`)는 검색 경로가 생기면 화면이 그 결과의 sku·lotId 로 거르면 되어 따로 안 만든다.
- **한계(알림)**: 상품명이 바뀌어도 그 LOT 이 다시 움직이거나 재발행해야 색인이 바뀐다(상품 사건을 이 색인에 따로 잇지 않음 — 원하면 Logstash 가 `ProductVersionPublished` 로 sku 별 이름을 덮어써도 됨).
