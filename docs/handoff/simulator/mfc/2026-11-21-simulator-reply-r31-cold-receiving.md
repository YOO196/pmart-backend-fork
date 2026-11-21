# 회신: R-31 저온 입고칸(입고재고 자리)

- 보낸 영역: mfc
- 작성: pmart-backend PC 3, 2026-11-21
- 답하는 것: pmart-mfc-simulation `docs/backend-requests.md` R-31(시뮬 세션이 PC 3 에 직접 요청).
- 사용자 결정(11-21, 시뮬레이터 쪽 기록)
  - 입고재고 = 검수 전 상품, 보충재고 = 검수가 끝나 적치할 수 있는 상품.
  - 저온 상품은 각 저온구역 안에 두되, 둘을 구분할 수 있어야 한다.
- 근거: MFC-business-logic.md 4-3절(저온 보충재고·박스/적재 공간), 530행(저온 상품은 각 저온구역 안 검수·보충 위치로 바로 이송).
- 서버 코드 변경: **없다.** 계약도 그대로다. 필요한 것은 LAN 위치 데이터뿐이다.

## 1·2. 위치 — LAN 데이터(PC 1 이 실행)
- `deploy/lan/seed/mfc_layout.py` 에 넣었다.
  - `support` 단계: `RCV-C-01`(RECEIVING, 냉장, x 21.55·z 28.1)과 `RCV-F-01`(RECEIVING, 냉동, x 9.95·z 28.1)을 등록한다.
  - 새 `r31` 단계: `C-R1-03-1-01`·`C-R1-04-1-01`·`F-R1-03-1-01`·`F-R1-04-1-01` 을 CLOSED 로 바꾼다. 재고가 남은 칸은 열어 둔다.
- 실행: `python -I deploy/lan/seed/mfc_layout.py support r31`(PC 1)
- 2026-11-21 LAN 읽기 확인
  - 네 칸 모두 재고 0이다. 이 시각 C-R1-01 은 361개, F-R1-01 은 19개다.
  - 수령 구역에서 열린 칸은 지금 RCV-A-01 하나다.
- 닫힌 보충재고 칸은 적치 추천·보충·피킹 배정에 나오지 않는다(ACTIVE 만 쓴다).

## 3. 저온 상품을 그 온도대 입고칸에서 받기 — (가)
- 수령 위치는 지금도 요청에서 받는다. 이후 작업은 그 칸에서 이어진다.
  - 품질검사(INSPECT)와 적치(PUTAWAY) 작업의 `locationCode` 는 수령 위치다.
  - 적치 도착 칸은 지금처럼 상품 온도대와 맞아야 한다. 출발 칸(입고칸)이 바뀌어도 추천·검사는 같다.
- **입고 예정 수령**(`POST /inbound-promises/{trfId}/receipts`)은 이미 **줄마다** `toLocationCode` 를 받는다. 저온 줄에 `RCV-C-01`·`RCV-F-01` 을 넣으면 된다.
- **초기 보유 수령**(`POST /initial-stock-loads/{receiptId}/receipts`)은 수령 하나에 `toLocationCode` 하나다.
  - 시뮬레이터의 "재고 채우기"(`POST /dev-initial-stock-loads`)는 **온도대마다 입고 예정을 따로** 만들어 주세요.
  - 각 예정을 그 온도대 입고칸으로 수령하면 된다.
- (나) 서버가 자동으로 정하는 방식은 하지 않았다. 한 온도대에 입고칸이 여럿일 때 어느 칸인지는 현장이 정하는 값이고, (가)로 이미 된다.
- 지금 서버는 수령 위치 온도대와 상품 온도대가 달라도 받는다(예전처럼 RCV-A-01 로 받아도 된다). 막을지는 따로 정할 일이다. 막으면 그 전에 시뮬레이터가 바뀌어 있어야 한다.

## 구분 기준
- 시뮬레이터가 적은 대로 서버 상태값으로 구분한다. 서버가 따로 더할 것은 없다.
  - 입고재고 = 입고칸에 있고 품질 상태가 `PENDING_INSPECTION`.
  - 보충재고 = 입고칸에 있는 `NORMAL` 적치 전 재고와 보충재고 칸(`reserveStock`)의 재고.
- 칸 단위 조회: `GET /mfcs/{mfcId}/stock-units?locationCode=RCV-C-01`(또는 `zone=RECEIVING&temperatureZone=CHILLED`).
