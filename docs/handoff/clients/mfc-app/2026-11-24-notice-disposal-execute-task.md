# 알림: 다음 작업 받기에 새 작업 유형 `DISPOSAL_EXECUTE`(폐기 실행) — mfc-capacity 0.35.0

- 보낸 영역: mfc
- 작성: pmart-backend, 2026-11-24 (PC 3)
- 근거: 사용자 결정(2026-11-24, PC 5 라이더 백엔드 전달) "폐기 실행을 MFC 작업자에게 할 일로 자동 배정" — 회수해서 MFC 에 돌려놓은 음식이 상했으면 폐기하고 재고를 뺀다. 업무·순서·승인자 제외는 PC 3 질문에 사용자 답.
- 계약: `mfc-capacity-service.yaml` **0.35.0**(PR 병합 뒤 main 에서 `gen:api`). 실행은 기존 `mfc-quality-service.yaml` `POST /disposal-approvals/{disposalApprovalId}/executions`(MFC_WORKER·MFC_SUPERVISOR — 바뀐 것 없음). LAN 반영은 PC 1 배포 뒤.

## 바뀐 것
1. `POST /mfcs/{mfcId}/workers/{workerId}/next-tasks` 가 `taskType: DISPOSAL_EXECUTE` 를 줄 수 있다. `taskId` = **disposalApprovalId**(mfc-quality 폐기 승인 ID).
   - 업무 **INSPECTION(품질검사)** — `workArea: INSPECTION` 으로 받는다. 순서는 회수품 검수(RETURN_INSPECT) 뒤, 재고 실사 앞.
   - 그 폐기를 **승인한 센터 관리자에게는 주지 않는다**(다른 작업자가 실행).
   - 작업자 업무(`duty.taskTypes`)에 `DISPOSAL_EXECUTE` 가 있어야 받는다 — 기존 작업자에게 자동으로 넣지 않으므로 센터 관리자가 작업자 업무에 추가한다(배정 화면 업무 목록에 새 값).
2. 응답 `detail.lines[]` = 남은 양이 있는 폐기 줄: `sku`·`lotId`·`qty`(= 남은 양 remainingQty)·`fromLocation`(= 격리 칸 locationCode)·`notice`(= 폐기 사유). 상품명은 비어 있다 — 필요하면 mfc-quality `GET /disposal-approvals/{id}` 나 SKU 조회.
3. 실행: 화면에서 `POST /api/v1/disposal-approvals/{taskId}/executions`(헤더 Idempotency-Key·X-Source-System, 본문 `lines[{sku, lotId, locationCode, qty}]`, `note` 선택). 한 번에 다 하지 않아도 된다(PARTIALLY_EXECUTED) — 남은 양이 있는 동안은 "들고 있는 작업"으로 다시 온다. 다 실행해 COMPLETED 가 되면 다음 작업 받기에서 새 작업을 준다.

## 화면에 필요한 것(제안)
- 작업 유형 이름 "폐기 실행", 품질검사 업무 묶음 안.
- 상세: 격리 칸·상품·LOT·남은 수량·폐기 사유, 줄마다 실행 수량 입력(기본 = 남은 양) → 실행 버튼.
- 오류: 422 QTY_EXCEEDS_APPROVED(남은 양보다 많음), 409(이미 끝남) → 다음 작업 받기 다시.
