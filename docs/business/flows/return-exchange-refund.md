# S6 반품·교환·회수·검수·환불 사가

버전 0.1 · 2026-10-23 · 설계안. 조정자: `claim-service`. 근거: OMS 10~11장, A.1.12~A.1.13, A.5.5; Commerce 13장; MFC IV-10; Delivery 9.5; BackOffice BO-13/14.

## 0. 확정 정책

| 항목 | 규칙 |
|---|---|
| 접수 기한 | 일반 교환·반품: 실제 수령 후 **7일**. 신선식품(냉장/냉동): **24시간**. 하자·오배송·광고 상이 **교환**: 수령 후 **3개월** 또는 안 날(알 수 있었던 날)부터 **30일** 중 하나 |
| 기한 계산 | Asia/Seoul 실제 수령시각 기준, 경계시각까지 가능. 3개월은 달력 월(없는 날은 말일). 분할배송은 신청 품목의 실제 수령시각 |
| 주소 | 회수·재배달 주소는 원주문 주소 고정 |
| 비용 | 단순 변심 교환 왕복 9,000원 합계. 상품/배달 문제는 0원. 일반 반품 회수비·환불 개시점·거절품 처분 `[팀 확인 필요]` |
| 환불 금액 | 원주문 가격 스냅샷·품목별 할인 안분. 현재 가격 재계산 금지. `추가 환불 가능 한도 = 승인액 − 성공 누적 − 불명 점유` |
| PG | 부분 반품은 원결제 부분취소(토스페이먼츠). 전체 취소 후 재결제 방식 아님 |
| 재고 | 환불·회수 요청만으로 재고 복구 없음. 상온 미개봉 적격품만 검수 후 재판매. 신선·냉장·냉동 회수품 재판매 금지 |
| 재배송 | 교환 승인 건에만 허용. 원주문·교환 클레임에 연결, 새 주문·새 결제 생성 금지 |
| 중복 방지 | 반품 가능 수량 = 실제 전달량 − 완료/진행 반품 점유. 동일 주문 중복 교환 차단 |

## 1. 흐름

```
(1) 접수   Commerce/CS ─ POST claims(RETURN|EXCHANGE) {orderLineId, qty, reason, evidence, receivedAt}
           claim-service: 자격(기한·사유 특례)·대상 수량·중복·진행 점유 검증 → ClaimOpened
           · delivery-execution의 DeliveryCompleted(품목별 수령 시각)와 order 스냅샷 참조
(2) 심사   CS/운영자(BO-14) 또는 자동 규칙 → ClaimDecided(품목별 APPROVED/REJECTED, 회수 필요 여부)
           · 3건 중 2건 승인 → 2건만 후속. 회수 없는 처리는 승인 사유·증빙 필수(기본 원칙의 예외)
(3) 회수   claim ─ ReturnPickupRequested ─► delivery-dispatch: 회수 배송(type RETURN, 별도 배송번호, 원주문 연결) → 배차
           delivery-execution: 고객 수거 ReturnPickedUp → 점포 반납 ReturnDelivered
           · 2026-11-17 배송 쪽 구현(사용자 결정): 사건 받으면 바로 배차(근처 라이더, 원래 기사도 가능), 회수 라이더 수행료 = 일반 표(고객 집 → MFC),
             회수지 = 원래 배송지. 고객 부재·거부면 라이더 실패 등록(사진 + 연락 2회) → 지원센터 확정 → ReturnPickupFailed(상품은 고객에게).
             실패마다 claim 에 알리고 다시 보낼지는 claim(2번 실패 → 상담원 재연락, 회수비 한 번). 고객 사정 실패도 라이더는 수락 당시 금액.
             같은 클레임에 진행 중인 회수 배송이 있으면 새 요청은 무시(경고)
(4) 수령   mfc-inbound: ReturnDelivered → 반납 도착 대기(GET /return-arrivals, 2026-11-19 MI-2) → 점원이 골라 반입(POST /return-receipts arrivalId — claimId·회수 ID·주문 줄을 채움)
           → ReturnItemReceived(줄 orderLineId) → mfc-inventory H+q, Q+q (검수 대기; 판매 가능 재고 아님)
           · 회수 승인량 2 / 실수령 1 → 미수령 1 남김. 검수·PG 결과 선완료 금지
(5) 검수   mfc-quality: ReturnInspected(품목별 RESALE / DISPOSE) → mfc-inventory: RESALE → Q−q, E+q ; DISPOSE → 폐기 승인 후 H−q
(6) 환불   claim-service: 환불 개시점(회수 확인/센터 수령/검수 완료 중 등록된 정책; 미설정이면 심사 대기 상신, 자동 환불 없음)
           → RefundIntentCreated(원안분 금액) → payment.PartialCancel(원거래, 금액, 멱등키)
           ← RefundResult SUCCESS/FAILED/UNKNOWN → order OrderLineQuantityChanged(반품 수량), 고객 안내
(7) 교환   ClaimDecided(EXCHANGE) → ExchangeReshipmentApproved → order: 교환 이행 단위 생성(원주문 연결) → S2 재진입(새 피킹·패킹·배송; 새 출고 수량만 1회 차감)
           · 교환품 품절 → 고객 협의(환불 전환/대기). 자동 대체 금지
(8) 종결   ClaimClosed: 회수·검수·환불/재배송·고객 안내 의무 모두 확인. 철회는 진행된 Delivery 수행·실물·PG 취소를 대사해 처리(사건 삭제 금지)
```

## 2. 환불 금액 예시 (OMS 10.2)

A 20,000 + B 10,000, 쿠폰 3,000(비례 안분, 원 미만 버림, 잔여 원은 최대 금액 품목), 배달팁 0(30,000 이상), 봉투 100
→ 원결제 27,100. B 1건 반품 → B 원안분 환불 9,000, 잔액 18,100. 누적 성공/진행/불명을 제외한 잔액만 새 취소 가능.

## 3. 상태 축

| 축 | 상태 |
|---|---|
| 클레임 | OPENED → UNDER_REVIEW → DECIDED(일부 승인 가능) → IN_PROGRESS → CLOSED / WITHDRAWN |
| 회수 | REQUESTED → DISPATCHED → PICKED_UP → DELIVERED_TO_STORE → RECEIVED → INSPECTED |
| 환불 | 접수 → 심사/승인 → 실행 대기 → PG 결과 확인 → 완료 / 실패 / 확인 중 |
| 교환 재배송 | APPROVED → FULFILLMENT_CREATED → (S2 상태) |

## 4. 분기

| 상황 | 처리 |
|---|---|
| 기한 경계(7일째 23:59 Asia/Seoul) | 접수 가능 |
| 신선식품 24h 경과, 사유=하자 | 특례(3개월/인지 30일)로 심사. 일반 기한으로 거절 금지 |
| 인지 시각 불명 | 증빙 보완 심사 |
| 환불 성공·쿠폰 복원 실패(3차) | 환불 유지, 복원만 별도 재처리 |
| PG 응답 유실 | 같은 환불 요청 ID 조회. 새 금액 취소 중복 실행 금지 |
| 완료 후 파손 신고(Delivery 9.3) | 기존 DeliveryCompleted 유지, 귀책 조사(case)와 고객 대응(환불/교환) 분리. 라이더 공제는 환불과 동일 처리 아님 |
| 고객 측 사유(부재·주소 오류) 회수 | 회수 후 취소·환불. 재주문은 새 주문(재배송 아님) |
| PMart 측 사유(파손·오배송) | 회수 + 교환 재배송 또는 회수 + 취소 환불 |

## 5. 운영자(BO-13/14) 경계
- 환불 의도는 claim/order가 산정, PG 실행은 payment. BackOffice 독자 PG 명령 금지.
- 회수·검수·환불 완료를 각각 표시. "검수 합격=재판매 가능=환불 완료"로 합치지 않는다.
- 비용 결정(회수비·교환비)은 사건에 근거·승인자와 함께 기록. 지급·수납은 회계 연계 `[결정 필요]`.

## 6. 구현 (2026-11-17, CL-1 — 라이더 인계 뒤 회수 반품)

근거: D-7(인계 뒤 고객 취소 = 고객센터 경유 회수, 회수비 9,000원 고객 부담·회사 잘못이면 면제, 회수비는 환불과 따로, 상품 환불은 검수 뒤), ADR-0060 결정 2, 사용자 결정 2026-11-17 "추천대로"(① 1차 감액 없음 ② 면제 사유 다섯 ③ 회수 실패 2번 뒤 상담원 재연락·회수비 한 번), 같은 날 추가 결정 1~9("추천대로" — 회수비 기한 24시간, 취소 때 회수비 규칙, 회수 전체 3번, 우리 잘못 안 셈, 반품 기한 7일, 받은 만큼만 끝내기, 반품 가능 기간은 상담원 판단, 검수 줄 orderLineId, order 반품 수량 — claim 0.3.0·order 0.11.2). 조정자 claim-service(회수비 결제는 오배송 다시 배송비 — ADR-0065, `misdelivery-recovery-reshipment.md` §2.2 — 와 같은 payment 경로). 요청서 `service-requests/claim-service.md` CL-1.

범위: **고객센터 접수 반품(type RETURN)** 만. 위 §0~§5 의 일반 반품·교환(고객 앱 접수·접수 기한·심사 BO-14·교환 재배송)은 아직 없다.

```
상담원(CS_AGENT·OPERATOR_ADMIN) ─ POST /claims {type RETURN, lines, reason, faultCode?, identityCheck, channel CS}
  claim: order GET /internal/orders/{id} — 행마다 요청 ≤ 전달 완료(deliveredQty) − 이 주문의 진행·완료 반품(취소 제외), 같은 주문은 advisory lock 으로 줄 세움
    · 반품 전체 기한 returnDeadlineAt = 접수 + pmart.claim.return-deadline(7일 — 없으면 422 CONFIG_REQUIRED)
    · faultCode 있음(MISDELIVERY·DEFECTIVE·DAMAGED_IN_DELIVERY·SEVERE_DELAY·WRONG_SUBSTITUTE) → 회수비 면제 → IN_PROGRESS + ReturnPickupRequested(attempt 1)
    · 없음(고객 사유) → FEE_PENDING, 회수비 행(기한 = 접수 + pmart.claim.return-fee-payment-wait 24시간 — 없으면 422 CONFIG_REQUIRED)
        → 커밋 뒤 payment POST /internal/payments(주문 참조 RTF-<claimId>, 9,000원, customerRef = 주문 accountId, 만료 = 기한, 키 <claimId>:FEE:<n>)
        → ReturnFeePaymentRequested(첫 시도만) → 고객 앱 결제(토스 위젯 orderId = paymentAttemptId → POST /payment-confirmations)
        → PaymentApproved(order.payment.v1) 또는 재조회 APPROVED → 회수비 PAID + IN_PROGRESS + ReturnPickupRequested(attempt 1)
        → 기한까지 승인 없음(진행 중 PENDING·UNKNOWN·MISMATCH 는 기다림) → UNPAID + CANCELLED + ClaimClosed(RETURN_FEE_UNPAID)
        → 기한·취소 뒤 늦은 승인 → 보상 취소(compensation-cancellations, reason RETURN_FEE_TOO_LATE)
  회수(delivery — DD-3, PC 5): ReturnPickupRequested(pickupRequestId <claimId>-P<n>, purpose CLAIM_RETURN, pickupAddressRef order:<orderId>, mfcId)
    → DeliveryCreated(type RETURN, purpose CLAIM_RETURN, claimId, pickupRequestId) → ReturnPickedUp → ReturnDelivered(MFC 반납)
    · ReturnPickupFailed(또는 claimId 있는 DeliveryFailed, 수거 전) — 실패 종류: CUSTOMER(customerReason·부재·연락 두절·주소 오류·거부) / OURS(RIDER_INCIDENT) / OTHER(파손·기타)
        OURS → 횟수에 넣지 않고 바로 새 회수 요청
        그 밖 → 횟수에 드는 실패 1번째면 자동으로 새 회수 요청, 2번째(상담원 재요청 뒤 3번째)면 RECOVERY_FAILED + ReturnRecoveryFailed(countedAttempts·retryAllowed)
        반품 기한이 지난 뒤의 실패 → 다시 요청하지 않고 자동 취소(RETURN_EXPIRED — 받은 것이 있으면 RECOVERY_FAILED 로 상담원)
    · RECOVERY_FAILED → 상담원 POST /claims/{id}/recovery-actions:
        RETRY_PICKUP(새 회수 요청, 회수비 다시 받지 않음 — 횟수에 드는 실패 3번이면 409 PICKUP_ATTEMPTS_EXHAUSTED, 기한 뒤 409 RETURN_DEADLINE_PASSED)
        CANCEL(수거·반입된 회수품 없을 때만 — 낸 회수비: CUSTOMER 실패가 있으면 그대로, OTHER 가 있으면 상담원 feeRefund(기본 그대로), OURS 뿐이면 돌려줌
               → 회수비 결제 시도의 전액 취소 환불 의도 reason RETURN_FEE, payment 사유 RETURN)
        CLOSE_AS_RECEIVED(받은 만큼만 끝내기 — IN_PROGRESS 에서도, 진행 중 회수 요청 없을 때: 행마다 정산 수량 approvedQty = 받은 수량, 남은 수량 환불 없음)
  기한 점검(return-deadline-sweep-interval, 5분): 기한 지남 + 수거·반입·검수 없음 + 진행 중 회수 요청 없음 → CANCELLED + ClaimClosed(RETURN_EXPIRED), 회수비는 CANCEL 과 같은 규칙(상담원 판단 몫은 돌려주지 않음)
  MFC: mfc-inbound 반납 도착 대기 → POST /return-receipts(arrivalId, 2026-11-19) → ReturnItemReceived(격리) → claim 수령 수량(receivedQty)
       → mfc-quality 검수 → ReturnInspected(줄 orderLineId — 같은 SKU 두 주문 행은 수량대로 나눔, MQ-1)
       mfc-quality POST /return-inspections → ReturnInspected(줄별 RESALE·DISPOSE, orderLineId 있으면 그 행 — 없으면 SKU, MQ-1) → claim 검수 수량마다 환불 의도(정산 수량까지)
         금액 = 원단가 × 수량 − 할인 안분(원수량 기준 누계 비례, 원 미만 버림 — 원수량을 다 돌려주면 정확히 discountShare), 봉투값·배달팁 없음
         RESALE·DISPOSE 모두 환불(1차 감액 없음 — 고객 잘못 파손·개봉도 회수비로 갈음)
         → RefundIntentCreated(basis.lines) → payment 부분 취소(scope PARTIAL, reason RETURN, 키 REFUND-<의도>) → RefundResult
  종결: 모든 행이 정산 수량(요청 수량, 받은 만큼만 끝냈으면 받은 수량)만큼 검수되고 환불 의도가 모두 COMPLETED
        → CLOSED + ClaimClosed(RETURN_REFUNDED / RETURN_SHORT_CLOSED, returnedLines[{orderLineId, qty, resaleQty, disposeQty}])
        → order(O-7): 행 returnedQty +qty(클레임 하나에 한 번, 한도 전달 − 이미 반품) + OrderLineQuantityChanged(reason RETURN)
```

| 축 | 상태 |
|---|---|
| 클레임(RETURN) | FEE_PENDING → IN_PROGRESS → CLOSED / RECOVERY_FAILED(→ IN_PROGRESS 재요청·받은 만큼만 끝내기 또는 CANCELLED) / CANCELLED(미결제·상담원 취소·기한 만료) |
| 회수비 | WAITING → PAID / UNPAID(→ 늦은 승인 LATE_CANCEL_PENDING → LATE_CANCELLED·LATE_CANCEL_FAILED). PAID 인 반품이 취소되면 refundDecision REFUND(환불 의도 RETURN_FEE)·KEEP |
| 회수(클레임 pickup_state) | REQUESTED → PICKED_UP → DELIVERED_TO_STORE → RECEIVED → INSPECTED, 두 번 실패면 FAILED |
| 회수 요청(시도마다) | REQUESTED → PICKED_UP → DELIVERED / FAILED(failure_kind CUSTOMER·OURS·OTHER — OURS 는 횟수에서 뺌) |
| 환불(검수마다 하나) | PENDING_EXECUTION → PG_PENDING → COMPLETED / FAILED / UNKNOWN(기존 환불 sweep) |

- **회수비를 언제 받나(이 구현의 선택)**: 접수 때 결제를 요청하고 **결제된 뒤에만 회수를 요청**한다(D-7 "회수비 고객 부담" — 결제 없이 라이더가 먼저 가면 못 받을 수 있다). 결제 기한은 설정값(24시간 — 사용자 확정 2026-11-17 추가 결정 1), 지나면 반품 취소. 회사 잘못이면 결제 단계 없이 바로 회수.
- **취소 때 낸 회수비(추가 결정 2, 이 구현의 해석)**: 그 반품의 회수 실패 **전부**를 본다 — 고객 사유가 한 번이라도 있으면 돌려주지 않고, 아니고 파손·기타가 있으면 상담원 판단(자동 취소는 판단이 없으니 돌려주지 않음), 우리 잘못뿐이거나 실패 없이 기한이 지났으면 돌려준다. 정해진 규칙과 반대인 상담원 값은 422(FEE_REFUND_NOT_ALLOWED).
- **기한(추가 결정 5, 이 구현의 해석)**: 기한이 지나도 **진행 중인 회수는 끊지 않는다**(취소 사건을 delivery 가 받지 않아 라이더가 이미 가는 중일 수 있다 — 그 회수가 수거되면 이어 가고, 실패하면 그때 자동 취소). 무엇이든 수거·반입된 반품은 자동 취소하지 않고 상담원 "받은 만큼만 끝내기"에 둔다.
- **받은 만큼만 끝내기(추가 결정 6)**: 남은 수량은 환불하지 않고, 이 주문의 반품 가능 수량에서도 계속 뺀다(같은 상품으로 반품을 다시 접수해 이중 환불되지 않게 — 다시 받아야 하면 상담원이 새 판단). 끝낸 뒤 늦게 들어온 반입·검수는 정산 수량을 넘는 몫이라 환불하지 않는다(경고 로그 — 운영자).
- 같은 반입(returnReceiptId)·검수(inspectionId)는 한 번만 센다(`return_fact` — inbox 와 별개 업무 키). 요청 수량을 넘는 반입·검수는 버리고 경고. 검수가 반입보다 먼저 와도 검수 수량으로 환불한다(검수 자체가 수령의 증거).
- 회수 실패로 본 뒤 늦게 수거·반입·검수 사건이 오면 IN_PROGRESS 로 되돌려 이어 간다. 이미 받은 뒤의 배송 실패(반납 전 사고)는 회수 실패로 세지 않는다(기록만 — 사람).
- 취소·종결·회수비 대기 반품에 검수가 들어오면 자동 환불하지 않는다(오류 로그 — 운영자).
- 회수비 결제와 상품 환불은 다른 결제 거래다(회수비 = 새 결제 시도 RTF-<claimId>, 환불 = 원주문 결제의 부분 취소). 상계하지 않는다.
- 이 단계의 한계: 접수 기한(7일·24시간)·증빙 검사 없음(상담원 판단 — 추가 결정 7, 배송 완료 시각을 받게 되면 자동 검사), 고객 알림은 사건만 낸다(notification 연결 전 — 회수비 돌려주기는 환불 알림 흐름을 탄다), 반품 취소·기한 만료를 delivery 에 알리지 않는다(진행 중 회수가 없을 때만 취소하므로 1차는 필요 없음). 2026-11-17 추가 결정 구현으로 주문 원장 반품 수량(O-7)·받은 만큼만 끝내기·취소 때 회수비 돌려주기는 됐다.
