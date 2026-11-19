# 요청 — MFC 화면의 라이더 표기를 "가린 이름 · 라이더 번호"로 (RS-MFC 붙이기)

보낸 영역: mfc
- 작성: pmart-backend PC 3(mfc), 2026-11-20
- 받는 앱: **mfc-backoffice**(웹), **mfc-app**(PDA) — 둘 다 클라이언트 PC 3 담당
- 근거: ADR-0070(화면 번호 — 라이더는 `김*수 · 482913`), 백오피스 요청 pmart-clients `docs/handoff/mfc-backoffice/2026-11-19-backend-display-labels.md`, 서버 회신 `docs/handoff/clients/mfc-backoffice/2026-11-20-reply-rider-display-names.md`(PC 5)
- 서버 상태: **LAN 배포됨** — rider-service **0.18.0**(백엔드 #261, main 80fe7c91·e17f83c1). 화면 작업 전에 `gen:api`(rider-service).

## 서버가 주는 것
- `GET /api/v1/riders/display-names?riderIds=RD-a,RD-b`
  - riderIds 는 쉼표로 **50개까지**, 비우면 400.
  - 토큰: MFC_SUPERVISOR·MFC_WORKER(그 밖에 OPERATOR_ADMIN·RIDER_OPS·CS_AGENT). 라이더·고객은 403.
- 응답 `{"items":[{"riderId":"RD-…","riderNumber":"482913","nameMasked":"김*수"}]}`
  - **없는 ID 는 빠지고 순서도 보장하지 않는다** — `riderId` 로 맞춘다.
  - 탈퇴한 라이더는 `nameMasked` 가 null → `라이더 · 482913`.
  - 전화·상태·제한 같은 다른 칸은 없다. 라이더 찾기·상세(`GET /riders`, `/riders/{id}`)는 지금처럼 센터 직원에게 403.

## 고칠 곳

### mfc-backoffice (web/mfc-backoffice)
지금 `shared/displayNumber.tsx` `RiderLabel` 은 `rider`(riderNumber·nameMasked)가 없으면 "숨김"(`m.riderHidden`)으로 보인다. 원천 응답에는 대개 `riderId` 만 있어서, 아래 화면은 지금 라이더가 다 "숨김"이다.

| 화면 | 파일 | 지금 |
|---|---|---|
| 센터 배송 목록 | `features/delivery/CenterDeliveryPages.tsx` | `<RiderLabel rider riderId>` |
| 배차 지연 | `features/delivery/DispatchDelayPage.tsx` | `<RiderLabel riderId>` |
| 포장 인계 현황 | `features/delivery/PackingHandoverPage.tsx` | `<RiderLabel rider riderId>` |
| 회수품 받기 | `features/delivery/ReturnHandoverPage.tsx` | `<RiderLabel riderId>` |
| 센터 운영판 | `features/ops/OpsBoard.tsx` | `<RiderLabel riderId>` |
| 인계 확인 | `features/tasks/HandoverChecks.tsx` | 라이더 칸 |

- 할 것: 화면이 받은 `riderId` 들을 모아(중복 제거, 50개씩) `display-names` 를 **한 번** 부르고, 결과를 `riderId → {riderNumber, nameMasked}` 로 `RiderLabel` 에 넘긴다. 줄마다 부르지 않는다.
- 결과에 없는 ID(삭제·조회 실패)는 지금처럼 "숨김" 표기. 조회 자체가 실패해도 목록은 그대로 보이고 라이더 칸만 "숨김".
- 시스템 ID(`RD-…`)는 목록에 보이지 않게(ADR-0070) — 상세의 "시스템 번호" 칸만.

### mfc-app (apps/mfc_app)
지금 라이더 칸이 **시스템 ID 그대로**다.

| 화면 | 파일·문구 |
|---|---|
| 인계 판정·확인 | `features/handover/presentation/handover_screens.dart` — `l10n.hoRider(h.riderId)` "허가받은 라이더: {riderId}", `l10n.confirmTakenBody(item.riderId)` |
| 회수품 받기 | `features/return_handover/presentation/return_handover_screen.dart` — `l10n.rhRider(item.riderId)` "라이더: {riderId}" |

- 할 것: 위와 같이 묶어서 한 번 조회 → `김*수 · 482913`(탈퇴는 `라이더 · 482913`), 못 찾으면 "라이더"만(ID 를 보이지 않는다). arb 의 `{riderId}` 자리는 표기 문자열로.
- 목(mock): `rider-mock-1`·`r-7`·`r-9` 에 가짜 번호·가린 이름.

## 회신
- 끝나면 pmart-clients `docs/handoff/mfc-backoffice/`(웹)·`mfc-app/`(PDA)에 짧게 — 바꾼 화면, 시험.
