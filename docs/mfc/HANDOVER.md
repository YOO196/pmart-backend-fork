# MFC 인수인계 (2026-11-13, 갱신 2026-11-21)

mfc 영역(`docs/operations/pc-split.md` §1 — PC 3)을 받는 사람·Claude 가 **처음 읽는 문서**. 지금까지 PC 1 에서 한 MFC 작업의 상태·결정·남은 일을 한곳에 모았다(그동안은 PC 1 Claude 메모에만 있던 것이 많다). 세부는 링크를 따라간다. 이 문서가 다른 문서와 다르면 **업무 원문·ADR·계약이 정본**이다.

## ★ 다음 세션 시작 (2026-11-21 PC 3 — 최신, 여기부터)

### 읽는 순서
0. **`docs/mfc/session-log-2026-11-21.md`** — 11-20~21 세션에서 한 일 전부(무엇·왜·어떻게·PR·LAN 모습·일하는 방법).
1. 이 절 전체 → 아래 §0(규칙).
2. `docs/worklog/mfc/2026-11-21.md` → `2026-11-20.md`. 최근 일, 결정, 시험 결과가 있다.
3. 시뮬레이터 회신 두 개(지금 진행 중인 일의 정본)
   - `docs/handoff/simulator/mfc/2026-11-21-simulator-reply-r30-reserve-stock.md`(보충재고 칸)
   - `docs/handoff/simulator/mfc/2026-11-21-simulator-reply-r31-cold-receiving.md`(입고칸, R-32 포함)
4. 손댈 서비스의 `services/<서비스>/CLAUDE.md` "상태" 끝 줄들. 최근 것은 inventory 0.44.x, inbound 0.19.0, location 0.9.0, fulfillment 0.34.0 이다(0.33.0 은 PC 1 작업자 도면 위치).
5. 프론트 요청을 받으면 `pmart-clients/docs/handoff/mfc-backoffice/`(읽기만)를 읽는다. 회신은 이 저장소 `docs/handoff/clients/mfc-backoffice/` 에 쓴다.

### PR — 11-21 저녁 PC 1 이 모두 병합·배포 끝(아래 표는 병합 순서 기록. #325 는 계약 0.33.0 이 main 과 겹쳐 **0.34.0** 으로 들어감)
| PR | 브랜치 | 내용 | 병합 뒤 PC 1 할 일 |
|---|---|---|---|
| #320 | `mfc/r30-hold-followup` | R-30 입고칸 보류 정정 결과, 회신 "나눠 정정" 안내 고침(문서) | — |
| #322 | `mfc/shared-shelf-capacity` | inventory 0.44.1: 일반 선반도 칸 합산 용량(`LocationCapacity`) | 배포 |
| #323 | `mfc/r31-cold-receiving` | R-31 저온 입고칸 LAN seed(코드 변경 없음) | — (#326 과 함께 실행) |
| #325 | `mfc/hourly-picking-metrics` | fulfillment 0.34.0(PR 에선 0.33.0): 결품률 지표 `bucket` HOUR·DAY·WEEK·MONTH, DD-6·O-11 요청 | 배포, 공용 표에 DD-6·O-11 줄 |
| #326 | `mfc/r32-ambient-receiving` | R-32 RCV-A-02 LAN seed(`r31` 단계에 묶음) | `python -I deploy/lan/seed/mfc_layout.py support r31` |
- 앞의 PR 은 병합이 끝났고 LAN 에 배포됐다: #318 R-30 보충재고 칸(location 0.9.0, inventory 0.44.0, inbound 0.19.0, LAN `reserve` 표시 끝).
- 새 작업은 **origin/main 에서 새 브랜치**로 시작한다. 단, 아직 병합 안 된 PR 과 같은 파일(특히 그날 일지)을 고치면 그 PR 브랜치 위에 쌓고, PR 본문에 병합 순서를 적는다.
- 확인: `gh pr list --repo code-and-pick/pmart-backend --author YOO196`. push 는 `fork` 원격(YOO196/pmart-backend-fork)이다. `origin` 에는 push 하지 않는다.

### 남은 일
- **보충(REPLENISH) 실제 생성 확인**(R-30)
  - 11-21 LAN 은 0건이다. 22개 상품의 일반 선반이 아직 가득 차 있어서 정상이다(예: B-00000455 는 C-B2-02-3-01 에 25개).
  - 피킹으로 선반이 칸 용량 25% 이하가 되면 1분 안에 생겨야 한다. 시뮬 세션이 다시 보기로 했다.
  - 안 생기면 이 순서로 본다: `ReplenishmentService.derivedPolicies`·`evaluateOne`(skipped 사유), mfc-inbound `MoveOrderService` 점검 주기.
- **#322 배포 뒤**: 여러 상품이 있는 LAN 선반은 예전보다 적게 받는다(LOCATION_CAPACITY_EXCEEDED). 넘치는 양은 추천에서 보충재고로 간다. 시뮬 세션이 막힘을 보고하면 이것부터 의심한다.
- **결정 대기(사용자)**
  - 저온 상품을 상온 입고칸에서 받는 것을 막을지. 지금은 받는다. 막는다면 시뮬레이터가 먼저 바뀌어야 한다.
  - O-11: 주문·매출 지표를 센터 관리자에게 열지. O-8 결정을 바꾸는 일이고, order-service 라 PC 1 이 한다.
  - Q-07: 보충 기준값. 25% 는 임시 기본값이다.
- **넘긴 것(대기)**
  - DD-6 정시 배송 시간 칸: PC 5.
  - O-11 주문·매출 추이: PC 1.
  - LAN 정정에 늘 approvalRef 가 필요한 것(RECORD_ONLY, pmart-config): PC 1.
  - CO-2 박스 라벨: 사용자 결정, PC 1.
- **화면 요청이 오면 할 것**: ML-1(실사 계획 잠금 칸 표시), R18-2(격리 사유·사진 다시 보이기).
- 시뮬레이터 요청 기록은 `pmart-mfc-simulation/docs/backend-requests.md`(읽기만). R-30·R-31·R-32 는 끝났다.
- **R-33 보충재고 랙 칸 990개**(11-21 밤, 브랜치 `mfc/r33-reserve-racks`): PR #330(병합·실행 끝, 랙 990 + A-R2 20) 다음에 **2차 정정**(시뮬레이터 cb0e730 — 지게차 배치)이 있다. 브랜치 `mfc/r33-forklift-layout` 이고, 내용은 랙 900·A-R2 16, 빠진 94칸 닫기, RCV-A-02 x 44.0·z 6.05, QRT-A-01 z 14.9 다. #331 도 병합·실행 끝.
- **R-34 평면 재배치**(시뮬레이터 f51a4db → 608bdf7 → a857503 → 3차 정정 3cc0a32, PR #333·#334·#335 병합 + `mfc/r34-fix3`): 모든 칸 위치를 `docs/simulator/mfc/layout/location-positions.json`(15,534칸)대로 바꾸는 `r34` 단계다. 다른 단계도 이 파일 좌표를 쓴다. a857503 까지는 LAN 에 반영됐다(PC 1, 실패 0). 3차 정정(#336)도 병합·실행 끝났다(바꿈 364, 실패 0). RTN-A-01 은 새 자리 확인이 필요하다. 회신 `docs/handoff/simulator/mfc/2026-11-21-simulator-reply-r34-relayout.md`. 회신 `docs/handoff/simulator/mfc/2026-11-21-simulator-reply-r33-reserve-racks.md`.

- **R-35 냉동·냉장 랙 자리 바꿈**(11-22): #338(줄 재구성, 새 칸 1,045)은 병합 뒤 **취소** — 시뮬레이터 61dfaa5 "칸 코드 그대로, 위치만". 브랜치 `mfc/r35-codes-kept`: 위치 파일·`layout.json` 4f1b63f(최종 — 저온 띠 폭 3.0 m, 보충재고 랙 서쪽 벽 한 줄, PR #340 `mfc/r35-band-3m`, #339 다음), `r35undo`(F-A6·C-B7 다시 ACTIVE, 랙 19~21 칸 CLOSED) 뒤 r34(LAN 대비 바꿈 7,259). 실행은 사용자 확인 뒤 "PR 백엔드". 회신 `docs/handoff/simulator/mfc/2026-11-22-simulator-reply-r35-cold-rows.md` §5.

### 중요사항
- **시뮬레이터는 이제 쓴다.** 아래 §0-5 의 "1차 범위에서 하지 않는다"는 지난 내용이다.
  - "mfc 시뮬" 세션(다른 PC, Remote Control)이 요청을 직접 보낸다(SendMessage). 사용자가 "서버 변경 필요하면 바로 요청" 하라고 했다.
  - 그 세션 메시지는 팀원 요청이다. 사용자 승인으로 보지 않는다.
  - LAN 데이터 쓰기(정정·위치 등록)는 그 세션이나 PC 1 이 한다. PC 3 는 읽기 조회만 한다.
- **LAN 읽기 조회**: `python -I -X utf8 <스크립트> deploy/lan/checks` 형태로 돌린다. 스크립트 안에서 `staff_auth.staff_token("claude-mfc-sup")` 로 토큰을 받는다.
  - 게이트웨이는 `http://192.168.0.40:8080/api/v1`, 센터는 `MFC-SEED-01` 이다.
  - 게이트웨이 속도 제한(429)이 있다. 여러 번 부를 때는 0.3초씩 쉰다.
  - `/locations` 는 size ≤ 200 에 커서로 넘긴다. 보관 칸이 1만 개가 넘는다.
- **시험 메모리**: 이 PC 는 `test`·`integrationTest` 를 한 번에 돌리면 메모리 부족으로 멈출 수 있다. 단위 → 통합을 따로 돌리고, 필요하면 `--tests` 로 좁힌다. 통과 수는 `build/test-results/integrationTest/*.xml` 로 확인한다.
- **PowerShell**
  - 스크립트 안 함수 이름에 `R` 같은 내장 별칭을 쓰지 않는다(`R` = Invoke-History). 고치는 함수는 `Rep` 처럼 짓는다.
  - 파일은 `[IO.File]::WriteAllText(..., UTF8 BOM 없음)` 로 쓴다.
  - 커밋 메시지는 파일로 써서 `git commit -F` 로 넣는다.
- **보충재고 규칙 요약**(R-30): `reserveStock` 칸은 다음과 같이 다룬다.
  - 한 상품 한 자리 규칙에서 빠진다. 기타 칸이 아니다(출하 부적격 LOT 409).
  - 용량은 칸 합산으로 센다. #322 부터 일반 선반도 합산이다.
  - 적치 추천은 그 상품 선반 → 기타 칸 → 그 온도대 보충재고 순이다.
  - R-33 부터 보충재고는 팔레트가 아니라 랙 칸이다(상온 36·냉동 12·냉장 12랙 × 5단 × 3칸 = 900칸, 375×450×420 mm — 2차 정정 뒤). 코드 `{A|C|F}-R1-{랙}-{단}-{칸}`. 상온에는 대형 묶음 바닥 팔레트 `A-R2-01~16-1-01`(보충재고, 1100×1100×1500, 북쪽 벽 2겹 × 8열 — 한 열에 한 상품)도 있다. 배치는 안쪽부터 대형 묶음 → 입고재고 → 문 쪽 보충재고, 지게차 통로 3 m 다.
  - 피킹은 모자랄 때만 보충재고에서 집는다.
  - 기준 없는 일반 선반은 25% 이하일 때 칸 용량까지 보충한다.
- **입고칸**
  - RCV-A-01: 하차·검수 자리.
  - RCV-A-02: 상온 입고재고(팔레트 5개, R-33 2차 정정 x 44.0·z 6.05).
  - RCV-C-01·RCV-F-01: 저온 입고재고.
  - 수령 위치는 요청 값이다. 입고 예정 수령은 줄마다, 초기 보유 수령은 수령마다 받는다. 품질검사·적치 작업은 그 칸에서 이어진다.

## 0. 시작 전에

> **2026-11-17 추가 — MFC 센터 관리자 웹(`web/mfc-backoffice`)도 PC 3**(사용자 "MFC 백오피스를 PC2에서 PC3로"). PC 2 가 11-16~17 에 메뉴 11개 화면을 만들었다. 시작: pmart-clients `docs/apps/mfc-backoffice-web.md`, 요청·공지 `docs/handoff/clients/mfc-backoffice/`. 로그인·세션 코드는 PC 1 만, 웹 공통(`web/core`·`web/ui`)은 PC 2 에 요청. LAN `https://192.168.0.40:8445`(올리기는 PC 1 `lan.sh publish-mfc-backoffice`).


1. `docs/operations/pc-split.md`(영역·일지·handoff 규칙), `docs/operations/dev-on-other-pcs.md`(직원 토큰·시험 계정·비밀번호 파일)를 읽는다.
2. 작업 일지는 `docs/worklog/mfc/YYYY-MM-DD.md`, 브랜치 `mfc/<주제>`. **main 에 직접 넣지 않는다 — PR 만, PC 1 이 검토 뒤 병합·배포.** 배포·`.secrets` 원본·Jib push 는 PC 1.
3. 저장소 위치는 어디든 된다(드라이브 무관). 다만 **`pmart-backend`·`pmart-clients`·`pmart-mfc-simulation` 을 같은 부모 폴더의 형제로** 둔다 — 시뮬레이터·클라이언트 문서·도구가 `../pmart-…` 를 가정한다.
4. **OR-Tools 시험**: PC 3 은 Windows 애플리케이션 제어 정책이 OR-Tools 네이티브 DLL 을 막는다 → `tools/ortools-test-docker.sh` 로 Docker 안에서 돌린다(2026-11-12 PC 3 37/37 확인. SSH 로 할 때는 `DOCKER_CONFIG=~/.docker-pmart` + Docker Desktop `resources/bin` 경로, PC 앞에서는 그냥 됨). 그 밖의 시험은 그대로 `./gradlew :services:<mfc 서비스>:check`.
5. (**지난 내용 — 2026-11-20 부터 시뮬레이터 연동 중, 맨 위 ★ 절**) **시뮬레이터는 1차 범위에서 하지 않는다**(사용자 2026-11-13 "1차적으로 지금당장 mfc 작업할때 시뮬레이션은 안할 예정"). 백엔드 서비스·계약·시험(`check`·`mfc_e2e.py`)으로 확인한다. `pmart-mfc-simulation` 저장소는 지금은 받지 않아도 된다. 시뮬레이터에 맞춘 것(아래 §3 시험 데이터 평면도, 작업 시간 처리 값)은 그대로 쓰되 시뮬레이터 쪽 요청·handoff(`docs/handoff/simulator/`)는 새로 만들지 않는다 — 다시 할 때 사용자가 정한다.

## 1. 서비스 6개 (모두 LAN PC 3 에서 돈다)

| 서비스 | 맡는 것 | 규칙 문서 |
|---|---|---|
| mfc-inventory-service | 재고 원장(로케이션·SKU·LOT·품질·작업 단위), 예약(FEFO·LOT 단위), 입고 검수·적치, 피킹·포장 이동, 결품 보고·재확보, 실사·조정, 품질 전기, 스냅샷 | `services/mfc-inventory-service/CLAUDE.md` |
| mfc-fulfillment-service | 작업 지시(작업·포장·출고), PICK·PACK 실행, 밀봉 → 출고 준비(READY), 배차 요청, 인계(펜스·QR), 전체 중지·복귀, 재포장 | `services/mfc-fulfillment-service/CLAUDE.md` |
| mfc-capacity-service | 작업자·업무·자격·근무·일일크루, 배정(수동·SYSTEM "다음 작업 받기"·OR-Tools 배정 계획 CP-SAT), 피킹 동선(OR-Tools Routing), 처리량·운영 제한, 휴식·작업 내려놓기·지연/무활동 표시 | `services/mfc-capacity-service/CLAUDE.md` |
| mfc-location-service | 로케이션 마스터(층·구역·온도대·좌표·크기, 계층 코드), 거리 계산 | `services/mfc-location-service/CLAUDE.md` |
| mfc-quality-service | 격리·해제 승인·폐기, 반품 검수 | `services/mfc-quality-service/CLAUDE.md` |
| mfc-inbound-service | 입고 예정(INITIAL_STOCK 승인 = 초기 재고), 현장 입고·검수·적치 작업, 공급 요청 | `services/mfc-inbound-service/CLAUDE.md` |

- 업무 원문 `docs/business/MFC-business-logic.md`(IV-01~13·WK-01~14), 책임 경계 `docs/architecture/service-map.md` §5, 상태 `contracts/states/mfc-states.md`, 계약 `contracts/openapi/mfc-*.yaml`.
- 주요 ADR: 0040(재고 모델 v2) · 0041(상품별 소비기한 보장) · 0042(LOT 대체·홀드 TTL·로케이션 코드) · 0043(피킹·포장) · 0044(결품 홀드·재확보) · 0045·0049(결품 = 항상 부분 취소·환불) · 0046(피킹 뒤 취소·복귀) · 0047(포장 인계 펜스) · 0048(품질·실사·운영 제한) · 0050(OR-Tools 배정·동선, JGraphT 안 씀) · 0053(OR-Tools 서비스는 JVM — Native 금지).

## 2. 지금 상태 (LAN, 2026-11-10 기준 + 이후, 2026-11-16 갱신)

- 6개 서비스 업무 원문대로 구현·배포(2026-11-07~09 슬라이스 6a·6b·6c, IV-08·09, 밤샘 작업 — 반품 재포장·실사·품질 격리/폐기·운영 제한·공급 요청 등).
- **LAN 끝까지 시험**: `deploy/lan/checks/mfc_e2e.py`(66/66, 11-09) — 예약 → 확정 → 작업 접수 → PICK 배정·동선(ROUTING)·피킹 → PACK → 배차 요청 → 밀봉 READY → 인계 → 전체 중지 → 복귀(RETURN) → 재고 원복 → 이동 지시·다음 작업 받기(MOVE) → 배정 계획·입력. 2026-11-13 부터 직원 토큰은 `staff_auth.py`(시험 계정 `claude-mfc-sup`·`claude-mfc-wrk`, 센터 `MFC-SEED-01`).
- **첫 실주문(11-10)**: 고객 앱 실주문 → 결제 → 작업 → 피킹·포장 → 밀봉 → **출고 준비(READY)에서 멈춤** — 배송(delivery-dispatch)이 `DispatchRequested` 를 아직 받지 않는다(delivery 영역 DD-1).
- 추적: 6개 서비스 HTTP·Kafka 가 한 traceId 로 이어짐(Zipkin 192.168.0.40:9411, 약 2일 보관). 서비스 **안의** 구간(span)은 없다 — §5.
- 시험 데이터: `MFC-SEED-01` 를 시뮬레이터 임시 평면도대로 다시 등록(11-09, `deploy/lan/seed/mfc_layout.py` — 선반 칸 6,762·팔레트 32, 재고 8,418). 시드 원본 `deploy/lan/seed/Seed.java`(상품·가격·권역·초기 재고).
- **2026-11-16 바뀐 것(PC 1, 사용자 결정)**:
  - 칸 구조를 MFC 문서 설계로 — 선반 14,559칸(냉동 5×6, 냉장 5×7, 상온 C9·D10·E8·F10·G9, H 5단×5), 재고 444종 랙 전체로 펼침, 상온 28종 H 로, 냉장·냉동 31종은 칸 용량 초과로 그대로(결정 대기). `docs/simulator/mfc/layout/README.md` '2026-11-16 칸 구조'.
  - 상품 실물 치수(추정) → 칸 용량 계산(product 0.2.0, mfc-inventory 0.18.0 V20).
  - 바코드: 503개 SKU 에 299 대역 EAN-13(승인 경로), 검사 숫자 틀린 7개 교체. 설명 `deploy/lan/seed/labels/README.md`.
  - **PDA 피킹 스캔**: mfc-fulfillment 0.7.0 — 작업 상세 줄에 `barcodes[]`·`barcode` 채움(사용자 허락으로 PC 1 수정, LAN 배포). 전에는 늘 null 이라 모든 스캔이 "다른 상품".
  - LOT 날짜: 비식품(문구·생활용품) LOT 소비기한 제거, 출고 불가 식품 LOT 소비기한 다시 매김(`deploy/lan/seed/mfc_fix_lot_dates.py`, approvalRef lot-dates-2026-11-16). 옛 시험 주문 예약에 걸린 25 LOT·68개는 그대로.
  - 위치 라벨 14,608곳·카트 4장 `deploy/lan/seed/labels/`. 카트가 4대뿐이라 동시 피킹 4명 — 늘릴지 결정 필요.

## 3. 결정 — 어디에 적혀 있나

| 무엇 | 위치 |
|---|---|
| **결정 대기 목록(한곳)** | `docs/business/flows/pending-user-decisions.md` — 결정되면 여기에 옮겨 적는다 |
| 내가 정한 기본값(사용자 검토 대기) | `docs/business/flows/mfc-defaults-status.md`, 일지 2026-11-08 "사용자 확인 없이 정한 것"(#1~81) |
| 포장 인계 결정 D-1~D-9 | `docs/business/flows/package-handover-decisions.md`, 계약 `package-handover-contract.md` |
| OR-Tools 쓰는 곳·진행 | `docs/architecture/or-tools-usage.md` |
| 백오피스 역할별 메뉴(임시 기본값) | `docs/architecture/backoffice-role-menus.md` |

사용자가 정한 주요 규칙(요약 — 정본은 위 문서):
- 결품 = 항상 부분 취소·환불(남은 주문 전체가 결품이면 전체 취소). 다른 LOT 대체 허용(같은 SKU·소비기한 적격). 홀드 TTL 10분(설정).
- 작업자: 센터 관리자가 등록, **로그인 정보와 업무는 분리**(업무·자격은 이력 남는 별도 기록). 모든 현장 작업자(정규·알바·일일)는 시스템이 하나씩 배정("다음 작업 받기"), 관리자는 덮어쓰기만. 정규 = 관리자가 업무, 일일 = 그날 유효 자격 전부(수요 기반 나누기는 운영에서 필요해질 때). MFC 에는 온도대 자격 없음(CDC 만).
- 처리량·입고: 포장대 4·75%, PICK 7분·PACK 4분, 시간당 45, 입고 하루 720·도착당 360·도크 1(07~10시). 배정 목표 순서: 미배정 > 지연 > 이동[평면도 뒤] > 불균형, 5초, 자동 확정 없음.
- 인계: D-1 C(같은 라이더 재스캔은 확인 없이, 그 밖은 점원 확인), 펜스 TTL 기본 3분, MFC 는 라이더 도착을 보지 않음(QR 바깥 랙), 포장 중 배차 요청 + READY 자동.
- 시뮬레이터는 실제 현장 대신 보여 주는 것(1차 MFC 작업에서는 쓰지 않음 — §0-5) — **맞지 않으면 시뮬레이터가 바뀐다**(백엔드 계약·업무 문서가 정본). 결정 기록 `docs/simulator/mfc/mfc-simulator-integration.md`(SIM-1~11), 평면도 `docs/simulator/mfc/layout/`, 범위 `docs/simulator/scope.md`. 시뮬레이터 handoff 는 `docs/handoff/simulator/`(mfc 영역).

## 4. 다른 영역·서비스와 걸린 것

| 무엇 | 상태 | 어디 |
|---|---|---|
| 배송이 출고 준비를 받아 배차(DD-1), 픽업 = 포장별 인계 확정(DE-1), 라이더 상태(RS-1), 용량 감소(CP-1) | 배송 쪽 대기 — **delivery 영역(PC 5)** | `docs/architecture/service-requests/delivery-kickoff-from-mfc.md`, `delivery-*.md` |
| 운영 제한 사건 → fulfillment 거절(F-1) | 대기(mfc 안) | `service-requests/mfc-fulfillment-service.md` |
| MFC 승인 번호 실제 검증(AP-1) — 지금 `RECORD_ONLY`(LAN 만) | 운영 전 필수, Q-11 | `service-requests/approval-service.md` |
| 반품 클레임·회수비·검수 뒤 환불(CL-1), 결품 고객 안내(N-1), 인계 → 주문 상태(O-1) | 대기 | `service-requests/claim-service.md`·`notification-service.md`·`order-service.md` |
| **작업자 명단 ↔ 인사(hr-service, 2026-11-13 새로)** | **결정·구현 2026-11-13**(사용자 "추천대로" + "센터 관리자는 작업자와 별개"): 그 센터 `MFC_WORKER` 권한(`GrantApplied`)이 붙을 때 작업자 생성, 퇴사·정지 자동 반영, 고용 형태 정본 인사, 권한 기간 밖이면 배정 안 됨(센터 이동). mfc-capacity V14·V15. LAN 은 PC 1 이 설정 저장소에 구독 바인딩 2개를 넣고 배포해야 켜짐. 권한 조기 회수 사건 요청 EA-1 | `services/mfc-capacity-service/CLAUDE.md`, `service-requests/employee-auth-service.md` |
| PDA 앱(mfc_app) 직원 로그인 | 클라이언트 구현 끝(a766d36), 실기기 확인 대기 | 클라이언트 handoff `mfc-app/2026-11-13-clients-request-mfc-app-employee-login.md` |

## 5. 남은 일 (mfc 영역)

- 서비스 안 구간(span) 열기 — OR-Tools(`CpSatPlanner`·`OrToolsRouter`, 상태·제한 시간·작업 수 태그), 재고 할당·예약·전기, 작업 중지·인계·작업 실행. 먼저 actuator 구간 빼기(Zipkin 2일 보관·용량). 사용자 "해야 할 목록"(11-10), 시작 지시 대기.
- E2E 에 아직 없는 것: 결품 사가 환불, 피킹 뒤 부분 중지(미구현), 라이더 인계 확정(배차 없음), 반품 재포장·실사·품질 격리·운영 제한·공급 요청, 입고 → 검수 → 적치 단계.
- 일일크루 그날 업무를 수요로 나누기(지금 = 유효 자격 전부, 사용자 "일단 유지").
- OR-Tools 배정 계획에 이동 포함은 됨, 작업 시간 추정의 처리 값은 시뮬레이터 값(임시).
- 첫 실주문 초안 `DRF-5099bf37…` 의 "차단 1" 원인 미기록.
- eureka-server 로그 "Batch update failure HTTP 401"(하루 약 9만 줄, 등록은 정상) — 조사 안 함.
- 결정 대기(`pending-user-decisions.md`): 남은 인계 결정(D-7 등), Q-08 가중치, 위험 추가 승인(Q-11) 기준 등.

## 6. 시험·도구

| 무엇 | 경로 |
|---|---|
| 서비스 시험 | `./gradlew :services:mfc-<…>-service:check` (OR-Tools 는 `tools/ortools-test-docker.sh` — PC 3) |
| LAN 끝까지 | `python -I -X utf8 deploy/lan/checks/mfc_e2e.py`(직원 토큰 `deploy/lan/checks/staff_auth.py`, 비밀번호 파일 `.secrets/keycloak-claude-mfc-{sup,wrk}-password`·`keycloak-claude-dev-password`) |
| 로컬 직원 토큰 | `POST http://localhost:8511/api/v1/internal/dev-tokens`(local 프로필 employee-auth-service) — `dev-on-other-pcs.md` |
| 평면도 재등록 | `deploy/lan/seed/mfc_layout.py`(센터 관리자 토큰) |
| 작업자 ID | 운영자 ID(`OP-…`) = 토큰 `sub`. `claude-mfc-wrk` = `OP-d696814f-4cd7-4263-86a6-032b591e5b42`, `claude-mfc-sup` = `OP-88399b05-87b7-48e2-aa33-229a3ab36f93` |
| 새 작업자 | 인사팀 백오피스(`https://192.168.0.40:8444`)로 등록 → 소속 센터 `MFC_WORKER` 자동 승인 → 소속 센터 `MFC_WORKER` 자동 승인(`GrantApplied`) → mfc-capacity 작업자 자동 생성(2026-11-13 — §4. 센터 관리자는 `MFC_WORKER` 권한이 없으면 작업자가 아님) → 센터 관리자가 자격·업무 지정(정규직·알바) |
