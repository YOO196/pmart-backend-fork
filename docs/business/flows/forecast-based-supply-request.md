# 판매 예상량 기반 CDC 공급 요청 — 보류 (서비스별 할 일)

- 상태: **보류**(2026-11-08 사용자: "보류하고 관련 서비스에 해야 하는 거 문서 넣어줘")
- **일부 시작(2026-11-24 사용자 "B" — MFC 시뮬레이터 R-48)**: SCM 없이 mfc-inbound 에서 여러 줄 발주 → 센터 관리자 승인 → 입고 예정, 발주 수량 추천 조회(임시 이동평균). 요청 `mfc-inbound-service.md` MI-4. SCM 이 생기면 추천·승인을 replenishment 로 옮긴다.
  **구현(2026-11-24, mfc-inbound 0.21.0)**: 승인 `POST /supply-requests/{id}/approvals`·거절 `…/rejections`(센터 관리자) → 입고 예정 EXPECTED, 추천 `GET /supply-recommendations`(인계 실적 28일 이동평균, 정책 표 `supply_policy` 시작값 R 1·L 1·SS 1일치·배수 1·최소 1 — 사용자 확인, 승인 전). 품절 날 제외·정책 수정 화면은 남은 것.
- 목표(2026-11-08 사용자 Q-15: "일단 1, 최종은 2"): 지금은 관리자 요청, **최종 = 시스템 추천 → 관리자 요청**(정책 자동 요청은 하지 않음).
- 방향(사용자, 2026-11-08): "CDC 에 요청할 때 애초에 판매 예상량 생각해서 주문하게" — 고회전 선반 자동 보충(IV-04)이 예약 때문에 막히지 않으려면 센터 재고 자체가 판매 예상량만큼 들어와 있어야 한다.
- 근거 원문: `MFC-business-logic.md` 1장 6-3(발주·입고 주기, 산식), WK-14(센터 공급 요청), IV-12(발주·SCM 연계), Q-15(SCM 공급 요청 계약 — 미결). `service-map.md` demand-planning-service·replenishment-service(둘 다 2단계, 아직 없음).
- 왜 보류했나: 예측은 SCM `demand-planning-service`, 공급 추천·승인은 `replenishment-service` 의 책임인데 **둘 다 아직 만들지 않았다.** MFC 안에서 예측을 계산하면 서비스 경계를 어긴다(다른 서비스 정본 복제 금지). 검토한 안(A 새 서비스·B MFC 임시 추천·C 보류) 중 C.

## 업무 규칙(원문 1장 6-3, 값은 미결)

```
목표 재고          S  = d × (R + L) + SS
재고 포지션        IP = 가용 재고 + 확정 발주(이송) 중 수량 − 미출고 확정 수요
발주(공급 요청)량  Q  = max(0, S − IP) → 박스 입수·최소 발주량(MOQ)에 맞춰 올림, 구역 수용 한도로 제한
```
- d = 일 예상 판매량(예측), R = 검토 주기(일), L = 리드타임(일), SS = 안전재고.
- 원문 설계 가정(**승인 전 — 운영값 아님**): 입고 차량 매일 오전, 리드타임 1일(전일 오후 발주 → 익일 오전 입고). 검토 주기 = 신선·A급 매일, B급 2~3일, C급 7일, 냉동 저·중회전 2~3일. 안전재고 0.5일치는 "검증용 예시"(소비기한·수요분산을 알면 재계산).
- 전체 SKU 를 매일 발주한다는 뜻이 아니다(차량만 매일).

## 서비스별 할 일

### demand-planning-service (새로 — SCM, 2단계)
- **소유**: 예측 버전·버킷(센터 × SKU × 영업일 Asia/Seoul)·신뢰도·품절 구간 표시, 승인된 수동 수요, 초기 모델(이동평균 — service-map).
- **입력(구독, 계약 있음)**: `OrderConfirmed`·`OrderLineQuantityChanged`(order — 확정 수요·취소), `PackageHandedOver`/`PackageHandedOverPosted`(mfc — 실제 출고 = 판매 실적), `ProductSaleStateChanged`(판매 중지 구간), `InventorySnapshotPublished`(품절 구간 판정).
- **해야 할 것**
  1. 일별 판매 실적 집계(센터 × SKU). **품절 구간의 판매 0 과 정상 판매 0 을 구분**(품절이면 수요를 0 으로 학습하지 않음) — 브리프·원문 공통 주의.
  2. 예측 기준 선택(주문 확정 / 실제 인계 / 전달 중 — service-map "기준" 결정 필요).
  3. 초기 모델 = 이동평균(기간·요일 보정은 결정). 이력이 부족한 SKU 는 기준선(신상품 = 수동 수요 또는 카테고리 평균)과 사용 근거 기록.
  4. 예측 버전 발행 `ForecastVersionPublished`(계약 있음) + 조회 API(센터·SKU·기간).
  5. 백테스트(최근 평균·요일 기준선과 비교, 오차·구간 포함률) — 브리프 권고.
- **결정 필요**: 예측 기준, 이동평균 기간, 품절 판정 기준, 신상품 기준선, (나중) Chronos-2 같은 모델 도입 여부 — **Python 별도 런타임은 기술 스택과 충돌**(별도 ADR 필요, `baro-pick-docs` 검토 충돌 ①).

### replenishment-service (새로 — SCM, 2단계)
- **소유**: 공급 정책(검토 주기 R·리드타임 L·안전재고 SS·박스 입수·MOQ·우선순위 `센터+SKU → 그룹 → 전역`), planRun 스냅샷, 수량 사전(H/C/I/P/F/SS/S/IP/N), 추천·공급 요청 접수·승인·이송(TRF).
- **해야 할 것**
  1. 정책 저장(값이 없으면 그 SKU 는 추천 안 함 — CONFIG_REQUIRED, 0·무제한 대체 금지).
  2. 검토일마다 S·IP·Q 계산(위 산식) → `ReplenishmentRecommended`(계약 있음). IP 의 "확정 발주 중" = 승인된 TRF 중 미도착, "미출고 확정 수요" = order 확정 수요.
  3. MFC 로컬 공급 요청(`SupplyRequestSubmitted`, mfc-inbound 가 이미 발행) 접수 → `SupplyRequestReceived`(계약 있음) → 심사·승인(approval) → `TransferApproved`(계약 있음).
  4. 이미 출발한 물량은 자동 취소 금지(Q-15 원문), 부분 승인·잔량 종료.
- **결정 필요(Q-15)**: 공급 요청 생성 방식(관리자 요청 / 자동 추천 후 요청 / 정책 자동 요청), 승인 주체·부분 승인, TRF 계약, 재시도·대사 주기.

### mfc-inbound-service (있음 — MFC)
- 지금: 로컬 공급 요청 제출·변경·철회(WK-14, `SupplyRequestSubmitted`), `recommendationRef` 필드 자리만 있음.
- **해야 할 것(위 두 서비스가 생긴 뒤)**
  1. 공급 요청 화면용 **추천 수량 조회**(replenishment 추천을 읽어 `recommendationRef` 로 연결) — 센터 관리자가 추천을 보고 요청(또는 정책이 정하면 자동 요청).
  2. `SupplyRequestReceived` 구독 → 요청 ACKNOWLEDGED(지금은 생산자가 없어 못 함).
  3. 요청·승인·출고·실제 입고 수량을 한 화면에 — 승인·이송은 SCM 사건으로 조회 모델(경계는 그대로, `baro-pick-docs` 검토 충돌 ②).

### mfc-inventory-service (있음 — MFC)
- **했음(2026-11-08)**: `InventorySnapshotPublished` 주기 발행(센터 × SKU H·Q·E·R·A, 설정 `snapshot-interval`). **남은 것**: 변화 때 발행(trigger EVENT — 품절 진입·해제 순간), 안전량 S(정책 정해지면). demand-planning 품절 판정과 replenishment IP 에 쓴다.
- 판매 실적은 이미 `PackageHandedOverPosted` 로 나간다.

### mfc-capacity-service (있음 — MFC)
- 오전 입고 수령창·하차면 — 조회·예약 구현됨(2026-11-08, `GET /internal/receiving-capabilities`). 남은 것: replenishment 가 생기면 `ReceivingCapabilityChanged` 발행(설정 갱신 시).

### order-service / mfc-fulfillment (있음)
- 추가 일 없음 — 필요한 사건(`OrderConfirmed`·`OrderLineQuantityChanged`·`PackageHandedOver`)은 이미 발행한다.

## 계획 — 시작할 때 쓸 시작값(2026-11-08, 사용자: "계획만 만들고 보충해야 하는 사항에 기록")
판매 이력이 없어 지금 검증할 수 없다. demand-planning·replenishment 를 시작할 때 이 값으로 시작하고, 이력이 쌓이면 다시 본다.

| 항목 | 시작값(계획) | 근거 |
|---|---|---|
| 예측 기준 | **실제 인계 수량**(`PackageHandedOver`/`PackageHandedOverPosted`) | 확정 주문은 결품·취소로 실제 판매와 다르다 |
| 이동평균 | 최근 **4주 같은 요일** 평균 | 요일 차이(주말 등) 반영 |
| 품절 판정 | 그날 판매 가능(A) 0 인 시간이 있으면 그날은 학습 제외 | 품절 때 판매 0 을 수요 0 으로 배우지 않게(원문·브리프 공통) — `InventorySnapshotPublished` 로 판정 |
| 신상품 | 관리자 입력 수요, 없으면 같은 카테고리 평균 | 이력 없음 |
| 검토 주기 R | 원문 가정(신선·A급 매일, B급 2~3일, C급 7일) | 원문 6-3 |
| 리드타임 L | 1일(전일 오후 요청 → 익일 오전 입고) | 원문 가정 |
| 안전재고 SS | 0.5일치로 시작 | 원문 "검증용 예시" |
| 입고 상한 | 수령창 하루 720·1회 360박스(사용자 결정) | 공급 요청이 넘지 않게 |
| 요청 방식 | 시스템 추천 → 관리자 요청(Q-15 최종 목표), 정책 자동 요청 없음 | 사용자 결정 |
| 예측 모델 | 처음엔 이동평균(Java, demand-planning 안). **Chronos-2 등 별도 Python 모델은 보류** — 판매 이력이 쌓이고 이동평균과 비교(백테스트)할 수 있을 때 ADR 로(기술 스택 "별도 Python 런타임 없음"과 충돌) | `baro-pick-docs` 충돌 ① |

## 시작할 때 순서(제안)
1. demand-planning 뼈대(일별 실적 집계 + 품절 구간 + 이동평균 + 조회) — 판매 이력이 쌓이게 먼저.
2. mfc-inventory `InventorySnapshotPublished` 발행.
3. replenishment 정책·추천(산식) — 정책 값은 "승인 전 시험값"으로 LAN 에만.
4. mfc-inbound 추천 연결·`SupplyRequestReceived` 구독.
5. 승인(approval)·TRF·대사.

## 관련 기록
- 자동 선반 보충(IV-04)은 이와 별개로 구현됨(센터 안 보관 → 고회전 선반, `mfc-inventory-service` CLAUDE.md). 예약이 가용을 다 잡으면 보충은 재고 확보까지 대기(사용자 결정) — 이 문서의 공급 요청이 그 재고를 채우는 쪽이다.
- 메모리: 이 보류 결정과 순서.
