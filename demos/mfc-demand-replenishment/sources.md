# 데이터 출처 기록 (명세 6장 구현 지시 5)

원본·변환본 파일은 저장소에 넣지 않는다(`data/` 는 `.gitignore`). 내려받을 때마다 이 표를 채운다. 라이선스·저자·DOI 는 **내려받기 전에 원 게시처에서 다시 확인**하고 확인한 페이지 URL·날짜·사람을 적는다.

| 데이터 | 원본 URL | 버전 | 라이선스(확인 URL·날짜·사람) | 저자·DOI | 내려받은 날짜 | 원본 SHA-256 | 변환 도구·버전 | 변환 명령 | 원본 행 수 | 변환본 SHA-256 | 비고 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| UCI Online Retail II | https://archive.ics.uci.edu/dataset/502/online+retail+ii | | CC BY 4.0(명세 작성자 2026-11-24 확인 — 받을 때 재확인) | Daqing Chen, https://doi.org/10.24432/C5CG6D | (아직 안 받음) | | DuckDB | `data-prep/uci-to-csv.sql` | | | 기본 데모 |
| Dingdong FreshRetailNet-50K | https://huggingface.co/datasets/Dingdong-Inc/FreshRetailNet-50K | | CC BY 4.0(명세 작성자 2026-11-24 확인 — 받을 때 재확인) | Dingdong-Inc | (아직 안 받음) | | | | | | 보조 검증 — 이 판에 미구현 |
| Retail Transactions and Stocks Data v1 | https://data.mendeley.com/datasets/27x8mjm8k4/1 | v1 | CC BY 4.0(명세 작성자 2026-11-24 확인 — 받을 때 재확인) | Jimmy Smith, https://doi.org/10.17632/27x8mjm8k4.1 | (아직 안 받음) | | | | | | 보조 검증 — 이 판에 미구현 |

## 저장소에 있는 합성 샘플

| 파일 | 만든 방법 | 비고 |
|---|---|---|
| `sample/uci-synthetic.csv` | `./gradlew -p demos/mfc-demand-replenishment generateSample`(`SyntheticUciSample`, 고정 시드 20261124) | 실제 거래·고객 아님. UCI 와 같은 열. 고객 ID 비움 |
| `sample/scenario/*.csv` | 손으로 작성 | 명세 3장 예시 표·7장 회귀 사례·실패 사례. `data_mode = simulated_inventory` |
