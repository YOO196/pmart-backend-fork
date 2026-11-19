# 회신: BR-52 입고·품질 남은 것 — R7·R12·R18·R19-4·R19-5·R19-7

- 보낸 영역: mfc
- 작성: pmart-backend(PC 3), 2026-11-20
- 답하는 것: pmart-clients `docs/handoff/mfc-app/2026-11-10-backend-pda-work-areas.md` 의 R7, R12, R18(1 제외 — 11-17 회신), R19-4·R19-5·R19-7. 앞 회신 `2026-11-14-reply-br52-r2-r19-1.md`·`2026-11-17-reply-qc-criteria-and-reject-reason.md`.
- 계약: `mfc-inbound-service.yaml` **0.15.0**, `mfc-quality-service.yaml` **0.9.0**. PR 병합 뒤 main 에서 `gen:api`. LAN 반영은 PC 1 배포 뒤 — **사진 저장소는 PC 1 설정(버킷·자격 증명·업로드 크기)이 들어가야 켜진다**(그 전 사진 올리기 503).
- 이번에 안 한 것: 입고 예정 `/inbound-promises`(사용자 결정 대기), R16 documentNo.

## 0. 요약

| # | 답 |
|---|---|
| R7 | 아래 §1 — 입고 품질검사는 mfc-inbound → mfc-inventory 로 바로 반영, mfc-quality 를 거치지 않는다 |
| R12 | `GET /return-receipts?mfcId=&inspectionState=PENDING`(오래된 순) + 반입 줄 `temperatureZone`·`productName`. 신선 여부(fresh)는 원천 값이 없어 못 줌 |
| R18-2 | 사진: mfc-quality `POST /quality-evidence-photos?mfcId=`(multipart) → `evidenceRef`. 품질검사 격리 줄 `quarantineReason`·`evidenceRef` |
| R18-3 | 반입 줄·반납 대기 줄 `temperatureZone`(R12 와 같음) |
| R18-4 | 서버는 합계 = 수령 수량을 요구하지 않는다(≤ 잔량, 부분 등록 허용). "전량 판정 뒤 한 번에"로 쓰면 된다 |
| R18-5 | 서버는 기준 미달 줄의 NORMAL 을 **거절하지 않는다**(Problem code 없음). PDA 가 막는 것이 최종 관문 |
| R19-4 | 적치 작업 단건 `GET /inbound-tasks/{taskId}` 에 `suggestedToLocations` |
| R19-5 | **이번에 못 함** — 재고 쪽 이동 규칙이 격리품을 옮길 수 없다. 요청서로 남김(§5) |
| R19-7 | 이동·보충·적치 예외 보고 경로(기록만). 남은 양 처리 규칙은 미정(§6) |

## 1. R7 — `/inbound-tasks/{id}/inspections` 와 mfc-quality 의 관계
- **품질검사(INSPECT)는 mfc-inbound 의 작업**이다. 결과 줄(수량·NORMAL/QUARANTINED·`sourceLotRef`·소비기한·제조일)을 mfc-inbound 가 기록하고(CAPTURED) **mfc-inventory INSPECT 실행으로 바로** 반영한다. mfc-quality 는 이 경로에 없다 — 사건도 오가지 않는다(품질 판정 `QualityDecisionMade` 는 나오지 않음).
- LOT 등록: 줄마다 inventory 가 `sourceLotRef`·소비기한·제조일로 추적 LOT 을 정한다(같은 표기·기한 = 같은 LOT, 시스템이 `lotNo` 를 붙임 — R15). `sourceLotRef` 없는 줄은 수령 LOT 그대로(나누지 않음).
- 반영되면(`applyStatus` APPLIED) 정상 LOT 마다 적치(PUTAWAY) 작업이 생긴다 — 응답 `createdTaskIds`. 격리 줄은 **수령 위치에 격리(QUARANTINED)로 남는다**.
- 격리 뒤의 처리는 mfc-quality: 해제는 `POST /quality-decisions`(RELEASE, lotId·locationCode = 수령 위치, workState UNSTOWED) → 다른 센터 관리자 승인, 폐기는 `/disposal-approvals`. **주의**: 해제된 물건에 대한 적치 작업은 지금 자동으로 생기지 않는다(§7 남은 것).
- 단계별 지시: 다음 작업 받기(`INSPECT`, taskId = 입고 작업) → `GET /inbound-tasks/{taskId}`(상품명·바코드·판정 기준·보관 온도대) → 줄 등록 → `createdTaskIds` 의 적치.

## 2. R12 — 회수품 검수 대기
- `GET /return-receipts?mfcId={mfcId}&inspectionState=PENDING` — **오래된 반입 순**(커서 = 마지막 `returnReceiptId`, size ≤ 200). `state` 를 비우면 재고 반영된(APPLIED) 반입만 준다 — mfc-quality 는 반영된 반입만 검수할 수 있다. `INSPECTED` 로 끝난 것도 볼 수 있다. 다른 값은 400.
- 반입 줄(`ReturnReceipt.lines[]`, 단건·목록)에 `productName`·**`temperatureZone`**(AMBIENT·CHILLED·FROZEN). 값은 mfc-inventory SKU 사본의 상품 보관 유형을 **조회 때** 읽는다(입고 작업의 `storageType` 과 같은 원천). 모르면 null. 회수품 검수 본문 `temperatureZone` 에 그대로 넣으면 된다.
- **신선 여부(`fresh`)는 주지 않는다** — 상품 정보(product-service → SKU 사본)에 신선 카테고리 값이 없다. 무엇을 "신선"으로 볼지(카테고리·보관 유형 기준)는 정해지지 않았다 — 정하면 상품 쪽 필드가 먼저다. 지금처럼 작업자가 고르고 서버 판정(422 RESALE_NOT_ALLOWED)을 따른다.
- 회수품 검수를 **배정 작업(`RETURN_INSPECT`)** 으로 주는 것은 mfc-capacity 일(이 PR 아님). 이 목록이 그 원천이 될 수 있다.

## 3. R18 — 증빙 사진·격리 사유·판정 확인
### R18-2 사진 (사용자 결정 2026-11-20 "사진은 mfc-quality")
- 올리기 `POST /quality-evidence-photos?mfcId={mfcId}` — `multipart/form-data` 의 `file` 하나, 헤더 `Idempotency-Key`·`X-Source-System`. 그 센터 `MFC_WORKER`·`MFC_SUPERVISOR`.
  - JPEG·PNG·WEBP(파일 앞부분으로 판정), 10MB 이하 — 아니면 **422 `PHOTO_INVALID`**. 같은 키·같은 사진 = 같은 `evidenceRef`, 같은 키·다른 사진 409 `IDEMPOTENCY_CONFLICT`. 저장소 미설정·장애 503.
  - 응답 201 `{evidenceRef: "EVQ-…", mfcId, contentType, sizeBytes, uploadedBy, uploadedAt}`.
- 보기 `GET /quality-evidence-photos/{evidenceRef}` — 원본 이미지(`Cache-Control: no-store, private`), 그 센터 운영자(역할 무관). 그 밖·없는 사진 404.
- 쓰는 곳: 회수품 검수 줄 `evidenceRef`, 입고 품질검사 격리 줄 `evidenceRef`, 현장 예외 보고 `evidenceRef`. 받는 쪽은 **참조만 저장**한다(존재 확인 안 함) — 반드시 이 업로드가 준 값을 넣어 주세요.
- 저장은 delivery-execution 전달 증빙 사진과 같은 방식(비공개 S3 호환 저장소).
### R18-2 격리 사유
- `/inbound-tasks/{id}/inspections` 결과 줄에 선택 `quarantineReason`(**EXPIRY**·**MANUFACTURE**·**DAMAGED**·**TEMPERATURE**·**OTHER** — 요청서 예시 그대로)·`evidenceRef`(≤ 64자). **QUARANTINED 줄만** — NORMAL 줄에 주면 400.
- 서버는 격리에 사유·사진을 **강제하지 않는다**(사용자 승인 "격리는 사유+사진"은 PDA 가 막는 것으로). 값은 실행 기록에 남고 재고 반영 값은 아니다. 아직 조회 응답에 다시 나오지 않는다(필요하면 말해 주세요 — 백오피스 화면 등).
### R18-4 줄 합계
- 서버 검사는 **합계 ≤ 작업 잔량**(넘으면 409 `TASK_QTY_EXCEEDED`)뿐 — 부분 등록 허용, 남은 양은 작업 READY 로 남는다. PDA 가 "전량 판정 후 한 번에 등록"으로 쓰는 것과 맞다.
### R18-5 기준 미달 NORMAL
- **거절하지 않는다.** 근거: 업무 문서에 서버 거절 규칙이 없고(사용자 승인 2026-11-10 의 "기준 미달·온도 이탈은 격리 강제"는 PDA 판정 화면 규칙), 기준 미달 LOT 은 재고에 들어가도 **예약·출하 적격 판정**(같은 `minRemainingDays`·제조 경과일 기준)에서 빠져 고객 주문에 배정되지 않는다. 그래서 Problem code 는 없다. 서버 거절이 필요하면 사용자 결정으로 다시 요청해 주세요.

## 4. R19-4 — 적치 추천 칸
- `InboundTask.suggestedToLocations`(문자열 배열, 앞이 우선) — RETURN 작업(mfc-fulfillment)과 **같은 이름·같은 원천**(mfc-inventory placement-suggestions: 그 상품이 이미 있는 칸 → 기타 칸, 진열 규칙 통과 칸만, 잔량 기준).
- **READY 적치 작업의 단건 조회 `GET /inbound-tasks/{taskId}` 에서만** 채운다. 목록·실행 응답·품질검사 작업·`withLabels=false` 는 빈 배열, 조회 실패도 빈 배열. 참고값 — 실제 판정은 적치 때 inventory(거절이면 `REJECTED` + 사유).

## 5. R19-5 — 격리품 이동: 이번에 못 함
- 재고 쪽 이동 규칙이 격리품을 옮길 수 없다: 이동 배정은 출발·도착 모두 **선반·피킹 구역**의 **정상(NORMAL)·적치 완료** 재고만, 적치는 정상만 받는다. 격리 구역(상온·냉장·냉동 각 4평 — 업무 문서 확정)으로 옮기는 길이 없다.
- 재고 쪽 요청서로 남겼다: `docs/architecture/service-requests/mfc-inventory-service.md` **IV-6**. 그게 생기면 mfc-inbound 가 품질검사 격리 줄마다 이동 작업을 만들고 다음 작업 받기로 준다. 반쪽(작업만 만들고 실행은 거절) 구현은 하지 않았다.
- 정할 것(사용자): 격리 이동을 누가 하나(품질검사 직원 이어서 / 재고 이동 업무), 격리 구역 칸 코드, 회수품 반품 위치(RETURN 구역)와 격리 구역의 관계.

## 6. R19-7 — 현장 예외 보고(이동·보충·적치)
- `POST /move-orders/{moveId}/exceptions`(이동·자동 보충 — 그 센터 MFC_WORKER·MFC_SUPERVISOR, ACTIVE·CANCEL_PENDING, 담당이 있으면 담당 또는 카트에 실은 사람)
- `POST /inbound-tasks/{taskId}/exceptions`(적치 PUTAWAY 의 READY 만 — 품질검사 작업은 409 INVALID_STATE, 담당이 있으면 담당)
- 본문 `{reason, qty?, locationCode?, note?(≤200), evidenceRef?}` — `reason` 은 **PICK 결품 사유와 같은 값**: `SHORT`(출발 칸에 없음·모자람) · `DAMAGED`(파손) · `SPOILED`(변질) · `BLOCKED`(칸을 못 씀 — 도착 칸 가득·막힘·접근 불가). 헤더 `Idempotency-Key`(같은 키 = 같은 기록, 다른 내용 409)·`X-Source-System`.
- 응답 201 `TaskException`(exceptionId·targetType MOVE/REPLENISH/PUTAWAY·targetId·sku·lotId·reason·qty·locationCode·note·evidenceRef·reportedBy·reportedAt). 센터 관리자 목록 `GET /task-exceptions?mfcId=`(최근 순, from·to·커서).
- **기록만** 한다 — 재고·이동 배정·작업 잔량·상태는 그대로. 보낸 뒤 서버가 남은 양을 어떻게 다룰지(다른 칸 재지시·관리자 이관·자동 격리·품질 사고)는 **업무 규칙이 정해지지 않아** 하지 않았다. 지금 운영: 이동은 센터 관리자가 목록을 보고 취소(→ 카트 정리)·새 지시, 적치는 다른 칸으로 다시 적치.
- 복귀(RETURN — mfc-fulfillment)·실사(mfc-inventory)의 예외 보고는 이 PR 범위 밖(그 서비스 몫).

## 7. 남은 것
- R12 `fresh` — 신선 기준(사용자 결정) + 상품 필드.
- R12-1 회수품 검수·반입을 배정 작업으로(mfc-capacity), R13 반입 예정 조회.
- R18-2 격리 사유를 조회 응답에 다시 보이기(필요하면).
- R19-5 격리 이동 — IV-6(inventory) + 사용자 결정 뒤.
- R19-7 예외 뒤 처리 규칙(재지시·이관·격리·품질 사고 연결), 복귀·실사 예외.
- 격리 해제(RELEASE)된 입고분의 적치 작업 자동 생성 — 지금은 수령 위치에 정상으로 남는다(새 요청 필요 여부 사용자 판단).
- 참조 없는 사진 객체 정리(올린 뒤 참조 저장이 실패하고 다시 보내지 않은 요청) — delivery 의 5시간 정리 같은 것은 아직.

## 시험(PC 3, 2026-11-20)
- mfc-inbound `check`+`integrationTest`: 단위 5·통합 14 통과 — 격리 줄 사유·사진 기록(재고 본문에는 없음)·정상 줄 사유 400, 적치 추천 칸(단건만·`withLabels=false` 빈 배열), 적치 예외 보고(같은 키·다른 내용 409·품질검사 409·목록·다른 센터 403), 이동 예외 보고(기록만·작업자만·사유 값), 검수 대기 큐·줄 온도대.
- mfc-quality `check`+`integrationTest`: 단위 14·통합 9 통과 — 새 `EvidencePhotoIT`(올리기 201·같은 키 같은 ref·같은 키 다른 사진 409·형식 422 PHOTO_INVALID·다른 센터·역할 없음 403, 보기 그 센터 200 no-store·다른 센터 404). 저장소는 시험용 메모리(실제 S3 연결은 LAN 설정 뒤).
- 게이트웨이 허용 목록 시험 통과(새 경로 5줄).
