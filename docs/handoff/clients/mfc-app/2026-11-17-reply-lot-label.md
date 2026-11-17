# 회신: LOT 라벨(QR) 발급 — L1~L6

- 보낸 영역: mfc
- 작성: pmart-backend, 2026-11-17
- 답하는 것: pmart-clients `docs/handoff/mfc-app/2026-11-17-backend-lot-label.md`(브랜치 `mfc-app/pick-to-cart`, 커밋 bd7509b).
- 계약: `mfc-inventory-service.yaml` 0.20.0. PR 병합 뒤 main 에서 `gen:api`. LAN 반영은 PC 1 배포 뒤.
- 사용자 결정(2026-11-17 "추천대로"): 아래 §0 세 가지.

## 0. 결정

| 질문 | 결정 |
|---|---|
| L6 LOT 묶음 기준 | **지금 기준 그대로** — 같은 LOT = 같은 SKU·제조사 LOT 원문·소비기한·제조일. **납입일로 LOT 를 나누지 않는다**(라벨에 표시만). 제조사·공급사는 MFC 재고에 없어 기준이 아니다. 선입선출(소비기한 순)은 이 기준으로 된다 |
| L5 박스 상품 | 박스는 **박스 SKU**(`unitType` B = 밀봉 묶음, `unitsPerSku` = 입수). 라벨 1장 = 그 SKU 1단위 — 박스 SKU 는 박스마다, 낱개 SKU 는 낱개마다. 피킹·이동 수량도 SKU 단위라 박스 라벨 한 번 = 1(박스). 박스를 풀어 낱개로 쓰는 흐름은 없다 |
| L1 라벨 종류 | **QR**, 값 `LOT:{lotId}`(기존 `TRF:`·`PKG:` 와 같은 `{유형}:{id}`). lotId 를 그대로 담으므로 짧은 코드 대조표는 두지 않는다 |

## 1. L1 — 라벨 값

- `LOT:{lotId}` 예 `LOT:LOT-3f2c…`(lotId 최대 40자). **LOT 하나 = 값 하나, 재출력해도 같다.**
- PDA 는 찍은 값에서 `LOT:` 뒤를 lotId 로 읽어 **서버 호출 없이** 피킹 지시 LOT(`TaskLine.picks[].lotId`)와 대조할 수 있다. 다르면 막는 것은 화면 판단이고, 서버도 그대로 `LOT_MISMATCH` 로 거절한다.
- 상품 바코드(880…·299…)는 LOT 를 알려 주지 않는다 — 라벨 대신 쓰지 않는다(요청서와 같음).

## 2. L2·L4 — 인쇄 정보·라벨 값 해석 `GET /mfcs/{mfcId}/lot-labels/{lotId}`

- 그 센터 운영자. LOT 이 없으면 404 `LOT_NOT_FOUND`. 그 센터에 보유가 없어도 LOT 정보는 준다(`onHand` 0).
- 응답 `LotLabel`:

| 필드 | 뜻 |
|---|---|
| `labelValue` | `LOT:{lotId}` — QR 에 넣을 값 |
| `lotId`·`sku` | |
| `productName`·`barcodes` | SKU 사본(모르면 null·빈 배열) |
| `unitType`·`unitsPerSku` | E 낱개 / B 박스와 입수 |
| `tracking` | TRACKED / UNTRACKED(소비기한·원문 없음 — 선입선출 근거 아님, 초기 이관분 등) |
| `sourceLotRef` | 제조사 LOT 원문 |
| `expiryDate`·`manufactureDate` | 소비기한·제조일 |
| `receivedAt` | **납입일** — 이 센터에 처음 정상으로 들어온 시각(검수 전이면 null) |
| `onHand` | 이 센터 보유 |
| `qtyAtLocation` | 위치로 찾았을 때만(§3) |

- **언제 뽑나 — 품질검사 LOT 분리 단계(사용자 2026-11-17)**: 품질검사 `POST /inbound-tasks/{taskId}/inspections` 가 APPLIED 면 응답 `createdTaskIds` = 나뉜 정상 LOT 마다 만든 적치 작업. 그 작업마다 `GET /inbound-tasks/{id}` → `lotId`·`plannedQty`(그 LOT 의 합격 수량) → **`LOT:{lotId}` 를 `plannedQty` 장** 출력(인쇄 정보는 이 조회). 낱개 SKU 는 낱개 수, 박스 SKU 는 박스 수. 격리(QUARANTINED) 분은 적치 작업이 없고 선반에 오르지 않아 라벨 대상이 아니다. 이 조회에는 장수가 없다(LOT 는 여러 번 들어올 수 있어 "이번에 붙일 수"는 적치 작업 수량이 정본). 서버 추가 없이 지금 계약으로 된다.
- **찍은 라벨 확인**(L4): 유통기한·품질·보유를 보여 줄 때만 이 조회. 대조만이면 §1 처럼 값에서 바로.
- 제조사·공급사는 MFC 재고에 없어 싣지 않는다 — 라벨에 꼭 필요하면 다시 요청해 주세요(상품·공급 쪽에서 받아 와야 한다).

## 3. L3 — 재출력할 LOT 찾기 `GET /mfcs/{mfcId}/lot-labels?sku=&locationCode=`

- 라벨이 훼손돼 값을 모를 때. `sku` 나 `locationCode` 중 하나 이상(없으면 400). 둘 다 주면 둘 다 맞는 것.
- 그 센터에 지금 보유가 있는 LOT 만, **소비기한 이른 순**(기한 없는 LOT 은 뒤), 최대 100건. `locationCode` 를 주면 그 위치에 있는 LOT 만 + `qtyAtLocation`.
- 재출력은 물류 실적이 아니라 서버에 기록하지 않는다.

## 4. PDA 흐름에 대해

- 입고: 품질검사(LOT 분리) 직후 나뉜 LOT 마다 "LOT 라벨 N장 출력"(N = 그 LOT 적치 작업 `plannedQty`, §2) → 적치. 맞다.
- 피킹: 칸 → LOT 라벨 → 수량. 첫 스캔을 상품 바코드에서 LOT 라벨로 바꾸면 된다. 지시 LOT 와 다르면 막기 — 맞다(서버도 거절).
- 이동·적치·실사·재고 확인: 같은 라벨 값으로 LOT 확인 — 이동 지시(`MoveOrder.lotId`)·실사 계수표(`sheet[].lotId`)에 이미 lotId 가 있다.
- 지금 LAN 재고에는 라벨이 붙어 있지 않다(초기 재고·이관분). 처음 쓸 때는 §3 으로 찾아 한 번 뽑아 붙여야 한다 — 그 전까지 PDA 가 상품 바코드 대체를 막으면 피킹이 멈춘다. **기존 재고 데이터는 PC 1 이 갱신하기로 했다(사용자 2026-11-17)** — 상품 바코드 대체를 막는 시점은 그 갱신 뒤로 맞춰 주세요.

## 5. 시험(PC 3, 2026-11-17)

- `:services:mfc-inventory-service:check` — 단위 44·통합 31 통과(`StockReservationFlowIT` 정정 시험에 라벨 목록 순서·값·위치 수량·단건·404·400·403).
