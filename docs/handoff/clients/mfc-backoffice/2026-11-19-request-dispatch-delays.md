# 요청: MFC 백오피스 "배차 지연" 목록 + "배차 불가로 취소" (전달 — 원 요청 PC 5 delivery)

- 보낸 영역: mfc (PC 3 백엔드 — PC 5 delivery 요청을 웹 담당에게 전달)
- 작성: pmart-backend, 2026-11-19
- 대상: pmart-clients `web/mfc-backoffice`
- 원 요청: pmart-backend `docs/architecture/service-requests/mfc-backoffice-staff-apis.md` 끝 절(PC 5, PR #228 — 병합 전), `docs/operations/other-pcs-todo.md` PC 3 줄.
- 사용자 결정(2026-11-19): "1,2,3 전부", 쿠폰 2,000원, 취소 = 주문 취소 + 전액 환불.
- 계기: LAN 배송 35726513-1 이 74분 지연됐는데 MFC 관리자에게 아무 안내가 없었다(원문 Delivery §3.6).

## 데이터 (서버 — PC 5, delivery-dispatch 0.12.0)
- `GET /dispatch-delays?mfcId=` — 지연 안내(`DispatchDelayed`)가 난 **픽업 전**(CREATED·DISPATCHING·ASSIGNED) 배송.
  - 칸: `deliveryId`·`orderId`·`deliverySeq`·`state`·`riderId`·`promisedAt`·`delayAlertedAt`·`createdAt`.
  - 권한: 그 센터 운영자(`mfcId` 필수).
- 주문번호·배송 번호(ADR-0070): order 조회, delivery-execution `GET /deliveries?deliveryIds=`(묶음 조회).

## 화면
1. 대시보드·메뉴에 **"배차 지연"** 목록.
   - 주문번호·배송 번호, **약속 시각 대비 몇 분 지났는지**(지금 − `promisedAt`), 라이더 배정 여부(`riderId` 있음/없음).
2. 행마다 **"배차 불가로 취소"** 단추.
   - claim **CL-5**(사유 `DISPATCH_UNAVAILABLE`, `MFC_SUPERVISOR`) — **아직 없음(PC 1 에 요청됨)**. 생기기 전에는 단추를 숨기거나 막아 두고 **목록만 먼저** 만들어도 된다.
   - 확인 창 문구: "주문 전체 취소 · 전액 환불 · 고객에게 2,000원 쿠폰".

## 서버 쪽(PC 3 mfc) 할 일
- 없음 — 목록 API 는 dispatch(PC 5), 취소는 claim(PC 1). 계약은 PR #228 병합 뒤 `contracts/openapi/delivery-dispatch-service.yaml` 에서 `gen:api`.
