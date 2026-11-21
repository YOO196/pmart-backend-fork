# 회신: 대시보드 시간대별 지표 · 센터 주문 수·매출 추이

- 보낸 영역: mfc
- 작성: pmart-backend PC 3, 2026-11-21
- 답하는 것
  - pmart-clients `docs/handoff/mfc-backoffice/2026-11-21-backend-hourly-metrics.md`
  - pmart-clients `docs/handoff/mfc-backoffice/2026-11-21-backend-center-order-metrics.md`
- 상태: PR(브랜치 `mfc/hourly-picking-metrics`). LAN 반영은 PC 1 이 배포한 뒤다.

## 1. 결품률(피킹 줄) 시간 칸 — 됨 (mfc-fulfillment 0.33.0)
- `GET /metrics/picking-lines?from&to&mfcId&bucket=HOUR|DAY|WEEK|MONTH`
  - `bucket` 을 빼면 DAY 다. 지금과 똑같이 동작한다.
- **칸 기준 시각**: 그 주문의 피킹을 처음 시작한 시각(Asia/Seoul)이다. 날짜 칸과 같은 기준이다.
- **줄**
  - `date`: 칸 시작 날짜. DAY 는 그날, WEEK 는 월요일, MONTH 는 1일, HOUR 는 그 시각의 날짜다.
  - `hour`: HOUR 일 때만 채운다. 칸 시작 시각이고 예시는 `2026-11-21T14:00:00+09:00` 이다. 그 밖에는 null 이다.
  - 나머지 칸(`mfcId`·`pickedLines`·`shortLines`·`shortLinesByReason`)과 0건 칸에 줄이 없는 규칙은 그대로다.
- **기간 상한**: HOUR 7일(168칸), DAY 92일, WEEK·MONTH 366일이다. 그래서 1년 보기는 한 번에 읽을 수 있다. 넘으면 400 이다.
- 첫 주·첫 달과 끝 주·끝 달은 from·to 로 잘린다. 그 기간 안의 날만 센다.
- 시간 칸을 더하면 그날 값과 같다(통합 시험으로 확인).
- 권한은 그대로다. 센터 운영자는 토큰 센터만, OPERATOR_ADMIN 은 모든 센터를 본다.

## 2. 정시 배송 시간 칸 — delivery-dispatch(PC 5)로 넘김
- 받는 서비스가 PC 5 영역이라 서비스 간 요청 **DD-6** 으로 적었다(`docs/architecture/service-requests/delivery-dispatch-service.md`).
- 제안한 기준 시각은 날짜 칸과 같은 "약속 시간대 끝"이다. 모양은 위 피킹 지표와 같게 해 달라고 했다.
- 생길 때까지는 화면에서 지금처럼 정시 배송의 "시간" 단추를 꺼 두면 된다.

## 3. 센터 주문 수·매출 추이 — order-service(PC 1)로 넘김
- order-service 는 PC 1 영역이라 **O-11** 로 적었다(`docs/architecture/service-requests/order-service.md`). 요청 1~4를 그대로 옮겼다.
- **사용자 결정이 필요한 것**
  - 센터 관리자(MFC_SUPERVISOR)에게 주문·매출 지표를 여는 일이다.
  - 지금 summary·top-products 는 2026-11-18 사용자 결정(O-8)으로 OPERATOR_ADMIN 만 볼 수 있다.
- **확인이 필요한 것**: 카테고리 원천이다. 주문 줄에 카테고리 사본이 있는지 PC 1 이 확인한다.
- 매출 정의(승인 금액, 환불 빼지 않음)는 요청서와 같다.
