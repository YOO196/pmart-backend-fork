# 회신 — 출하 약속 준수율 지표(mfc-fulfillment 0.35.0)

보낸 영역: mfc
받는 앱: mfc-backoffice
요청: pmart-clients `docs/handoff/mfc-backoffice/2026-11-24-backend-ship-promise-metrics.md`
작성: pmart-backend PC 3, 2026-11-24 — 사용자 결정(아래 "기준")
계약: **mfc-fulfillment-service 0.35.0**(`contracts/openapi/mfc-fulfillment-service.yaml`)

## 기준 (사용자 결정 2026-11-24)
- 서버에는 "출하 약속 시각"이 따로 없었다. 작업의 `promisedReadyAt` 은 실제로는 **PICK 시작 가능 시각**(즉시 주문 = 접수 시각, 예약 = 슬롯 시작 − 리드타임)이라 약속으로 쓸 수 없다.
- **약속 = 배차 요청 때 라이더에게 알린 예상 준비 시각**(`dispatchRequest.estimatedReadyAt` = 요청 시각 + 점원이 넣은 예상 포장 분). **배차 요청이 없는 출고는 세지 않는다.**
- **끝 = 포장 끝(출고 READY, 모든 포장 밀봉)** 시각 `readyAt`. 라이더 인계 시각은 쓰지 않는다(라이더 도착에 달림).
- 화면 문구 제안: "배차 요청 때 알린 준비 시각 안에 포장을 끝낸 출고 ÷ 그 시각이 지난 출고".

## `GET /metrics/ship-promises?from=&to=&mfcId=&bucket=`
- 요청서 모양 그대로 — `items[] {date, hour, mfcId, due, onTime, late, pending}`, `asOf`. `due = onTime + late`, `pending` = 약속이 아직 안 온 출고(분모 밖).
- 칸 = 약속 시각(Asia/Seoul)을 내린 값. bucket·기간 상한(HOUR 7일·DAY 92일·WEEK·MONTH 366일)·0건 칸 줄 없음·권한은 `/metrics/picking-lines` 와 같다(그 센터 운영자 = 토큰 센터만, OPERATOR_ADMIN 모든 센터).
- 취소된 출고 제외, 오배송 재발송 출고 포함. 출하 대기 뒤에 배차를 요청한 출고는 약속보다 먼저 끝났으니 onTime.
- 배포 전 지난 출고도 배차 요청 기록이 있으면 그대로 계산된다(새 기록 없음).

## 함께 바뀐 것 — 운영판 `GET /shipments/promise-summary`
- 같은 이유로 지금까지 즉시 주문이 피킹 내내 `overdue`, 피킹이 끝나면 `noPromise` 였다. 0.35.0 부터 **약속 = 배차 요청 예상 준비 시각**, 대상 = 아직 READY 전 출고, `noPromise` = 배차 요청 전. 응답 모양은 그대로 — 숫자 뜻만 맞춰졌다(초과 숫자가 크게 줄어든다).

## 게이트웨이
- `/api/v1/metrics/ship-promises` 라우트는 PC 1 이 pmart-config 에 넣은 뒤 열린다(허용 목록은 이 PR 에 있음).
