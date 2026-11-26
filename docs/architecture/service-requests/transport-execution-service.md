# transport-execution-service 에 대한 요청

받는 영역: cdc(PC 1 — TMS 1단계는 cdc 세션이 만들었으나 2026-11-27 사용자 "DC 백엔드 고장 — PC1 백엔드로", TE-1 은 PC1 백엔드 세션). 표는 `README.md`(PC 1).

## TE-1. 정차 도착 사건 `StopArrived` 발행 — MFC 입고 예정 자동 도착 (2026-11-27, 보낸 영역 mfc — PC 3)
- **계기**: 사용자(2026-11-27) "DC 에서 MFC 도착을 누르면 MFC 쪽에 도착이 떠야". LAN 에서 DC 운송 TO-20261201-001 이 정차 ARRIVED 인데 MFC 입고 예정(TRF-SR-SR-4987104b…)은 EXPECTED 그대로였다 — `TripService.arrive` 가 정차 상태와 자기 `trip_event`(STOP_ARRIVED)만 남기고 **사건을 내지 않는다**(이 서비스는 아직 발행이 없다).
- **필요한 것**: 정차 도착(`POST …/stops/{seq}/arrivals` — 처음 ARRIVED 가 될 때 한 번) 같은 트랜잭션에서 outbox 로 `StopArrived.v1`(계약 이미 있음 — `contracts/events/StopArrived.v1.json`, 토픽 `tms.trip.v1`, aggregate Trip/tripId). payload `tripId·stopId·mfcId·sequence·arrivedAt·arrivalBasis`에 **`trfIds`(그 정차의 trip_transfer TRF 전부)를 꼭** 넣어 주기 — MFC 는 trfId 로 입고 예정을 찾는다.
- **MFC 쪽(구현, mfc-inbound — 브랜치 `mfc/stop-arrived-auto-arrival`)**: `tms.trip.v1` 구독(바인딩 `tmsTripEvents-in-0`) → trfIds 마다 그 센터 예정이 EXPECTED 면 RECEIVING + `InboundArrivalRecorded`(행위자 SYSTEM, arrivalRecordedBy `SYSTEM:transport-execution`). 이미 도착·수령한 예정·다른 센터·예정 없는 TRF 는 그대로(현장 도착 등록은 예전처럼).
- **설정(PC 1)**: pmart-config `mfc-inbound-service.yml` 에 `tmsTripEvents` 함수·`tmsTripEvents-in-0: tms.trip.v1`. transport-execution outbox → Debezium 커넥터(이 서비스는 처음 내는 사건이라 커넥터가 없을 수 있음).
- **상태**: 요청(2026-11-27 PC 3).
