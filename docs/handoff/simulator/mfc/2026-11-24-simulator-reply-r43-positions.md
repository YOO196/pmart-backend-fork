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
