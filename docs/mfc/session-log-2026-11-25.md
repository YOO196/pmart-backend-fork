# MFC 세션 인계 — 2026-11-25 (PC 3, 계정 전환용)

다른 Claude 계정의 세션이 **이 파일 하나만 읽고 바로 이어 일할 수 있게** 적었다. 이 PC 의 Claude 메모리(계정마다 따로라 새 계정에는 없다)는 §1 에 옮겼다.
읽는 순서: 이 파일 → `docs/mfc/HANDOVER.md` 맨 위 ★★ 2026-11-25 절 → `docs/worklog/mfc/2026-11-25.md`(+ `2026-11-24.md`) → 손댈 서비스의 `services/<서비스>/CLAUDE.md` 끝 줄들.
앞 판 `session-log-2026-11-24.md` §1(메모리·규칙·배운 것)은 그대로 유효 — 아래에 다시 옮기고 이번에 배운 것을 더했다.

## 1. 기억해 둔 것(Claude 메모리 — 새 계정에 그대로 옮길 것)
1. **이 PC = PC 3, 영역 mfc**(`docs/operations/pc-split.md`). 일지 `docs/worklog/mfc/YYYY-MM-DD.md`, 브랜치 `mfc/<주제>`.
   - main·`origin` push 금지. **포크에서 PR**: remote `fork` = `YOO196/pmart-backend-fork`. `git push fork <브랜치>` → `gh pr create --repo code-and-pick/pmart-backend --base main --head YOO196:<브랜치>`. 병합·배포는 PC 1(지금은 주로 "PR 백엔드" 세션)이 한다.
   - 배포·`.secrets`·pmart-config 저장소는 손대지 않는다. 게이트웨이 새 경로는 PR 에 허용 목록(`PMART_WRITE_ALLOWLIST=1 ./gradlew :platform-services:api-gateway:test --tests '*UserTypeAllowlistFileTest'` → CRLF→LF `sed -i 's/\r$//'`)·시험 사본 2곳(`platform-services/api-gateway/src/{test,integrationTest}/resources/pmart-config/api-gateway.yml`)을 넣고, 실제 pmart-config 라우트는 PR 본문에 "PC 1 할 일"로.
2. **PR·일지는 묻지 말고 알아서**: 기능 하나(계약+코드+시험+문서)가 끝나면 커밋·push·PR 까지, 일지는 같은 커밋에.
3. **보류·병합 순서는 PR 댓글에도**(`gh pr comment`) — 병합 세션이 여럿이라 세션 메시지만으로는 못 본다.
4. **WireMock 은 상대 계약 모양 그대로**: 다른 서비스 응답을 흉내 낼 때 `contracts/openapi/<상대>.yaml` 스키마(특히 `_common` AsOf 객체)를 베낀다 — 문자열로 흉내 내 LAN 500 을 놓친 적 있음(#432).
5. 새 세션은 `docs/mfc/HANDOVER.md` 맨 위 ★★ 절부터, 일을 끝낼 때마다 그 절 갱신.

### 루트 CLAUDE.md 에서 특히 지킬 것
- **서브에이전트·Agent·Workflow 를 쓰지 않는다**(토큰 절약).
- 무거운 시험(gradle) 전 남은 메모리 확인 `powershell -NoProfile -Command "[int]((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1024)"` — 4GB 아래면 미루고 알린다. **확인을 시험과 한 명령으로 잇지 말고 따로 먼저**(2026-11-25 3.6GB 인데 이어 붙인 시험이 그대로 돈 적 있음).
- 실행하지 않은 시험을 통과했다고 하지 않는다. LAN·운영 DB 를 바꾸지 않는다. 비밀은 git·yml 에 넣지 않는다. 대답은 한국어.
- 문서에 없는 업무 규칙은 사용자에게 묻는다(AskUserQuestion, 추천안을 첫째로).

### 일하면서 배운 것
- 새 통합 시험 클래스는 `pmart.test.context=<이름>` 으로 컨텍스트 분리(아니면 멈춘 DB 컨테이너를 물려받아 전체 실행에서만 실패).
- 컨트롤러는 생성된 `*Api` 를 구현한다(`ContractStubTest`). 계약 먼저 → `./gradlew :services:<서비스>:openApiGenerate` 로 시그니처 확인. 헤더 파라미터(Idempotency-Key 등)를 계약에 넣으면 생성 시그니처가 바뀐다.
- inventory 의 **변경 요청(PUT/POST)은 공통 필터가 `Idempotency-Key` 를 요구**한다 — 내부 경로라도 계약에 `_common` IdempotencyKey·SourceSystem 파라미터를 넣고 호출 쪽이 보낸다. X-Source-System 은 `ServiceCallHeadersCustomizer` 가 붙인다.
- 기존 요청 본문에 칸을 더할 때 그 칸이 **지문·요청 해시**(`TaskExecutionService.fingerprint`, `RequestIdempotency` hash)에 들어가면, 배포 직전에 보낸 같은 요청을 다시 보낼 때 충돌한다 → 별도 인자로 받아 행에만 남긴다(#472 `performedBy`).
- **쌓은 PR(앞 PR 브랜치 위)은 앞 PR 이 squash 로 들어가면 다시 쌓는다**: `git rebase --onto origin/main <앞 PR 커밋> <브랜치>` → `git push --force-with-lease=<브랜치>:<옛 커밋> fork <브랜치>` → PR 본문·댓글 갱신(#473).
- bash heredoc 에 긴 한국어·따옴표가 섞이면 깨진다 → scratchpad 에 파이썬 스크립트를 Write 로 만들고 `python <경로>` 로 고친다(문자열 바꾸기 전에 `assert old in s`).
- `mfc-inbound-service:check` 는 오래 걸린다 — `test`·`integrationTest` 를 나눠 돌리거나 백그라운드로.
- 남의 화면 저장소 작업 폴더를 건드리지 않는다: `pmart-clients` 는 "MFC 프론트" 세션이 쓰는 중(커밋 안 한 변경) → 화면 일은 그 세션에 세션 메시지로 요청서를 보낸다(ListAgents 로 이름 확인).

## 2. 이번 세션(2026-11-24 밤 ~ 11-25)에 한 일 — 모두 main·LAN 배포됨
| PR | 내용 | 상태 |
|---|---|---|
| #438 | mfc-fulfillment 0.35.0 출하 약속 준수율 `GET /metrics/ship-promises`(약속 = 배차 요청 예상 준비, 끝 = 출고 READY — 사용자 결정), 운영판 `promise-summary` 약속도 같게 | 병합·배포 |
| #465 | 곧 품절 LS-1~3(통합 백오피스 요청, 사용자 결정 "하루 판매량 기준") — mfc-inventory 0.47.0 V32 + mfc-inbound `LowStockBasisPublisher`(1시간마다 d·정책 PUT) | 375cd21e, 배포 |
| #470 | 재고 이력 `GET /mfcs/{mfcId}/stock-ledger?sku=`(mfc-inventory 0.48.0) | a3e01377, 배포, pmart-config 5700f40 라우트 |
| #472 | 재고 이력 `orderId`·`actorOperatorId`·거르기 orderId(0.49.0 V33) + fulfillment·inbound 실적 `performedBy` | b3778965, 배포 |
| #473 | mfc-fulfillment 0.36.0 `GET /work-orders` 응답 `orderNumber` | f9bdc358, 배포(LAN 번호 일치 확인) |
- 데모 DB 컨테이너 `pmart-mfc-demo-pg` 는 사용자 "멈추기만" → `docker stop`(데이터 남음, `docker start` 로 다시). UCI 원본·로컬 `mfc/r45-reshape` 는 그대로 둠(사용자).
- 답한 질문: 사가는 결제 뒤 이행·취소·시간 변경·재발송·반품까지(배송 구간은 사건 전파), 재고 추적은 원장(`stock_commit`·`stock_ledger_entry`)이 있고 이제 이력 조회도 있음.

## 3. 열린 것·다음 할 일
1. **열린 백엔드 PR 없음.**
2. 화면(다른 세션 몫 — 기다림):
   - MFC 프론트: 재고 이력 탭 + LOT 추적 탭(clients #537 등, 사용자 "PR 해" 대기), 출하 약속 준수율·정시 배송률 시간 칸을 서버 값으로, 곧 품절 칸.
   - 통합백오피스 프론트(PC 2): 곧 품절 칸(회신 `docs/handoff/clients/backoffice/2026-11-25-reply-low-stock-count.md`).
   - PC 1: 게이트웨이 `Last-Event-ID` 허용 헤더(MFC 프론트 요청서 `pmart-clients docs/handoff/mfc-backoffice/2026-11-25-backend-gateway-sse-ledger.md`) — 지금은 실시간 연결이 끊기면 재고를 전부 다시 읽음.
3. LAN 확인 거리:
   - 곧 품절 "기준 모름"이 전부(503)인 것은 LAN 에 인계 실적(`handover_sale_daily`)이 없어서 — 실적이 생긴 센터부터 1시간 안에 채워진다. 이력 7일 미만이면 INSUFFICIENT_HISTORY.
   - 재고 이력 orderId·actor 는 배포 뒤 새 실적부터(예전 null, 소급 없음). 실제 실적으로는 아직 확인 안 함.
4. 백엔드 남은 개선(요청이 오면):
   - 이동 지시(MOVE_* — `/internal/move-allocations`)에는 작업자를 아직 안 싣는다(재고 이력 actor null).
   - 곧 품절은 공급 정책 중 safetyDays 만 쓴다(사용자에게 알림 — 다르게 원하면 바꿀 것).
   - 재고 이력 `after` 는 지금 합계에서 원장을 거슬러 계산 — 원장을 거치지 않은 변경이 있으면 어긋난다.
5. 그 전 남은 일(11-24 판): 폐기 실행 PDA 화면, 작업자 업무에 DISPOSAL_EXECUTE 넣기(센터 관리자), HANDOVER 11-21 절 "남은 일".
