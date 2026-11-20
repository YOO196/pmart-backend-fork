# 요청: 재고 실사 시간대·순환 실사 시작 화면 (MFC 백오피스)

보낸 영역: mfc (PC 3) · 2026-11-20 · 백엔드 PR(브랜치 `mfc/count-windows`) — LAN 배포 전

**자세한 지침(요청·응답 예, 오류, 화면 제안, PDA 포함)은 `../all/2026-11-20-guide-count-windows-and-rotation.md`.**

사용자 결정(2026-11-20): "재고실사는 하루 두 번 아침·저녁 — 아침 7시~8시, 저녁 8시~9시 디폴트이되 변경 가능하게, 관리자 권한으로 재고실사 시작, 20칸, 추천대로".

## 1. 실사 시간대 설정 (mfc-capacity 0.33.0)
- `GET /api/v1/mfcs/{mfcId}/count-windows` → `{ windows:[{startTime:"07:00", endTime:"08:00"}, …], source: DEFAULT|CENTER, version, updatedBy, updatedAt, activeNow }`. 바꾼 적 없으면 기본값(source DEFAULT, version 0).
- `PUT` 같은 경로(그 센터 MFC_SUPERVISOR) 본문 `{ windows:[…] }` — 목록 통째로, 헤더 `Idempotency-Key`·`X-Source-System`·`X-Expected-Version`(= 받은 version). 서울 시각 "HH:mm", 시작 < 끝(자정 넘김 불가), 겹침 불가, 최대 6개 → 틀리면 400. 빈 목록 = 시간대 없음.
- 화면 제안: 센터 설정에 "재고 실사 시간대" 줄 편집(추가·삭제), 지금 시간대면 "실사 시간" 표시(`activeNow`).
- 동작: 시간대 안에서는 실사 업무가 있는 작업자의 "다음 작업 받기"가 실사를 피킹보다 먼저 준다(실사가 없으면 다른 일). 시간대가 됐다고 실사가 저절로 열리지는 않는다 — 아래 2로 관리자가 시작.

## 2. 순환 실사 시작 (mfc-inventory 0.42.0)
- `POST /api/v1/mfcs/{mfcId}/count-plans` 본문에 `locationCodes` 없이 `{ "rotationSize": 20 }`(생략하면 20) → 서버가 선반·피킹 칸 중 실사·이동 중이 아닌 칸을 **오래 안 센 칸부터** 골라 계획을 연다(응답은 지금 CountPlan 과 같음 — `sessions[].locationCode` 로 고른 칸 표시). 사유 없으면 "순환 실사". `assigneeId`(담당)도 같이 줄 수 있다.
- 고를 칸이 없으면 422 `NO_COUNT_LOCATIONS`, `locationCodes` 와 `rotationSize` 를 같이 주면 400.
- 화면 제안: 실사 화면에 "순환 실사 시작" 버튼(칸 수 기본 20, 담당 선택) + 기존 "칸 골라 계획". 시작 전 "고른 칸은 실사가 끝날 때까지 피킹·이동이 막힌다" 안내.
