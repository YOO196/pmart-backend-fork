# [pmart-backend → pmart-mfc-simulation] 회신: R-20 출하대기 랙 스캔 — 만듦 (2026-11-19)

- 보낸 영역: mfc (PC 3)
- 답하는 것: `pmart-mfc-simulation/docs/backend-requests.md` R-20, 서비스 간 요청 F-12(PDA BR-52 R20 과 같은 것)
- 서버: mfc-fulfillment 0.20.0(V17)·mfc-inventory 0.29.0, 브랜치 `mfc/package-staging`. LAN 은 PC 1 병합·배포 뒤.

## 명령
- `POST /api/v1/packages/{packageId}/staging` 본문 `{ "stagingLocation": "DSP-A-01" }`
  - 헤더 `Idempotency-Key`·`X-Source-System`, 그 센터 `MFC_WORKER` 또는 `MFC_SUPERVISOR` 토큰.
  - **밀봉(SEALED) 포장만** — 아니면 409 `INVALID_STATE`. 랙이 아니면(구역 DISPATCH_STAGING 아님) 422 `STAGING_REJECTED`.
  - 같은 랙이면 바뀌지 않고 200, 다른 랙을 찍으면 그 랙으로. 결과 모름 503 → 같은 키로 다시.
  - 응답 = `Package`(`packingLocation` 패킹대·**`stagingLocation`** 랙).
- 서버가 하는 일: mfc-inventory 작업 실행 `STAGE` — 그 포장분 PACKED 의 **위치만** 패킹대(또는 앞 랙) → 랙. 수량(H·E·R)·예약·포장 상태 그대로.
- 라이더 인계(재고 인계 전기)·주문 중지 복귀는 이제 **포장분이 지금 있는 곳(랙)**에서. 재포장해도 랙 위치는 따라간다.

## 시뮬레이터
- 포장 ⑤단계에서 랙을 정하면 위 명령을 부르고, 그 뒤엔 "추정" 대신 서버 `Package.stagingLocation`(출고 `GET /shipments/{id}` 의 packages 에도)으로 그린다. 값이 null 이면 아직 패킹대.
- LAN 랙: `DSP-A-01`·`DSP-A-02`(MFC-SEED-01).
