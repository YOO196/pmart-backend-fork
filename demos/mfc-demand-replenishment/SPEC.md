# PMart MFC 수요예측·보충추천 구현 명세

## Claude 작업 요청

기존 저장소의 서비스 구조, 계약 및 SCM 정본을 확인한 뒤 **Java 21 데모 배치**를 구현한다. 일별 판매 집계, 보호기간 수요예측, CDC→MFC 보충추천, 순차 검증을 재현 가능한 형태로 제공한다. 데모 데이터를 운영 DB에 쓰거나 실제 공급 요청을 생성하지 않는다. PMart 운영 통합은 후속 범위다. 수량 규칙은 `pmart-backend/docs/business/SCM-business-logic.md` 4~9장을 우선한다. 정본에 접근할 수 없거나 본 문서와 충돌하면 영향을 받는 규칙을 명시하고 데모 가정과 운영 결정을 구분한다.

## 0. 범위와 전제

| 항목 | 내용 |
|---|---|
| 두 단계 | **(가) 데모 배치**: 공개 데이터와 가상 재고로 집계·예측·추천 흐름을 검증한다. 이 명세의 구현 대상이다. **(나) PMart 구현**: `demand-planning-service`(예측)와 `replenishment-service`(추천)에 넣는 것. (가)의 결과를 본 뒤 따로 결정한다. |
| 데모 코드 위치 | `pmart-backend/demos/mfc-demand-replenishment/`에 독립 Gradle 프로젝트를 둔다. 자체 `settings.gradle.kts`와 Java 21 설정을 사용하며, 루트 멀티프로젝트의 서비스 목록에는 등록하지 않는다. 버전은 루트 카탈로그를 그대로 읽는다(`dependencyResolutionManagement { versionCatalogs { create("libs") { from(files("../../gradle/libs.versions.toml")) } } }`). 카탈로그에 없는 의존성을 데모에서 쓰려면 루트 카탈로그에 먼저 등록한다. 실행은 루트 wrapper로 한다(`./gradlew -p demos/mfc-demand-replenishment <task>`, Windows `.\gradlew.bat -p ...`). 루트 `CLAUDE.md` 구조 절에 `demos/` 한 줄을 추가한다. |
| Spring Batch 저장소 | 데모 전용 로컬 PostgreSQL DB `pmart_mfc_demo`에 Batch 메타데이터와 데모 결과를 저장한다. `deploy/local` 인스턴스를 재사용할 경우 전용 DB·계정으로 격리한다. 루트 카탈로그의 Spring Boot 4.x(Spring Batch 6)에서는 DB 없는 작업 저장소가 기본일 수 있으므로, JDBC 메타 저장소용 스타터(예: `spring-boot-starter-batch-jdbc`)를 구현 시 정본 문서로 확인해 명시한다. `deploy/local/init-db.sql`은 볼륨을 처음 만들 때만 실행되므로, 이미 쓰는 인스턴스용 DB·계정 생성 명령(`CREATE DATABASE pmart_mfc_demo` 등)을 실행 문서에 따로 적는다. 스키마 초기화 방법과 동일 파라미터 재실행·실패 재시작 절차도 적는다. 시험은 Testcontainers PostgreSQL로 하며 로컬 DB에 의존하지 않는다. |
| 공급 구조 | MFC는 공급사에 발주하지 않는다. 칠곡 CDC → MFC 공급(MFC 요청 → SCM 사업승인 → CDC 확보·출고 → 운송 → MFC 수령)이다. 공급사 구매·PO·MOQ는 범위 밖이다. |
| 서비스 경계 | 예측(F01~F04·F06)은 demand-planning, 추천(F05)은 replenishment 책임이다. (나)에서 둘을 한 배치·한 서비스로 합치지 않는다. 예측은 `ForecastVersionPublished` 이벤트로 넘긴다. 두 서비스 모두 2단계이고 아직 없다. 담당 영역은 PC 1. |
| 모델 도입 | PMart 기술 스택은 "별도 Python 런타임을 두지 않는다"가 원칙이다(예외는 ADR-0058 배송 배차 하나). 승인 전 (나)의 1차 범위는 기존 설계의 승인된 수동 수요와 단순 이동평균이다. (가)는 Java 21·Gradle Kotlin DSL·Spring Batch를 사용하며 PMart 운영 서비스와 독립적으로 실행한다. ONNX Runtime Java의 운영 도입과 기존 스택에 없는 의존성은 PMart 승인 절차를 따른다. |
| 실행 경계 | 추천만 만든다. 공급 요청은 MFC 관리자가 확인한 뒤 제출한다. 추천이나 경보로 요청·승인을 자동 생성하지 않는다(SCM 8.4). |
| 미설정 값 | 정책·상한·물성이 없으면 0·무제한·정상으로 채우지 않는다. 수량을 비워 두고 `INPUT_PENDING` 으로 표시한다. |

## 1. 기능 명세표

| ID | 기능 | 구현 요구사항 | 검수 기준 |
|---|---|---|---|
| F01 | 판매 집계 | `mfc_id × sku_id × business_date` 로 일별 판매를 집계한다. 데이터 출처별 `source_timezone`을 기록한다. PMart는 `Asia/Seoul`, UCI는 `Europe/London`이다. **집계 기준(`sales_basis`: 주문확정·실제인계·전달 또는 데모 전용 대리값)과 취소·반품 처리 방식**을 결과에 함께 남기고, 기준이 다른 숫자를 섞지 않는다. 주·월·계절·연간 집계, 관측·품절·누락·비영업 일수를 제공한다. | 일별 합계와 각 기간 합계가 같다. 계절은 봄 3~5월, 여름 6~8월, 가을 9~11월, 겨울 12~2월이고, 12월은 다음 해 1~2월과 같은 겨울로 묶는다. 기준이 없으면 `FORECAST_BASIS_PENDING`. |
| F02 | 데이터 검증 | 정상 무판매·품절·수집 누락·판매 개시 전·비영업일·상태 미확인·데모 가정 무판매를 구분한다. 품절일 판매는 관측량으로 보존하고 실수요는 미확정으로 표시한다. 누락·품절·비영업일을 0수요로 채우지 않는다. | 판매량 0인 날도 상태에 따라 구분된다. 중복 일별 키와 유효하지 않은 수량은 오류로 기록한다. |
| F03 | 수요예측 | 순수 Java 이동평균을 기본으로 실행한다. Chronos-2 ONNX는 별도 PoC로 비교하며, 외생변수는 기준시각에 확정된 명절·행사·가격만 사용한다. 예측 기간은 보호기간을 덮는다. **예측의 의미(`forecast_scope`: 기준시각 이후 전체 수요 / 미확정 신규 수요만)를 결과에 남긴다.** | 상품·센터별 일별 예측이 음수 없이 저장되고 모델·버전·`forecast_scope` 를 확인할 수 있다. |
| F04 | 기준 모델 | Java 요일 `SeasonalNaive` 및 간헐 수요용 `CrostonSBA`를 비교한다. 이력 부족·모델 실패 시 대체 경로를 정한다. 이력 부족·신규 SKU 를 자동 0으로 줄이지 않는다. | 실제 사용 모델과 대체 이유를 기록한다. 믿을 만한 예측이 없으면 `REFERENCE_ONLY` 또는 `INPUT_PENDING`. |
| F05 | 보충추천 | 2·3장의 수량 정의와 계산 규칙으로 보호기간 목표, 재고 포지션, 원부족, 배수 희망량, 실행 상한, 추천량을 계산한다. | 같은 입력이면 같은 결과. 재고가 충분하면 추천 0. 양수 추천은 운송 배수 `m` 의 배수이고 실행 상한 `B` 를 넘지 않는다. 원부족·미충족·배수 초과분을 각각 기록한다. |
| F06 | 과거 검증 | 실제(또는 데모 시나리오의) 검토일 기준으로 순차 백테스트한다. 당시 확보 가능했던 입력만 쓴다. | 보호기간 누적 예측의 MAE·편향, 분위수 사용 시 pinball loss·관측 커버리지, 시뮬레이션 품절률·평균 재고를 비교한다. 품절로 실수요를 모르는 구간은 분리 표시한다. |
| F07 | 배치 운영 | 기준시각을 받는 배치. 활성 추천 키는 기준시각·센터·SKU, 실행 버전·입력 스냅샷 버전·실패 건을 기록한다. | 재실행해도 활성 추천이 중복되지 않는다. 한 SKU 실패는 추적되고 다른 SKU 는 처리된다. |

계산 결과 상태는 SCM 의 `CALCULABLE`(계산 가능) / `REFERENCE_ONLY`(참고만) / `INPUT_PENDING`(입력 대기)을 쓴다.

## 2. 수량 정의 (SCM 4장 요약)

| 기호 | 정의 | 주의 |
|---|---|---|
| H | 기준시각의 MFC **적격 현물**, 고객 예약을 **빼기 전** 수량. 격리·불량·판매 부적격·리콜 제외. 보호기간 안에 소비기한 적격을 잃는 현물은 그 시점부터 제외(실물 원장은 바꾸지 않음) | WMS 의 "예약 차감 후 가용"으로 대신하지 않는다 |
| C | 보호기간 안의 **유효 미이행 고객 약속** 전체. BLOCKED 도 의무가 유효하면 포함 | 미충족 주문(backorder)만이 아니다 |
| I | 보호기간 안에 실제 사용 준비될 기존 공급의 **유효 미수령 잔량** (아래 표) | 같은 공급의 승인·확보·출발은 한 번만 |
| P(t) | 이미 수령했으나 H 에 아직 없는 물량 중 시각 t 까지 정상 준비가 확인된 양 | H·I 와 중복 금지. 준비 불명·격리는 제외 |
| F | C 와 겹치지 않는 보호기간의 신규 예상 수요 | 예측 소비로 만든다(3장) |
| SS | 안전량. 분위수 목표를 쓰면 SS 를 따로 더하지 않는다 | — |
| S | 목표 `F + SS` (분위수 방식이면 신규 수요 누적 분위수) | — |

**입고 예정 I 인정 조건 (SCM 7.1)**

| 공급 단계 | 처리 |
|---|---|
| 추천·미제출·미승인 요청 | I 에 넣지 않는다. 중복 요청 방지용으로 진행 의도만 표시 |
| 승인됐으나 미확보·운송 미확인 | I 에 넣지 않는다. "승인 미이행" 표시 |
| 승인·확보·운송 가능·준비시각 근거 있음 | 인정 정책 범위의 미수령량만 I |
| 실제 출발, 기한 안 준비 가능 | 출발분의 유효 미수령 잔량을 I |
| 미수령이나 ETA·품질 불명, 기한 초과 | 그 기간 I 에서 제외(손실 처리하지 않음) |
| 수령·정상·준비 완료 | I 에서 빼고 H 로 |
| 수령·격리·준비 대기 | I 에 남기지 않음. 확인된 범위만 P(t) |

## 3. 계산 규칙

```text
보호기간      = 기준시각 → 다음 검토에서 정한 공급이 MFC 에서 실제 사용 준비되는 시각
                (CDC 출고 마감 · 운송 회차·도착 · MFC 수령창 · 검수·적치·판매 준비)
예측 소비     F  = Σ max(0, 구간별 남은 예측 − 해당 구간 예측에 포함된 C)  ※ 전체 수요 예측
                F  = 보호기간 남은 예측                                ※ forecast_scope = 신규 수요만일 때
목표          S  = F + SS   (분위수 방식이면 S = 신규 수요 누적 분위수, SS 없음)
재고 포지션   IP = H − C + I (+ P(t))      ※ 음수 그대로 보존
원부족        N  = max(0, S − IP)
배수 희망량   W  = m × ceil(N / m)          (N = 0 이면 0)
실행 상한     B  = min(CDC 신규 공급가능량, MFC 추가 수용량, 운송 허용 상한)
배수 상한     K  = m × floor(B / m)
추천량        Q  = min(W, K)
원수요 미충족 = max(0, N − Q)
배수 초과     = max(0, Q − N)
```

- **예측 소비 단위**. 데모는 일별 예측 구간에 약속의 `due_at`을 배정한다. 구간별 차감량은 해당 구간의 예측량을 넘지 않으며, 초과 확정량은 다른 날의 예측에서 빼지 않는다. `C`는 IP에서 전체 유효 약속량을 유지한다. 첫 부분일은 기준시각 이후의 예측과 약속만 사용하며, 일 데이터 데모는 일 경계에 실행한다. 실운영의 차감 단위와 기한 경과 약속 처리는 SCM 정본으로 확정한다.
- **보호기간 근사**: `리드타임 + 검토주기` 는 리드타임이 위 다섯 요소(마감·운송·수령창·검수·적치)를 이미 담고 있을 때만 쓴다. 쓰면 `protection_method = APPROX` 로 남긴다.
- **분위수**: 목표 분위수는 설정값이다. P90 은 누적 수요 자체의 P90(백테스트 잔차 보정이나 공동 예측 경로 합산)으로 구한다. 일별 P90 합계를 누적 P90으로 쓰지 않는다. 보정 데이터가 부족하면 점예측 합계를 쓰고 `target_method` 에 표시한다.
- **시간대별 부족**: 기간 끝 총량과 별도로 수요·입고 사건 순서대로 잔량을 계산해 **최초 부족 시각·최대 부족·회복 예정**을 남긴다. 이번 추천분의 사용 준비 시각 `proposed_ready_at`과 다음 검토분의 `protection_end`를 구분한다. 일 단위 데모에서 같은 날짜에는 수요를 먼저 차감하고 입고 준비분을 나중에 반영한다. 실제 시각이 있는 시나리오는 시각 순서대로 처리하며 동시 사건은 데모에서도 수요 우선으로 둔다. 기존 공급만 반영한 경로와 추천분을 `proposed_ready_at`에 반영한 경로를 비교해, 추천분 도착 전 부족과 보충 후 잔여 부족을 기록한다. 총량 기준으로 Q=0이어도 조기 부족 경보는 유지한다. 데모(일 단위 데이터)는 날짜 단위로 하고 `granularity = DAY` 로 표시한다.
- **상한 미설정**: B 의 세 값 중 하나라도 없으면 Q 를 비우고 `INPUT_PENDING`. 무제한으로 두지 않는다.
- **다품목**: 품목별 상한만으로 전체 적재를 보장하지 않는다. 합계 중량·부피·온도 구획 검증은 운송·MFC 결과를 받는다. 데모에서는 범위 밖이라고 표시한다.

**예시 (SCM 8.1·9장 수치)**

| 입력 | 값 | 결과 |
|---|---|---|
| H 18, C 6, I 24, F 72, SS 18 | | S 90, IP 36, N 54 |
| m 12, B 48 | | W 60, K 48, **Q 48**, 원수요 미충족 6, 초과 0 |
| 같은 N 54, B 10 | | K 0, **Q 0** (12로 올려 강제 공급하지 않음), 미충족 54 |
| N 5, m 6, B 12 | | **Q 6**, 초과 1 (기한·보관 정책 재검토 표시) |
| H 2, C 5, I 0 | | IP −3 (0으로 자르지 않음) |
| 남은 전체 예측 20, 그 안의 C 6 | | F 14, C 6 |

## 4. 입력·출력 데이터 계약

| 구분 | 필수 필드 | 의미 |
|---|---|---|
| 일별 판매 | `mfc_id`, `sku_id`, `business_date`, `units_sold`, `availability_status`, `sales_basis`, `source_timezone` | 낱개 기준. 상태: `NORMAL`·`STOCKOUT`·`MISSING`·`PRE_LAUNCH`·`CLOSED_DAY`·`UNKNOWN`·`ZERO_ASSUMED`(UCI 데모 전용). `UNKNOWN`은 사유 `unknown_reason`(`NO_CENTER_SALES`·`BEFORE_FIRST_OBSERVED`·`AFTER_LAST_OBSERVED`·`SOURCE_UNAVAILABLE`)을 함께 남긴다 |
| 품절일 원천 | (나) PMart: 상품 판매 상태 변경(`ProductSaleStateChanged`), 결품 처리 결과, MFC 재고 0 구간 중 무엇을 쓸지 정한다 | 데모: 원천이 없으면 `UNKNOWN` |
| 공급 정책 | `mfc_id`, `sku_id`, `review_interval`, `protection_method`, `transfer_multiple_m`, `target_method`, `target_quantile`, `ss_qty`, 검토 일정, 출고 마감, 운송·수령·검수·적치 준비 규칙 | m은 양의 정수. 목표량 방식에 해당하는 설정만 필수 |
| 계산 스냅샷 | `as_of`, `mfc_id`, `sku_id`, `proposed_ready_at`, `protection_end`, `protection_method`, `granularity`, `policy_version` | 정책과 해당 회차 일정으로 계산한 시각. 데모에서는 시나리오에 고정한 일정으로 재현 |
| 실행 상한 | `as_of`, `mfc_id`, `sku_id`, `cdc_available_qty`, `mfc_receivable_qty`, `transport_cap_qty` | 없으면 `INPUT_PENDING` |
| 재고 스냅샷 | `as_of`, `mfc_id`, `sku_id`, `eligible_on_hand_H`, `ready_pending_P[t]`, 소비기한별 적격 수량 | H는 예약 차감 전. 준비와 적격 상실 시각을 경로에 반영 |
| 고객 약속 | `mfc_id`, `sku_id`, `order_line_id`, `open_qty`, `due_at`, `status` | C. 예측 소비에 쓴 줄과 소비량을 남긴다 |
| 기존 공급 | `supply_id`, `mfc_id`, `sku_id`, `stage`, `remaining_qty`, `ready_eta`, `status` | 2장 표로 I 판정. 같은 공급 계보는 한 번만 |
| 선택 입력 | `holiday`, `promotion`, `planned_price`, `known_at` | 기준시각 전에 확정된 값만 |
| 예측 결과 | `as_of`, `mfc_id`, `sku_id`, `forecast_date`, `point_forecast`, `quantiles?`, `forecast_scope`, `model_id`, `model_version` | 모델별 실제 사용 여부 추적 |
| 추천 결과 | `as_of`, `mfc_id`, `sku_id`, `protection_end`, `protection_method`, `S`, `target_method`, `H`, `C`, `I`, `P`, `IP`, `N`, `m`, `W`, `B`, `K`, `Q`, `unmet_qty`, `excess_qty`, `proposed_ready_at`, `first_shortage_at`, `max_shortage`, `recovery_at`, `shortage_before_proposed_ready`, `residual_shortage_after_replenishment`, `granularity`, `status`, `reason`, `data_mode` | 산출 방식과 근거를 함께. 필수 입력이 없으면 수량을 비우고 상태를 표시 |

## 5. 모델 선정과 실행 환경

| 우선순위 | 모델 | 실행 방식 | 용도와 채택 조건 |
|---|---|---|---|
| 운영 1차 | 단순 이동평균 | Java 21에서 직접 계산. 신규 외부 런타임이나 가중치 없음. | 수정본에 명시된 1차 범위에 맞춘다. 창 길이는 검증으로 선택한다. 품절·누락을 정상 0으로 계산하지 않는다. [방법 참고](https://otexts.com/fpppy/05-toolbox.html) |
| Java 기준 모델 | 요일 Seasonal Naive, Croston-SBA | Java로 직접 구현. 첫 후보는 동일 요일 반복 패턴, 두 번째는 판매가 드문 SKU의 평균 수요율을 비교한다. | 운영 데이터의 백테스트 비교군으로 사용한다. Croston-SBA 점예측에서 보호기간 P90을 직접 얻을 수 없으므로 분위수 도입은 별도 보정을 검증한다. [계절 기준 모델](https://otexts.com/fpppy/05-toolbox.html) · [간헐 수요 방법](https://cran.r-project.org/web/packages/tsintermittent/vignettes/tsintermittent.html) |
| 사전학습 검증 | Chronos-2 ONNX | Hugging Face의 `machineandfolk/chronos-2-onnx`와 Microsoft ONNX Runtime Java CPU 사용. 운영 추론에 Python 프로세스 불필요. | Amazon 사전학습 모델의 제3자 변환본. 문서상 외생변수 입력을 유지한다. Java 전처리·출력 일치와 이미지 호환성, 기존 CPU에서의 처리량을 검증하고 PMart 도입은 승인 후 판단한다. [Amazon 원본](https://huggingface.co/amazon/chronos-2) · [ONNX 변환본](https://huggingface.co/machineandfolk/chronos-2-onnx) · [Java 런타임](https://onnxruntime.ai/docs/get-started/with-java) |
| 자체 학습 후속 | XGBoost4J | Java/JNI에서 CPU 학습·추론. 센터·SKU 간 데이터를 묶고 요일, 시차 판매량, 기준시각에 확정된 행사·가격을 특성으로 사용한다. | PMart 일별 이력이 확보되면 검토한다. 자체 학습 모델이므로 사전학습 가중치는 필요하지 않다. [Java 문서](https://xgboost.readthedocs.io/en/stable/jvm/) · [Apache-2.0](https://github.com/dmlc/xgboost/blob/master/LICENSE) |

### 비용과 검증 상태

Chronos-2 원본과 확인한 변환본은 Apache-2.0, ONNX Runtime은 MIT, XGBoost는 Apache-2.0이다. 기존 장비의 로컬 CPU에서 실행하면 모델 라이선스료와 외부 추론 API 호출료가 없다. ONNX와 XGBoost4J는 네이티브 라이브러리를 사용하므로 실제 Linux 컨테이너의 CPU 아키텍처와 호환성, 배치 시간 내 처리 여부를 확인한다. 이 문서는 공식 문서 조사 결과이며 PMart 장비에서 실행한 벤치마크는 포함하지 않는다. [ONNX Runtime 라이선스](https://github.com/microsoft/onnxruntime/blob/main/LICENSE)

- ONNX PoC는 `com.microsoft.onnxruntime:onnxruntime` CPU를 사용한다. `chronos2.onnx`와 `chronos2.onnx.data`를 같은 경로에 두고 리비전과 체크섬을 고정한다. PoC 준비 단계에서만 공식 Amazon Python 파이프라인을 한 번 실행해 기준 입력·예상 결과를 파일로 고정한다. 공식 모델·라이브러리 리비전과 설정, 체크섬을 함께 기록한다. 이후 Java 테스트는 고정 파일을 읽으며 기본 배치, 운영 이미지, 정규 테스트에 Python을 설치하거나 호출하지 않는다. 입력 마스크, 패딩, 결측, 외생변수, 보호기간 길이, 수치 오차 허용 범위를 검수한다. 운영 이미지 호환성과 CPU·메모리 예산을 확인한다. [변환본 인터페이스](https://huggingface.co/machineandfolk/chronos-2-onnx)

- ONNX Runtime과 향후 XGBoost4J는 네이티브 라이브러리를 로드하는 표준 JVM 모드로 실행한다. GraalVM Native Image는 데모 범위에서 제외한다. 운영 도입 때 OR-Tools 관련 ADR-0053의 적용 가능성을 정본에서 확인한다. XGBoost4J 도입을 검토할 때는 선택한 아티팩트의 Scala 버전 접미사와 전이 의존성을 확인한다.

## 6. 공개 검증 데이터 (데모용)

> 라이선스·저자·DOI 는 **내려받기 전에 원 게시처 페이지에서 확인**하고, 확인한 날짜와 함께 기록한다. 원본 파일은 저장소에 넣지 않고 내려받기 스크립트·체크섬·변환 내역만 둔다.

| 데이터 | 용도 | 한계 |
|---|---|---|
| **[UCI Online Retail II](https://archive.ics.uci.edu/dataset/502/online%2Bretail%2Bii)** ([ZIP](https://archive.ics.uci.edu/static/public/502/online%2Bretail%2Bii.zip), Daqing Chen, 2009-12-01~2011-12-09, 약 107만 행) | 기본 데모: 일별 집계(F01), 계절 리포트, 예측 백테스트(F03·F04·F06) | 영국 온라인 잡화·선물 판매(도매 주문 섞임). 신선·냉장·냉동 위주 MFC 와 수요 모양이 달라 **흐름 검증용이지 모델 선택 근거로는 약하다**. 재고·품절·입고 정보 없음 |
| **[Dingdong FreshRetailNet-50K](https://huggingface.co/datasets/Dingdong-Inc/FreshRetailNet-50K)** ([Parquet](https://huggingface.co/datasets/Dingdong-Inc/FreshRetailNet-50K/tree/main/data), Dingdong-Inc, 약 90일, 시간대별 품절 표시) | 품절 vs 정상 무판매 구분(F02), 시간대별 최초 부족 검증 | 90일이라 계절·연간 검증 불가. 판매량이 정규화돼 낱개 추천량 계산에 쓰지 않음 |
| **[Retail Transactions and Stocks Data v1](https://data.mendeley.com/datasets/27x8mjm8k4/1)** (Jimmy Smith, Mendeley) | 판매와 재고 스냅샷 연동(F05 입력 형태) | 기간·조인 키·스냅샷 시점을 먼저 프로파일링 |

2026-11-24에 세 데이터의 원 게시처에서 CC BY 4.0 표시를 재확인했다. 같은 날 Chronos-2 ONNX 변환본의 공개 모델 카드와 Apache-2.0 표시도 확인했다(명세 작성자 확인). 구현 때 출처 기록 파일에 확인한 페이지 URL·확인 날짜·확인한 사람을 데이터별로 남기고, 내려받을 때 다시 확인한다. 원본·가공본을 공유할 때 저자, 출처, 라이선스 링크, 변경 내용을 포함한다. [CC BY 4.0 조건](https://creativecommons.org/licenses/by/4.0/)

### 입력 포맷과 준비 절차

Java 데모는 UTF-8 CSV만 읽는다. UCI XLSX 두 시트와 FreshRetailNet Parquet 샘플은 별도 준비 단계에서 **DuckDB CLI 한 가지**(버전 고정, `excel`·`parquet` 확장 사용)로 CSV로 변환한다. 변환 SQL은 `demos/mfc-demand-replenishment/data-prep/*.sql`에 두고, 변환은 정제 없이 열 이름·형식만 바꾼다(정제·필터는 Java 데모가 하고 건수를 기록한다). DuckDB를 쓸 수 없으면 대체 도구와 이유를 출처 기록에 적는다. Mendeley CSV는 동일 계약으로 매핑한다. 원본·변환본의 SHA-256, 변환 도구·버전, 변환 명령 또는 절차, 원본별 행 수와 필터 결과를 기록한다. 합성 CSV 샘플로는 다운로드 없이 기본 배치를 검수할 수 있어야 한다.

Java에서 원본 직접 읽기는 후속 선택 사항이다. 이를 채택하면 XLSX는 Apache POI 스트리밍 이벤트 읽기, Parquet은 호환 라이브러리와 전이 의존성 검토를 거친다. PMart에 새 의존성을 도입할 때 `gradle/libs.versions.toml` 등 정본 버전 카탈로그에 등록하고 기존 승인 절차를 따른다.

### 구현 지시

1. **UCI 기본 데모**
   - 두 시트에서 만든 CSV를 사용한다. `StockCode → sku_id`, `InvoiceDate → business_date`(영국 현지 날짜), `Quantity → units_sold`, `mfc_id = DEMO_UK_01`로 매핑한다. `sales_basis=INVOICE_ORDER_CONFIRMED_PROXY`로 기록한다. 송장일을 주문확정 시점의 대리값으로 쓰는 데모 가정이다. 영국 거래만 쓰고 고객 ID 는 출력하지 않는다.
   - 제외하고 건수를 기록한다: 취소 송장(`Invoice` 가 `C` 로 시작), 음수 수량, 단가 0 이하, **상품이 아닌 코드**(`POST`, `DOT`, `D`, `M`, `C2`, `BANK CHARGES`, `AMAZONFEE`, `ADJUST`, `TEST` 계열 등 — 실제 목록은 프로파일링으로 확정).
   - **도매 이상치**: 이상치 임계값은 각 검토일 이전 학습 데이터로만 계산한다. 처리 전후 모델은 동일한 검증일과 동일한 원 관측 판매량으로 비교한다.
   - **비영업일**: 출처로 확인한 영업 달력이 있을 때만 `CLOSED_DAY`로 표시한다. 정제 후 유효한 판매가 있는 날을 거래 관측일로 본다.
   - **일별 상태 부여 순서** (한 SKU·한 날짜에 위에서부터 처음 맞는 것):
     1. 센터 전체에 유효 판매가 없는 날 → `UNKNOWN`(`NO_CENTER_SALES`).
     2. 해당 SKU 유효 판매가 있음 → `NORMAL`.
     3. 해당 SKU 첫 유효 판매일 전 → `UNKNOWN`(`BEFORE_FIRST_OBSERVED`). 데이터 시작(2009-12-01) 전부터 팔던 상품일 수 있으므로 `PRE_LAUNCH`(판매 개시 전)로 단정하지 않는다. `PRE_LAUNCH`는 출처가 출시일을 줄 때만 쓴다(UCI에는 없음).
     4. 직전 유효 판매일로부터 `stale_gap_days`를 넘김 → `UNKNOWN`(`AFTER_LAST_OBSERVED`). 판매가 끊긴(단종 등) 상품에 0이 끝없이 쌓여 예측을 0으로 끌어내리고 MAE를 좋게 보이게 하는 것을 막는다. 이후 다시 판매가 나오면 그날부터 2번 규칙으로 돌아간다.
     5. 그 밖(첫 판매 이후, 간격 안, 센터 판매 있음) → `ZERO_ASSUMED`, `units_sold=0`.
   - **`stale_gap_days` 산정**: 검토일 이전 학습 구간에서 그 SKU의 판매 간격(유효 판매일 사이 일수) 95번째 백분위 × 2, 최소 14일·최대 90일. 판매 간격이 3개 미만이면 전체 SKU 간격의 같은 백분위를 쓴다. 검토일마다 그 시점 데이터로 다시 계산하고(미래 정보 사용 금지), 사용한 값을 결과에 남긴다. 계수·한계값은 데모 가정이며 민감도(×1.5·×3)를 보고서에 함께 낸다.
   - `NORMAL`과 `ZERO_ASSUMED`는 이 데모의 판매 관측 상태이며 재고 보유를 입증하지 않는다. 보고서에 상태별 일수 비율(`ZERO_ASSUMED`·`UNKNOWN` 사유별)을 SKU 군별로 낸다.
2. **기간**: 첫 1년은 2009-12-01~2010-11-30(겨울 = 2009-12·2010-01~02). 그 뒤 기간에서 데모 검토일마다 순차 검증한다. `NORMAL`과 `ZERO_ASSUMED`를 모델 입력과 일별 평가에 사용한다. `UNKNOWN`은 학습·평가 목표값에서 제외하되 달력 행을 보존한다. 요일 모델은 원래 날짜로 요일을 산정한다. Croston-SBA는 미확인일을 건너뛴 구간을 연속 관측으로 연결하지 않고 그 구간의 판매 간격을 추정에 쓰지 않는다. 유효한 간격이 부족하면 F04의 대체 경로를 적용한다. 보호기간 전체 누적 오차는 모든 대상일의 평가값이 있는 구간끼리 비교하고, 제외 구간과 `ZERO_ASSUMED` 비중을 보고한다.
3. **추천 계산은 가상 시나리오로**: H·C·I·P·m·B 는 고정된 데모 시나리오로 주고 `data_mode = simulated_inventory` 로 표시한다. 3장 예시 표의 경우를 모두 시나리오에 넣는다. 시뮬레이션 결과를 실제 품절률·공급 효과로 보고하지 않는다.
4. **보조 검증**: FreshRetailNet 은 `city_id + store_id`, `product_id`, `dt` 키로 품절 사례(`stock_hour6_22_cnt`, `hours_stock_status`)를 확인한다. Mendeley 는 판매·재고 키와 날짜 정합성을 확인한 뒤 연동 시험에 쓴다. 세 출처의 이력을 한 시계열로 잇지 않는다.
5. **출처 기록**: 데이터별 원본 URL·버전·라이선스·저자·DOI·내려받은 날짜·변환 내역.

### PMart 자체 데이터 (나 단계 준비)

LAN 5개 센터(503 SKU)는 PMart 상품·온도대·단위가 그대로 맞는다. MFC 시뮬레이터 실주문으로 주문·결품 이력이 쌓이면 품절일 원천(F02)과 H·C·I 입력(F05)을 실제 계약으로 검증할 수 있다. 이력이 짧아 계절 검증은 하지 않는다.

## 7. Claude 구현 순서와 완료 조건

1. Java 데모의 일별 입력 정제·집계와 재현 가능한 샘플을 먼저 만든다. 실제 검증 데이터가 확보되면 동일 스키마로 실행한다.
2. Java 이동평균과 비교 모델을 구현하고 같은 기준시점으로 순차 검증한다. 추가 Python 런타임이 없는 상태에서 기본 데모가 완주해야 한다.
3. 고정 시나리오로 CDC→MFC 추천 계산과 시간별 부족 경보를 검증한다. 같은 시점의 상한이 여러 SKU에 공유되는 경우 개별 추천의 합계가 실행 가능한 수량인지 별도 표시한다.
4. ONNX PoC를 독립 실행하고 일치성·처리량·최대 메모리를 기록한다. 통합 여부는 PMart 실제 데이터의 보호기간 오차와 운영 자원 예산, 승인 결과로 결정한다.
5. PMart 운영 통합 시에만 기존 PostgreSQL·Flyway·Kafka 계약을 대조한다. `ForecastVersionPublished`는 버전 발행 계약으로 확인하고 재시도 시 동일 버전의 중복 반영을 막는다.

- 입력 샘플, 배치 실행 명령, 집계·예측·추천 결과 예시, 검증 보고서를 낸다. 추천 출력까지 만들고, 공급 요청 제출·공급사 전송은 범위 밖이다.
- 예측 소비 회귀 사례로 1일차 전체 예측 2개와 확정 8개, 2일차 전체 예측 10개와 확정 0개를 넣는다. 기대값은 신규 수요 F=10, 고객 약속 C=8이다. 조기 부족 사례는 아침 적격 현물 H=5, 오전 고객 약속 C=6, 오후 기존 입고 I=10, 신규 수요 F=0, 안전량 SS=0으로 고정한다. IP=9, Q=0이어도 부족 경보가 나와야 하며, 두 해상도로 각각 검수한다.
  - **시각 시나리오(`granularity = HOUR`)**: 기준 D일 06:00, 약속 `due_at` D일 11:00, 입고 `ready_eta` D일 15:00. 기대값 `first_shortage_at` = D일 11:00, `max_shortage` = 1, `recovery_at` = D일 15:00.
  - **일 단위(`granularity = DAY`)**: 같은 날 수요 먼저·입고 나중 규칙으로 기대값 `first_shortage_at` = D일, `max_shortage` = 1, `recovery_at` = D일(같은 날 회복). 일 단위에서는 하루 안의 부족 시간을 알 수 없다는 한계를 `reason`에 남긴다.
- 상태 부여 회귀 사례(합성 CSV): 데이터 첫날부터 팔린 SKU의 첫 판매 전 날 = `UNKNOWN`(`BEFORE_FIRST_OBSERVED`)이고 `PRE_LAUNCH`가 아님. 판매 간격 안의 무판매일 = `ZERO_ASSUMED`. 마지막 판매 뒤 `stale_gap_days`를 넘긴 날 = `UNKNOWN`(`AFTER_LAST_OBSERVED`)이며, 다시 팔린 날부터 `NORMAL`. 센터 전체 무판매일 = `UNKNOWN`(`NO_CENTER_SALES`). `stale_gap_days`는 검토일 뒤 데이터를 바꿔도 같은 값.
- 검수 항목: 계절 경계, 누락·품절·비영업일, 판매 상태 부여 순서·끝 경계, 간헐 수요, 이력 부족, 입고 준비시각 경계, I 단계별 인정, 예측 소비(이중 계산 없음), IP 음수, 운송 배수와 실행 상한(B 미만·미설정 포함), 시간대(일) 부족, 배치 재실행.
- 1년 이력은 연간 계절을 한 번 본 데이터로 다룬다. 요일·명절·행사 효과를 먼저 검증하고 연간 반복 효과는 추가 연도가 생기면 비교한다.
- 운영 전 실제 데이터로 모델별 성능·처리량을 비교한다. 합격 기준과 목표 분위수는 MFC 품절·재고 비용과 배치 시간에 맞춰 정한다(정책값은 승인 대상).
- 구현과 실행 검증은 로컬에서 진행한다. 정상 작동을 확인하기 전에는 push하거나 PR을 열지 않는다. `mfc-inbound` 공급 필요 제시와 MFC 백오피스 화면은 별도 운영 통합 범위로 둔다.
