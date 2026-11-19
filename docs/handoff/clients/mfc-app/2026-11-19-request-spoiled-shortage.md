# 요청: 피킹 결품 사유에 "변질" 단추 (SPOILED)

- 보낸 영역: mfc (PC 3 백엔드)
- 작성: pmart-backend, 2026-11-19
- 대상: pmart-clients `mfc-app`(PDA 피킹 결품 보고), `web/mfc-backoffice`(결품 목록·지표 이름표)
- 서버: mfc-fulfillment **0.22.0**·mfc-inventory 0.32.0(PR 브랜치 `mfc/spoilage-shortage` — LAN 은 PC 1 배포 뒤)

## 왜
품질 사고(MB-2) 사용자 결정 2026-11-19 "결품 보고 중 **파손·변질만** 사고". 지금 결품 사유에는 SHORT(못 찾음)·DAMAGED(파손)·BLOCKED(보류)뿐이라 변질을 보고할 길이 없어, 변질 사고는 센터 관리자 직접 등록으로만 열렸다.

## 서버
- PICK 실적 줄 `shortage.reason` 에 **`SPOILED`**(변질 — 상함·부패·냉장 이탈 흔적 등, 실물은 있으나 팔 수 없음).
- 재고: 파손처럼 그 칸·LOT 을 **격리**하고 같은 SKU 적격 LOT 으로 재확보한다(못 하면 결품).
- 품질: **변질 사고(SPOILAGE)가 자동으로** 열린다(같은 센터·LOT 의 열린 자동 변질 사고에 묶음 — 파손 사고와는 따로).
- 지표 `GET /metrics/picking-lines` 의 `shortLinesByReason.SPOILED`(선택 칸), 결품 목록 `GET /shortages?reason=SPOILED`.

## 화면에 바랄 것
1. mfc-app 피킹 결품 보고: 사유 단추에 **"변질"**(파손 옆). 파손과 같이 사진·메모 칸.
2. mfc-backoffice: 결품 목록·지표 이름표에 SPOILED = "변질".
