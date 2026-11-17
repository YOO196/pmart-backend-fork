# [pmart-backend → pmart-clients] 공지: MFC 센터 관리자 웹 — 본사 승인 번호·업무 내역·주문번호 (2026-11-17)

- 보낸 영역: mfc (PC 3)
- 받는 앱: mfc-backoffice(`web/mfc-backoffice`, PC 2)
- 모두 **PR 병합·LAN 배포 뒤** 적용(PC 1). 순서 #116 → #120 → #122 → #123 → #124.

## 1. 본사 승인 번호 — PR #116(요청 AP-1, inventory 0.22.0·quality 0.3.0·capacity 0.16.0)

기준(사용자 2026-11-17 Q-11): **한 건 손실 10만 원 이상 또는 한 SKU 20개 이상**이면 본사 승인. 운영 제한 해제는 **기준 없이 늘 본사**. 손실 금액 = 줄어드는 수량 × 판매 기준가(원가 없음), 단가 없는 상품은 미평가 → 본사.

| 동작 | 경로 | 승인 번호 |
|---|---|---|
| 실사 차이 승인 | `POST /mfcs/{mfcId}/count-sessions/{id}/approvals`(APPROVE) | 본문 `approvalRef` |
| 재고 정정 | `POST /mfcs/{mfcId}/stock-corrections` | 본문 `approvalRef`(이제 선택) |
| 폐기 승인 | `POST /disposal-approvals`(APPROVE) | 본문 `approvalRef` |
| 격리 해제 승인 | `POST /quality-decisions/{id}/approvals`(APPROVE) | 본문 `approvalRef` **(새 칸)** |
| 운영 제한 해제 | `DELETE /operation-restrictions/{id}` | query `approvalRef`(이제 선택) |

화면 흐름:
1. 번호 없이 보냄 → 기준 이상이면 **422 `APPROVAL_REQUIRED`**. `details` 에서
   - `field=approvalRequest` → `reason` = approval-service 변경 요청 본문(**JSON 문자열** — 파싱해서 그대로). 공통 오류 형식이 문자열 쌍뿐이라 이렇게 담았다.
   - `field=threshold` → 걸린 기준(사람이 읽는 문장, 여러 개 가능 — 화면에 보여 주기).
2. 그 본문으로 approval-service `POST /change-requests` → `POST /change-requests/{id}/submissions`(센터 관리자 토큰).
3. 본사 승인(재고 `INVENTORY_APPROVER`, 운영 제한 `OPERATOR_ADMIN` — 백오피스 승인함) → 받은 `approvalId` 를 `approvalRef` 로 **같은 요청을 다시**.
4. 다시 422 면 `details` `field=approvalRef` 의 reason: NOT_APPROVED(아직 심사 중·반려)·ALREADY_USED(다른 집행에 이미 씀)·EXPIRED·NOT_YET_EFFECTIVE·SELF_APPROVAL·TARGET_MISMATCH·OUT_OF_SCOPE(SKU 수량이 승인보다 많음 — 새 승인).

주의:
- **재고 정정은 승인 뒤 같은 `Idempotency-Key` 로** 다시 보낸다 — 정정은 실행 전 ID 가 없어 요청 키가 승인 대상 참조(`targetRef <mfcId>:CORRECTION:<키>`)다. 422 는 기록되지 않아 같은 키를 다시 써도 된다.
- 기준 아래면 지금처럼 센터 관리자 승인만(번호를 묻지 않음, 주면 기록만).
- 한 승인은 한 번만 쓴다. 승인 범위(SKU별 수량)보다 많이 집행하면 거절.
- LAN 은 PC 1 이 설정을 `VERIFY` 로 바꾸기 전까지 예전대로(번호 필수·확인 없음 — 격리 해제는 번호 없이).

## 2. 작업자 오늘 업무 내역 — PR #120(요청 F-7, capacity 0.17.0)

- `GET /mfcs/{mfcId}/workers/{workerId}/activities?date=`(Asia/Seoul 날짜, 없으면 오늘) — 작업자 본인 또는 **그 센터 MFC_SUPERVISOR**.
- `items[]` `{source, activityType, refId, taskId?, sku?, qty?, result, at}` 시각 순 — 피킹·포장·복귀·입고 수령·품질검사·적치·이동·회수품 반입·실사 계수·품질 판정·폐기·회수품 검수. `totals[]` = 정본·종류별 건수·수량(REJECTED 는 수량 제외).
- `unavailableSources` 가 비어 있지 않으면 "일부 내역을 못 불러왔어요"(빈 내역으로 보이지 않게).
- 작업자 관리 화면에 쓰면 된다(선택).

## 3. 화면용 주문번호·라벨 숫자 — PR #123(요청 F-5·F-8, fulfillment 0.13.0)

- 포장·출고·작업 응답에 `orderNumber`(숫자 8자리 — 고객·라이더·상담원과 같은 번호). 번호 전 주문은 null → orderId 를 보인다.
- 포장 `handoverCode`(6자리) — 라벨 재출력·포장 목록에 보이면 라이더가 QR 대신 입력할 수 있다.
- 통합 조회(F-3 `GET /work-orders?orderId=`)에는 아직 orderNumber 가 없다 — 필요하면 요청.

## 4. 목록 API·권한(MB-1~3)

- 아직 시작 전(PC 3) — 위 PR 들이 병합된 뒤. 화면에서 가장 먼저 필요한 목록이 있으면 순서를 알려 주세요(요청서 `docs/architecture/service-requests/mfc-backoffice-staff-apis.md`).
