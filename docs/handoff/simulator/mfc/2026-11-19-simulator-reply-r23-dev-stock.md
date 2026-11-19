# 회신 — R-23 시험 입고(재고 채우기) 입구 (MI-3)

보낸 영역: mfc (PC 3)
- 작성: pmart-backend, 2026-11-19
- 대상: pmart-mfc-simulation `docs/backend-requests.md` R-23 (요청서 `docs/architecture/service-requests/mfc-inbound-service.md` MI-3)
- 상태: **구현**(PR 브랜치 `mfc/return-arrivals`, mfc-inbound **0.14.0**). LAN 은 PC 1 이 배포하고 LAN 설정에 `pmart.inbound.dev-arrivals.enabled: true` 를 켠 뒤.

## 정한 것
- 경로: `POST /api/v1/dev-initial-stock-loads` (헤더 `Idempotency-Key`·`X-Source-System`)
  ```json
  { "mfcId": "MFC-SEED-01", "lines": [ { "sku": "E-00000031", "qty": 120 } ] }
  ```
- 호출 계정: **그 센터 운영자 토큰**(관찰 계정 `claude-mfc-wrk`, 가상 작업자 `mfcw-pp-01~10` 모두 됨).
- 한도: 줄 1~40, SKU 당 1~500, 같은 SKU 두 번 400. 재고 서비스 SKU 사본에 없는 SKU 는 400 `UNKNOWN_SKU`(details 에 SKU).
- 설정이 꺼진 곳(운영 등)은 **404**. 같은 `Idempotency-Key` = 같은 입고 예정.
- 응답 201 = `InitialStockLoad`(state `EXPECTED`, approvalId `DEV-xxxxxxxx`, `receiptId`, 줄 상품명). INITIAL_STOCK 승인이 만드는 것과 같은 모양.

## 그다음은 지금 흐름 그대로(조정으로 넣지 않음 — seed 원칙 D2)
1. 현장 수령 `POST /api/v1/initial-stock-loads/{receiptId}/receipts` — **가상 작업자 토큰으로 불러도 된다**(그 센터 운영자면 누구나 — 요청 3번 확인). 그래서 `receive: true` 선택지는 만들지 않았다.
2. 수령 → 줄마다 품질검사(INSPECT) 작업 → `POST /inbound-tasks/{id}/inspections` → 적치(PUTAWAY) 작업 → `POST /inbound-tasks/{id}/putaways`. 다음 작업 받기에도 그대로 나온다.
- 센터 관리자 웹의 "초기 보유 수령 대기"에도 보인다(approvalId `DEV-` 로 구분).

## 하지 않은 것
- (선택 2) 서버가 SKU 고르기(`auto`) — 하지 않음. 출하 가능 수량은 `GET /api/v1/mfcs/{mfcId}/stock-units` 등으로 시뮬레이터가 보고 줄을 정해 주세요.
- 정식 입고(`/inbound-promises`)가 생기면 이 입구는 없앤다.
