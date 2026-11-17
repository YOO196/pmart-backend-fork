# [pmart-backend → pmart-clients] 회신: PDA 재고 확인·오늘 업무 내역 (2026-11-17)

- 보낸 영역: platform(PC 1) — 받은 요청 2건을 백엔드 담당(mfc = PC 3)에 넘기고, 이미 답할 수 있는 것은 여기서 답한다.
- 원 요청: `docs/handoff/mfc-app/2026-11-17-backend-pda-stock-check.md`, `…-backend-my-work-history.md`

## 재고 확인
- **Q1 `GET /mfcs/{mfcId}/stock?sku=`**: 이미 **그 센터 직원이면 역할 상관없이** 된다(MFC_WORKER 자기 센터 OK, 다른 센터 403). 화면 그대로.
- **Q2 `GET /barcodes/{barcode}`**: **직원 토큰이면 누구나**(MFC_WORKER 포함, 센터 무관). 2026-11-17 부터 고객·라이더 토큰은 403. 404 = 등록 안 된 바코드.
- **Q4 상품명**: 재고 응답에 `productName` 을 넣어 달라고 PC 3 에 요청(`service-requests/mfc-inventory-service.md` IV-2). 바코드 응답(`BarcodeResolution`)에는 지금 이름이 없다 — 재고 응답에 생기면 그것을 쓴다.
- **Q3 칸으로 재고 찾기**: 기존 MB-1 과 같은 요청으로 PC 3 대기.

## 오늘 업무 내역
- R1~R4 를 PC 3 에 요청(`service-requests/mfc-fulfillment-service.md` F-7, PC 3 할 일 목록). 서비스 경계(어디서 모아 줄지)는 PC 3 가 정한다. 생기기 전까지 지금 화면(오늘 `lastActivityAt` 으로 고르기 + "입고·검사… 아직" 안내) 그대로.

## 피킹 도착지 = 장바구니 (`2026-11-17-backend-pick-to-cart.md`)
- 백엔드 요청서 `service-requests/mfc-fulfillment-service.md` **F-6**(P1~P4 그대로 옮김) + `mfc-inventory-service.md` **IV-3**, PC 3 할 일 목록 맨 위(급함). 담당은 백엔드 mfc 영역(PC 3). 같이 고칠 서버 오류: `GET /shipments/{id}` 가 `ready_at` 이 빈 출고에서 500.

