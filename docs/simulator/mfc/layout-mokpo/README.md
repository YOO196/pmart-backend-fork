# 목포 MFC-MOKPO-01 칸 위치 (2026-11-24, 시뮬레이터 R-43 561fbc9)

- 목포는 11-22 R-39 에서 1호점 파일(`../layout/`)로 만들었다. R-43(사용자 "확정", "상온F랙을 제일 남쪽으로 빼고 나머지를 뒤로")부터 목포 정본은 `export/`(시뮬레이터 `docs/layout-export/mokpo/` 사본). 칸 코드·피킹 순서는 1호점과 같고 위치만 다르다(3,536칸).
- 반영: `PMART_MFC=MFC-MOKPO-01 PMART_SUP_ACCOUNT=claude-mfc-sup-mk PMART_POSITIONS=docs/simulator/mfc/layout-mokpo/export/location-positions.json python -I deploy/lan/seed/mfc_layout.py r34plan` → `r34`. 서버에만 있는 RTN-A-01 은 파일에 없어 그대로 둔다.
