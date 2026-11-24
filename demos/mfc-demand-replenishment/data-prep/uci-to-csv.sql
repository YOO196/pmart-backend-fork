-- UCI Online Retail II(xlsx, 두 시트) → 데모 입력 CSV. DuckDB CLI 로 실행(명세 6장 "입력 포맷과 준비 절차").
--   duckdb -c ".read demos/mfc-demand-replenishment/data-prep/uci-to-csv.sql"
-- 변환은 열 이름·형식만 바꾼다. 정제·필터(취소·음수·비상품 코드·영국 밖)는 Java 데모가 하고 건수를 남긴다.
-- 고객 ID 는 데모가 쓰지 않으므로 변환 단계에서 비운다(열 이름은 형식 맞춤용으로 남김).
-- 주의(2026-11-24 PC 3): DuckDB v2.0.1 의 excel 확장 DLL 이 Windows 애플리케이션 제어 정책에 막혀 이 SQL 로는 변환하지 못했다.
--   그 PC 에서는 data-prep/xlsx_to_csv.py(Python 표준 라이브러리)를 썼다. 이 SQL 은 아직 실제 파일로 돌려 보지 않았다 —
--   처음 돌릴 때 결과 행 수(1,067,371)와 SHA-256 을 sources.md 의 변환본과 맞춰 본다.

INSTALL excel;
LOAD excel;

COPY (
  SELECT
    CAST(Invoice AS VARCHAR)                         AS "Invoice",
    CAST(StockCode AS VARCHAR)                       AS "StockCode",
    Description                                      AS "Description",
    Quantity                                         AS "Quantity",
    strftime(CAST(InvoiceDate AS TIMESTAMP), '%Y-%m-%d %H:%M:%S') AS "InvoiceDate",
    Price                                            AS "Price",
    NULL                                             AS "Customer ID",
    Country                                          AS "Country"
  FROM read_xlsx('data/raw/online_retail_II.xlsx', sheet = 'Year 2009-2010')
  UNION ALL
  SELECT
    CAST(Invoice AS VARCHAR),
    CAST(StockCode AS VARCHAR),
    Description,
    Quantity,
    strftime(CAST(InvoiceDate AS TIMESTAMP), '%Y-%m-%d %H:%M:%S'),
    Price,
    NULL,
    Country
  FROM read_xlsx('data/raw/online_retail_II.xlsx', sheet = 'Year 2010-2011')
) TO 'data/csv/uci-online-retail-ii.csv' (HEADER, DELIMITER ',');
