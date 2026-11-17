# 회신(구현): PDA 재고 확인 상품명(IV-2)·작업자 오늘 업무 내역(F-7)

- 보낸 영역: mfc
- 작성: pmart-backend, 2026-11-17
- 답하는 것: pmart-clients `docs/handoff/mfc-app/2026-11-17-backend-pda-stock-check.md`(Q1~Q4), `docs/handoff/mfc-app/2026-11-17-backend-my-work-history.md`(R1~R4).
- 같은 요청에 PC 1 이 먼저 보낸 회신(권한 Q1·Q2, 전달 안내): `2026-11-17-reply-stock-check-and-work-history.md` — 이 파일은 PC 3 구현 회신.
- 계약: mfc-inventory 0.23.0·mfc-fulfillment 0.12.0·mfc-capacity 0.17.0(·inbound 0.10.0·quality 0.4.0 은 내부 조회만). 브랜치 `mfc/worker-history`(PR — #112·#116 위에 쌓음). LAN 반영은 PC 1 배포·설정 뒤.

## 재고 확인 (IV-2)

| # | 답 |
|---|---|
| Q1 | `GET /mfcs/{mfcId}/stock?sku=` 는 **그 센터 운영자면 역할 무관** — MFC_WORKER 자기 센터 토큰으로 된다(바꾼 것 없음, 계약 설명에 적음). 403 이면 다른 센터 |
| Q2 | `GET /barcodes/{barcode}` 는 product-service — PC 1 회신대로 직원 토큰이면 누구나(MFC_WORKER 포함) |
| Q3 | 칸으로 재고 찾기는 MB-1 그대로(아직). LOT 라벨 재출력용 `GET /mfcs/{mfcId}/lot-labels?locationCode=` 가 그 칸의 LOT·수량을 준다(보기만이면 이걸로도 된다) |
| Q4 | **`CenterStock.productName`·`barcodes`**(SKU 사본, 모르면 null·빈 배열) — 응답 맨 위 한 번(SKU 하나 조회라) |

## 오늘 업무 내역 (F-7)

| # | 답 |
|---|---|
| R1 | `GET /tasks?assignee=me&activityFrom=YYYY-MM-DD[&activityTo=]`(Asia/Seoul 날짜, 하나만 주면 그 하루, 최대 31일) — 그날 **시작·실적·끝**(COMPLETED·CLOSED_PARTIAL·CANCELLED 로 바뀜)이 있는 작업만. state 는 따로 그대로 거른다 |
| R2 | `TaskSummary.completedAt` — COMPLETED·CLOSED_PARTIAL 이 된 시각(그 밖 null) |
| R3 | **`GET /mfcs/{mfcId}/workers/me/activities?date=`**(mfc-capacity — 작업자 정본, 본인 또는 그 센터 MFC_SUPERVISOR, date 없으면 오늘). 네 정본의 내 처리를 모은다: fulfillment PICK·RETURN(실적)·PACK(포장), inbound RECEIVE(입고 수령)·INSPECT·PUTAWAY·MOVE(이동·보충 — 집어 올린 이동, 시각 = 마지막 변경)·RETURN_RECEIPT(회수품 반입), inventory COUNT·RECOUNT(실사 계수), quality QUARANTINE·RELEASE·DISPOSE·RETURN_INSPECTION. `items[]` = `{source, activityType, refId, taskId?, sku?, qty?, result, at}` 시각 순 |
| R4 | 같은 응답 `totals[]` = 정본·종류별 `count`·`qty`(REJECTED 는 건수만, 수량에서 뺌) — 앱이 더하지 않아도 된다 |

- **`unavailableSources`**: 조회가 안 된 정본 이름(주소 미설정·일시 실패). 비어 있지 않으면 "일부 내역을 못 불러왔어요"처럼 보여 주세요 — 빈 내역으로 보이지 않게.
- 피킹 실적 `result` 는 RECORDED(반영 대기)·APPLIED·REJECTED·UNKNOWN_RESULT 그대로. 수량은 반영되면 반영 수량, 아니면 보낸 집품 수량.
- 실사 계수 시각은 이번에 처음 기록한다(V22) — 그 전 실사는 마지막 변경 시각으로 채워져 하루가 어긋날 수 있다.

## 시험(PC 3, 2026-11-17)

- inventory check 단위 61·통합 31, inbound check 단위 5·통합 12, quality check 단위 14·통합 4, fulfillment check 단위 19·통합 27, capacity `WorkActivityServiceTest`(모으기·일부 실패·합계).
