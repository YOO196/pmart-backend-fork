# 알림 — 포장 인계 번호(handoverCode) 4자리, 5번 틀리면 잠김

보낸 영역: mfc
- 작성: pmart-backend PC 3(mfc), 2026-11-20
- 대상: **라이더 앱**(픽업 번호 입력), **mfc-app**(포장 라벨 인쇄), **MFC 백오피스·기본 백오피스**(포장 라벨 표기)
- 근거: MFC 시뮬레이터 R-26, 사용자 2026-11-20 "QR 인식이 불가할 때 번호입력해서 픽업할 수 있는 기능 … 앞으로 발생하는 주문 인계 번호는 다 4자리로"
- 상태: 백엔드 PR(PC 1 검토·배포 전) — mfc-fulfillment 계약 **0.23.0**. 병합 뒤 `gen:api`

## 바뀌는 것
- `Package.handoverCode`·인계 판정 `handoverCode`·센터 출고 목록 `packages[].handoverCode`(bff 포장 인계 현황이 넘기는 값): 새 포장은 **숫자 4자리**(앞자리 0 포함 — 문자열 그대로). 센터에서 지금 쓰는(OPEN·SEALED) 포장끼리 겹치지 않는다. 배포 전 포장은 6자리 그대로 — 화면은 길이를 가정하지 말 것.
- `POST /packages/handover-code-authorizations`(라이더, QR 대신 번호로 픽업 허가): 본문 그대로, `handoverCode` pattern `^([0-9]{4}|[0-9]{6})$`.
  - 틀림: 409 `HANDOVER_CODE_MISMATCH`, details `attemptsLeft`(남은 횟수).
  - **같은 라이더·배송이 5번 틀리면 409 `HANDOVER_CODE_LOCKED`** — 맞는 번호도 거절. "QR 을 찍거나 센터 직원에게 확인받으세요" 안내(반납 인증번호 잠김과 같은 모양).

## 앱별
| 앱 | 할 일 |
|---|---|
| 라이더 앱 | 지금은 이 API 를 부르지 않음(픽업 QR 만 — PC 5 회신 2026-11-20). "번호로 인계"를 넣게 되면 길이 4자리(옛 포장 6자리도 허용), 틀림 남은 횟수(`attemptsLeft`)·잠김(`HANDOVER_CODE_LOCKED`) 문구 |
| mfc-app | 라벨 인쇄는 받은 값 그대로(4자리). "6자리" 문구가 있으면 고침 |
| MFC 백오피스·기본 백오피스 | 표기는 받은 값 그대로. 주석·문구·목 데이터의 "6자리"(`482913`, `305118` 등)는 4자리로 |
