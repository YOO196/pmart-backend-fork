# employee-auth-service 에 요청

## EA-1 권한 조기 회수·종료 사건 (2026-11-13, 2026-11-17 구현 — PC 1)

- **요청한 곳**: mfc-capacity(mfc 영역, PC 3) — 작업자 명단 ↔ 권한 연결(사용자 2026-11-13 "센터 관리자는 작업자와 별개").
- **필요한 것**: 직원 권한(grant)이 **기간 전에 끝날 때**(BO-01 권한 변경·회수, 센터 이동 때 앞 센터 권한을 일찍 끝냄 등) 그 사실을 알리는 사건 — 예 `GrantRevoked`(또는 `GrantEnded`) `{grantId, operatorId, roles, centerScope, endedAt, reason}`, 토픽 `platform.auth.v1`(`GrantApplied` 와 같은 곳). 기간대로 끝나는 것(`GrantApplied.effective.to`)은 사건이 없어도 된다 — mfc-capacity 가 기간을 저장해 맞춘다.
- **지금(임시 방식)**: mfc-capacity 는 `GrantApplied`(역할 MFC_WORKER)의 기간 `[effective.from, effective.to)` 만 안다. 권한이 일찍 회수돼도 작업자는 기간 끝까지 그 센터에서 ACTIVE 로 남는다(토큰에서 그 센터가 빠지므로 본인은 다음 작업 받기를 못 하지만, 센터 관리자 수동 배정·배정 추천에는 나올 수 있다). 센터 관리자가 작업자 상태를 직접 SUSPENDED 로 바꾸면 막힌다.
- **요청한 쪽에서 바꿀 것**: 사건이 생기면 `HrLinkService` 가 그 권한 행의 `valid_to` 를 `endedAt` 로 당기고 맞춘다(구독 필터에 사건 이름 하나 추가).
- **사용자 결정 필요**: 없음(회수 자체는 BO-01 확정 정책 — 백오피스 영역에서 구현될 때 같이).
- 근거: `services/mfc-capacity-service/CLAUDE.md`(작업자 명단 ↔ 인사·권한 연결), `docs/architecture/auth/employee-auth-structure.md`(센터 이동 = 소속만 바꾸고 권한은 A 종료·B 시작을 따로 요청).
- **만든 것(2026-11-17, 사용자 "바로 할수있는거 다해줘")**: `GrantEnded` v1(`contracts/events/GrantEnded.v1.json`, 토픽 `platform.auth.v1`, aggregate `Grant`·aggregateId = grantId·aggregateVersion 2 — `GrantApplied` 가 1) `{grantId, approvalRef{approvalId}, operatorId, roles[], centerScope[], endedAt, reason}`. grant 마다 한 번, 상태 변경과 같은 트랜잭션(outbox).
  - `REVOKED` — 회수 `POST /employee-auth/employees/{id}/grant-revocations`(`EmployeeAdminService.revokeGrant`), endedAt = 회수 시각, actor = 회수한 운영자. 이미 회수한 것을 다시 보내면 사건 없음.
  - `AFFILIATION_CHANGED` — `EmployeeAffiliationChanged` 로 이전 센터 grant 를 끝낼 때(`EmployeeEventHandler`), endedAt = `previousCenterAccessUntil`(다음 날 00:00 Asia/Seoul), actor = SYSTEM hr-service. 원래 끝이 그보다 이르면(당겨지지 않음) 사건 없음.
  - 내지 않는 것: 기간대로 끝남, 퇴사·정지(grant 를 바꾸지 않는다 — mfc-capacity 는 `EmployeeLeft`·`EmployeeSuspended` 를 이미 구독). 회수 사유 글은 싣지 않는다(DB 에만).
  - 남은 것: mfc-capacity 구독(PC 3 — `other-pcs-todo.md`). endedAt 이 그 권한 시작보다 이를 수 있다(시작 전 권한을 회수) — 그때는 그 기간이 비게 처리.
- **받는 쪽 구현(PC 3, 2026-11-17 — 브랜치 `mfc/grant-ended`, mfc-capacity 계약 변경 없음)**: 구독 필터(`HrLinkService.workerGrant`)에 `GrantEnded`(역할 MFC_WORKER) 추가 → `onGrantEnded` 가 grant 의 MFC 센터마다 `HrLinkStore.endGrant` — `valid_to = max(valid_from, min(valid_to, endedAt))`(늦추지 않음, 시작 전 회수는 빈 기간), 행이 없으면(종료가 반영보다 먼저) `[endedAt, endedAt)` 빈 행을 먼저 남겨 뒤의 반영(ON CONFLICT DO NOTHING)이 되살리지 못함 → 인사 등록이 있으면 바로 `reconcile`(권한 주체로 SUSPENDED). REVOKED·AFFILIATION_CHANGED 같은 처리(사유는 로그만). 바인딩 `authEvents-in-0`(platform.auth.v1) 그대로라 설정 변경 없음. 시험 `HrLinkIT.revokedGrantSuspendsWorkerAtOnceAndEarlyEndIsNotRevived`.

## EA-2 역할 목록(BR-57) (2026-11-15 구현)

- **요청한 곳**: 클라이언트 hr-backoffice(pmart-clients `docs/handoff/hr-backoffice/2026-11-15-backend-rider-ops-role-and-role-list.md`, BR-57) — 직원 등록 "요청 권한 역할" 고르기가 화면에 고정 목록을 두어, 역할이 늘 때마다 알림 → 화면 수정 → 배포가 필요하고 그 사이 어긋남(화면만 늘면 UNKNOWN_ROLE).
- **만든 것**: `GET /api/v1/employee-auth/roles`(계약 0.5.0, `x-pmart-user-types: [OPERATOR]` — 비밀이 아니라 역할과 상관없이 직원 토큰이면) → `{items: [{code, name, centerScoped, order}]}`, order 오름차순.
- **정본**: 설정 `pmart.employee-auth.roles`(순서 — 승인 반영·관리 경로의 UNKNOWN_ROLE 판정도 그대로 이 목록) + 새 `role-info`(목록 `{code, name, center-scoped}` — 이름 없으면 code). centerScoped = MFC_SUPERVISOR·MFC_WORKER(화면 안내용, 판정은 approval-service).
- **요청한 쪽에서 바꿀 것**: 화면 고정 목록 `GRANT_ROLES` 대신 이 응답, 못 불러오면 오류로(클라이언트 회신 예정).
- **사용자 결정 필요**: 없음(사용자 2026-11-15 "만들거야").
