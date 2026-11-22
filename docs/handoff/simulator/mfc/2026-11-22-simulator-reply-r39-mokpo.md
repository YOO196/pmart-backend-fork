# 회신 — R-39 2호점 목포 MFC(MFC-MOKPO-01) (2026-11-22, PC 3 mfc)

보낸 영역: mfc(PC 3). 요청: pmart-mfc-simulation `docs/backend-requests.md` R-39(시뮬레이터 main 029745d). 사용자 결정(시뮬레이터 세션 전달) "여기랑 평수, 구조, sku 수, 랙 갯수 등 완전 똑같이 해서 목포 MFC 만들어줘".
이 PC 사용자 결정(11-22): **PC 3 는 스크립트 PR 만**. LAN 실행은 PC 1.

## 1. 순서와 담당
| 순서 | 무엇 | 담당 |
|---|---|---|
| 1 | 1호점 R-38 반영 `mfc_layout.py r33 r34`(#352) — A-R2-11·12 가 닫혀야 목포에 안 생김 | PC 1 |
| 2 | center-master 센터 `MFC-MOKPO-01` "2호점 목포 MFC"(목포 주소·좌표, 시간표는 1호점과 같게) — Seed.java 를 목포 seed-data 로 | PC 1(platform) |
| 3 | 목포 관리자 `claude-mfc-sup-mk`·작업자 30명·관찰 계정 센터 추가 | PC 1(이미 맡음) |
| 4 | 칸 `python -I deploy/lan/seed/mfc_clone_center.py locations --apply` | PC 1 |
| 5 | 재고 — 정식 입고 흐름 `mfc_clone_center.py stock --apply`(§4), 사용자 확인 뒤·목포 가상 작업자 끈 채로 | PC 1 |

- mfc 서비스(위치·재고·작업자)는 센터를 따로 등록하지 않는다. "센터가 먼저 있어야" 는 center-master 센터와 그 센터 권한을 가진 관리자 계정이다.

## 2. 칸 — `deploy/lan/seed/mfc_clone_center.py locations`
- 1호점 칸을 읽어 같은 코드·구역·온도대·고회전·보충재고·pickSequence·치수로 목포에 등록한다. 넓은 칸 합침(치수)·고회전 시험 칸·카트 CRT-A-01~40·패킹대·인계 랙·입고칸·격리칸이 그대로 따라간다.
- 위치는 위치 파일(R-38 c7d4b7b, 15,528칸) 값. 파일에 없는 칸(RTN-A-01)만 1호점 값.
- 만드는 칸 = 1호점 ACTIVE 칸 + 위치 파일에 있는 칸. 1호점에서 닫힌 칸이면 목포에서도 닫는다(넓은 칸 합침으로 닫힌 100칸). 옛 시험 선반·취소된 R-35 랙 19~21 같은 닫힌 칸은 만들지 않는다(`--all` 이면 만듦).
- 계획(11-22 LAN 읽기, 1호점 칸 16,853 = ACTIVE 15,431 · CLOSED 1,422):
  - 만들 칸 15,531(보관 15,471·카트 40·패킹 10·입고 4·격리 3·인계 2·회수 1), 그중 닫을 칸 100.
  - 15,531 = 위치 파일 15,528 + RTN-A-01 + A-R2-11·12. 1호점 r33 뒤면 A-R2-11·12 가 빠져 **15,529** 이 된다.
- 다시 돌려도 안전하다(이미 있는 칸은 건너뜀). 대상 관리자 계정이 없으면 계획은 빈 센터로 보고 낸다.

## 3. 재고 — 넣지 않았다
- `mfc_clone_center.py stock-plan --out <파일>`: 1호점 재고를 요약하고 줄마다(SKU·LOT·유통기한·칸·수량) 파일로 쓴다. 11-22: 줄 1,334, SKU 503, 수량 9,671(NORMAL·STORED 1,316, CANCEL_RETURN_PENDING 18), 할당·예약·보류 0. 목포 재고 줄 0.
- 실서버 재고 등록이라 사용자 승인이 필요하다. 조정(`/internal/adjustments`)·위치 정정 같은 직접 복사는 쓰지 않는다.

## 4. 재고 넣기 — `mfc_clone_center.py stock`(사용자 결정 11-22 "정식 입고 흐름으로")
- 흐름(온도대마다 SKU 40개씩 한 묶음):
  1. 시험 입고 예정 `POST /dev-initial-stock-loads`(SKU 당 1호점 수량 — 최대 110, 한도 500 안).
  2. 현장 수령 `POST /initial-stock-loads/{receiptId}/receipts` — 수령 칸 RCV-A-01·RCV-C-01·RCV-F-01.
  3. 품질검사 `POST /inbound-tasks/{taskId}/inspections` — 1호점 LOT 마다 한 번(NORMAL, 1호점 소비기한·제조일, 표기 "R39-" + 1호점 표기). 부분 등록이라 LOT 마다 적치 작업이 하나씩 생긴다.
  4. 적치 `POST /inbound-tasks/{taskId}/putaways` — 1호점 LOT 이 있던 칸마다 그 수량.
- **하는 사람: 스크립트**(목포 관리자 `claude-mfc-sup-mk` 토큰). 가상 작업자가 하면 1호점 칸으로 정확히 놓게 만들 수 없다(작업자는 추천 칸에 놓음).
  - 작업에 담당(assigneeId)이 생기면 409 NOT_ASSIGNEE 로 멈춘다. **실행하는 동안 시뮬레이터의 목포 가상 작업자를 꺼 주세요.**
- 계획(11-22 LAN 읽기): 1호점 줄 1,334 → LOT 1,298, SKU 503, 수량 9,671. 묶음 14(상온 3,676·냉장 2,564·냉동 3,431), 품질검사 1,298번, 적치 1,324번.
  - 주문 취소 반납 대기 18줄(카트 CRT-A-01·인계 랙 DSP-A-01·02 위)은 적치가 선반 칸만 받아서 그 SKU 의 선반 칸에 합친다. 그래서 칸 분포는 이 18줄만 다르다. 상태도 STORED 로 들어간다.
  - 한 SKU 가 선반 두 칸 이상에 있는 경우는 0 이다(보충재고 칸 23줄 빼고). 한 상품 한 자리 규칙에 걸리지 않는다.
  - LOT ID·LOT 번호는 새로 생긴다(목포에서 처음 받은 LOT). 납입일은 실행 시각이다.
- 걸릴 수 있는 것(실패로 세고 계속한다): 칸 용량 초과(반납 대기를 합친 칸), 출하 기준 미달 LOT 을 카테고리 칸에 넣기(409 LOT_NOT_SHIPPABLE). 실패한 LOT 은 RCV 칸에 적치 전 상태로 남는다.
- 다시 돌려도 된다. 목포 재고(SKU·칸·소비기한별 정상·적치)와 비교해 모자란 것만 넣는다. `--max-loads 1` 로 한 묶음만 먼저 돌려 보기를 권한다.
- 실행(PC 1, 사용자 확인 뒤): 칸 복사(§2) 다음에 `python -I deploy/lan/seed/mfc_clone_center.py stock`(계획) → `stock --apply --max-loads 1` → `stock --apply`.
