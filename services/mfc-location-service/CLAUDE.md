# mfc-location-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `mfc-location-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/mfc-location-service.yaml` → `openApiGenerate` 가 `com.pmart.mfc.location.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `InboxGuard` 안에서.

## 패키지
`com.pmart.mfc.location.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:mfc-location-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:mfc-location-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8304.

## 상태
- 3단계 스캐폴딩(2026-10-23).
- 위치 마스터(ADR-0040 2단계, 2026-11-07, 브랜치 `mfc-v2`): V2 `location`(불변 location_id, 센터 안 코드 유일 — 문법 Q-03 미결이라 자유 문자열)·`location_state_change`. 등록·속성 변경(코드·센터 불변, X-Expected-Version)·상태 전이(ACTIVE↔CLOSED·COUNT_LOCKED, RETIRED 는 DELETE)·목록(코드 커서)·적치 후보(온도대 필수, ACTIVE STORAGE/PICKING, 동선 순서, 공간 V 는 null). 쓰기는 그 센터 운영자, 읽기는 그 센터 운영자 또는 서비스 토큰. 바뀔 때마다 같은 트랜잭션에서 `MfcLocationStateChanged`(전체 속성, 토픽 `mfc.location.v1`) — 구독 mfc-inventory 투영. `POST /internal/location-republications[?mfcId]` 는 같은 버전으로 다시 알림(최대 20000). 폐지(DELETE)는 inventory 위치 잔량 조회가 생길 때까지 501.
- **위치 코드 문법(ADR-0042, Q-03 결정 2026-11-07)**: 새 등록만 `LocationCodes` 로 검사 — 선반·피킹 `{A/C/F}-{구역 영문1+숫자1}-{랙 2}-{단 1~9}-{칸 2}`(온도대 글자 = temperatureZone, 구역 = areaCode, 없으면 코드에서 채움), 그 밖 `{RCV/PAK/QRT/DSP/RTN/DSL}-{A/C/F}-{번호 2}`. 어기면 400 `LOCATION_CODE_INVALID`. 옛 자유 형식 코드는 그대로 쓰고(속성 변경 때 문법 검사 안 함), 새 문법 코드는 속성 변경 때도 온도대·구역 일치 검사.

- 위치 폐지(2026-11-08, 0.3.0): `DELETE /locations/{id}` — 그 센터 운영자, CLOSED 만, mfc-inventory 스냅샷(locationCode)에 재고가 있으면 409·조회 실패 503(`infrastructure/InventoryClient`, `pmart.location.mfc-inventory-base-url`). RETIRED + MfcLocationStateChanged, 되돌리지 않음.
- **카트·고회전 선반(2026-11-08, 계약 0.4.0, V3)**: 구역 `CART`(재고 이동 바구니 — 식별번호 있는 위치, 코드 `CRT-{온도대}-{번호}`), 위치 속성 `highTurnover`(고회전 선반 — 같은 상품이 여러 칸에 있어도 됨, 그 밖은 한 상품 한 자리). 진열 카테고리 `categoryId`(0.5.0, V4 — 칸에 여러 상품, 그 카테고리 상품만; 없으면 기타 칸). `MfcLocationStateChanged` 에 highTurnover·categoryId·widthMm·depthMm·heightMm·maxWeightG(재고 이동 용량 계산용).
- **권한(2026-11-18, 계약 0.8.0 — 요청 MB-3, PC 3 결정)**: 칸 등록·수정·닫기/열기·폐지(`LocationService.requireOperator`)는 그 센터 **MFC_SUPERVISOR 만**. 조회는 그대로.
- **보충재고 칸(2026-11-21, 계약 0.9.0, V6 — MFC 시뮬레이터 R-30, 사용자 결정 2026-11-20 "선반칸이 가득차면 남는 건 입고 창고에")**: 위치 속성 `reserveStock`(`location.reserve_stock`) — 보관(STORAGE) 칸만, 고회전과 같이 쓰면 400(`LocationService.checkReserve`). `MfcLocationStateChanged` 에 reserveStock. 규칙(한 상품 한 자리 예외·피킹 마지막 순위·일반 선반 보충 출발지)은 mfc-inventory 0.44.0. 해제 전에 비우기는 안내만(재고는 inventory). LAN 팔레트 32칸 표시는 `deploy/lan/seed/mfc_layout.py reserve`(PC 1). 시험 `LocationFlowIT.reserveStockOnlyOnStorageAndNotWithHighTurnover`.
