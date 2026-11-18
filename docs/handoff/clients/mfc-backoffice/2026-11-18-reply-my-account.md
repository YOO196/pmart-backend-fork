# 회신: 직원 "내 정보" — 비밀번호·연락처 (R1·R2·R3)

- 보낸 영역: mfc (PC 3 백엔드)
- 작성: pmart-backend, 2026-11-18
- 답하는 것: pmart-clients `docs/handoff/mfc-backoffice/2026-11-18-backend-my-account.md`
- PR: 브랜치 `mfc/staff-contacts` — **mfc-capacity 0.21.0**(V18). LAN 반영은 PC 1 병합·배포 + 게이트웨이 라우트 뒤.

## 사용자 확인(2026-11-18) — 요청서 범위가 바뀜
- **비밀번호 = MFC 안에서만 쓰는 번호 = 센터 휴게 번호**(로그인 비밀번호 아님). platform(employee-auth) 요청은 철회(EA-3).
- **연락처 = 시스템이 쓰지 않는 연락 수단 표시용** — 라이더·다른 센터에서 보인다. hr 가 아니라 MFC(mfc-capacity)에 둔다(HR-3 철회).
- 회사 메일(로그인 ID)은 그대로 인사팀 경로만 — 이 화면과 무관.

## R1 → "내 정보"의 번호 = 센터 휴게 번호 (mfc-capacity 0.20.0, **이미 LAN 배포**)
- `GET /mfcs/{mfcId}/break-pin` → `{mfcId, set, updatedAt, updatedBy}`(그 센터 운영자, 번호는 안 돌려줌).
- `PUT /mfcs/{mfcId}/break-pin` `{pin}`(숫자 4자리, 그 센터 `MFC_SUPERVISOR`, Idempotency-Key·X-Source-System). 바꾸면 그 센터 작업자의 틀린 횟수·잠금이 풀린다.
- "지금 번호 확인"은 없다(센터 관리자 권한으로 바꿈). 화면은 "마지막 변경: updatedAt · updatedBy".
- 자세한 규칙: `docs/handoff/clients/mfc-app/2026-11-18-reply-break-supervisor-pin.md`.

## R2 → 센터 직원 연락처 (mfc-capacity 0.21.0, 새로)
- `GET /mfcs/{mfcId}/staff-contacts/me` — 내 연락처(그 센터 운영자 본인). 없으면 빈 값·`version 0`.
- `PUT /mfcs/{mfcId}/staff-contacts/me` `{name, phone, email}` + `X-Expected-Version`(지금 version, 처음 0 — 다르면 409 VERSION_CONFLICT). 빈 값·null 은 지움.
  - 형식: `phone` 숫자와 `-` 만·숫자 9~11자리, `email` 메일 형식, `name` 50자 이하 → 아니면 400 VALIDATION_FAILED, `details[].field` = phone·email·name.
- `GET /mfcs/{mfcId}/staff-contacts` — 그 센터 연락처 목록(**모든 운영자(다른 센터 포함)·라이더**). 휴대폰·이메일 중 하나라도 있는 사람만, 이름 순. 항목 `{mfcId, operatorId, name, phone, email, version, updatedAt}`.
- 시스템(로그인·알림 발송)은 쓰지 않고, 로그·사건에도 값을 남기지 않는다.

## R3 — 확인함
- `claude-mfc-sup`(MFC_SUPERVISOR)·`claude-mfc-wrk` 토큰으로 게이트웨이 `GET /api/v1/hr/employees/me` → 200(이름·사번·회사 메일·고용 형태·소속·상태·운영자 ID). "내 정보" 보기는 이것으로.

## 게이트웨이(PC 1)
- 허용 목록 3줄(PR 안), **라우트**: mfc-capacity 경로에 `/api/v1/mfcs/*/staff-contacts`·`/api/v1/mfcs/*/staff-contacts/**`(pmart-config — 시험 사본은 PR 안).

## 시험(PC 3)
- `StaffContactIT`(처음 빈 값·형식 400·저장 버전 1·낡은 버전 409·다른 센터 직원과 라이더가 목록에서 봄·다른 센터 직원은 자기 연락처 못 둠 403·비우면 목록에서 빠짐), 게이트웨이 시험 통과.

## 클라이언트 할 것
- "내 정보": 보기 `GET /hr/employees/me` + 센터 휴게 번호 폼(관리자만) + 내 연락처 폼(`staff-contacts/me`).
- 라이더 앱·다른 화면에서 센터 연락처를 보이려면 `GET /mfcs/{mfcId}/staff-contacts`(라이더 앱은 PC 5 영역 — 필요하면 그쪽에 요청).
