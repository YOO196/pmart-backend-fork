# 회신: 화면에 코드 그대로 남은 사유 값 — MFC 몫 C1~C3 (F-11)

- 보낸 영역: mfc (PC 3 백엔드)
- 작성: pmart-backend, 2026-11-19
- 원 요청: pmart-clients `docs/handoff/backoffice/2026-11-18-backend-code-names.md` C1~C3 (요청서 `docs/architecture/service-requests/mfc-fulfillment-service.md` F-11)
- 정본: `contracts/openapi/mfc-fulfillment-service.yaml` info 설명 "사유 값"(서버 동작은 그대로 — 설명만, 브랜치 `mfc/return-arrivals`)
- **목록 밖 값이 올 수 있다**: 재고 서비스가 새 거절 코드를 더하면 그대로 온다 → 모르는 코드는 코드 그대로 보여 주세요.

## C1 MFC 작업 중지 실패 `StopRequest.lastError`(처리 대기 `MFC_STOP_FAILED` reasonCode)
모양 `코드: 상세` — 이름표는 `:` 앞 코드로.
| 코드 | 이름표(제안) | 뜻 |
|---|---|---|
| `RECORD_MISMATCH` | 기록 불일치 | 집품·포장 기록이 재고와 맞지 않음 — 현장 확인 뒤 다시 시도 |
| `RETURN_MISMATCH` | 복귀 수량 불일치 | 되돌릴 위치별 수량 합이 집품·포장량과 다름 |
| `INVALID_STATE` | 상태 바뀜 | 예약이 이미 인계·소비 등으로 바뀜 |
| `VERSION_CONFLICT` | 동시 변경 | 같은 때 다른 변경 — 다시 시도 |
| `NOT_FOUND`·`…_NOT_FOUND` | 대상 없음 | 예약·위치를 찾지 못함 |
| `VALIDATION_FAILED` | 요청 값 오류 | 서버 요청 값 오류(개발 확인) |
- 목에서 쓴 `PDA_OFFLINE` 은 **실제로 없는 값**. 재고가 응답하지 않은 경우(결과 모름)는 실패가 아니라 서버가 다시 시도한다(lastError 없음).

## C2 작업·출고 막힌 사유 `blockReason`
**코드가 아니라 한국어 문장**이다. 두 가지로 시작한다:
| 앞부분 | 이름표(제안) | 뜻 |
|---|---|---|
| `예약 보류(격리·실사 손실 — 재확보 못 함): …` | 재고 보류 | 피킹 시작 때 재고가 보류(격리·실사 손실)한 양을 다시 확보하지 못함 |
| `결품 보고: …` | 결품 | 작업자가 결품 보고(사유 SHORT 부족·DAMAGED 파손·BLOCKED 보류), 다시 확보 못 함 |
- 뒤의 문장(`<주문 줄> <수량>개 …`)은 상세에만. 주문 줄은 시스템 ID 라 목록에 보이지 않게(ADR-0070).

## C3 포장 인계 거절 `handoverRejectReason`(처리 대기 `MFC_HANDOVER_REJECTED` reasonCode)
모양 `코드: 상세`.
| 코드 | 이름표(제안) | 뜻 |
|---|---|---|
| `INSUFFICIENT_STOCK` | 포장분 없음 | 포장 위치에 포장분이 모자람(다른 곳에 놓였거나 이미 나감) |
| `HANDOVER_QTY_EXCEEDED` | 인계 수량 초과 | 포장한 양보다 많이 넘기려 함 |
| `INVALID_STATE` | 주문 상태 바뀜 | 예약이 확정 상태가 아님(취소·보류) |
| `FENCE_VERSION_MISMATCH` | 다른 인계로 이미 넘김 | 같은 포장을 다른 인계 승인으로 이미 넘김 |
| `VERSION_CONFLICT` | 동시 변경 | 다시 시도 |
| `VALIDATION_FAILED` | 요청 값 오류 | 개발 확인 |
- `RECORD_MISMATCH` 는 C1 의 값이다(인계에는 나오지 않음).
