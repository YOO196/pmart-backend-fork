# 시뮬레이터 회신 — R-43 목포·세종·춘천 배치 확정, 위치 갱신 (2026-11-24)

보낸 영역: mfc (PC 3). 요청: 시뮬레이터 R-43 561fbc9(사용자 "확정"). 칸 코드·pickSequence 그대로, 위치만, 재고 이동 없음.

## 1. 사본
- 목포(새): `docs/simulator/mfc/layout-mokpo/export/`(15,528) — 지금까지 1호점 파일로 만들었다.
- 세종: `docs/simulator/mfc/layout-sejong/export/`(18,056).
- 춘천: `docs/simulator/mfc/layout-chuncheon/export/`(9,804) — #400 의 6f980e5 판을 대신한다(LAN 은 아직 4020670 판 — #400 실행 전 멈춤).

## 2. 대조(PC 3, 파일끼리)
| 센터 | 비교 기준 | 위치만 바뀐 칸 | 새·없어진 칸 | 종류·선반 순서 |
|---|---|---:|---|---|
| 목포 | 1호점 파일(목포를 만든 파일) | 3,536 = A-E 240·A-F 1,440·A-G 1,350·A-H 500·DSP-A-01·02·PAK-C-01·PAK-F-01·RCV-C-01·RCV-F-01 | 0 | 같음 |
| 세종 | R-41 확정 0198336 사본 | 2 = DSP-A-01·02 | 0 | 같음 |
| 춘천 | LAN 에 만든 4020670 파일 | 4,530(A-C 810·A-D 900·A-E 624·A-F 900·A-G 918·A-H 325·CRT-A 40·DSP-A 2·PAK-A 6·PAK-C·F 2·QRT-A·C·F 3) | 없어짐 PAK-A-07·08 | 같음 |
- 보충재고·대형재고 사본: 세종·춘천 바뀐 것 없음. 숫자가 요청과 모두 같다.

## 3. 실행(사용자 확인 뒤 — PR용 백엔드 세션)
`mfc_layout.py r34` 는 파일 줄만 본다(파일에 없는 칸은 손대지 않음 — 목포 RTN-A-01 그대로). 각 센터 `r34plan` 으로 숫자를 본 뒤 `r34`.
1. 목포: `PMART_MFC=MFC-MOKPO-01 PMART_SUP_ACCOUNT=claude-mfc-sup-mk PMART_POSITIONS=docs/simulator/mfc/layout-mokpo/export/location-positions.json python -I deploy/lan/seed/mfc_layout.py r34plan` → 바꿀 칸 3,536·서버에 없음 0 → `r34`.
2. 세종: `PMART_MFC=MFC-SEJ-01 PMART_SUP_ACCOUNT=claude-mfc-sup-sj PMART_POSITIONS=docs/simulator/mfc/layout-sejong/export/location-positions.json …mfc_layout.py r34plan` → 바꿀 칸 2 → `r34`.
3. 춘천:
   - `PMART_TARGET_MFC=MFC-CHC-01 PMART_TARGET_SUP=claude-mfc-sup-cc PMART_POSITIONS=docs/simulator/mfc/layout-chuncheon/export/location-positions.json python -I deploy/lan/seed/mfc_clone_center.py prune` → 닫을 칸 2(PAK-A-07·08) → `--apply`.
   - `PMART_MFC=MFC-CHC-01 PMART_SUP_ACCOUNT=claude-mfc-sup-cc PMART_POSITIONS=(같음) …mfc_layout.py r34plan` → 바꿀 칸 4,530 → `r34`.
   - 그다음 #401 병합 → `wide-move`(E 상품 셋 → A-H3-02 넓은 칸, 사용자 "옮겨") → `--apply`.
- 1호점·부산은 바뀌지 않는다.

## 4. 실행 결과(PR용 백엔드, 사용자 확인 뒤)
- 목포 r34 바꿈 3,536 · 세종 2 · 춘천 prune 닫음 2(PAK-A-07·08) + r34 바꿈 4,530. 모두 실패 0·서버에 없음 0·옮길 재고 0. 춘천 보류 요청은 실행 직후에 닿았다(r34 는 위치만이라 새 판으로 다시 덮으면 된다).

## 5. 춘천 다시 확정(시뮬레이터 500612b, 사용자 "확정" — "냉장냉동구역에 입고팔레트랑 그 옆에 적치랙이 너무 가까운데")
- 사본 `docs/simulator/mfc/layout-chuncheon/export/location-positions.json` 을 500612b 판으로(보충재고·대형재고 사본 같음).
- 대조: 처음 LAN(4020670) 대비 8,862(요청과 같음 — 상온 4,477·C-B 2,800·F-A 1,530·CRT-A 40·DSP 2·PAK-A 6·PAK-C/F 2·QRT 3·RCV-C/F 2), PAK-A-07·08 없음, 새 칸 0, 순서 같음.
- **지금 LAN(561fbc9 판 r34 뒤) 대비 4,332** = 냉장 C-B 2,800·냉동 F-A 1,530·RCV-C-01·RCV-F-01. 그래서 r34plan 기대값은 4,332.
- 실행: `PMART_MFC=MFC-CHC-01 PMART_SUP_ACCOUNT=claude-mfc-sup-cc PMART_POSITIONS=docs/simulator/mfc/layout-chuncheon/export/location-positions.json python -I deploy/lan/seed/mfc_layout.py r34plan` → 바꿀 칸 4,332·서버에 없음 0 → `r34`. prune 은 이미 됨(다시 돌려도 닫을 칸 0). 그다음 #401 → `wide-move --apply`.

