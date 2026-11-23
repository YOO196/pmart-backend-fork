# 시뮬레이터 회신 — 5호점 부산남구 MFC-BNG-01 칸 계획 (2026-11-24, R-44)

보낸 영역: mfc (PC 3). 요청: PC 1 `2026-11-24-request-busan-namgu-mfc.md`(사용자 "mfc 하나 더 만들자"), 시뮬레이터 R-44 15f652d(사용자 "확정"). 세종·춘천과 같은 틀.

## 1. 사본
`docs/simulator/mfc/layout-busan-namgu/export/`: `location-positions.json` 13,540(선반 12,706·보충재고 765·대형재고 10·보조 59), `reserve-rack-cells.json` 765, `bulk-pallet-cells.json` 10.

## 2. 계획(PC 3, LAN 읽기 — 남구 관리자 비밀번호가 없어 대상은 빈 센터로 봄)
- `layout`: 만들 칸 **13,540**, 못 만드는 칸 0(1호점 같은 코드 10,951·같은 모양 선반 2,589), 넓은 칸 합침으로 닫을 칸 **34**(남구에 코드가 있는 1호점 합침 31).
- `wide`(시뮬레이터 제안 자리 그대로 — `PMART_WIDE_RACKS="A-H1-02=A-H2-01,A-H1-03=A-H2-02,A-H1-04=A-H2-03,A-H1-05=A-H2-04,A-H1-06=A-H3-01,A-H2-07=A-H3-02"`): 1호점 H 넓은 칸 랙 6대를 칸 구성 그대로 옮긴다 → 넓은 칸 **28**, 닫을 칸 **66**(A-H2-01 15·H2-02 12·H2-03 12·H2-04 10·H3-01 3·H3-02 14). 자리 경고 0(대상 랙에 다른 합침 없음).
- 합: 합침 59·닫힘 100 — 1호점과 같은 수(시뮬레이터 숫자와 같음).

## 3. 실행(PC 1, 사용자 확인 뒤 — 춘천과 같은 순서)
공통 `PMART_TARGET_MFC=MFC-BNG-01 PMART_TARGET_SUP=claude-mfc-sup-bn PMART_POSITIONS=docs/simulator/mfc/layout-busan-namgu/export/location-positions.json`.
1. `PMART_RESERVE_RACKS=docs/simulator/mfc/layout-busan-namgu/export/reserve-rack-cells.json PMART_BULK_PALLETS=docs/simulator/mfc/layout-busan-namgu/export/bulk-pallet-cells.json python -I deploy/lan/seed/mfc_clone_center.py layout` → 13,540·닫음 34 → `--apply`.
2. `PMART_WIDE_RACKS="A-H1-02=A-H2-01,A-H1-03=A-H2-02,A-H1-04=A-H2-03,A-H1-05=A-H2-04,A-H1-06=A-H3-01,A-H2-07=A-H3-02" python -I deploy/lan/seed/mfc_clone_center.py wide` → 넓은 칸 28·닫음 66 → `--apply`(재고 전 — 칸만).
3. `PMART_MFC=MFC-BNG-01 PMART_SUP_ACCOUNT=claude-mfc-sup-bn python -I deploy/lan/seed/mfc_layout.py r40plan` → `r40`.
4. `PMART_LOT_PREFIX=BNG python -I deploy/lan/seed/mfc_clone_center.py stock --placement mixed` → `--apply --max-loads 1` → `--apply` → 남으면 `stock-retry --apply`. 남구 가상 작업자는 끈다.
5. 2 와 같은 `wide --apply` 다시 — 수령 칸에 남은 넓은 칸 상품을 그 칸으로.
6. `PMART_WIDE_RACKS="A-H1-02=A-H2-01,A-H1-03=A-H2-02,A-H1-04=A-H2-03,A-H1-05=A-H2-04,A-H1-06=A-H3-01,A-H2-07=A-H3-02" python -I deploy/lan/seed/mfc_clone_center.py wide-move` — 넓은 칸 상품이 서버 추천으로 다른 칸에 들어갔으면 그 넓은 칸으로(춘천 사용자 "옮겨"와 같은 규칙) → `--apply`.
- 주의: 운영이 시작된 뒤에는 `stock` 을 다시 돌리지 않는다.

## 4. 다시 확정(시뮬레이터 cc47d87, 사용자 "확정" — 입고창고 보충재고 랙 줄마다 5랙 3쌍 + 남는 1랙 한 줄)
- 부산남구 사본을 cc47d87 판으로. 15f652d 대비 칸 코드·순서·수 그대로, **위치만 467칸**(A-R1-01~31 465·QRT-F-01·QRT-C-01). `reserve-rack-cells.json` 도 같은 465칸 위치. 칸 수·치수가 같아 §2 계획(13,540·닫음 34·넓은 칸 28·닫음 66)은 그대로, 실행 §3 그대로.
- **춘천(이미 칸 있음)**: 같은 커밋에서 QRT-F-01·QRT-C-01 두 칸만 바뀜 — zM 24.7 → 15.0, **xM 도 +0.15**(8.45 → 8.6, 0.65 → 0.8 — 요청 글은 "xM 그대로"였지만 파일 값을 따름). 사본 `docs/simulator/mfc/layout-chuncheon/export/location-positions.json` 갱신.
  - 실행(사용자 확인 뒤): `PMART_MFC=MFC-CHC-01 PMART_SUP_ACCOUNT=claude-mfc-sup-cc PMART_POSITIONS=docs/simulator/mfc/layout-chuncheon/export/location-positions.json python -I deploy/lan/seed/mfc_layout.py r34plan` → 바꿀 칸 2 → `r34`.

