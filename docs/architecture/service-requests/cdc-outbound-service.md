# cdc-outbound-service 에 들어온 요청

## CO-1 송장(TRF) 라벨 스캔 값 `TRF:{trfId}:{version}`
- **요청한 곳**: mfc-inbound(클라이언트 BR-53 R10, 2026-11-13)
- **필요한 것**: 출발 센터가 이송 박스·팔레트에 붙이는 송장 라벨의 바코드(QR) 원문을 `TRF:{trfId}:{version}` 으로(라벨 스캔 형식 `{유형}:{id}:{버전}` — 포장 `PKG:{packageId}:{packageVersion}` 과 같은 규칙). trfId 는 MFC 입고 예정(`InboundPromise.trfId`)과 같은 값.
- **지금(임시 방식)**: CDC 출고·이송이 없어 라벨도 없다. MFC PDA 는 목에서 이 형식을 해석하고, 맞는 송장인지는 trfId 만 비교한다(버전은 인쇄 때 값).
- **요청한 쪽에서 바꿀 것**: 없음(계약 `mfc-inbound-service.yaml` 0.8.0 도착 등록 설명에 적음). 형식을 바꾸면 mfc-inbound 계약과 클라이언트에 알린다.
- **사용자 결정 필요**: 없음(라벨 형식은 기존 규칙을 따름). LPN 별 라벨을 따로 둘지는 cdc-outbound 설계 때.
- **상태(2026-11-26 PC 1 — 사용자 "구현해"·"전부 여기서")**: **구현** — cdc-outbound 0.30.0 TRF 응답(TransferSummary·TransferDetail) `labelValue` = `TRF:{trfId}:{trfVersion}`(`application/TransferLabels`), 출발 매니페스트 사건에도 `labelValue`.
- 근거: `docs/handoff/clients/mfc-app/2026-11-13-reply-br53-inbound-receiving.md` §3

## CO-2 이송 박스 바코드 형식·박스 단위 값 (입고 예정 박스 목록)
- **요청한 곳**: mfc-inbound(클라이언트 mfc-app BR-53 R17, 2026-11-20 — 보낸 영역 mfc, PC 3)
- **필요한 것**: MFC 현장은 이송 물량을 박스째 센다(TMS D-07 "적재는 롤테이너, 수량 검증은 박스 바코드"). PDA 가 입고 예정에서 박스 목록을 보이고 하차 때 박스 바코드를 찍어 맞추려면
  1. **박스 바코드(라벨) 원문 형식** — 앱은 채번 지침 G-05 `BOX-…` 를 가정했지만 백엔드에는 정한 형식이 없다. 라벨 스캔 형식 `{유형}:{id}:{버전}`(CO-1 `TRF:`·포장 `PKG:`)을 따를지, `BOX-…` 인지, LPN 값 그대로인지 정해 주세요.
  2. `TransferShipmentManifestIssued.lines[]` 의 `lpn` 이 박스 하나(= 박스 바코드)인지 팔레트·롤테이너인지 — 둘 다면 단위 유형(BOX·PALLET·ROLL_CONTAINER)과 박스 → 롤테이너 소속.
  3. (가능하면) 줄마다 입수(`unitsPerBox` — 박스 하나에 든 판매 단위 수)와 롤테이너 수. 지금 사건에는 sku·lot·lpn·qty·expiryDate 뿐이다.
- **지금(임시 방식)**: mfc-inbound 0.16.0 `InboundPromise.packages[]` 는 매니페스트(없으면 출발 TOUT 줄)의 `lpn` 을 **받은 값 그대로** 보인다(형식을 만들지 않음). 박스 수·입수·롤테이너 수는 넣지 않았다. 앱은 목에서 `BOX-…`·박스째 +12 버튼을 쓰고, 실서버에서는 LPN 목록만 보인다.
- **요청한 쪽에서 바꿀 것**: 형식이 정해지면 mfc-inbound 계약 `packages` 설명(필요하면 `unitsPerBox`·`rollContainers` 칸)과 클라이언트 회신을 고친다.
- **사용자 결정 필요**: 박스 라벨 형식(G-05 를 백엔드 규칙으로 올릴지). cdc-outbound 설계 때.
- **상태(2026-11-26 PC 1 — 사용자 "구현해"·"전부 여기서", 추천안)**: **구현** — ① 박스 바코드 원문 = **CDC 분류 때 등록한 LPN 값 그대로**(새 형식 `BOX-…` 를 백엔드 규칙으로 올리지 않음 — 현장 라벨 값이 곧 LPN). ② 매니페스트 줄 lpn = BOX(수량은 BOX 만), 줄 `unitType: BOX`·`parentLpn`(담긴 RT·PLT), 사건 `containers[{lpn, unitType}]`(cdc-outbound 0.30.0, `_defs` SkuLotLpnQty 에 unitType·parentLpn 추가). mfc-inbound 0.22.0 `InboundPromise.packages[].parentLpn`. ③ 입수(`unitsPerBox`)는 상품 마스터에 없어 **아직**(박스 하나 = 줄 qty 로 셈).
- 근거: pmart-clients `docs/handoff/mfc-app/2026-11-10-backend-pda-work-areas.md` R17, 회신 `docs/handoff/clients/mfc-app/2026-11-20-reply-br53-inbound-promises.md`

## CO-3 MFC 발주 TRF 미출발 취소 — 서비스 간 경로·취소 사실 사건
- **요청한 곳**: mfc-inbound(2026-11-27 PC 3 — mfc 시뮬 R-54 뒤 PC 1 지적, 사용자 "요청해")
- **배경**: mfc-inbound 0.26.0(#551)부터 센터 관리자가 **CDC 출발 없는 입고 예정**도 종결(CLOSED)한다 — 시험 송장 정리용. 하지만 R-27(ADR-0091) 공급 요청 TRF 는 이미 cdc-outbound `/internal/transfer-intakes` 로 인수돼 있어, MFC 만 닫으면 DC 는 그 TRF 를 계속 할당·피킹·출고할 수 있다(MFC 는 받지 않을 물건). 미출발 취소 `POST /cdcs/{cdcId}/transfers/{trfId}/pre-departure-cancellations` 는 DC 운영자용이라 MFC 가 부를 수 없다.
- **필요한 것**:
  1. **서비스 간 미출발 취소** — 예: `POST /internal/transfer-intakes/{trfId}/cancellations`(서비스 토큰, Idempotency-Key, 본문 `{reason: TRANSFER_CANCELLED, scope: FULL, sourceRef: <공급 요청 ID>, requestedBy}`). 규칙은 운영자 미출발 취소와 같게(피킹 시작 전만 — 0.7.1). 결과: 취소됨 / 409 PICKING_STARTED·INVALID_STATE(출발·이미 취소)·ALLOCATION_IN_PROGRESS·CANCELLATION_IN_PROGRESS / UNKNOWN(같은 키로 다시). 조회 `GET …/cancellations/{requestId}` 도 있으면 좋다.
  2. **TRF 미출발 취소 사실 사건**(cdc.outbound.v1 — 예 `TransferPreDepartureCancelled {trfId, toMfc, cancelledAt, reason, requestedBy}`) — DC 운영자가 직접 취소한 경우에도 MFC 입고 예정을 CANCELLED 로 닫게.
- **요청한 쪽에서 바꿀 것(1·2 가 생기면 PC 3)**: 인수된 공급 요청 TRF(`supply_request_transfer` 에 있고 ACCEPTED)의 관리자 종결은 cdc-outbound 취소를 먼저 부르고, 취소되면 CLOSED, PICKING_STARTED 면 409(그대로 받아야 함), 모름이면 재시도. 사건 2 구독으로 예정 CANCELLED. 그때까지 MFC 종결은 DC 를 멈추지 않는다(지금 그대로).
- **사용자 결정 필요**: 2026-11-25 사용자 "공급쪽은 안 해"(cdc-outbound 는 공급 쪽 TransferCancelled 를 받지 않음)와 1 이 부딪치는지 — 1 은 공급(replenishment) 사건이 아니라 받을 센터(MFC)의 직접 요청이다. PC 1 이 확인.
- **상태**: 요청(2026-11-27)
