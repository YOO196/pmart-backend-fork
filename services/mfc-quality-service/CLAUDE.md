# mfc-quality-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `mfc-quality-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/mfc-quality-service.yaml` → `openApiGenerate` 가 `com.pmart.mfc.quality.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `InboxGuard` 안에서.

## 패키지
`com.pmart.mfc.quality.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:mfc-quality-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:mfc-quality-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8305.

## 상태
3단계 스캐폴딩(2026-10-23).

## 격리·해제·폐기 (2026-11-08, IV-09·IV-10, 계약 0.2.0, V2)
- 권한(`application/Actors`, Q-11 결정 전 기본): 그 센터 운영자 + 판정·요청·실행 = MFC_WORKER·MFC_SUPERVISOR, 승인·반려 = MFC_SUPERVISOR(요청자 본인 아님 — 422 SELF_APPROVAL). 행위자 ID = 토큰 주체(본문 decidedBy·actorId 가 다르면 403).
- 판정 `QualityDecisionService`: QUARANTINE 은 바로 재고 반영, RELEASE 는 요청(REQUESTED) → `POST /quality-decisions/{id}/approvals`(APPROVE → 재고 반영 / REJECT → DECLINED). 계약 원안의 "요청 본문 approverId" 는 승인으로 쓰지 않는다.
- 폐기 `DisposalService`: REQUEST → APPROVE(approvalRef 필수 — BO-15 승인 ID, 존재 확인 없이 기록, 부분 승인)·REJECT → `POST /disposal-approvals/{id}/executions`(승인 − 실행 누계 − 진행 중 실행 안에서, 키 = Idempotency-Key) → PARTIALLY_EXECUTED / COMPLETED. 승인만으로 현물을 줄이지 않는다.
- 재고 반영: mfc-inventory `/internal/quality-postings`(`InventoryClient`, 키 = 판정 ID·실행 ID, `pmart.quality.mfc-inventory-base-url`). 결과 모름·미전달은 sweep(`pmart.quality.sweep-interval`, 기본 PT30S)이 같은 키로, 재고 거절은 REJECTED(자동 재시도 없음).
- 사건(mfc.quality.v1): `QualityDecisionMade`(재고 반영 뒤, 줄마다), `DisposalApproved`(승인 때).
- **회수품 검수(IV-10, 2026-11-08, V3)**: `inboundEvents`(mfc.inbound.v1) `ReturnItemReceived` → `return_receipt_projection`. `POST /return-inspections`(`ReturnInspectionService`) — 그 센터 MFC_SUPERVISOR, **반입자 아님**(422 SELF_APPROVAL — Q-11), 반입 없음·이미 검수 409, SKU 별 합계 = 반입 수량(422 QTY_EXCEEDS_RECEIVED), RESALE 은 상온·미개봉·신선 아님만(422 RESALE_NOT_ALLOWED). RESALE → inventory RELEASE(UNSTOWED, approvalRef = 검수자:검수 ID — 운영은 승인 번호 관문), DISPOSE → `DisposalService` REQUEST(sourceRef = 검수 ID). 끝나면 `ReturnInspected`. 결과 모름은 같은 키로 다시. 품질 사고(quality-incidents)는 아직 501.
- **본사 승인 번호 확인(2026-11-17, 계약 0.3.0 — 요청 AP-1, 사용자 Q-11·격리 해제도 같은 기준)**: `application/ApprovalRefs`(inventory 와 같은 판정 `domain/HqApprovalRules`·`infrastructure/ApprovalRefClient`) — 폐기 승인(`DisposalService.approve`, 승인 수량, ref = 폐기 요청 ID)·격리 해제 승인(`QualityDecisionService.approve`, 본문 `approvalRef` 새 칸, 해제 수량, ref = 판정 ID — 잠금 전). 기준 아래면 번호 선택(폐기는 기록 `CENTER:<id>`). RECORD_ONLY 는 예전대로(폐기 번호 필수, 해제는 번호 없이).
- **작업자 처리 내역(2026-11-17 — 요청 F-7 R3)**: `GET /internal/work-activities?mfcId&operatorId&from&to`(`WorkActivities`·`infrastructure/WorkActivityStore` — 이 원장의 행위자·시각 칸만, 최대 48시간·500건). mfc-capacity 가 모은다.
