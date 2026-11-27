보낸 영역: mfc(PC 3 백엔드)
받는 앱: mfc-backoffice(·mfc-app)
원 문서: pmart-clients `docs/handoff/mfc-backoffice/2026-11-27-backend-assignment-release.md`(PR #617)

# 회신 — 센터 관리자 배정 풀기 (mfc-capacity 0.36.0)

사용자 결정(2026-11-27): 추천대로, 이미 일부를 한 작업은 **남은 양만 풀기**.

## API
`POST /api/v1/assignments/{taskId}/releases` (mfc-capacity)
- 헤더 `Idempotency-Key`·`X-Source-System`. 본문 `{ "mfcId", "workerId"(화면이 본 지금 담당), "reason"(1~200자) }`.
- 누가: 그 센터 `MFC_SUPERVISOR`, 본사 `OPERATOR_ADMIN`. 작업자 403.
- 200 `{ taskId, released, assignmentVersion }` — released false = 활성 배정이 이미 없음(변화 없음).
- 409 `NOT_ASSIGNEE`(담당이 바뀜 — 다시 읽기), `USE_FULFILLMENT_RELEASE`(PICK·PACK·RETURN·DISPATCH — 지금처럼 fulfillment `POST /tasks/{taskId}/releases`), `ON_CART`(재고 이동을 카트에 실은 채 — 실은 사람이 내려놓아야), 403 다른 센터 작업, 400 사유 없음, 503 이동 지시를 못 읽음.

## 풀리면
- 배정 이력: RELEASED, `endedBy` = 푼 관리자, `endReason` = 사유.
- 사건 `AssignmentReleased` → mfc-inbound 가 입고 작업(INSPECT·PUTAWAY·QUARANTINE_MOVE)·이동 지시(MOVE·REPLENISH)의 `assigneeId` 를 비움(몇 초). 한 만큼은 그대로, **남은 양**이 담당 없이 다음 작업 받기·그 센터 운영자에게 열림.
- 입고(RECEIVE)·회수품·폐기·실사처럼 담당이 capacity 에만 있는 유형은 배정만 풀림.
- 격리 이동(QUARANTINE_MOVE)은 미리 정한 담당이 비므로, 다음 작업 받기 "격리 이동 먼저"는 더 이상 그 사람에게 가지 않음 — 필요하면 `POST /assignments` 로 다시 배정.
- 입고 작업에 재고 반영 중(결과 모름)인 실행이 있어도 막지 않음 — 같은 실행 키로 이어서 반영되고 그 양은 남은 양에서 빠짐.

## 배포
PR(PC 3 — 브랜치 `mfc/assignment-release`), 게이트웨이 허용 목록 포함. 경로는 기존 `/api/v1/assignments/**` 라우트.
