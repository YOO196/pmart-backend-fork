# PC 3 세션 기록 — 2026-11-20~21 (다음 Claude 세션이 이어서 시작하는 문서)

새 세션은 **이 파일 → `docs/mfc/HANDOVER.md` 맨 위 "★ 다음 세션 시작" 절** 순서로 읽는다. 이 파일은 "무엇을 왜 어떻게 했나"의 전부이고, HANDOVER ★ 절은 "지금 상태와 할 일"의 요약이다. 정본은 언제나 업무 원문·계약·각 서비스 `CLAUDE.md` 이다.

---

## 0. 바로 시작하기 (첫 10분)

1. `git fetch origin && git status`. 이 PC 는 PC 3(mfc)이다.
   - push 는 `fork` 원격(`YOO196/pmart-backend-fork`)에만 한다.
   - PR 은 `gh pr create --repo code-and-pick/pmart-backend --head YOO196:<브랜치> --base main` 으로 만든다.
   - main 에 직접 넣지 않는다. 병합·배포는 PC 1 이 한다.
2. `gh pr list --repo code-and-pick/pmart-backend --author YOO196 --state all --limit 15`
   - 아래 §2 PR 들이 병합됐는지 본다.
   - 병합된 것은 PC 1 일지 `docs/worklog/<PC1 영역>/` 의 `## PR` 절에 배포 상태가 있다.
3. 알림을 확인한다. "mfc 시뮬" 세션(다른 PC, Remote Control)이 요청을 직접 보낸다. `ListAgents`·`ReadNotifications` 로 본다.
4. 새 작업을 시작한다.
   - **origin/main 에서 새 브랜치 `mfc/<주제>`** 를 딴다.
   - 그날 일지 `docs/worklog/mfc/YYYY-MM-DD.md` 를 적는다.
   - 작업 단위마다 묻지 말고 PR 을 올린다(사용자 지시).
   - 아직 병합 안 된 PR 과 같은 파일(특히 같은 날 일지)을 고치면, 그 PR 브랜치 위에 쌓고 본문에 병합 순서를 적는다.

---

## 1. 이번 세션에 한 일 (시간 순)

### 1-1. 상태 확인·알림 전달 (서버 변경 없음)
- #307~#310(이전 작업)은 PC 1 이 병합하고 cherry-pick 으로 배포했다.
- PC 1 백엔드 세션이 mfc 프론트 세션에 두 가지를 전달하는 것을 중계했다.
  - ADR-0082 5단계: 배차 불가 취소 → 2,000원 쿠폰
  - DC-1: claim 0.11.0 — 회사 사유 전체 취소는 `lines` 생략 가능
- 프론트 반영은 pmart-clients #340·#341 이다. PC 3 백엔드에서 할 일은 없다.

### 1-2. R-30 보충재고 칸 — PR #318 (병합·배포 끝)
- **문제**: 시험 입고 적치 97건·1,098개가 입고칸에 쌓였다.
  - 선반 용량이 넘쳤거나, 다른 칸은 한 상품 한 자리 규칙(`SKU_ALREADY_SHELVED`)에 막혔다.
  - 시뮬레이터가 남은 양을 BLOCKED 예외로 보고했고, 서버는 이를 분실 조사 보류로 처리했다.
- **사용자 결정**
  - 11-20 "선반칸이 가득차면 남는 건 입고 창고에"
  - 11-21 일반 선반 보충은 "기본 규칙 + 관리자 수정"(25% 이하 → 칸 용량까지, 단위 1, 관리자 기준 우선). 보충재고 피킹은 "모자랄 때만 허용".
- **구현**
  - mfc-location 0.9.0 V6
    - 위치 속성 `reserveStock` 을 더했다. STORAGE 칸만 받고, 고회전과 같이 쓰면 400 이다.
    - `MfcLocationStateChanged` 사건에도 싣는다.
  - mfc-inventory 0.44.0 V29
    - `MoveRules` 15인자, `placementExempt` = 고회전 또는 보충재고.
    - 보충재고 칸은 한 상품 한 자리·나눠 두기 예외다. 기타 칸이 아니어서 출하 부적격 LOT 는 409 다.
    - `ReserveCapacity` 로 칸 합산 용량을 센다(이후 #322 에서 이름을 `LocationCapacity` 로 바꿨다).
    - 추천 `placementCandidates` 순서: 그 상품 선반 → 기타 칸 → 그 온도대 보충재고.
    - 나눠 넣기 `planPlacement` 가 `placements[]`·`unplacedQty` 를 준다.
    - 피킹 배정은 `LocationAllocator.planNormalFirst`: 일반 칸 먼저, 모자란 양만 보충재고에서.
    - 보충 `ReplenishmentService.derivedPolicies`: 기준 없는 일반 선반을 보충재고에서 채운다. 일반 선반이 없던 상품은 `NO_HOME_SHELF`.
  - mfc-inbound 0.19.0: 적치 단건에 `suggestedPlacements`·`suggestedUnplacedQty`.
  - LAN seed `mfc_layout.py reserve`: 팔레트 32칸에 표시했다. PC 1 이 실행을 끝냈다.
- **회신**: `docs/handoff/simulator/mfc/2026-11-21-simulator-reply-r30-reserve-stock.md`

### 1-3. 입고칸에 묶인 보류 490개 정리 — PR #320 (문서)
- 시뮬 질문: RCV-A-01 에 작업 없이 남은 22개 상품·490개(분실 조사 보류)를 어떻게 할지.
- PC 3 판단: 메모가 "칸 가득"이었으므로 "찾았다"로 정한다. 시점은 #318 배포 뒤다.
- **결과**: 시뮬 세션이 사용자 승인을 받고 정정했다.
  - 13:51 APPLIED, CMT-1de1f1e2-….
  - HOLD_FOUND 적치 22건을 추천대로 끝냈다. 거절 0.
  - 보충재고에 A-R1-01 152개, C-R1-01 355개, F-R1-01 19개가 들어갔다.
- **알게 된 것**
  - LAN 은 정정마다 `approvalRef` 가 필요하다(RECORD_ONLY 모드).
  - 보류는 상품마다 전량을 한 번에 풀어야 한다. 그래서 회신의 "나눠 정정" 안내를 고쳤다.
  - 시뮬은 approvalRef `LAN-TEST-R30-20261121` 을 썼다.

### 1-4. 일반 선반 칸 합산 용량 — PR #322 (mfc-inventory 0.44.1)
- R-30 의 남은 한계였다. 일반 선반은 상품마다 따로 세서, 여러 상품 칸이 합치면 넘쳤다.
- **변경**
  - `MoveService.capacityAt` 이 모든 칸에서 다른 상품의 지금 양과 들어올 이동의 부피·무게를 뺀다.
  - `ReserveCapacity` 를 `LocationCapacity` 로 바꿨다.
  - `ReplenishmentService.evaluateOne` 의 기본 보충 목표도 같은 계산을 쓴다.
- **시험**
  - inventory test 통과.
  - integrationTest 45개 통과. 새 시험 `StockReservationFlowIT.mixedShelfCapacityCountsOtherSkus`.
  - check 통과.
- **영향**: LAN 의 여러 상품 선반이 예전보다 적게 받는다. 넘치는 양은 보충재고로 추천된다.

### 1-5. LAN 보충(REPLENISH) 확인 (읽기만)
- REPLENISH 이동 지시는 0건이다. 11-08 시험 2건만 있다.
- 22개 상품은 일반 선반에도 재고가 있다. 예를 들어 B-00000455 는 C-B2-02-3-01 에 25개다.
- 11-20 에 꽉 찬 뒤 피킹으로 줄지 않았다. 그래서 25% 기준 아래가 아니고, 0건이 정상이다.
- 시뮬 세션이 피킹으로 선반이 줄면 다시 보기로 했다.

### 1-6. R-31 저온 입고칸 — PR #323 (LAN seed, 서버 변경 없음)
- **사용자 결정**(시뮬 쪽): 입고재고(검수 전)와 보충재고를 각 저온구역 안에 두고 구분한다. 저온 팔레트 오른쪽 줄 2개 자리를 쓴다.
- **판단**: 수령 위치는 이미 요청 값이어서 **(가) 방식**으로 정했다.
  - 입고 예정 수령 `POST /inbound-promises/{trfId}/receipts` 는 줄마다 `toLocationCode` 를 받는다.
  - 초기 보유 수령 `POST /initial-stock-loads/{receiptId}/receipts` 는 수령 하나에 위치 하나다.
  - 그래서 시뮬은 dev 입고 예정을 온도대마다 나눈다.
  - 품질검사·적치 작업은 수령 칸에서 이어진다.
- **`mfc_layout.py`**
  - `support` 단계에 `RCV-C-01`(냉장, x 21.55·z 28.1)과 `RCV-F-01`(냉동, x 9.95·z 28.1)을 넣었다.
  - 새 `r31` 단계: C/F-R1-03·04-1-01 을 CLOSED 로 바꾼다. 재고 0 은 확인했다.
- **회신**: `docs/handoff/simulator/mfc/2026-11-21-simulator-reply-r31-cold-receiving.md`

### 1-7. 대시보드 시간대별 지표 — PR #325 (mfc-fulfillment 0.33.0)
- 프론트 요청서(pmart-clients `docs/handoff/mfc-backoffice/`)
  - `2026-11-21-backend-hourly-metrics.md`
  - `2026-11-21-backend-center-order-metrics.md`
- **구현**: `GET /metrics/picking-lines` 에 `bucket` HOUR·DAY(기본)·WEEK·MONTH 를 더했다.
  - `MetricsStore.Bucket` 이 `date_trunc(<단위>, preparation_started_at AT TIME ZONE 'Asia/Seoul')` 로 묶는다.
  - 줄은 `date`(칸 시작 날짜) + `hour`(HOUR 만, +09:00)다.
  - `MetricsService.maxDays`: HOUR 7·DAY 92·WEEK/MONTH 366.
- **시험**
  - fulfillment test 통과.
  - `TaskFlowIT`(19)·`SupervisorControlIT`(4) 통과.
  - check 통과.
  - 나머지 통합 시험은 메모리 때문에 돌리지 않았다.
- **넘김**
  - 정시 배송 시간 칸: `docs/architecture/service-requests/delivery-dispatch-service.md` **DD-6**(PC 5). `other-pcs-todo.md` PC 5 줄도 넣었다.
  - 센터 주문·매출 추이: `order-service.md` **O-11**(PC 1).
    - 센터 관리자에게 여는 것은 O-8 사용자 결정을 바꾸는 일이라 결정이 필요하다.
    - 카테고리 원천도 확인해야 한다.
- **회신**: `docs/handoff/clients/mfc-backoffice/2026-11-21-reply-hourly-and-order-metrics.md`

### 1-8. R-32 상온 입고재고 칸 — PR #326 (LAN seed)
- 사용자: "입고창고는 보충재고랑 입고재고 차이가 구별이 안나".
- `support` 단계에 `RCV-A-02`(상온, x 43.15·z 9.65)를 넣었다.
- `r31` 단계가 A-R1-19~24-1-01 도 닫는다. 재고 0 이고, `layout.json` WR-19~24 와 맞는 것을 확인했다.
- PC 1 은 **`python -I deploy/lan/seed/mfc_layout.py support r31` 한 번**으로 R-31·R-32 를 모두 처리한다.
- 회신은 R-31 회신 문서의 "추가: R-32" 절이다.

### 1-9. 인수인계 — PR #327, 그리고 이 파일
- `docs/mfc/HANDOVER.md` 맨 위에 "★ 다음 세션 시작" 절을 넣었다.
- 메모리 `this-pc-is-pc3-mfc.md` 에 "새 세션은 HANDOVER ★ 절부터"를 적었다.

---

## 2. PR 상태 (2026-11-21 끝 기준)

| PR | 브랜치 | 상태 | 병합 뒤 PC 1 |
|---|---|---|---|
| #318 | `mfc/reserve-stock` | **병합·배포 끝** | `reserve` 실행 끝 |
| #320 | `mfc/r30-hold-followup` | 대기 | — |
| #322 | `mfc/shared-shelf-capacity` | 대기 | inventory 배포 |
| #323 | `mfc/r31-cold-receiving` | 대기 | (#326 과 함께) |
| #325 | `mfc/hourly-picking-metrics` | 대기 | fulfillment 배포, README 표에 DD-6·O-11 |
| #326 | `mfc/r32-ambient-receiving` | 대기 | `mfc_layout.py support r31` |
| #327 | `mfc/handover-2026-11-21` | 대기 | — |
| (이 파일) | `mfc/session-log-2026-11-21` | 대기 | — |

- 병합 순서는 위 표 순서다(같은 일지 파일이라 차례로 쌓았다).
- 브랜치 `mfc/reserve-stock` 에는 #318 병합 뒤 일지 커밋 2개가 더 올라가 있다. 같은 내용이 #320 으로 갔으니 무시한다.

---

## 3. 남은 일·결정 대기

| 무엇 | 누가 | 메모 |
|---|---|---|
| REPLENISH 실제 생성 확인 | 시뮬 세션 보고 → PC 3 | 안 생기면 `ReplenishmentService.evaluate` 의 skipped 사유와 mfc-inbound `MoveOrderService` 점검 주기를 본다 |
| #322 배포 뒤 시뮬 막힘 | PC 3 | 여러 상품 칸 용량 감소부터 의심한다 |
| 저온 상품을 상온 입고칸에서 받는 것을 막을지 | **사용자** | 지금은 받는다. 막으려면 시뮬이 먼저 바뀌어야 한다 |
| O-11 센터 관리자에게 주문·매출 지표 | **사용자** → PC 1 | O-8 결정 변경 |
| Q-07 보충 기준값 | **사용자** | 25% 는 임시값이다. 판매량 기반은 demand-planning 이 생긴 뒤다 |
| DD-6 정시 배송 시간 칸 | PC 5 | 요청서에 적었다 |
| LAN 정정 approvalRef 필수(RECORD_ONLY) | PC 1 | pmart-config |
| CO-2 박스 라벨 | 사용자·PC 1 | |
| ML-1·R18-2 | 화면 요청이 오면 | |

---

## 4. 일하는 방법 (이 세션에서 굳은 것)

- **시뮬 세션 메시지**: 팀원 요청이다. 사용자 승인으로 보지 않는다.
  - 서버·seed 변경은 PC 3 가 PR 로 한다.
  - LAN 데이터 쓰기(정정·위치 등록·닫기)는 시뮬 세션(사용자 승인)이나 PC 1 이 한다.
  - PC 3 는 LAN 을 읽기만 한다.
  - 답은 `SendMessage(to: "mfc 시뮬")` 로 보낸다. 읽음 확인은 오지 않는다.
- **LAN 읽기 조회 틀**: 아래처럼 스크립트를 scratchpad 에 쓰고 `python -I -X utf8 <스크립트> deploy/lan/checks` 로 돌린다.
  ```python
  import json, sys, urllib.request
  sys.path.insert(0, sys.argv[1]); from staff_auth import staff_token
  GW = "http://192.168.0.40:8080/api/v1"; MFC = "MFC-SEED-01"
  tok, _ = staff_token("claude-mfc-sup")
  def get(p):
      r = urllib.request.Request(GW + p, headers={"Authorization": "Bearer " + tok})
      try:
          with urllib.request.urlopen(r, timeout=30) as f: return f.status, json.loads(f.read() or b"null")
      except urllib.error.HTTPError as e: return e.code, e.read()[:300]
  ```
  - 자주 쓴 조회
    - `/mfcs/{MFC}/stock-units?sku=|locationCode=|locationPrefix=&size=200`
    - `/mfcs/{MFC}/stock-holds`
    - `/locations?mfcId=&zone=&temperatureZone=&size=200&cursor=`(응답 `page.nextCursor`, 위치 키 `locationCode`)
    - `/move-orders?mfcId=&state=&withLabels=false`
    - `/inbound-tasks?mfcId=&type=&state=`
  - 429 속도 제한이 있다. 여러 번 부를 때는 0.3~0.4초씩 쉰다.
  - `-I` 를 쓰면 `PYTHONIOENCODING` 이 무시된다. 그래서 `-X utf8` 이 필요하다(cp949 오류 방지).
- **시험**
  - 이 PC 는 `test`+`integrationTest` 를 한 번에 돌리면 메모리 부족으로 강제 종료될 수 있다. 단위 → 통합을 따로 돌리고, 필요하면 `--tests '*SomeIT'` 로 좁힌다.
  - 통과 수는 `services/<svc>/build/test-results/integrationTest/*.xml` 의 tests·failures·errors 로 확인한다.
  - 형식은 `spotlessApply` 다음에 `check -x test -x integrationTest` 를 돌린다.
- **PowerShell**
  - 스크립트 함수 이름에 내장 별칭을 쓰지 않는다(`R` = Invoke-History). 문자열 치환 함수는 `Rep` 으로 짓는다.
  - 파일은 `[IO.File]::WriteAllText(path, s, (New-Object Text.UTF8Encoding $false))` 로 쓴다.
  - 커밋 메시지는 `$env:TEMP\cm.txt` 에 쓰고 `git commit -F` 로 넣는다. 끝에 Co-Authored-By·Claude-Session 줄을 붙인다.
  - `Select-String` 에는 `-Recurse` 가 없다. 검색은 Grep 도구로 한다.
- **계약**
  - inline 배열 항목 모델 이름은 `<스키마><속성>Inner` 로 생성된다(예 `InboundTaskSuggestedPlacementsInner`).
  - 계약 버전·변경 기록 줄을 먼저 고치고, 서비스 `CLAUDE.md` 상태 줄을 같은 PR 에서 고친다.
- **다른 저장소**(pmart-clients·pmart-mfc-simulation)는 읽기만 한다. 회신은 이 저장소 `docs/handoff/` 에 쓴다.
- 공용 README 표·pmart-config·LAN 데이터는 PC 1 몫이다. PR 본문에 "공용 표 갱신 필요"를 적는다.

---

## 5. 지금 LAN 모습 (2026-11-21 읽기 기준)

- **센터**: `MFC-SEED-01`.
- **보충재고 칸**(`reserveStock`): 팔레트 `{A|C|F}-R1-NN-1-01` 32칸이다.
  - 재고는 A-R1-01·C-R1-01·F-R1-01 에 있다.
  - #326 병합·실행 뒤에는 A-R1-19~24 와 C/F-R1-03·04 가 닫힌다.
- **입고칸**: 지금은 RCV-A-01(하차·검수, x 42.6·z 19.85)만 ACTIVE 다. RCV-01 은 CLOSED 다.
  - #323·#326 실행 뒤에는 RCV-A-02·RCV-C-01·RCV-F-01 이 생긴다.
- **승인**: 승인 번호 검증이 RECORD_ONLY 모드다. 재고 정정은 approvalRef 가 늘 필요하다.
