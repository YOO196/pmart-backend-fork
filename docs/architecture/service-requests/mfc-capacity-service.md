# mfc-capacity-service 에 대한 요청

받는 영역: mfc(PC 3). 표는 `README.md`(PC 1).

## AP-1 참조. 승인 번호 실제 확인 — 본사 승인 기준·한 번만 쓰기 (2026-11-17, platform PC 1)
- 요청 본문은 `approval-service.md` **AP-1 "PC 3 이 할 것" 1~6**(이 서비스 몫: 운영 제한 해제 — `RestrictionService.lift`(기준 없이 본사 승인)). 기준(사용자 2026-11-17): 한 건 손실 10만 원 이상 또는 한 SKU 20개 이상이면 본사 승인, 그 아래는 센터 관리자. approval-service 쪽(승인 종류·`/internal/approval-ref-checks`·`/internal/approval-ref-uses`)은 구현됨(b969005d).
- **구현**(PC 3, 2026-11-17 — 운영 제한 해제 0.16.0, 브랜치 `mfc/approval-ref-check`): 회신은 `approval-service.md` AP-1 "회신(PC 3)". LAN 은 설정(기준 값·주소·VERIFY) 뒤에 켜진다 — PC 1.

## MC-1. 다음 작업 받기에 재고 실사(CYCLE_COUNT) (2026-11-20, 보낸 영역 mfc — 클라이언트 BR-52 R2·R14)
- **요청한 곳**: mfc-inventory(실사 계획, 계약 0.34.0) · 클라이언트 mfc-app BR-52 R2("재고 실사" 업무에 작업 유형)·R14.
- **필요한 것**: 업무 `STOCK_COUNT` → 작업 유형 `CYCLE_COUNT`, `taskId = sessionId`. 후보는 mfc-inventory `GET /internal/count-work?mfcId=`(계획 실사 중 계수 단계·예외 보고 없는 것, 기준 시각 오래된 순, 최대 500) — `excludedOperatorIds` 에 든 작업자(재계수면 첫 계수자)에게는 주지 않는다. `detail` 은 칸 하나(`locationCodes[0]`). 계수 제출은 앱이 mfc-inventory `POST /mfcs/{mfcId}/count-sessions/{sessionId}/counts` 로.
- **한 실사 = 한 사람**: mfc-inventory 는 누가 들고 있는지 모른다 — 같은 실사를 두 작업자에게 동시에 주면 두 번째 제출이 재계수가 된다. 배정 쪽에서 들고 있는 실사는 다른 사람에게 주지 않기(입고 작업과 같은 "한 번에 하나"). 끝난 판정은 count-work 에서 빠졌는지로.
- **지금(임시 방식)**: `STOCK_COUNT` 는 누구의 `workAreas` 에도 없다(2026-11-14 회신). 앱은 실사 시작·조회·계수 연결만(목).
- **요청한 쪽에서 바꿀 것**: 없음(필요하면 count-work 에 칸 수·SKU 수 같은 크기 값을 더한다).
- **사용자 결정 필요 여부**: 순서(다른 작업과의 우선순위 — 지금 표 `주문 → 보충·긴급 이동 → 품질검사 → 적치 → 관리자 이동 → 복귀` 어디에 넣을지). Q-08 전 임시 순서로 정하면 됨.
- 근거: `docs/handoff/clients/mfc-app/2026-11-20-reply-br52-inventory.md`, 계약 `contracts/openapi/mfc-inventory-service.yaml` `/internal/count-work`.
- **구현**(PC 3, 2026-11-20 — capacity 0.24.0, 브랜치 `mfc/br52-capacity`, #270 위): `CYCLE_COUNT`(업무 STOCK_COUNT)·같이 `RETURN_RECEIPT`(STOCK_MOVE)·`RETURN_INSPECT`(INSPECTION). 한 실사 = 한 사람은 capacity 배정(ACTIVE 하나)으로, 첫 계수자는 후보·들고 있는 작업에서 빼고 그 배정은 다음 사람이 받을 때 RELEASED. 순서는 같은 업무 기존 유형 뒤(임시 — 사용자 결정 남음). 주소는 `pmart.capacity.activities.mfc-inventory-base-url`(작업자 내역과 같은 키 — 없으면 실사 건너뜀). 회신 `docs/handoff/clients/mfc-app/2026-11-20-reply-br52-capacity.md`.

## MC-2. 다음 작업 받기에 격리 이동(QUARANTINE_MOVE) — 미리 정한 담당에게 (2026-11-20, 보낸 영역 mfc — inbound 0.17.0)
- **요청한 곳**: mfc-inbound 0.17.0(사용자 결정 2026-11-20 "격리품은 회수품은 반입한 사람, 입고 결함은 찾은 검수자가 격리 구역으로").
- **지금**: `GET /inbound-tasks?withLabels=false` 에 `taskType: QUARANTINE_MOVE` 가 나오고 `assigneeId` 가 이미 채워져 있다(검수자·반입자·현장 예외 보고자). capacity `AssignmentService.INBOUND_TASK_TYPES` 는 INSPECT·PUTAWAY 만이라 이 작업은 다음 작업 받기에 안 나온다 — 담당자는 PDA 작업 목록(`GET /inbound-tasks?taskType=QUARANTINE_MOVE`)에서 연다.
- **필요한 것**: 다음 작업 받기 후보에 `QUARANTINE_MOVE` — 업무 영역 STOCK_MOVE(또는 INSPECTION — 사용자 결정), **이미 담당이 있으면 그 사람에게만**(다른 사람에게 다시 배정하지 않음). 우선순위는 적치 앞/뒤(사용자 결정).
- **요청한 쪽에서 바꿀 것**: 없음. 상태: 요청(2026-11-20 PC 3, 자기 영역).
- **구현**(PC 3, 2026-11-20 — capacity 0.26.0, 브랜치 `mfc/capacity-quarantine-first`, #277 위): 사용자 결정(2026-11-20 "다음 업무에서 픽업하기 전에 새 업무로 줘도 되고") — 담당이 이 작업자인 READY 격리 이동을, 들고 있는 작업이 없으면 **다른 어떤 새 작업보다 먼저, 고른 업무·업무 범위와 상관없이** 준다(업무 영역 질문은 해당 없음). 다른 사람·담당 없는 격리 이동은 아무에게도 주지 않는다. 회신 `docs/handoff/clients/mfc-app/2026-11-20-reply-br52-capacity.md` §7.
