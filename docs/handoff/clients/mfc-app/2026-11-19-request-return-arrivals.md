# 요청: 반납 도착 대기 화면 — 회수품 반입을 대기 건에서 고르기 (MI-1·MI-2)

- 보낸 영역: mfc (PC 3 백엔드)
- 작성: pmart-backend, 2026-11-19
- 대상: pmart-clients `mfc-app`(PDA), `web/mfc-backoffice`(보기만이면 선택)
- 서버: mfc-inbound **0.13.0**(PR 브랜치 `mfc/return-arrivals` — LAN 은 PC 1 배포 뒤), mfc-quality 0.7.0

## 왜
라이더가 회수품을 MFC 에 반납하면 배송 쪽은 끝나지만, 점원은 회수품이 온 줄 몰랐고 반입 본문의 `claimId`·`returnPickupId` 를 알 길이 없었다 → 반입·검수·환불이 멈춤. 이제 서버가 반납 사건을 받아 **센터별 대기 목록**을 만든다.

## 서버
- `GET /api/v1/return-arrivals?mfcId=&state=WAITING`(기본 WAITING, 오래된 순, 그 센터 운영자)
  - 항목: `arrivalId`, `purpose`(CLAIM_RETURN 고객 반품 / MISDELIVERY_RECOVERY 오배송 회수 / FAILED_DELIVERY_RETURN 전달 실패 반납), `claimId`·`caseId`·`orderId`·`riderId`, `lines[{orderLineId, sku, productName, qty}]`, `arrivedAt`, `state`, `returnReceiptId`.
- 반입 `POST /api/v1/return-receipts` 본문 `{ mfcId, toLocationCode, arrivalId, lines? }`
  - `arrivalId` 를 주면 claimId·returnPickupId·orderId·주문 줄 번호는 **서버가 채운다**(보내지 않아도 됨).
  - `lines` 생략 = 대기 줄 그대로. 실제 받은 수량이 다르면 스캔한 줄(`sku`·`qty`, 소비기한·상태 메모)을 보낸다 — 주문 줄 번호는 서버가 수량대로 나눠 붙인다.
  - 409 `ARRIVAL_ALREADY_RECEIVED` = 다른 사람이 이미 반입(목록 새로고침). 404 `RETURN_ARRIVAL_NOT_FOUND`.
- 반입하면 대기에서 빠진다(재고가 거절한 반입이면 다시 대기로).

## 화면에 바랄 것
1. 회수품 반입 메뉴 첫 화면 = 대기 목록: **주문번호 + 종류**(고객 반품 / 오배송 회수 / 배송 실패 반납) · 도착 시각 · 상품 줄(상품명 × 수량). 시스템 ID(`DLV-…`·`CLM-…`)는 목록에 보이지 않게(ADR-0070) — 주문번호는 지금 응답에 없으니 당장은 "종류 · 도착 시각".
2. 고르면 반품 위치 스캔 → 상품 스캔(수량 확인) → 반입. claimId 를 손으로 넣는 칸은 없앤다.
3. 오배송 회수·전달 실패 반납은 claim 이 없다 — 검수 결과가 환불로 이어지지 않는다는 안내 한 줄(환불·재배송은 고객센터).
4. 검수 화면(센터 관리자)은 그대로 — `claimId` 는 이제 선택(반입에서 가져옴). 주문 줄 번호도 보내지 않아도 된다.
