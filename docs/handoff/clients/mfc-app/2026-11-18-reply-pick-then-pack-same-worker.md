# 회신: 피킹한 사람이 그 주문 패킹 (R1·R2·R3)

- 보낸 영역: mfc (PC 3 백엔드)
- 작성: pmart-backend, 2026-11-18
- 답하는 것: pmart-clients `docs/handoff/mfc-app/2026-11-18-backend-pick-then-pack-same-worker.md`
- PR: 브랜치 `mfc/pick-pack-same-worker` — mfc-fulfillment 0.18.0·mfc-capacity 0.19.0. LAN 반영은 PC 1 병합·배포 뒤(둘 다 — fulfillment 가 먼저 나가도 capacity 가 예전이면 예전 동작).

## R1 — 다음 작업 받기가 자기 PACK 을 먼저 (구현)
- fulfillment 작업 목록(`GET /tasks`) `TaskSummary.pickedBy` — PACK 만, 같은 출고 PICK 을 **끝낸** 담당(COMPLETED·CLOSED_PARTIAL). 그 밖 null.
- capacity `POST …/next-tasks`: 들고 있는 작업이 없을 때, 자기가 피킹한 출고의 READY PACK 이 있으면 **다른 어떤 후보보다 먼저**(주문 PICK·긴급 보충보다도). 업무 범위(workArea)·자격 검사는 그대로 — `PICK_PACK` 이고 PACK 자격이 있어야 한다.
- PDA 변경 없음(다음 작업이 PACK 이면 지금처럼 패킹 화면).

## R2 — 피커가 아닌 사람에게 주지 않음 (사용자 결정 2026-11-18 "피커만 받음")
- 다른 사람이 피킹한 PACK 은 다음 작업 받기 후보에서 뺀다. 배정 추천(OR-Tools)도 그 PACK 은 계산에서 뺀다(추천이 다른 사람에게 주지 않게).
- 피커가 휴게·퇴근·정지면 **센터 관리자 배정(`POST /assignments`)으로만** 다른 사람에게 — 장바구니를 넘겨받는 것은 현장 확인. 수동 배정은 막지 않는다.
- 피커를 모르는 PACK(배포 전에 만들어진 것 중 PICK 담당 기록이 없는 것)은 누구나.
- 주의: 피커가 PACK 자격이 없거나 `PICK` 만 고른 업무면 그 PACK 은 아무도 자동으로 못 받는다 → 센터 관리자 배정. 피패킹은 `PICK_PACK` 으로.

## R3 — LAN 에 남은 것
- `ORD-438b0cdc-…-S1-PACK`(READY, 담당 없음): fulfillment 가 PICK 담당을 기록해 두어서 배포 뒤 `pickedBy = OP-a666a1be-…` 로 보인다. 그 작업자가 들고 있는 `ORD-56cd4042-…-S1-PICK` 을 끝내고 다음 작업을 받으면 — 자기 PACK 이 둘(438b·56cd)이 되고 약속 이른 순(438b 먼저)으로 받는다. 배포 전에 처리하려면 센터 관리자 배정으로.

## 시험(PC 3)
- fulfillment check 통과(`TaskFlowIT.pickThenPackMakesShipmentReady` — 목록 PACK `pickedBy`=피커, PICK null).
- capacity 단위 54 통과(Docker, 새 `PickerPackTest` 3), 통합 33 중 30 통과 — 실패 3개는 `AssignmentPlanIT`(이 PC 앱 제어가 OR-Tools DLL 을 막음, PC 1 Linux 에서 확인 필요).
