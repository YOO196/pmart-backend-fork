# 회신 덧붙임: BR-52 격리 이동(R19-5)·현장 예외 처리(R19-7)·신선 여부(R12)·해제분 적치(R7)

- 보낸 영역: mfc
- 작성: pmart-backend(PC 3), 2026-11-20
- 답하는 것: 같은 날 회신 `2026-11-20-reply-br52-inbound-quality.md` 의 "이번에 못 함·남은 것"(R12 fresh, R19-5, R19-7 후속, R7 해제분 적치) — 사용자 결정(2026-11-20 3~6번)으로 이번에 만든 것.
- 계약: `mfc-inbound-service.yaml` **0.17.0**, `mfc-inventory-service.yaml` **0.35.0**(같은 버전에 덧붙임). 두 PR 이 병합·배포된 뒤 main 에서 `gen:api`. LAN 반영은 PC 1 배포 뒤.

## 0. 요약

| # | 답 |
|---|---|
| R12 fresh | 반입 줄(`ReturnReceipt.lines[]`)·반납 대기 줄(`ReturnArrival.lines[]`) `fresh` — 온도대 CHILLED·FROZEN = true, AMBIENT = false, 온도대 모르면 null(사용자 결정 "신선 = 냉장·냉동") |
| R19-5 | **됨** — 작업 유형 `QUARANTINE_MOVE`, 실행 `POST /inbound-tasks/{taskId}/quarantine-moves`. 만든 사람에게 미리 배정(§1) |
| R19-7 | 예외 보고가 **재고에 반영**된다 — PICK 결품처럼 SHORT·BLOCKED = 분실 조사 보류, DAMAGED·SPOILED = 격리 + 격리 이동 작업(§2) |
| R7 | 격리 해제(mfc-quality RELEASE)되면 **적치 작업이 자동으로 생긴다**(`origin: RELEASE`, §3) |

## 1. 격리 이동 (R19-5)
- **언제 생기나·누구에게**(사용자 결정): 품질검사 격리 줄 → 그 검수를 실행한 사람, 회수품 검수에서 **폐기(DISPOSE)** 로 판정된 줄 → 그 회수품을 반입한 사람, 이동·보충·적치 예외의 파손·변질 → 보고한 사람. 작업 `assigneeId` 가 처음부터 그 사람이다(담당만 실행 — 409 `NOT_ASSIGNEE`).
- **어디서 보나**: mfc-capacity 다음 작업 받기에는 **아직 안 나온다**(요청 MC-2). 그 전까지 `GET /inbound-tasks?mfcId=&taskType=QUARANTINE_MOVE&state=READY` 에서 내 것(`assigneeId` = 나)을 연다.
- **작업 모양**: `InboundTask.taskType = QUARANTINE_MOVE`, `origin` = INSPECTION·RETURN·EXCEPTION, `locationCode` = 출발 칸(수령 구역·반품 위치·선반), `lotId`, `plannedQty`·`doneQty`·`exceptedQty`(잔량 = planned − done − excepted).
  단건 `GET /inbound-tasks/{taskId}` 의 `suggestedToLocations` = 격리 구역 칸(구역 QUARANTINE·온도대 = 상품 보관 유형, 위치 코드 순 — LAN 은 `QRT-A-01`·`QRT-C-01`·`QRT-F-01`).
- **실행**: `POST /inbound-tasks/{taskId}/quarantine-moves` `{toLocationCode, qty}` — 처리 방식은 적치와 같다(`InboundTaskExecution`: APPLIED / REJECTED / 503 UNKNOWN_RESULT 는 같은 Idempotency-Key 로 다시).
  격리 구역이 아니면 `rejectReason` `LOCATION_ZONE_MISMATCH`, 온도대가 다르면 `TEMPERATURE_ZONE_MISMATCH`.
- 옮긴 뒤에도 품질 해제·폐기는 그대로 된다(재고가 격리 구역을 따라간다 — mfc-quality 화면은 바뀐 것 없음). 옮기기 전에 해제·폐기되면 그만큼 작업 잔량이 줄어든다(`exceptedQty`).
- 회수품 검수 **전**(검수 대기)의 격리분은 옮기지 않는다 — 검수는 반품 위치에서 한다.

## 2. 현장 예외 처리 (R19-7 후속)
- 경로는 그대로: `POST /move-orders/{moveId}/exceptions`(이동·보충), `POST /inbound-tasks/{taskId}/exceptions`(적치). 응답 `TaskException` 에 `applyStatus`(APPLIED·REJECTED·UNKNOWN_RESULT, 예전 기록은 RECORDED)·`inventoryCommitId`·`quarantineTaskId`·`rejectReason`.
- **qty 를 생략하면 남은 양 전부** — 이동은 아직 집지 않은 양, 적치는 작업 잔량. 넘으면 409 `MOVE_QTY_EXCEEDED` / `TASK_QTY_EXCEEDED`.
- 이동·보충: 아직 집지 않은 몫에서만. 지시 수량이 그만큼 줄어든다 — 남은 것을 다 옮기면 COMPLETED, 아무것도 안 집었는데 0 이 되면 CANCELLED. **카트 위 물건의 파손은 이 경로가 아니다** — 내려놓은 뒤 품질 격리(mfc-quality)로.
- 적치: 작업 잔량에서 뺀다(`exceptedQty`), 0 이면 COMPLETED.
- 효과: SHORT·BLOCKED → 그 칸 분실 조사 보류(실사가 정리), DAMAGED·SPOILED → 그 칸 격리 + 보고자에게 격리 이동 작업(`quarantineTaskId`). 이동·보충은 ACTIVE 지시만(취소 중이면 409 INVALID_STATE).
- 재고 거절은 그 코드(409·422)로, 결과 모름은 503 — 같은 Idempotency-Key 로 다시 보내면 재고는 한 번만 반영된다.

## 3. 해제분 적치 (R7 남은 것)
- mfc-quality 격리 해제(RELEASE)가 끝나면 그 LOT 이 적치 전으로 남은 칸(처음 칸 또는 격리 구역 칸)에서 **적치(PUTAWAY) 작업**이 생긴다 — `origin: RELEASE`, `receiptId` = 품질 판정 ID, 담당 없음(다음 작업 받기로 배정). 실행·추천 칸은 보통 적치와 같다.
- 선반 칸에서 해제한 것은 그 자리에서 바로 판매 가능이라 작업이 없다.

## 4. 남은 것
- MC-2: 다음 작업 받기에 `QUARANTINE_MOVE`(미리 정한 담당에게만) — 우선순위·업무 영역은 사용자 결정.
- ~~수령 구역(적치 전)의 분실 조사 보류는 실사(선반 칸만)로 정리되지 않는다 — 정리 방법 결정 필요.~~ → 추가분 §6(센터 관리자 재고 정정).

---

## 추가분 (2026-11-20 오후, 사용자 결정 A·B)

## 5. 반품 정상 판정분은 다시 적치 (A — 이미 됨, 바뀐 것 없음)
- 사용자 결정: 반품 검수에서 **폐기(DISPOSE)** 판정 줄만 격리 구역으로(§1 그대로), **정상 판정은 다시 적치**.
- 지금 흐름(mfc-inbound 0.7.0 부터, 코드 확인): mfc-quality 회수품 검수 `verdict: RESALE`(= 정상 판정) → mfc-inventory 품질 반영 RELEASE(반품 위치 그대로 격리 → **NORMAL·UNSTOWED**) → `ReturnInspected` → mfc-inbound 가 줄마다 **적치(PUTAWAY) 작업**(출발 = 반품 위치, `receiptId` = 반입 ID, `origin` null, 담당 없음). 반입 단위로 한 번(`inspectionState` INSPECTED).
- 화면: 보통 적치와 같다 — 단건 `GET /inbound-tasks/{taskId}` 의 `suggestedToLocations`(진열 규칙 통과 칸), 실행 `POST /inbound-tasks/{taskId}/putaways`. 반입 단건 `GET /return-receipts/{id}` 의 `putawayTaskIds`.
- 시험: `ReturnReceiptIT`(재판매 1·폐기 1 → 적치 작업 1·격리 이동 작업 1).

## 6. 수령장(적치 전) 분실 조사 보류 정리 = 센터 관리자 재고 정정 (B — mfc-inventory 0.37.0, mfc-inbound 0.18.0)
- 사용자 결정: 적치 예외 SHORT·BLOCKED 로 수령·반품 구역에 걸린 보류는 **센터 관리자(MFC_SUPERVISOR) 재고 정정**으로 푼다. 선반 칸 보류는 전처럼 실사.
- 센터 관리자 웹: `POST /mfcs/{mfcId}/stock-corrections` 에 `holdResolutions[]` `{sku, lotId, locationCode, foundQty, lostQty}`(`lines` 는 이제 선택). 한 줄 = 한 칸·LOT, **foundQty + lostQty = 그 칸·LOT 의 열린 보류 합**(다르면 409 `HOLD_QTY_MISMATCH` — details reason `openHoldQty=<n>`, 보류가 없으면 409 `NO_OPEN_HOLD`). 권한·승인 번호는 선반 정정과 같다(작업자 403, 본사 기준 이상이면 422 `APPROVAL_REQUIRED` — 못 찾은 양이 손실 금액에 들어간다).
  - 정정 줄의 값은 적치 예외 기록 `GET /task-exceptions?mfcId=`(적치 `targetType: PUTAWAY`, `reason` SHORT·BLOCKED)의 `locationCode`·`lotId`·`sku`·`qty` 로 채울 수 있다.
- 효과: 찾음 = 보류만 풀림 → mfc-inbound 가 그 칸·LOT 에서 **적치 작업**(`origin: HOLD_FOUND`, `receiptId` = 원래 적치 작업의 입고 건, 담당 없음)을 만든다. 못 찾음 = 재고에서 뺌(손실, `StockAdjusted`).
- PDA 쪽: 새 작업은 보통 적치와 같다(`origin` 표시값 `HOLD_FOUND` 만 새로 — 모르는 값이면 보통 적치로 보여도 된다).
