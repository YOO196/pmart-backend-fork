# 회신: PDA 포장 ⑤ 출하대기 랙 스캔 (BR-52 R20) — 만듦

- 보낸 영역: mfc (PC 3 백엔드)
- 작성: pmart-backend, 2026-11-19
- 답하는 것: PDA BR-52 R20(서비스 간 요청 F-12·시뮬레이터 R-20 과 같은 것)
- 서버: mfc-fulfillment 0.20.0·mfc-inventory 0.29.0(브랜치 `mfc/package-staging`, LAN 은 PC 1 배포 뒤)

## PDA 가 할 일
- 포장 ⑤ 랙을 찍으면 `POST /api/v1/packages/{packageId}/staging` `{ stagingLocation: <찍은 랙 코드> }`(Idempotency-Key·X-Source-System).
  - 출고에 포장이 여럿이면 포장마다 부른다.
  - 200 → 완료(응답 `stagingLocation`). 같은 랙 다시 찍기도 200.
  - 409 `INVALID_STATE` — 아직 밀봉 안 된(또는 이미 인계·무효) 포장. 422 `STAGING_REJECTED` — 랙이 아님("출하대기 랙을 찍어 주세요"). 503 — 같은 키로 다시.
- 그 뒤 화면·점원 확인에서 봉투 위치는 `Package.stagingLocation`(null 이면 패킹대).
- 라이더 인계는 바뀐 것 없음(QR·숫자 그대로) — 서버가 랙 위치에서 재고를 뺀다.
