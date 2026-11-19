# mfc-capacity-service 에 대한 요청

받는 영역: mfc(PC 3). 표는 `README.md`(PC 1).

## AP-1 참조. 승인 번호 실제 확인 — 본사 승인 기준·한 번만 쓰기 (2026-11-17, platform PC 1)
- 요청 본문은 `approval-service.md` **AP-1 "PC 3 이 할 것" 1~6**(이 서비스 몫: 운영 제한 해제 — `RestrictionService.lift`(기준 없이 본사 승인)). 기준(사용자 2026-11-17): 한 건 손실 10만 원 이상 또는 한 SKU 20개 이상이면 본사 승인, 그 아래는 센터 관리자. approval-service 쪽(승인 종류·`/internal/approval-ref-checks`·`/internal/approval-ref-uses`)은 구현됨(b969005d).
- **구현**(PC 3, 2026-11-17 — 운영 제한 해제 0.16.0, 브랜치 `mfc/approval-ref-check`): 회신은 `approval-service.md` AP-1 "회신(PC 3)". LAN 은 설정(기준 값·주소·VERIFY) 뒤에 켜진다 — PC 1.

## MC-2. 다음 작업 받기에 격리 이동(QUARANTINE_MOVE) — 미리 정한 담당에게 (2026-11-20, 보낸 영역 mfc — inbound 0.17.0)
- **요청한 곳**: mfc-inbound 0.17.0(사용자 결정 2026-11-20 "격리품은 회수품은 반입한 사람, 입고 결함은 찾은 검수자가 격리 구역으로").
- **지금**: `GET /inbound-tasks?withLabels=false` 에 `taskType: QUARANTINE_MOVE` 가 나오고 `assigneeId` 가 이미 채워져 있다(검수자·반입자·현장 예외 보고자). capacity `AssignmentService.INBOUND_TASK_TYPES` 는 INSPECT·PUTAWAY 만이라 이 작업은 다음 작업 받기에 안 나온다 — 담당자는 PDA 작업 목록(`GET /inbound-tasks?taskType=QUARANTINE_MOVE`)에서 연다.
- **필요한 것**: 다음 작업 받기 후보에 `QUARANTINE_MOVE` — 업무 영역 STOCK_MOVE(또는 INSPECTION — 사용자 결정), **이미 담당이 있으면 그 사람에게만**(다른 사람에게 다시 배정하지 않음). 우선순위는 적치 앞/뒤(사용자 결정).
- **요청한 쪽에서 바꿀 것**: 없음. 상태: 요청(2026-11-20 PC 3, 자기 영역).
