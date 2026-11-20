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
- **회수품 검수(IV-10, 2026-11-08, V3)**: `inboundEvents`(mfc.inbound.v1) `ReturnItemReceived` → `return_receipt_projection`. `POST /return-inspections`(`ReturnInspectionService`) — 그 센터 MFC_SUPERVISOR, **반입자 아님**(422 SELF_APPROVAL — Q-11), 반입 없음·이미 검수 409, SKU 별 합계 = 반입 수량(422 QTY_EXCEEDS_RECEIVED), RESALE 은 상온·미개봉·신선 아님만(422 RESALE_NOT_ALLOWED). RESALE → inventory RELEASE(UNSTOWED, approvalRef = 검수자:검수 ID — 운영은 승인 번호 관문), DISPOSE → `DisposalService` REQUEST(sourceRef = 검수 ID). 끝나면 `ReturnInspected`. 결과 모름은 같은 키로 다시. 품질 사고는 아래(0.6.0).
- **본사 승인 번호 확인(2026-11-17, 계약 0.3.0 — 요청 AP-1, 사용자 Q-11·격리 해제도 같은 기준)**: `application/ApprovalRefs`(inventory 와 같은 판정 `domain/HqApprovalRules`·`infrastructure/ApprovalRefClient`) — 폐기 승인(`DisposalService.approve`, 승인 수량, ref = 폐기 요청 ID)·격리 해제 승인(`QualityDecisionService.approve`, 본문 `approvalRef` 새 칸, 해제 수량, ref = 판정 ID — 잠금 전). 기준 아래면 번호 선택(폐기는 기록 `CENTER:<id>`). RECORD_ONLY 는 예전대로(폐기 번호 필수, 해제는 번호 없이).
- **작업자 처리 내역(2026-11-17 — 요청 F-7 R3)**: `GET /internal/work-activities?mfcId&operatorId&from&to`(`WorkActivities`·`infrastructure/WorkActivityStore` — 이 원장의 행위자·시각 칸만, 최대 48시간·500건). mfc-capacity 가 모은다.
- **센터 관리자 웹 목록(2026-11-18, 계약 0.5.0 — 요청 MB-1)**: `GET /quality-decisions`·`/disposal-approvals`·`/return-inspections`(`application/QualityLists`·`infrastructure/QualityListStore` — 센터·상태로 ID, 기본 끝나지 않은 것, 오래된 순, 커서 = 마지막 ID). 항목은 컨트롤러가 단건 조회 변환 그대로.
- **품질 사고(2026-11-19, 계약 0.6.0, V4 — MB-2 1차, 사용자 결정)**: `IncidentService`·`infrastructure/IncidentStore`(`quality_incident`·`quality_incident_lot`). 목록·단건(그 센터 운영자, 기본 OPEN·DECIDED 오래된 순, 조회 때 상태 다시 맞춤), 직접 등록 `POST /quality-incidents`(MFC_SUPERVISOR, LOT 은 `StockLookupClient` 로 그 센터 재고 확인 — 토큰 전달, 없으면 422 LOT_NOT_IN_CENTER, 격리는 걸지 않음), 닫기 `…/closures`(MFC_SUPERVISOR, 사유·버전, 남은 LOT 409 LOTS_NOT_RESOLVED). 자동 = `fulfillmentEvents`(mfc.fulfillment.v1) LineShortageReported reason DAMAGED만 → 같은 센터·LOT 의 열린 자동 파손 사고에 묶음. LOT 처리 = RELEASE APPLIED(판정 incident_id 또는 같은 센터·LOT·사고 뒤) 또는 폐기 승인(같은 센터·LOT·사고 뒤). IncidentKind SPOILAGE(변질) — 자동 변질은 결품 사유 값이 없어 아직. 고객 영향 case(R6)는 2차. 시험 `IncidentIT`.
- **claim 없는 반납 검수·주문 줄 번호(2026-11-19, 계약 0.7.0, V5 — 요청 MI-1·MQ-1)**: 반입 사본·검수에 purpose·case_id·origin_delivery_id, claim_id 선택(검수 요청 claimId 는 반입과 같아야 — 다르면 400, 검수의 claimId 는 반입 사본에서). 반입 줄 orderLineId 를 사본에 두고, 검수 줄에 없으면 `ReturnInspectionService.byOrderLine` 이 반입 줄 수량대로 나눠 채움 → `ReturnInspected.lines[].orderLineId`·purpose·caseId(claimId 는 있을 때만). 검수 규칙은 같음. 시험 `ReturnInspectionIT.misdeliveryReturnIsInspectedWithoutClaimAndKeepsOrderLines`.
- **품질 사고 고객 영향(2026-11-19, 계약 0.8.0, V6 — MB-2 2차 R6)**: `IncidentService.assessImpacts`(@Scheduled `pmart.quality.sweep-interval`) — 안 셌거나(새 사고·LOT 추가 = `affected_checked_at` NULL) `pmart.quality.incident-impact-recheck`(기본 PT5M) 지난 열린 사고를 mfc-inventory `/internal/lot-orders`(`infrastructure/LotOrdersClient`)로 다시 셈 — 인계(handedQty>0) 주문만. 결과가 바뀌면(jsonb 는 열쇠 순서를 바꿔 트리로 비교) `affected_orders`·`affected_order_count`·`impact_version` + `QualityIncidentImpactAssessed`(mfc.quality.v1, 전체 목록, 0건도 처음 한 번). 사고 응답 `affectedOrderCount`. caseId 는 case-service 요청(CS-MFC-Q) 뒤. 시험 `IncidentIT.impactCountsHandedOverOrdersAndPublishesOnChange`.

- **변질 자동 사고(2026-11-19)**: `LineShortageReported` reason SPOILED(fulfillment 0.22.0) → kind SPOILAGE 자동 사고(파손 DAMAGED → DAMAGE 와 따로 묶음). 시험 `IncidentIT.damagedShortageOpensAutoIncidentAndSameLotIsGrouped`.
- **품질 증빙 사진(2026-11-20, 계약 0.9.0, V7 — PDA BR-52 R18-2, 사용자 결정 "사진은 mfc-quality")**: `application/EvidencePhotos`·`api/EvidenceController` — `POST /quality-evidence-photos?mfcId=`(그 센터 MFC_WORKER·SUPERVISOR, multipart, JPEG·PNG·WEBP 10MB `domain/EvidencePhotoRules`, ref `EVQ-` = 출처·키·사람·센터·해시, RequestIdempotency) → `quality_evidence_photo`, 원본은 `infrastructure/EvidencePhotoStorageConfig`(S3 호환 `pmart.storage.s3.*` — delivery-execution 과 같은 방식, 버킷 없으면 빈 없음 → 503). 보기 `GET /quality-evidence-photos/{ref}`(그 센터 운영자, 그 밖 404, no-store). 참조 없는 객체 정리는 아직. 시험 `EvidencePhotoIT`(메모리 저장소).
- **품질 사고 LOT 판매 보류(2026-11-20, 계약 0.10.0, V8 — 사용자 결정, IV-09 범위 불명 보류)**: 직접 등록 `saleHold`(기본 true) → `quality_incident_lot.sale_hold` HOLD_PENDING → `IncidentService.syncSaleHolds`(등록·닫기 직후 한 번 + @Scheduled `pmart.quality.sweep-interval`)가 mfc-inventory `/internal/lot-sale-holds`(`InventoryClient.lotSaleHold`, 키 = 사고:LOT:place/release, sourceRef = 사고 ID)로 HELD, LOT 이 처리(해제 APPLIED·폐기 승인)되거나 사고가 닫히면 `/releases` → RELEASED(실패면 RELEASE_PENDING 으로 남아 다음 주기). 자동 사고(결품 파손·변질)는 걸지 않는다(null — 보고된 실물만 격리됨). 응답 `affectedLots[].saleHold`. 시험 `IncidentIT.manualIncidentHoldsLotsFromSaleUntilResolvedOrClosed`.
