# 시뮬레이터 회신 — 4호점 춘천 MFC-CHC-01 칸 만들기 준비 (2026-11-24)

> **바뀜(같은 날, 사용자 "랙마다 칸 수는 세종이랑 동일하게")**: 랙당 칸이 1호점·세종과 같아졌다(요청서 §3). 그래서 세종 `layout` **그대로 된다** — 같은 코드면 1호점 칸 속성, 없는 랙 번호는 같은 모양 칸(§1 의 걱정은 없어짐). 시뮬레이터는 **`dimMm` 을 넣지 않아도 된다**(세종과 같은 파일 형식). §2 의 보호 장치는 남긴다 — 모양이 같은 랙에서는 동작이 전과 같고(세종 본문 같음 확인), 나중에 모양이 다른 랙이 오면 틀린 치수로 만들지 않고 멈춘다. 아래 §1·§3 은 처음 표(랙당 칸 40·35·72…) 기준 기록.

보낸 영역: mfc (PC 3). 요청: PC 1 "mfc 하나 더 만들자"(사용자 2026-11-24) — `2026-11-24-request-chuncheon-mfc.md`(PC 1). 세종 R-41 과 같은 틀.

## 1. 세종 `layout` 그대로는 안 된다
- `mfc_clone_center.py layout` 은 칸 치수를 1호점에서 가져온다(같은 코드면 그 칸, 없으면 온도대·구역 글자·단·칸이 같은 칸).
- 1호점 랙 모양(LAN 읽기, 열린 칸): 냉동 F-A 5단×6칸, 냉장 C-B 5×7, 상온 A-C·A-G 6×9, A-D·A-F 6×10, A-E 6×8, A-H 5×5(6단은 닫힌 칸).
- 춘천은 랙당 칸이 다르다(냉장 40·냉동 35·상온 C·D·F 72·E 60·G 66·H 30). 그래서
  - 1호점 범위를 넘는 단·칸 번호(예 C-B 칸 08)는 본뜰 칸이 없어 멈춘다.
  - **더 나쁜 것**: 범위 안 번호(예 A-C1-01-1-01)는 1호점 같은 코드 치수(폭 125 mm — 9칸 기준)를 그대로 받아, 12칸 랙이면 칸 폭이 실제보다 넓게 등록된다(용량 계산이 틀림).

## 2. 고친 것(PR)
- 위치 파일 선반 줄에 **`dimMm: {w, d, h}`**(mm)가 있으면 그 치수가 먼저. 구역·온도대는 같은 온도대·구역 글자의 1호점 선반. 이때 1호점 넓은 칸 합침·닫힘은 따라 하지 않는다(파일이 정본).
- 대상 랙 모양(단마다 칸 수)이 1호점 그 구역 글자와 다른 랙은 `dimMm` 이 없으면 **못 만드는 칸으로 멈춘다**(틀린 치수로 만들지 않음). 멈출 때 랙 코드를 찍는다.
- 본뜨기는 1호점의 열린 칸만(닫힌 H 6단 281×450×350 을 6단 랙에 주지 않게).
- 확인: 세종 파일로 본문 18,056·닫을 칸 86 이 고치기 전과 **완전히 같음**. 가상 춘천 랙(A-C 6×12): dimMm 없음 → 72칸 멈춤, 있음 → 그 치수로 72칸.

## 3. 시뮬레이터에 부탁
- `docs/layout-export/chuncheon/location-positions.json` 의 **선반 줄마다 `dimMm`**(그 칸 폭·깊이·높이, mm)을 넣어 주세요. 1호점과 모양이 같은 랙도 넣어 주면 그 값이 정본이 된다.
- 보충재고 랙·대형재고는 세종처럼 `reserve-rack-cells.json`·`bulk-pallet-cells.json`(dimMm 포함).
- 넓은 칸(1호점 59개 합침)은 춘천 랙 모양이 달라 그대로 옮길 수 없다. 큰 상품 자리가 필요하면 파일에 그 칸을 넓은 치수로 주고 합쳐진 칸은 빼 주세요. 없으면 재고 넣은 뒤 수령 칸에 남는 상품을 보고 정한다(세종 `wide` 처럼).

## 4. 그다음(파일이 오면 PC 3 → PC 1)
- PC 3: 사본을 `docs/simulator/mfc/layout-chuncheon/export/` 에 두고 `layout` 계획(못 만드는 칸 0 확인).
- PC 1(사용자 확인 뒤): `PMART_TARGET_MFC=MFC-CHC-01 PMART_TARGET_SUP=claude-mfc-sup-cc PMART_POSITIONS=… PMART_RESERVE_RACKS=… PMART_BULK_PALLETS=… python -I deploy/lan/seed/mfc_clone_center.py layout` → `--apply`, `mfc_layout.py r40`(PMART_MFC=MFC-CHC-01 PMART_SUP_ACCOUNT=claude-mfc-sup-cc), `stock --placement mixed`(PMART_LOT_PREFIX=CHC) → `stock-retry`.
- 재고: 1호점 칸 코드가 춘천에도 있으면 그 칸으로 적치하는데 칸 크기가 다를 수 있다 — 넘치면 서버가 거절하고 수령 칸에 남아 `stock-retry`(서버 추천)로 간다.

## 5. 위치 파일 계획(시뮬레이터 R-42 4020670)
- 사본: `docs/simulator/mfc/layout-chuncheon/export/` `location-positions.json` 9,806(선반 9,287·보충재고 450·대형재고 10·보조 59)·`reserve-rack-cells.json` 450·`bulk-pallet-cells.json` 10.
- `layout` 계획: 만들 칸 **9,806**, 못 만드는 칸 0(1호점 같은 코드 9,596·같은 모양 선반 210), 닫을 칸 **83**(넓은 칸 합침 — 시뮬레이터 숫자와 같음). 춘천 관리자 비밀번호가 PC 3 에 없어 대상은 빈 센터로 본 계획.
- **넓은 칸 4개(춘천에 코드 없는 1호점 칸)**: 처음 `wide` 는 1호점 A-H1-06 의 넓은 칸 3개를 랙 셋에 흩고 랙마다 3칸씩 닫았다(1호점 기록은 랙 단위 닫힌 칸 목록). 고침 — 1호점 랙 하나를 대상 랙 하나로 통째로, 자리는 `PMART_WIDE_RACKS` 로 지정. 시뮬레이터 제안 그대로:
  - A-H1-06 → **A-H3-02**: 1-01·1-03·2-01 = 450×450×420(E-00000066·071·144), 닫음 1-02·1-04·2-02.
  - A-H2-07 → **A-H3-03**: 1-01 = 1125×450×1260(B-00000006), 닫음 14(1-02~3-05).
- 다시 돌려도 안전: 이미 넓혔으면 적치만, 상품이 이미 수령 칸 밖에 있으면 그 랙은 건너뜀(세종에 다시 돌림 → "A-H2-07 이미 적치됨 — 건너뜀" 확인. 고치기 전에는 재고가 든 A-H4-05 를 피해 A-H4-04 를 새로 잡았다).

## 6. 실행(PC 1, 사용자 확인 뒤 — 순서대로)
공통 `E=docs/simulator/mfc/layout-chuncheon/export`, `PMART_TARGET_MFC=MFC-CHC-01 PMART_TARGET_SUP=claude-mfc-sup-cc`.
1. `PMART_POSITIONS=$E/location-positions.json PMART_RESERVE_RACKS=$E/reserve-rack-cells.json PMART_BULK_PALLETS=$E/bulk-pallet-cells.json python -I deploy/lan/seed/mfc_clone_center.py layout` → 만들 칸 9,806·닫을 칸 83 → `--apply`.
2. `PMART_POSITIONS=$E/location-positions.json PMART_WIDE_RACKS="A-H1-06=A-H3-02,A-H2-07=A-H3-03" python -I deploy/lan/seed/mfc_clone_center.py wide` → 위 4칸·닫을 칸 17 → `--apply`(재고 전이라 적치 0 — 칸만. 재고가 그 랙에 먼저 들어가지 않게).
3. `PMART_MFC=MFC-CHC-01 PMART_SUP_ACCOUNT=claude-mfc-sup-cc python -I deploy/lan/seed/mfc_layout.py r40plan` → `r40`.
4. `PMART_LOT_PREFIX=CHC python -I deploy/lan/seed/mfc_clone_center.py stock --placement mixed` → `--apply --max-loads 1` → `--apply` → 남으면 `stock-retry --apply`. 춘천 가상 작업자는 끈다.
5. 2 와 같은 `wide --apply` 다시 — 넓은 칸 4개 상품의 수령 칸 적치 작업을 그 칸으로(이미 넓힘 → 적치만).
