# 회신: 센터 운영판 결품 목록·출하 약속 건수(OE-1·OE-2), 포장 화면 번호(DL-1)

- 보낸 영역: mfc (PC 3 백엔드)
- 작성: pmart-backend, 2026-11-19
- 원 요청: pmart-clients `docs/handoff/mfc-backoffice/2026-11-19-backend-ops-exceptions.md`(OE-1·OE-2), `…/2026-11-19-backend-display-labels.md`(DL-1)
- 서버: mfc-fulfillment **0.21.0**(V18, PR 브랜치 `mfc/ops-lists` — LAN 은 PC 1 배포 뒤). DL-2 는 bff(PC 2, #248)가 넘기고 원천 `CenterShipment.packages[].handoverCode` 는 여기서 채움.

## OE-1 센터 결품 목록 — `GET /api/v1/shortages`
- 쿼리: `mfcId`(필수), `reason`(반복, SHORT·DAMAGED·BLOCKED), `from`·`to`(보고 시각, 이상·미만), `size`(≤200, 기본 50), `cursor`.
- 응답: `items`, `nextCursor`, **`total`**(같은 조건 전체 — 쪽과 무관), `asOf`. 최근 순.
- 항목: `shortageId`, `reason`, `taskId`·`taskType`·**`taskState`**(그 작업의 지금 상태), `shipmentId`, `orderId`·`orderNumber`, `orderLineId`, `sku`·`productName`, `lotId`, `locationCode`(피킹 칸), `qty`(보고 수량), `reportedBy`(운영자 ID → 이름은 capacity `staff-names`), `reportedAt`.
- 요청과 다른 점:
  - **처리 상태(`state`·`resolvedAt`)는 없다.** 이 목록은 재확보하지 못한 결품만이다. 같은 SKU 적격 LOT 으로 재확보되면 결품으로 남지 않는다. 그 뒤 대체·부분 환불은 주문 쪽 결품 사가가 정한다. 대신 `taskState`(BLOCKED·IN_PROGRESS·CLOSED_PARTIAL 등)로 "아직 막힘인지"를 보인다.
  - 오늘 배포 전 보고는 `lotId`·`locationCode`·`reportedBy` 가 null 이다. 상품은 채웠다.
- 권한: 그 센터 운영자(작업자·센터 관리자), OPERATOR_ADMIN 은 모든 센터. 다른 센터 403, 잘못된 cursor 400.

## OE-2 출하 약속 요약 — `GET /api/v1/shipments/promise-summary?mfcId=&dueWithinMinutes=`
- 응답 `{ overdue, dueSoon, onTime, noPromise, dueWithinMinutes, asOf }` — **출고 수**.
- 대상: 취소 안 된 출고 중 PICK·PACK 이 끝나지 않은 것(출하 대기 전).
- 출고의 약속: 열린 PICK·PACK 작업 `promisedReadyAt` 중 가장 이른 것. 지금 화면이 작업 목록으로 세던 값과 같다.
- **임박 기본 15분**(사용자 2026-11-19 "임박 기준은 15분"), 1~240 바꿀 수 있음. 화면은 이 숫자로 "N건 이상" 없이 정확히 보이면 된다.

## DL-1 인계 판정에 화면 번호
- `GET /packages?mfcId=&checkRequired=true` 항목과 `GET /packages/{id}/handover-resolution`(그리고 점원 확인 응답)에 **`handoverCode`**(라벨 6자리)·**`orderNumber`**(8자리, 번호 전 주문은 null).
- 목록 표기 "305118 인계" — 값이 null 이면 지금처럼 "포장 n".
- **DL-2 원천도 됨**: 센터 출고 목록 `GET /shipments` 의 `packages[].handoverCode`(PC 2 요청 — bff 0.21.0 PR #248 이 그대로 넘긴다) → 포장 인계 현황 표 포장 줄도 라벨 번호로.
