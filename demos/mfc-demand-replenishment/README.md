# MFC 수요예측·보충추천 데모 배치

명세: [`SPEC.md`](SPEC.md)(2026-11-24 통합 명세 사본). 수량 규칙의 정본은 `docs/business/SCM-business-logic.md` 4~9장이다.

**데모다.** PMart 운영 서비스와 따로 도는 독립 Gradle 빌드이고(루트 `settings.gradle.kts` 에 등록하지 않음), 운영 DB 에 쓰지 않으며 실제 공급 요청을 만들지 않는다. 운영 통합(demand-planning·replenishment 서비스, mfc-inbound 공급 필요 제시, MFC 백오피스 화면)은 후속 범위다.

## 무엇을 하나

| 단계 | 명세 | 내용 |
|---|---|---|
| `prepare` | F07 | 실행 기록(`demo_run`): run_id·앱 버전·입력 파일 SHA-256 |
| `sales` | F01·F02 | UCI 형식 CSV 정제(취소·음수·단가 0·비상품 코드·영국 밖 — 건수 기록) → 일별 상태(6장 다섯 단계 순서, `stale_gap_days`) → 주·월·계절·연 집계. 상태 비율을 배수 1.5·2·3, SKU 판매 빈도 군별로 |
| `forecast` | F03·F04 | SKU 별 예측: 이동평균(1순위) → Croston-SBA(대체). 둘 다 안 되면 예측 없음(0 으로 채우지 않음) |
| `recommend` | F05 | 고정 시나리오(`sample/scenario/`)로 `F·S·IP·N·W·B·K·Q`, 입고 예정 인정, 예측 소비, 시간대별 부족 경로, 여러 SKU 공유 상한 검사 |
| `backtest` | F06 | 순차 백테스트(MA7·14·28, 요일 Seasonal Naive, Croston-SBA × 이상치 처리 전후), STRICT·DAY_MATCHED 지표, 재고 시뮬레이션 |
| `report` | — | `build/demo-out/<run_id>/report.md` 와 결과 CSV |

코드 지도: `sales/`(정제·상태·집계), `forecast/`(모델·선택·이상치), `replenishment/`(계산 — SCM 수식), `backtest/`, `scenario/`(시나리오 CSV), `batch/`(Spring Batch 작업·저장·보고서), `sample/`(합성 샘플 생성기).

## 실행

루트(`pmart-backend/`)에서 루트 wrapper 로 실행한다(Windows 는 `.\gradlew.bat`).

```bash
./gradlew -p demos/mfc-demand-replenishment check        # 단위 + 통합(Testcontainers PostgreSQL, Docker 필요)
./gradlew -p demos/mfc-demand-replenishment generateSample   # sample/uci-synthetic.csv 다시 만들기(고정 시드)
./gradlew -p demos/mfc-demand-replenishment generatePmartSample   # PMart 503 SKU 입력 → build/pmart-sample/(약 27MB, 커밋 안 함)
```

### PMart 취급 상품으로 실행(기본 권장)

```bash
./gradlew -p demos/mfc-demand-replenishment generatePmartSample
./gradlew -p demos/mfc-demand-replenishment bootRun \
  --args="asOf=2026-11-24T00:00 salesCsv=build/pmart-sample/sales.csv scenarioDir=build/pmart-sample/scenario runLabel=pmart-1"
```

| 입력 | 원천 | 내용 |
|---|---|---|
| `sample/pmart/catalog.csv` | `deploy/lan/seed/dimensions-estimated-2026-11-16.csv` | 503 SKU: SKU·이름(`[시험] …`)·온도대(상온 189·냉동 177·냉장 137)·낱개 E 345/묶음 B 158·포장 형태 |
| `sample/pmart/seed-stock.csv` | `deploy/lan/seed/seed-template-2026-11-24.json`(1호점 고정본) | MFC-SEED-01 재고를 SKU·품질·작업 상태·소비기한별로 합친 것(1,293줄, 9,671개) |
| `build/pmart-sample/sales.csv` | `PmartSample`(고정 시드) | 명세 4장 일별 판매 형식. 2024-11-01 ~ 2026-11-23 × 503 SKU = 378,759줄, `ORDER_CONFIRMED`·`Asia/Seoul`. **합성 — 실제 판매가 아니다** |
| `build/pmart-sample/scenario/` | `PmartSample` | 기준 2026-11-24 00:00. H = 1호점 NORMAL·STORED 재고(기한 지난 LOT·적치 전 제외 → 9,543), 보호기간 안 소비기한 → EXPIRY. 약속·공급·상한은 가정 |

합성 판매의 가정(실제 값이 아니라 흐름 검증용):

| 항목 | 가정 |
|---|---|
| 기본 일 수요(낱개 SKU) | 포장 형태별 범위에서 SKU 마다 고정 — 농산물 6~14, 정육 트레이 3~9, 냉동 박스 1.5~6, 박스 1~5, 병 2~9, 캔 2~8, 병조림 0.8~3, 포대 0.4~2, 면 2~7, 과자 2~8, 트레이 2~6, 필기구 0.1~0.6, 화장지 0.5~2. 묶음(B) SKU 는 1/4 |
| 요일 | 월·화 0.9, 수 0.95, 목 1.0, 금·일 1.15, 토 1.25(센터는 매일 영업) |
| 계절 | 냉동 여름 1.35·겨울 0.85, 병·캔 여름 1.25·겨울 0.9, 농산물 가을 1.1 |
| 명절 | 설·추석 연휴 5일 전부터 정육·농산물·냉동 1.5(그 밖 1.1), 연휴 중 0.7 — 모델은 아직 명절 변수를 쓰지 않는다 |
| 흔들림 | 평균 × 감마(형태 4) 뒤 포아송 |
| 품절 | 평균 1 이상 SKU 에 하루 0.4% 확률로 1~3일 품절 — 관측 판매 = 수요 × 0~40%(STOCKOUT) |
| 수집 누락 | 2025-06-17·2026-04-10·2026-08-05 센터 전체 MISSING |
| 출시 | 4% SKU 가 2025-03 ~ 2026-07 사이 출시 — 그 전은 PRE_LAUNCH |
| 시나리오 | 운송 배수 m = 포장 형태별(낱개 4~24, 묶음 1), SS = 일 평균 올림, 보호기간 = 기준 → +3일(APPROX: 리드 1일 + 검토 2일), 추천분 준비 +1일. 약속 15% SKU·공급 20% SKU(출발 10%·승인 미이행 5%·확보 3%·ETA 불명 2%). CDC 공급가능량 m×10~39(5% SKU 는 m 미만 — 부족), MFC 수용 200(묶음 40), 운송 500, 온도대별 차량 공유 상한 냉동 1,200·냉장 1,500·상온 3,000 |

### 데모 DB

데모 결과와 Spring Batch 메타데이터는 데모 전용 DB `pmart_mfc_demo` 에 둔다. 운영·LAN DB 를 쓰지 않는다.

**A. 데모 전용 컨테이너(기본 — 포트 15433)**

```bash
docker run -d --name pmart-mfc-demo-pg \
  -e POSTGRES_USER=pmart_mfc_demo -e POSTGRES_PASSWORD=<로컬 비밀번호> -e POSTGRES_DB=pmart_mfc_demo \
  -p 15433:5432 postgres:17-alpine
```

**B. `deploy/local` PostgreSQL 재사용(포트 15432)** — `deploy/local/init-db.sql` 은 볼륨을 처음 만들 때만 돌기 때문에, 이미 쓰는 인스턴스에는 직접 만든다. 이 PC 처럼 15432 가 LAN 배포 DB(`pmart-lan-postgres-1`)이면 B 를 쓰지 않는다.

```bash
docker compose -f deploy/local/docker-compose.yml exec postgres psql -U pmart \
  -c "CREATE ROLE pmart_mfc_demo LOGIN PASSWORD '<로컬 비밀번호>'" \
  -c "CREATE DATABASE pmart_mfc_demo OWNER pmart_mfc_demo"
export PMART_DEMO_DB_URL=jdbc:postgresql://localhost:15432/pmart_mfc_demo
```

스키마는 실행할 때 만든다: Batch 메타 테이블은 `spring.batch.jdbc.initialize-schema=always`(Boot 4 의 `spring-boot-starter-batch-jdbc`), 데모 결과 테이블은 Flyway(`src/main/resources/db/migration`). 비밀번호는 환경 변수 `PMART_DEMO_DB_PASSWORD` 로만 준다(파일·yml 에 넣지 않는다).

### 배치 실행

```bash
export PMART_DEMO_DB_PASSWORD=<로컬 비밀번호>
./gradlew -p demos/mfc-demand-replenishment bootRun \
  --args="asOf=2011-06-01T00:00 salesCsv=sample/uci-synthetic.csv scenarioDir=sample/scenario runLabel=manual-1"
```

- 작업 매개변수(식별): `asOf`(기준시각, 센터 현지 시각 ISO — 일 단위 계산은 날짜 경계), `salesCsv`, `scenarioDir`(비우면 추천 단계 건너뜀), `runLabel`.
- 설정(식별 아님, `--demo.ma-window=14` 처럼): `demo.ma-window`(28), `demo.forecast-horizon-days`(14), `demo.stale-gap-multiplier`(2.0), `demo.lookback-days`(365), `demo.backtest`(true), `demo.backtest-step-days`(7), `demo.backtest-lead-days`(2), `demo.output-dir`(build/demo-out).
- 결과: DB `demo_*` 표 + `build/demo-out/<run_id>/report.md`·`recommendations.csv`·`forecasts.csv`·`aggregates.csv`·`backtest.csv`·`failures.csv`.

### 재실행·재시작

| 상황 | 동작 |
|---|---|
| 같은 매개변수로 다시(성공한 뒤) | Spring Batch 가 거절(`JobInstanceAlreadyCompleteException`). 다시 계산하려면 `runLabel` 을 바꾼다 |
| 새 `runLabel` | 새 run_id. 같은 기준시각·센터·SKU 의 이전 활성 추천은 비활성이 되고 새 추천이 활성(부분 유일 색인 `ux_demo_recommendation_active` — 활성은 하나) |
| 단계가 실패한 뒤 같은 매개변수로 다시 | 실패한 단계부터 다시 시작(같은 run_id). 각 단계는 그 run_id 의 자기 결과를 지우고 다시 쓴다 |
| 한 SKU 의 입력이 틀림(m ≤ 0, 모르는 단계 이름 등) | 그 SKU 만 `demo_failure` 에 남고 다른 SKU 는 처리된다 |

## 입력 형식

- 판매(머리줄로 형식을 고른다):
  - **명세 4장 일별 판매**(머리줄에 `availability_status`): `mfc_id, sku_id, business_date, units_sold, availability_status, sales_basis, source_timezone, unknown_reason`. 상태는 원천 그대로 쓰고, 파일에 없는 날은 MISSING. 한 파일에 센터·집계 기준·시간대가 섞이면 거절, 중복 일별 키·잘못된 줄은 오류로 센다.
  - UCI 거래 줄: UCI Online Retail II 열(`Invoice, StockCode, Description, Quantity, InvoiceDate, Price, Customer ID, Country`) UTF-8 CSV. `mfc_id = DEMO_UK_01`, `sales_basis = INVOICE_ORDER_CONFIRMED_PROXY`, `source_timezone = Europe/London`. 고객 ID 는 읽지 않는다.
- 실제 UCI 원본(xlsx)은 `data/raw/` 에 받아 CSV 로 바꾼다 — `data-prep/uci-to-csv.sql`(DuckDB, 명세 기본) 또는 DuckDB excel 확장을 쓸 수 없으면 `python -I data-prep/xlsx_to_csv.py data/raw/online_retail_II.xlsx data/csv/uci-online-retail-ii.csv`(표준 라이브러리만). 출처·체크섬은 [`sources.md`](sources.md).
- 시나리오(`sample/scenario/`): `policy.csv`(대상 SKU·정책·계산 스냅샷 — 필수), `caps.csv`, `shared_caps.csv`, `inventory.csv`(kind H·P·EXPIRY), `commitments.csv`, `supplies.csv`(같은 `supply_id` = 같은 공급 계보), `forecast.csv`(선택 — 없으면 배치 예측). 시각은 ISO(`2011-06-01T10:00`).

## 계산에서 정한 경계(데모 가정)

| 항목 | 정한 값 |
|---|---|
| 입고·준비(I·P) | 보호기간 끝 시각에 준비되면 포함(≤ protection_end) |
| 약속(C)·예측 구간 | 보호기간 끝 전(< protection_end). 기한 지난 약속은 C 에 남기고 사유 `OVERDUE_COMMITMENTS`(소비는 안 함) |
| 적격 상실 | 보호기간 끝 전에 적격을 잃는 H 안의 양은 IP 에서 뺀다. 경로에서는 그 시각에 뺀다 |
| 경로의 예측 수요 | 여러 날(시각 단위면 여러 시간) 구간은 하루(한 시간) 조각에 시간 비례로 나눠 조각 시작에 둔다 |
| 같은 시각 | 수요 먼저, 입고 나중, 추천분은 마지막 |
| 회복 시각 | 최초 부족 뒤 처음 잔량이 0 이상이 된 시각(기간 안에 없으면 비움) |
| 분위수 목표 | 보정된 누적 분위수(`calibrated_cumulative_quantile`)가 없으면 점예측 합계(`POINT_SUM_FALLBACK`, REFERENCE_ONLY) |
| 이동평균 | 최근 365일 안 유효 관측(NORMAL·ZERO_ASSUMED) window 개 평균, min(window, 7) 개 미만이면 이력 부족 |
| Seasonal Naive | 최근 4주 안 같은 요일 유효 관측. 없으면 그날 근거 없음(NaN) |
| Croston-SBA | α 0.1. 미확인일을 낀 간격은 p 추정에 쓰지 않음 |
| 이상치 | 검토일 전 양수 유효 관측(20개 이상)의 99번째 백분위로 학습 이력만 자름 |
| 재고 시뮬레이션 | 주기 = 백테스트 주기, 목표 = 보호기간(리드+주기) 예측 합, 리드타임 뒤 입고, 시작 재고 = 첫 목표 |

## 이 판에서 확인한 것(2026-11-24, PC 3)

- `./gradlew -p demos/mfc-demand-replenishment check`: 단위 38 + 통합 3 통과(통합은 Testcontainers PostgreSQL 17 — 합성 UCI 2, PMart 503 SKU 1).
- **PMart 503 SKU** `bootRun`(`asOf=2026-11-24T00:00`): COMPLETED 20초. 일별 판매 378,759줄(NORMAL 366,655·PRE_LAUNCH 8,146·STOCKOUT 2,471·MISSING 1,487), 예측 503(모두 MA28), 추천 503·실패 0(양수 추천 112, 실행 상한으로 미충족 2, 조기 부족 경보 73), 온도대별 차량 공유 상한 셋 모두 안(냉동 432 ≤ 1,200·냉장 555 ≤ 1,500·상온 516 ≤ 3,000). 매일 영업이라 STRICT 창이 평가된다(25,556창): MAE MA28 6.28 < Croston 6.52 < MA14 6.91 < MA7 8.06 < SNAIVE 8.53, WAPE 0.23~0.31(합성 수요라 실제 성능이 아니다).
- 합성 샘플 `bootRun`(데모 전용 컨테이너 15433): COMPLETED, 예측 7, 추천 12, 실패 2(의도한 사례). 같은 매개변수 재실행 거절, 새 runLabel 재실행 시 활성 추천 12·이전 실행 활성 0.
- **실제 UCI 원본** `bootRun`(`asOf=2011-06-01T00:00`, 주기 7일 백테스트): COMPLETED, 1분 26초. 읽은 줄 1,067,371 → 남긴 줄 955,994(영국 밖 86,041·취소 16,650·음수 3,457·단가 0 이하 2,722·비상품 2,507), SKU 4,714. 예측 MA28 3,857·Croston 1·예측 없음 424. 백테스트 SKU×검토일 107,445.
  - 정제 규칙을 프로파일링으로 고침: 처음의 "숫자 없는 코드 = 비상품" 추측이 실물 상품(DCGSSBOY·DCGSSGIRL 파티 백, PADS)을 빼고 있었다 → 명시 목록으로 바꾸고, 남긴 숫자 없는 코드는 보고서 `kept_nodigit_review.*` 로 띄운다.
  - STRICT 창 0(토요일·연말 센터 무판매 — 명세 규칙으로는 9일 창을 평가할 수 없음). DAY_MATCHED(coverage 0.80): MAE 는 MA14 30.4 < MA7·MA28 31.7 < SNAIVE 33.9 < Croston 61.1, 이상치 처리 뒤 모두 조금 낮아짐(MA14 29.2). WAPE 0.88~1.67 — 도매·잡화 데이터라 일 단위 오차가 크다(흐름 검증용, 모델 선택 근거로는 약함 — 명세 6장).
  - 시나리오 SKU 중 UCI 에 없는 코드(10001·10003·10005·99999)는 예측 없음 → INPUT_PENDING(0 으로 채우지 않음). 명세 예시 SKU(EX-*)는 합성 실행과 같은 값.

## 아직 안 한 것

- `data-prep/uci-to-csv.sql`(DuckDB) 실제 실행 — 이 PC 에서는 excel 확장이 막혀 Python 대체 도구로 변환했다.
- 보조 검증(FreshRetailNet 품절 사례, Mendeley 판매·재고 연동) — 명세 6장 구현 지시 4.
- 분위수 지표(pinball loss·커버리지)와 누적 분위수 보정.
- Chronos-2 ONNX PoC(명세 7장 4번) — 모델 내려받기·공식 파이프라인 기준 결과 필요.
- 운영 통합(서비스·이벤트·화면).
- Spotless 형식 검사(이 독립 빌드에는 convention plugin 을 붙이지 않았다).
