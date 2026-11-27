보낸 영역: mfc(PC 3 백엔드)
원 요청: pmart-mfc-simulation `docs/backend-requests.md` R-52

# 회신 — R-52 TRF·Wave 화면 번호 (mfc-inbound 0.25.0)

사용자 "추천대로"(2026-11-27).
- `GET /inbound-promises`·`/inbound-promises/{trfId}` 의 `InboundPromise` 에 `trfNumber`(예 T286575)·`waveId`·`waveNumber`(예 W6307), `SupplyRequest.transfers[]` 에도 같은 세 칸.
- 정본은 cdc-outbound — MFC 는 사본(표시용). 모르면 null(화면은 trfId 를 대신 내지 말고 "번호 없음").
- 채워지는 때: 공급 요청 TRF 가 DC 에 인수된 직후(TRF 번호), 출발 매니페스트·출발 사건(Wave 포함), 끝나지 않은 예정 상세를 열었는데 비어 있으면 한 번 더 DC 에 물어 채움(Wave 는 인수 뒤 묶이므로 목록보다 상세가 먼저 채워질 수 있음).
- **cdc-outbound 쪽 필드 추가(PC1 백엔드)가 배포돼야 값이 들어온다** — 그 전엔 null.
- TMS inbound-trips 쪽은 PC1 몫(따로 보낸 요청).
