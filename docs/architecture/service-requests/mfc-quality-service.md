# mfc-quality-service 에 대한 요청

받는 영역: mfc(PC 3). 표는 `README.md`(PC 1).

## MQ-1. `ReturnInspected` 줄에 주문 행 번호 `orderLineId` (2026-11-17, 보낸 영역 platform — PC 1, claim CL-1 추가 결정 8)
- **요청한 곳**: claim-service(CL-1 반품 — 검수 뒤 환불). 사용자 결정 2026-11-17 "추천대로" 8번("MFC 검수 결과에 주문 줄 번호 — PC 3 요청(선택)").
- **왜**: 한 주문에 **같은 SKU 가 두 주문 행**(단가·할인 안분이 다를 수 있음)에 있으면 claim 은 검수 줄을 SKU 로만 클레임 행에 맞출 수 있어, 어느 행 수량인지가 모호하고 **환불 금액이 달라질 수 있다**(예 1,900원 행 3개·2,500원 행 2개를 반품했는데 2개씩 검수 → SKU 로 앞에서부터 채우면 3 × 1,900 + 1 × 2,500 = 8,200원, 실제는 2 × 1,900 + 2 × 2,500 = 8,800원).
- **필요한 것**: `ReturnInspected`(mfc.quality.v1) `lines[]` 에 **선택 필드 `orderLineId`**(필드 추가 — 하위 호환). 값은 반입 때 받은 것 그대로 — claim 회수 요청 `ReturnPickupRequested.lines[].orderLineId` → delivery `ReturnPickedUp`·`ReturnDelivered`(줄에 orderLineId 필수) → mfc-inbound 반입(`ReturnReceiptService` 가 알면 `ReturnItemReceived` 줄에 이미 싣는다 — 그 계약에는 아직 안 적힌 선택 필드)로 이어지니, 검수 사본이 그 줄의 값을 이어받으면 된다(`ReturnItemReceived.v1.json` 에도 같이 적어 주면 좋다). 계약 `contracts/events/ReturnInspected.v1.json`(mfc 계약 — PC 3)과 events.md 행도 같이.
- **지금(임시 방식)**: claim 은 검수 줄에 `orderLineId` 가 있으면 그 주문 행에, 없으면 지금처럼 같은 SKU 행에 앞에서부터 배분한다(`ReturnRules.allocate`, 2026-11-17 같은 SKU 두 행 시험 `ReturnClaimFlowIT.shortCloseRefundsReceivedOnlyAndInspectionMatchesOrderLine`). 그래서 MFC 가 이 필드를 싣기만 하면 claim 쪽 변경 없이 맞춰진다.
- **요청한 쪽에서 바꿀 것**: 없음(이미 받음). 사용자 결정 필요 없음.
- 근거: `docs/architecture/service-requests/claim-service.md` CL-1 "추가 결정" 8, `services/claim-service/src/main/java/com/pmart/claim/application/ReturnService.java`(onQualityEvent).

- **회신(PC 3, 2026-11-19 — 브랜치 `mfc/return-arrivals`)**: 구현. 값의 출처가 반입뿐이라 반입부터 이었다 — mfc-inbound 0.13.0 이 `ReturnDelivered.lines[].orderLineId` 를 반납 도착 대기에 담고 반입 줄에 채움(같은 상품 두 줄은 수량대로 나눔) → `ReturnItemReceived.lines[].orderLineId` → mfc-quality 0.7.0 이 검수 줄에 이어(검수 요청에 orderLineId 가 없으면 반입 줄에서 수량대로 나눔) → `ReturnInspected.lines[].orderLineId`. claim 은 바꿀 것 없음(이미 받음). 시험 inbound `ReturnArrivalIT`·quality `ReturnInspectionIT.misdeliveryReturnIsInspectedWithoutClaimAndKeepsOrderLines`.

## AP-1 참조. 승인 번호 실제 확인 — 본사 승인 기준·한 번만 쓰기 (2026-11-17, platform PC 1)
- 요청 본문은 `approval-service.md` **AP-1 "PC 3 이 할 것" 1~6**(이 서비스 몫: 폐기 승인·격리 해제 — `DisposalService`, `QualityDecisionService`). 기준(사용자 2026-11-17): 한 건 손실 10만 원 이상 또는 한 SKU 20개 이상이면 본사 승인, 그 아래는 센터 관리자. approval-service 쪽(승인 종류·`/internal/approval-ref-checks`·`/internal/approval-ref-uses`)은 구현됨(b969005d).
- **구현**(PC 3, 2026-11-17 — 폐기·격리 해제 0.3.0, 브랜치 `mfc/approval-ref-check`): 회신은 `approval-service.md` AP-1 "회신(PC 3)". LAN 은 설정(기준 값·주소·VERIFY) 뒤에 켜진다 — PC 1.
