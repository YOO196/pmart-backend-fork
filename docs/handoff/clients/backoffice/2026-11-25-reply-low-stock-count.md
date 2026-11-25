# 회신 — 대시보드 "곧 품절" 상품 수·목록·실시간 (mfc-inventory 0.47.0)

보낸 영역: mfc
받는 앱: backoffice (MFC 백오피스도 같은 API)
요청: pmart-clients `docs/handoff/backoffice/2026-11-25-backend-low-stock-count.md`(LS-1~3), 요청서 `service-requests/mfc-inventory-service.md` LS-1~3
작성: pmart-backend PC 3, 2026-11-25 — 사용자 결정 "하루 판매량 기준"
계약: **mfc-inventory-service 0.47.0**(`contracts/openapi/mfc-inventory-service.yaml`)

## 기준
- 곧 품절 = 취급 ON 상품 중 **0 < A ≤ SS**, SS = d × safetyDays. d·safetyDays 는 mfc-inbound 발주 수량 추천과 **같은 값**(지난 averageWindowDays(28)일 인계 실적 하루 평균, 센터 첫 인계일부터 어제까지로 잘림, 공급 정책 표 센터 > 전 센터 기본). 지금 시작값 = 하루치.
- 공급 정책 중 **safetyDays 만** 쓴다. 판매가 없던 상품은 d = 0 → 곧 품절 아님.
- 기준은 1시간마다 새로 계산돼 들어온다(d 는 어제까지라 하루 한 번 바뀜). A 는 지금 값.

## LS-1 `GET /stock-summaries`
- 줄마다 `lowStockSkuCount`, `lowStockUnknownSkuCount`(A > 0 인데 SS 를 몰라 못 센 상품 — 0 으로 채우지 않음), `lowStockBasis`.
- `lowStockBasis` = `{ uniform, safetyDays, averageWindowDays, salesThrough, calculatedAt, policies[] }`. `uniform` true 면 한 줄("하루 판매량 이하로 남음 · 지난 28일 평균" = safetyDays 1·averageWindowDays 28), false 면 온도대마다 다름 → "센터 정책"으로. 기준을 아직 못 받은 센터는 **null**(그때는 A > 0 상품이 모두 unknown).

## LS-2 `GET /stock-summaries/{mfcId}/skus?kind=&size=&cursor=`
- kind `LOW_STOCK`(남은 날 `coverDays` = A ÷ d 적은 순) · `SOLD_OUT`(A ≤ 0, SKU 순 — 품절 목록도 이것) · `LOW_STOCK_UNKNOWN`(SKU 순, `unknownReason` BASIS_MISSING·STORAGE_TYPE_UNKNOWN·POLICY_MISSING·INSUFFICIENT_HISTORY).
- 줄 `{sku, productId, productName, storageType, availableQty, dailyQty, safetyQty, coverDays, unknownReason}`, `totalCount`, `nextCursor`(다음 쪽 — 없으면 null), size 1~200(기본 50). 권한은 `/stock-summaries` 와 같다.

## LS-3 실시간
- `/stock-stream` `summary-changed` 가 이제 **SS 경계**(곧 품절 ↔ 여유)에서도 온다(품절 경계는 그대로). 기준(d·SS)만 바뀌는 것은 보내지 않는다 — 다음 읽기에 반영.

## 게이트웨이
- `/api/v1/stock-summaries/{mfcId}/skus` 는 PC 1 이 pmart-config 라우트에 넣은 뒤 열린다(허용 목록은 PR 에 있음). `/stock-summaries` 새 칸은 배포만 되면 보인다.
