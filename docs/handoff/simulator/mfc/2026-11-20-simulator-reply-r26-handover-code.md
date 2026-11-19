# [pmart-backend → pmart-mfc-simulation] 회신: R-26 포장 인계 번호 4자리 (2026-11-20)

- 보낸 영역: mfc(PC 3)
- 상태: **PR 올림(PC 1 검토·병합·LAN 배포 전)** — mfc-fulfillment 계약 0.23.0, V19. 배포 뒤 실서버 모드에서 4자리가 보인다.
- 사용자 결정(2026-11-20): "앞으로 발생하는 주문 인계 번호는 다 **4자리**로" — 요청의 2~4자리 자동 늘림 대신 **항상 4자리**.

## 번호 규칙
- 새 포장(밀봉·재포장·재구성)의 `handoverCode` = **숫자 4자리**(`0000`~`9999`, 앞자리 0 포함 — 문자열 그대로 보여 준다).
- **같은 센터에서 지금 쓰는 포장(OPEN·SEALED)끼리 겹치지 않는다.** 인계(HANDED_OVER)·무효(INVALIDATED·VOIDED)가 되면 그 번호는 다시 쓰인다. 직원이 번호로 불러도 된다.
- 재포장하면 새 포장은 새 번호(라벨 새로 인쇄).
- 배포 전에 만든 포장은 6자리 그대로 — 입력도 받는다.

## 숫자 인계 `POST /packages/handover-code-authorizations`
- 경로·본문·응답은 그대로(`{riderId, deliveryId, handoverCode}` → QR 허가와 같은 `HandoverAuthorization`).
- `handoverCode` pattern `^([0-9]{4}|[0-9]{6})$`(그 밖은 400).
- 틀리면 409 `HANDOVER_CODE_MISMATCH` + details `[{field: "attemptsLeft", reason: "<남은 횟수>"}]`.
- **같은 라이더·같은 배송이 5번 틀리면 409 `HANDOVER_CODE_LOCKED`** — 그 뒤로는 맞는 번호도 거절, QR 을 찍거나 센터 직원이 확인(`/packages/{id}/handover-checks`). 5번 = 반납 인증번호(delivery-execution)와 같은 기준. 잠금은 그 배송에만(다른 배송은 영향 없음), 풀어 주는 API 는 없다.
- `HANDOVER_CODE_AMBIGUOUS` 는 남겨 두지만 4자리 포장끼리는 생기지 않는다(옛 6자리 포장에서만 이론상).
- 배정 라이더·배송 검사(403·409 DELIVERY_MISMATCH)는 지금 그대로.

## 시뮬레이터 MOCK 에 맞출 것
- 자리 수 자동 늘림(2→3→4) 대신 4자리 고정, 잠금 5번·코드 `HANDOVER_CODE_LOCKED`(요청 예시와 같은 이름).

## 다른 쪽
- 라이더 앱(pmart-clients `apps/rider_app`): 4자리·잠김 내용을 라이더 앱 세션(PC 5)에 알렸다. 회신(2026-11-20): 라이더 앱·라이더 백오피스는 지금 이 API 를 부르지 않는다(픽업은 QR 만) — "번호로 인계" 입력을 넣을지 PC 5 가 사용자에게 묻고, 넣으면 4/6자리·attemptsLeft·LOCKED 기준으로 만든다. 클라이언트 쪽에 같은 내용 알림을 남겼다 — `docs/handoff/clients/all/2026-11-20-notice-handover-code-4.md`.
- mfc-app(PDA 라벨 인쇄)·MFC 백오피스·기본 백오피스: 받은 값을 그대로 쓰면 되고, "6자리" 문구만 고치면 된다(같은 알림).
