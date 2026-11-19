# 요청: MFC 백오피스 화면 번호 적용 (ADR-0070) — 시스템 ID 는 상세 "시스템 번호" 칸에만

- 보낸 영역: mfc (PC 3 백엔드)
- 작성: pmart-backend, 2026-11-19
- 대상: pmart-clients `web/mfc-backoffice`
- 근거: `docs/adr/0070-display-numbers.md`(2026-11-18 승인, 11-19 넓힘)·`docs/handoff/clients/all/2026-11-18-notice-display-numbers.md`. 사용자(2026-11-19): `E2E-…-S1-PICK`·`OP-0077…`·`DLV-665800db…`·`ORD-b50b6b64…` 같은 값이 MFC 백오피스 목록에 그대로 보인다 — "관리자가 보기에 직관적으로, 세부사항에 들어가야 보이게".
- 서버 준비(PR 브랜치 `mfc/display-names`, LAN 은 PC 1 배포 뒤): mfc-capacity 0.23.0(작업자 이름·사번, 직원 이름 조회), mfc-inventory 0.30.0(LOT 번호 일괄 조회).

## 공통 규칙(ADR-0070)
- 목록·표·링크 글자·알림에는 **화면 표기만**. 긴 시스템 ID 도, "ID 끝 8자리 회색"도 쓰지 않는다.
- 상세 화면에 **"시스템 번호"** 칸(접어 두고 복사 단추) — 문의·로그 대조용. 여기에만 시스템 ID.
- 화면 표기를 아직 못 받는 것은 **종류 · 시각**.
- 찾기 칸은 화면 번호로(주문번호 등), 시스템 ID 를 붙여 넣어도 되게.

## 화면별 바꿀 것 (코드에서 찾은 곳 — `features/*`)
| 지금 보이는 값 | 바꿀 표기 | 쓸 데이터(있음/새로) | 화면(파일) |
|---|---|---|---|
| 작업 `taskId`(`…-S1-PICK`) | **주문번호 + 작업 종류** `74939255 피킹`(출고가 여럿이면 `· 출고 2`) | `TaskSummary.orderNumber`·`taskType`(있음, fulfillment) | TaskPages·AssignmentPages·OpsBoard |
| 입고 작업 `taskId` | **입고 표시번호(TRF) + 종류** `TRF-… 검수`, 없으면 종류·시각 | 입고 작업 응답의 표시번호(있음, inbound) | InboundPages |
| 작업자 `workerId`·`assignment.workerId` | **이름 · 사번** `홍길동 · P2026-00052` | 작업자 목록·상세 `name`·`employeeNo`(**새로**, capacity 0.23.0) | WorkerPages·TaskPages·AssignmentPages·OpsBoard |
| 처리자·결정자 `decidedBy`·`createdBy`·`changedBy`·`updatedBy`·`operatorId` | **이름 · 사번**(작업자가 아닌 센터 관리자도) | `GET /mfcs/{mfcId}/staff-names?operatorIds=a&operatorIds=b`(1~200, **새로**, capacity 0.23.0) — 화면 하나에 한 번 모아 부르기. 모르는 ID 는 빠짐 → "직원" | QualityPages·CenterLists·MovePages·WorkerPages·BreakPinCard·MyInfoPage·LoginPage |
| 주문 `orderId` | **주문번호 8자리** | `orderNumber`(있음 — 작업·출고·포장 응답) | OpsBoard·PackingHandoverPage |
| 출고 `shipmentId` | **주문번호**(출고가 둘 이상이면 `· 출고 2`) | 출고 응답 `orderNumber`(있음) | TaskPages |
| 포장 `packageId` | **라벨 숫자 6자리** `305118` | `Package.handoverCode`(있음) | TaskPages·HandoverChecks·PackingHandoverPage |
| 배송 `deliveryId` | **주문번호-순번 + 종류** `74939255-1 배송` | execution `GET /deliveries?deliveryIds=` 의 `deliveryNumber`(있음), dispatch 지연 목록은 `orderId`→주문번호 + `deliverySeq` | CenterDeliveryPages |
| 라이더 `riderId` | **가린 이름 · 라이더 번호** `김*수 · 482913` | rider `GET /riders?riderIds=`(있음) — **단 지금 MFC 센터 관리자는 403**(OPERATOR_ADMIN·RIDER_OPS·CS_AGENT 만). PC 5 에 요청함(`service-requests/rider-service.md` RS-MFC). 그 전엔 "라이더"만 | HandoverChecks·OpsBoard·TaskPages(fence) |
| LOT `lotId`(`LOT-…`) | **LOT 번호** `L261107000497`(+ 소비기한) | 재고 단위·LOT 라벨은 `lotNo` 있음, 품질·입고·이동 응답은 `GET /mfcs/{mfcId}/lot-numbers?lotIds=…`(1~200, **새로**, inventory 0.30.0) | QualityPages·IncidentPages·InventoryPages·MovePages·InboundPages·CenterLists |
| 품질 판정·폐기·검수 ID(`QD-`·`DSP-`·`RI-`) | **종류 · 요청자 · 시각** `격리 해제 · 홍길동 · 11/19 09:12` | 응답의 종류·요청자(→ staff-names)·시각(있음) | QualityPages·CenterLists |
| 품질 사고 `incidentId` | **종류 · 시각** `파손 · 11/19 09:12`(자동이면 "자동") | `kind`·`origin`·`openedAt`(있음) | IncidentPages |
| 실사 `sessionId` | **위치 · 시작 시각** `A-H2-01 · 11/19 09:12` | 실사 응답 위치·`baselineAt`(있음) | SessionPage·CenterLists |
| 이동 `moveId` | **종류 · 상품 · 시각** `보충 · 하루견과 · 11/19` | 이동 응답 종류·SKU(상품명)·시각(있음) | MovePages |
| 반입·수령 `receiptId` | **종류 · 시각**(회수품이면 주문번호 + `반품`) | 응답 값(있음) | InboundOtherTabs·QualityPages·CenterLists |
| 배정 추천 `planId` | **시각** `11/19 09:12 추천` | `createdAt`(있음) | CenterLists |
| 배정 `assignmentId` | 표시 안 함(상세 시스템 번호에만) | — | WorkerPages |

## 순서 제안
1. 바로 할 수 있는 것(서버 그대로): 작업·주문·출고·포장·배송·사고·실사·이동·추천 표기, 상세 "시스템 번호" 칸.
2. 서버 배포 뒤: 작업자·처리자 이름(staff-names·작업자 name), LOT 번호(lot-numbers).
3. PC 5 회신 뒤: 라이더 표기.
