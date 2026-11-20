# 알림: 작업자 목록·상세·근무 일정 — 작업자 토큰은 본인 것만 (mfc-capacity 0.28.0)

- 보낸 영역: mfc (PC 3 백엔드)
- 작성: pmart-backend, 2026-11-20
- 대상: pmart-clients `apps/mfc_app`(PDA), `web/mfc-backoffice`
- 배경: 사용자 결정(2026-11-20) "센터 관리자만 전체, 작업자는 자기 것만"(업무 문서 Q-16 최소 권한). 0.23.0 부터 목록에 이름·사번이 붙어, 크루가 다른 작업자의 상태·자격·이름을 볼 수 있었다.
- 배포: 백엔드 PR 병합·배포 뒤(PC 1). 모양 변화 없음 — 권한만.

## 바뀌는 것
| API | 작업자 역할(MFC_WORKER)만 가진 토큰 | 센터 관리자·본사 역할·서비스 |
|---|---|---|
| `GET /mfcs/{mfcId}/workers`(목록) | **403** | 그대로 |
| `GET /mfcs/{mfcId}/workers/{workerId}`(상세) | 본인만(남의 것 **403**) | 그대로 |
| `GET /mfcs/{mfcId}/workers/{workerId}/shifts`(근무 일정) | 본인만(남의 것 **403**) | 그대로 |

- PDA: 업무 선택·휴게 표시는 본인 상세라 그대로. 관리 화면(`/manage/workers`)은 센터 관리자 로그인에서만 열 것 — 작업자 토큰으로 열면 403.
- mfc-backoffice: 작업자 메뉴는 센터 관리자에게만. 작업자로 로그인했을 때 목록이 403 이면 "권한 없음" 표시.
- 바뀌지 않는 것: `staff-names`(번호 → 이름 표시)·`/assignments` 조회.
