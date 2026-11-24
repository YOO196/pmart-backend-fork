# MFC 세션 인계 — 2026-11-24 (PC 3, 계정 전환용)

다른 Claude 계정의 세션이 **이 파일 하나만 읽고 바로 이어 일할 수 있게** 적었다. 이 PC 의 Claude 메모리(계정마다 따로라 새 계정에는 없다)도 §1 에 옮겨 두었다.
읽는 순서: 이 파일 → `docs/mfc/HANDOVER.md` 맨 위 ★★ 2026-11-24 절(SSE 설계 자세히) → `docs/worklog/mfc/2026-11-24.md` → 손댈 서비스의 `services/<서비스>/CLAUDE.md` 끝 줄.

## 1. 기억해 둔 것(Claude 메모리 — 새 계정에 그대로 옮길 것)
1. **이 PC = PC 3, 영역 mfc**(`docs/operations/pc-split.md`). 일지 `docs/worklog/mfc/YYYY-MM-DD.md`, 브랜치 `mfc/<주제>`.
   - main 직접 push 금지. **포크에서 PR**: remote `fork` = `YOO196/pmart-backend-fork`(공개, 2026-11-17부터). 예전 `fork-old`(YOO196/pmart-backend)는 포크 연결이 끊겨 PR 이 안 된다. `origin` 에는 push 하지 않는다.
   - PR 명령: `git push fork <브랜치>` → `gh pr create --repo code-and-pick/pmart-backend --base main --head YOO196:<브랜치>`. 병합·배포는 PC 1 만.
   - 배포·`.secrets`·pmart-config 저장소는 손대지 않는다. platform 영역 파일(루트 CLAUDE.md 등)도 PR 로만.
2. **PR·일지는 묻지 말고 알아서**(사용자 2026-11-13): 기능 하나(계약+코드+시험+문서)가 끝나면 커밋·push·PR 까지 하고, 일지는 같은 커밋에.
3. **보류·병합 순서는 PR 댓글에도**(2026-11-24): `gh pr comment <번호>` 로 "보류 — 까닭·풀리는 조건"을 남긴다. 병합 세션이 여럿(PC1 백엔드, PR용 백엔드)이라 세션 메시지만으로는 못 본다.
4. 새 세션은 `docs/mfc/HANDOVER.md` 맨 위 ★ 절부터 읽고, 일을 끝낼 때마다 그 절을 갱신한다.

### 루트 CLAUDE.md 에서 특히 지킬 것
- **서브에이전트·Agent·Workflow 를 쓰지 않는다**(토큰 절약, 사용자 2026-11-21).
- 무거운 시험(gradle) 전 남은 메모리 확인 `powershell -NoProfile -Command "[int]((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1024)"` — 4GB 아래면 미루고 알린다.
- 실행하지 않은 시험을 통과했다고 하지 않는다. LAN·운영 DB 를 바꾸지 않는다. 비밀은 git·yml 에 넣지 않는다.
- 대답은 한국어.

### 일하면서 배운 것(이번 세션)
- 통합 시험 클래스를 새로 만들면 `@SpringBootTest(properties = {..., "pmart.test.context=<이름>"})` 로 **컨텍스트를 분리**한다. 다른 IT 와 설정이 같으면 캐시된 컨텍스트(이미 멈춘 DB 컨테이너 포트)를 물려받아 전체 `check` 에서만 실패한다(2026-11-24 `SupplyOrderIT`, 단독 실행은 통과).
- 컨트롤러는 반드시 생성된 `*Api` 인터페이스를 구현한다(`ContractStubTest`). 계약(`contracts/openapi/<서비스>.yaml`)을 먼저 고치고 `./gradlew :services:<서비스>:openApiGenerate` 로 시그니처를 본다.
- bash heredoc 안에 긴 한국어·따옴표가 섞이면 깨진다 → 파이썬 스크립트를 scratchpad 에 Write 로 만들고 `python -I <경로>` 로 고친다.
- `mfc-inbound-service:check` 는 13분 넘게 걸릴 수 있다 — `run_in_background` 로 돌린다.

## 2. 이번 세션(2026-11-24)에 한 일
| 일 | 결과 |
|---|---|
| 이전 세션 R-45(시뮬레이터 6e35a62 대기) | 다른 PC 가 main 에 넣음(1cd6a0f0·27a9e5fa·d1109ecd "R-45 시뮬레이터 대조 어긋남 0"). 로컬 `mfc/r45-reshape`(02cab2de, push 안 함)는 쓸모없어 보임 — 사용자 확인 뒤 지움 |
| 사용자 명세 검토·보완(`C:\Users\ds\Downloads\corap\PMart_Claude_수요예측_보충추천_통합명세.md`) | 여러 차례 검토, PC 3 가 직접 보완 |
| 수요예측·보충추천 데모 배치(`demos/mfc-demand-replenishment`, 독립 Gradle·Spring Batch) | **PR #411 병합됨**. 실제 UCI 원본·PMart 503 SKU 합성 판매로 실행 확인. 사용법·결과는 그 폴더 `README.md` |
| MI-4 센터 발주 승인·발주 수량 추천(mfc-inbound 0.21.0, V14) | **PR #424 열림**(PC 1 검토·병합·배포 대기). check 34·데모 41 통과. 시작값은 사용자가 고름(R 1·L 1·SS 1일치·배수 1·최소 1·이동평균 28·최소 이력 7) |
| 데모 센터 ID `MFC-SEED-01` → `MFC-BJG-01`(R-46) | #424 에 같이 |
| 인수인계 | PR #425(`docs/mfc/HANDOVER.md` ★★ 절 + 이 파일) |
| mfc-inventory 재고 실시간 SSE | **설계만, 코드 0줄** — §3-1 |

## 3. 앞으로 할 일(우선순위 순)
### 3-1. mfc-inventory 재고 실시간 SSE 스트림 — 지금 할 일
- 요청자: **PC 1 백엔드 세션**(세션 메시지로 직접 받음, 요청서 없음). 사용자 결정 2026-11-24 "실시간 재고 차감 취소 시 증감이 바로바로 백오피스에 반영 되어야 해" → 서버가 밀어 주기(SSE). 받는 화면: MFC 백오피스(mfc 프론트), 통합 백오피스(프론트2, PC 2).
- 요청 내용·PC 2 의 추가 바람·**정한 설계 전부**(경로 `GET /stock-stream?mfcId=`, `Ledger.write` → V31 `stock_stream_event` + `pg_notify`, 인스턴스마다 LISTEN, 이벤트 `ready`·`stock-changed`·`summary-changed`·`resync`, heartbeat 5초 주석 줄, Last-Event-ID 이어 받기·version 거르기, 권한 = `StockSummaryService` 와 같게, 참고 구현 `services/delivery-tracking-service/.../api/EtaStreams.java`, 시험 방법)는 **`docs/mfc/HANDOVER.md` ★★ 절 "하다 멈춘 일"** 에 있다. 그대로 따른다.
- 브랜치: origin/main 에서 `mfc/inventory-stock-stream` 을 새로 만든다(이 PC 의 같은 이름 브랜치는 커밋 없음).
- 끝나면: PR 본문에 게이트웨이 경로(`/api/v1/stock-stream`, GET, SSE, 응답 시간 제한 해제 필요 — PC 1 이 pmart-config api-gateway.yml 에 넣음), 서비스 CLAUDE.md·일지, PC 1 백엔드 세션에 알림.

### 3-2. PR #424 뒤처리
- PC 1 이 병합·LAN 배포하면: 인계 실적은 배포 뒤부터 쌓여 **7일 동안 모든 SKU 가 INSUFFICIENT_HISTORY** — 시뮬레이터·백오피스는 이미 회신으로 앎.
- 리뷰 지적이 오면 브랜치 `mfc/mi4-supply-order` 에서 고친다. #425 와 같은 일지 파일 끝을 고쳐 나중 병합 쪽이 충돌만 푼다.
- 남은 것(요청 오면): 품절 날 학습 제외, 정책(`supply_policy`) 수정 API·화면, 승인·거절 사건 발행.

### 3-3. 1호점(MFC-BJG-01, 옛 MFC-SEED-01) 다시 설계 — 시뮬레이터 위치 파일 대기
- 원본 고정은 끝남(`deploy/lan/seed/seed-template-2026-11-24.json`). 다시 설계 스크립트(새 칸·재고 옮기기·옛 칸 닫기·용량 검사)는 시뮬레이터 위치 파일이 오면 만든다.
- 사용자 결정: 랙당 칸은 지금 구조 그대로(냉장 35·냉동 30·상온 C·G 54·D·F 60·E 48·H 25), 같은 코드면 재고 그대로·없어진 칸 재고만 같은 온도대·구역 글자 빈 칸으로 위치 정정, 예약·배정·보류 줄은 남기고 보고(일지 2026-11-24 "1호점 다시 설계 준비").

### 3-4. 사용자에게 물어볼 정리
- 데모 DB 컨테이너 `pmart-mfc-demo-pg`(지금 실행 중, 포트 15433)와 내려받은 UCI 원본(`demos/mfc-demand-replenishment/data/` — git 밖)을 지울지.
  - 데모 DB 비밀번호는 git 에 없다. 환경변수 `PMART_DEMO_DB_PASSWORD` 로 넘긴다. 이전 세션 scratchpad 의 `demo-db-pw.txt` 는 새 계정에서 안 보일 수 있다 — 필요하면 컨테이너를 새로 만든다(데모 README).
- 로컬 브랜치 `mfc/r45-reshape` 를 지울지.

## 4. 연락 상대(세션 메시지)
- **PC1 백엔드**: 요청·배포 담당. 메시지는 `SendMessage` 로 그 세션 이름에 보낸다(새 계정에서는 `ListAgents` 로 이름을 다시 찾는다). 받았는지 확인되지 않는 경우가 많아 중요한 것은 PR 본문·댓글에도 쓴다.
- 클라이언트 요청은 `pmart-clients/docs/handoff/mfc-backoffice/`(읽기만), 회신은 이 저장소 `docs/handoff/clients/mfc-backoffice/`. 시뮬레이터 회신은 `docs/handoff/simulator/mfc/`(README 목록에 한 줄).

## 5. 빠른 시작 명령
```bash
cd D:/ds/development/pmart-project/pmart-backend
git fetch origin && gh pr list --repo code-and-pick/pmart-backend --author YOO196   # 열린 PR
git checkout -b mfc/inventory-stock-stream origin/main                               # 3-1 시작
./gradlew :services:mfc-inventory-service:openApiGenerate                            # 계약 고친 뒤
./gradlew :services:mfc-inventory-service:check                                      # 메모리 확인 후, 뒤에서
```
