# mfc-location-service 에 대한 요청

받는 영역: mfc(PC 3). 표는 `README.md`(PC 1).

## ML-1. 실사 계획 잠금을 칸 화면에 보이기 (2026-11-20, 보낸 영역 mfc — 클라이언트 BR-52 R14)
- **요청한 곳**: mfc-inventory(실사 계획, 계약 0.34.0).
- **배경**: 사용자 결정(2026-11-20) "관리자가 계획·서버 잠금". `POST /mfcs/{mfcId}/count-plans`(mfc-inventory)가 칸마다 실사를 만들고, 그 실사가 끝나지 않은 동안 **mfc-inventory 가 그 칸을 잠근다**(이동·보충·적치·피킹 409 LOCATION_CLOSED). 끝나거나 취소되면 저절로 풀린다. mfc-location 의 `COUNT_LOCKED` 는 쓰지 않는다 — `POST /locations/{id}/state-changes` 는 그 센터 MFC_SUPERVISOR 운영자 토큰만 받아(`LocationService.requireOperator`) 서비스가 부를 수 없고, `countSessionId` 를 받는데 세션은 잠금 뒤에만 만들 수 있어 순서가 꼬였다(R14 원문).
- **필요한 것**: 칸 목록·칸 상세(센터 관리자 웹 지도, mfc-app)에 "실사 중(계획 잠금)" 표시가 필요하면, 둘 중 하나:
  1. mfc-inventory 가 계획 실사 시작·끝 사건을 내고(예 `CountPlanLocksChanged` — 아직 없음) mfc-location 이 구독해 표시만(상태 전이 아님), 또는
  2. 화면이 mfc-inventory `GET /mfcs/{mfcId}/count-plans/{planId}` 의 `lockedLocationCodes` 를 직접 읽음(지금 가능 — mfc-location 변경 없음).
- **지금(임시 방식)**: 2번. mfc-location 의 칸 상태는 ACTIVE 그대로 보인다.
- **요청한 쪽에서 바꿀 것**: 1번으로 가면 mfc-inventory 사건 계약(contracts/events)·events.md.
- **사용자 결정 필요 여부**: 화면에 잠금을 어디서 보일지(지도 표시 필요 여부) — 백오피스·앱 요청이 올 때.
- 근거: `services/mfc-inventory-service/src/main/java/com/pmart/mfc/inventory/application/Locations.java`(`requireNotPlanLocked`), `docs/handoff/clients/mfc-app/2026-11-20-reply-br52-inventory.md`.
