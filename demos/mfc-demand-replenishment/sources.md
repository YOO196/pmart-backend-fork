# 데이터 출처 기록 (명세 6장 구현 지시 5)

원본·변환본 파일은 저장소에 넣지 않는다(`data/` 는 `.gitignore`). 내려받을 때마다 이 표를 채운다. 라이선스·저자·DOI 는 **내려받기 전에 원 게시처에서 다시 확인**하고 확인한 페이지 URL·날짜·사람을 적는다.

| 데이터 | 원본 URL | 버전 | 라이선스(확인 URL·날짜·사람) | 저자·DOI | 내려받은 날짜 | 원본 SHA-256 | 변환 도구·버전 | 변환 명령 | 원본 행 수 | 변환본 SHA-256 | 비고 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| UCI Online Retail II | https://archive.ics.uci.edu/static/public/502/online%2Bretail%2Bii.zip | ZIP 안 `online_retail_II.xlsx`(파일 시각 2023-05-22) | CC BY 4.0(명세 작성자 2026-11-24 확인. PC 3 는 게시처 페이지를 따로 열어 확인하지 않음 — 공유 전 재확인) | Daqing Chen, https://doi.org/10.24432/C5CG6D | 2026-11-24(PC 3) | ZIP `572e36277c2390fbfde10664750731e0a86f55e33470d91919085f0408e67bfb`, xlsx `bcbe73b35f5b7babf197fb0cb983a11f5d9ff929078d4aa53d171b1f2df2e980` | Python 3.14.5 표준 라이브러리(`data-prep/xlsx_to_csv.py`) — DuckDB v2.0.1 excel 확장이 Windows 애플리케이션 제어 정책에 막힘 | `python -I data-prep/xlsx_to_csv.py data/raw/online_retail_II.xlsx data/csv/uci-online-retail-ii.csv` | 1,067,371(Year 2009-2010 525,461 + Year 2010-2011 541,910) | `d6430c8d5bf7985a4478dfab20d6c3f605cb4846c1f15aa6ec833f385df2be67` | 기본 데모. 정제 뒤 955,994줄·SKU 4,714 |
| Dingdong FreshRetailNet-50K | https://huggingface.co/datasets/Dingdong-Inc/FreshRetailNet-50K | | CC BY 4.0(명세 작성자 2026-11-24 확인 — 받을 때 재확인) | Dingdong-Inc | (아직 안 받음) | | | | | | 보조 검증 — 이 판에 미구현 |
| Retail Transactions and Stocks Data v1 | https://data.mendeley.com/datasets/27x8mjm8k4/1 | v1 | CC BY 4.0(명세 작성자 2026-11-24 확인 — 받을 때 재확인) | Jimmy Smith, https://doi.org/10.17632/27x8mjm8k4.1 | (아직 안 받음) | | | | | | 보조 검증 — 이 판에 미구현 |

## 저장소에 있는 합성 샘플

| 파일 | 만든 방법 | 비고 |
|---|---|---|
| `sample/uci-synthetic.csv` | `./gradlew -p demos/mfc-demand-replenishment generateSample`(`SyntheticUciSample`, 고정 시드 20261124) | 실제 거래·고객 아님. UCI 와 같은 열. 고객 ID 비움 |
| `sample/pmart/catalog.csv` | `deploy/lan/seed/dimensions-estimated-2026-11-16.csv` 의 sku·name·storage·rule 열 + 단위(SKU 첫 글자 E/B) | PMart LAN 시험 상품 503(이름 `[시험] …`) |
| `sample/pmart/seed-stock.csv` | `deploy/lan/seed/seed-template-2026-11-24.json` 의 stock 을 (sku, qualityState, workState, expiryDate) 로 합침 | 1호점 시험 재고 9,671 |
| `build/pmart-sample/`(커밋 안 함) | `./gradlew -p demos/mfc-demand-replenishment generatePmartSample`(`PmartSample`, 고정 시드) | 합성 판매 378,759줄·시나리오. 실제 판매 아님 |
| `sample/scenario/*.csv` | 손으로 작성 | 명세 3장 예시 표·7장 회귀 사례·실패 사례. `data_mode = simulated_inventory` |
