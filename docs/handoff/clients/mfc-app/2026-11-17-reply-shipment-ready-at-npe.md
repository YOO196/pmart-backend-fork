# 회신: 출고 조회 READY 전 500(ready_at NULL NPE) — S1·S2

- 보낸 영역: mfc
- 작성: pmart-backend, 2026-11-17
- 답하는 것: pmart-clients `docs/handoff/mfc-app/2026-11-17-backend-shipment-ready-at-npe.md`(브랜치 `mfc-app/pick-to-cart`, 커밋 e455b76).
- 계약 변경 없음(fulfillment 응답 모양 그대로). LAN 반영은 PC 1 배포 뒤.

## S1 — 고침
- 원인은 요청서 그대로: `TaskStore.shipmentReadyAt` 이 `query(...).stream().findFirst()` 로 꺼내는데, READY 전이면 `ready_at` 이 NULL 이라 `Optional.of(null)` 에서 NPE.
- 행이 없거나 값이 NULL 이면 null 을 돌려주는 `firstOrNull` 로 바꿨다. 출고 조회 `GET /shipments/{shipmentId}` 는 READY 전이면 `readyAt: null` 로 200.
- 같은 패턴을 MFC 서비스 전체(fulfillment·inbound·inventory 등)에서 찾았다. 값이 NULL 일 수 있는 칸은 하나 더 — `TaskStore.returns`(`task.returns`, 복귀 대상을 아직 정하지 않은 작업). 함께 고쳤다. 나머지는 NOT NULL 칸이거나 행 객체라 해당 없다.

## S2 — 시험
- `TaskFlowIT` 피킹·포장 흐름에 READY 전 출고 조회 두 번: 피킹 시작 직후(200·`readyAt` null), 첫 포장 밀봉 뒤 출고 READY 전(200·`readyAt` null·포장 1).
- `:services:mfc-fulfillment-service:check` — 단위 19·통합 27 통과(PC 3, 2026-11-17).

## PDA 쪽
- 고칠 것 없음(요청서와 같음). 배포 뒤 실서버에서 피킹 → 패킹 → 출하대기 다시 확인해 주세요.
