# 회신 — R-41 3호점 세종 MFC(MFC-SEJ-01) 칸·재고 스크립트 (2026-11-23, PC 3 mfc)

보낸 영역: mfc(PC 3). 요청: `docs/handoff/simulator/mfc/2026-11-23-request-sejong-mfc.md` §5-5·§5-7(PC 1), 시뮬레이터 R-41. 사용자 "이 평면도대로 세종 MFC 1개 더 만들어줘", "sku 도 그대로 넣어".
이 PC 의 원칙(사용자 11-22): PC 3 는 스크립트 PR 만, LAN 실행은 PC 1(사용자 확인 뒤).

## 1. 칸 — `deploy/lan/seed/mfc_clone_center.py layout`
- 입력: 시뮬레이터 `docs/layout-export/sejong/` 의 위치 파일(1호점 `location-positions.json` 과 같은 모양 — locationCode·kind·xM·zM)과 보충재고 랙 사본(`reserve-rack-cells.json` 모양, dimMm·temperatureZone). 환경 변수 `PMART_POSITIONS`·`PMART_RESERVE_RACKS`(·`PMART_BULK_PALLETS`)로 준다.
- 규칙(1호점 그대로, 랙·칸 수만 세종):
  - 같은 코드가 1호점에 있으면 그 칸 속성 — 구역·온도대·보충재고·치수. 1호점의 넓은 칸 합침(치수)과 그로 닫힌 칸(CLOSED)도 따라간다.
  - 없으면 같은 모양의 1호점 칸을 본뜬다. 선반 = 온도대·구역 글자·단·칸이 같은 칸의 가장 흔한 치수(예 C-B8-01-1-01 → 냉장 161×450×420), 보조 칸 = 접두어·온도대(PAK-A-09 → 패킹, CRT-A-41 → 카트).
  - 보충재고 랙 = 사본의 치수, STORAGE·reserveStock.
  - pickSequence = 위치 파일의 선반 순서(1호점이 도면 칸 순서였던 것과 같음). 위치 = 파일 좌표.
  - 고회전은 모두 끈다 → F 구역은 칸 등록 뒤 `mfc_layout.py r40`(R-40, `PMART_MFC=MFC-SEJ-01 PMART_SUP_ACCOUNT=claude-mfc-sup-sj`).
  - 본뜰 1호점 칸이 없는 칸(예: 보충재고 사본에 없는 보충재고 칸)이 있으면 멈추고 목록을 낸다.
- 검증(LAN 읽기, 계획만): 1호점 위치 파일을 넣으면 15,528칸이 모두 "원본 같은 코드", 닫을 칸 100(넓은 칸 합침). 1호점 파일에 랙을 더한 시험 파일(냉장 C-B8 한 랙·상온 A-C3-29 한 랙·PAK-A-09·CRT-A-41)은 더한 칸 89·보조 2개를 본떠 만들고, 사본에 없는 보충재고 칸 1개에서 멈췄다.
- 세종 위치 파일이 오면 이 단계로 계획을 다시 돌린다. 예상 칸 수 = 요청서 §3 17,985 + 보조 칸(입고·패킹·인계·격리·카트).

## 2. 재고 — `stock --placement mixed`(사용자 "sku 도 그대로")
- 1호점과 같은 503 SKU·1호점 수량(9,671 — 11-23 LAN, LOT 1,298·소비기한·제조일 같게)을 정식 입고 흐름(시험 입고 예정 → 수령 → 품질검사 → 적치)으로 넣는다. 목포 `stock` 과 같은 틀.
- 칸 배치(세종은 칸 배치가 1호점과 다름): **1호점 칸 코드가 세종에도 있으면 그 칸**(코드 규칙이 같아 대부분 겹친다), 없으면 그 몫은 **서버 나눠 넣기**(suggestedPlacements — 그 상품 선반 남은 용량 → 기타 칸 → 보충재고 칸). 추천으로도 못 넣은 양은 수령 칸에 남고 `stock-retry` 로 다시.
- LOT 표기 앞부분 `PMART_LOT_PREFIX=SEJ`.
- 계획(LAN 읽기): 묶음 14(상온 3,676·냉장 2,564·냉동 3,431), 품질검사 1,298번·적치 1,324번. 세종 관리자 비밀번호가 이 PC 에 없어 "1호점 칸이 세종에 있는 수량"은 칸 등록 뒤에 센다.
- 실행하는 동안 세종 가상 작업자는 끈다(담당이 붙으면 NOT_ASSIGNEE 로 멈춤).
- 주의: 운영이 시작된 센터에는 `stock` 을 다시 돌리지 않는다 — SKU·소비기한별로 모자란 만큼을 채우므로 팔린 양을 다시 넣는다(목포 11-23: 보유 9,738, 다시 돌리면 14 LOT 을 더 넣으려 함).

## 3. 실행(PC 1, 사용자 확인 뒤)
1. 세종 관리자 `claude-mfc-sup-sj` 권한 승인(요청서 §5-3 대기).
2. `PMART_TARGET_MFC=MFC-SEJ-01 PMART_TARGET_SUP=claude-mfc-sup-sj PMART_POSITIONS=<세종 위치 파일> PMART_RESERVE_RACKS=<세종 보충재고 사본> python -I deploy/lan/seed/mfc_clone_center.py layout` → `--apply`.
3. `PMART_MFC=MFC-SEJ-01 PMART_SUP_ACCOUNT=claude-mfc-sup-sj python -I deploy/lan/seed/mfc_layout.py r40plan` → `r40`.
4. `PMART_TARGET_MFC=MFC-SEJ-01 PMART_TARGET_SUP=claude-mfc-sup-sj PMART_LOT_PREFIX=SEJ python -I deploy/lan/seed/mfc_clone_center.py stock --placement mixed` → `--apply --max-loads 1` → `--apply` → 남으면 `stock-retry --apply`.
