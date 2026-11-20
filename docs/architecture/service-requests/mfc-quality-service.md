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

## MQ-2. 품질 사고 `caseId` 채우기 — case `CaseOpened` 구독 (2026-11-20, 보낸 영역 backoffice — PC 2, CS-MFC-Q 회신)
- **왜**: PC 3 요청 CS-MFC-Q 3번("caseId 되돌려 주기")에 대한 case 쪽 답. case 0.31.0(#300, 2026-11-20 LAN 배포)이 `QualityIncidentImpactAssessed` 의 **영향 주문마다** 상담원 사건 `MFC_QUALITY_IMPACT` 를 연다. 새 사건은 만들지 않았다 — 기존 `CaseOpened` 로 받는다.
- **요청**: platform.case.v1 `CaseOpened` 구독 → payload `type` = `MFC_QUALITY_IMPACT` 만, `refs` 의 `{refType: incidentId, refId}` 로 사고를 찾아 `QualityIncident.caseId` 를 채운다. 사고 하나에 사건이 주문 수만큼 나오니 **비어 있을 때만 첫 caseId**(그 뒤 것은 무시하거나 주문별로 둘지는 PC 3). `refs` 에 `orderId`·`lotId` 도 있다.
- 사건 상태(접수·처리 중·닫힘)를 사고 상세에 보이려면 `CaseAssigned`·`CaseClosed`·`CaseReopened`(같은 토픽, caseId) — 또는 센터 관리자 토큰으로 case `GET /cases/{caseId}`(자기 센터 MFC_QUALITY_IMPACT 보기 가능).
- 근거: `case-service.md` CS-MFC-Q 상태 절, 계약 `contracts/events/CaseOpened.v1.json`·`case-service.yaml` 0.31.0.
- **상태**: 요청(PC 3).

## MQ-3. 회수품 검수 담당이 작업 배정과 어긋남 + 서비스 문서 정리 (2026-11-20, 보낸 영역 platform — PC 1)
- **왜**: MFC 사내 정책 문서(`docs/rag/rag-docs/mfc/`)를 쓰다 찾음. mfc-capacity 0.24.0 은 **회수품 검수** 작업을 다음 작업 받기로 업무에 품질검사가 있는 작업자에게 준다. 그런데 mfc-quality 검수 접수는 **반입한 사람이 아닌 MFC_SUPERVISOR** 만 받는다 → 관리자가 아닌 작업자는 작업을 받고도 검수가 거절된다.
- **요청**: 둘 중 하나로 맞추기 — (가) quality 가 업무 자격(품질검사) 작업자도 받게, 또는 (나) capacity 가 회수품 검수를 센터 관리자에게만 주게. 어느 쪽이 업무 규칙인지 사용자 결정이 필요하면 PC 3 이 묻기.
- **문서도 본인이 고쳐 주세요(사용자 2026-11-20 "그 문서 본인이 변경하라고 알려줘")**:
  - `services/mfc-fulfillment-service/CLAUDE.md` 0.13.0 절 — 인계 라벨 번호를 "6자리"로 적음. 코드는 V19(0.23.0)부터 **4자리·5번 틀리면 잠김**.
  - `services/mfc-inventory-service/CLAUDE.md` — UTF-8 로 읽히지 않는(깨진) 줄이 있음. UTF-8(LF)로 다시 저장.
- **상태**: 됨(2026-11-20, MFC 시뮬레이터 세션 — 사용자 결정 "가"·"고쳐"). mfc-quality 계약 0.11.0: 회수품 검수자 = 그 센터 MFC_WORKER 또는 MFC_SUPERVISOR(반입자 본인 422 SELF_APPROVAL 그대로), `ReturnInspectionIT` 오배송 회수 검수를 MFC_WORKER 로. 문서: `mfc-fulfillment-service/CLAUDE.md` 0.13.0 절 인계 번호 → 0.23.0(V19)부터 4자리·5번 틀리면 잠김으로 고침. `mfc-inventory-service/CLAUDE.md` 는 저장소(main·최근 6개 판)에서 UTF-8(LF)로 정상 — 깨진 줄 없음(PC 1 작업 복사본 문제로 보임, 다시 받기 권함).
