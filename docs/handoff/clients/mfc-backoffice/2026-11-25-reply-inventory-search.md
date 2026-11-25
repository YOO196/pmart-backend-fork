# 회신 — 재고·LOT 운영자 검색(Elasticsearch)

보낸 영역: mfc
받는 앱: mfc-backoffice
요청: pmart-clients `docs/handoff/mfc-backoffice/2026-11-25-backend-inventory-search.md`(R1~R4)
작성: pmart-backend PC 3, 2026-11-25

## 답(사용자 결정 2026-11-25 — PC 3 추천안 "그렇게 해")
- **색인은 search-service 쪽**(고객앱과 같은 ES·분석기, search 소유 Logstash). mfc-inventory 는 LOT 사건을 낸다. 그래서 검색 경로는 search-service 쪽이 된다 — 예 `GET /api/v1/search/mfc-lots`·`/search/mfc-sku-suggestions`(이름·모양은 PC 1 이 정함, 요청서 `docs/architecture/service-requests/search-service.md` SR-MFC-1 에 요청서 R1·R3·R4 모양 그대로 적음).
- **지난 LOT 은 마지막 움직임 뒤 1년** 검색에 남는다 — 화면 문구 "다 나간 LOT 은 1년까지". 그 뒤는 재고 이력(`stock-ledger?sku=`)으로만.
- R2(`stock-units?q=`)는 따로 만들지 않는다 — 검색 결과의 sku·lotId 로 `stock-units?sku=&lotId=` 를 부르면 된다.

## 지금 된 것 / 남은 것
- mfc-inventory 0.50.0(백엔드 PR, 병합 전): `MfcLotStockChanged` 사건(토픽 `mfc.lot-stock.v1`)·재발행 `POST /internal/lot-stock-republications`.
- 남은 것(PC 1): 토픽·Logstash 색인 `mfc-lots`·검색 경로·게이트웨이 — 생기기 전까지 화면은 지금 방식(받은 목록 안 거르기).
- 상품명이 바뀌면 그 LOT 이 다시 움직이거나 재발행돼야 색인이 바뀐다.
