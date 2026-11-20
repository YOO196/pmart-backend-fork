# 회신: BR-52 R19-6 남은 것 — 실사 중 SKU·LOT 을 정하지 못한 물건은 "발견품 조사"로

- 보낸 영역: mfc
- 작성: pmart-backend PC 3, 2026-11-20
- 답하는 것: pmart-clients `docs/handoff/mfc-app/2026-11-10-backend-pda-work-areas.md` R19-6 — 앞 회신 `2026-11-20-reply-br52-inventory.md` §5 "남은 미결 1(목록 밖에서 찾은, LOT 을 정하지 못한 물건)".
- 계약: `mfc-inventory-service.yaml` **0.43.0**(V28). PR 병합 뒤 main 에서 `gen:api`. LAN 은 PC 1 병합·배포·게이트웨이 라우트 뒤.
- 사용자 결정(2026-11-20): PC 3 추천안 "그대로 진행해".

## 정한 것
- 근거: MFC 업무 원문 IV-09(로트 불명 발견 = 판매 차단·조사), IV-11(위치·로트 오기는 총량을 늘리는 조정으로 풀지 않음, 원인 불명은 조사 대기).
- 실사 계수의 `unexpectedLines` 중 SKU·LOT 을 못 정한 줄(`unresolvedLines`)은 **실사를 승인할 때 발견품 조사 건**이 된다. **재고(장부)는 늘리지 않는다.** 실사 승인·칸 잠금 해제는 예전 그대로(조사가 칸을 붙잡지 않는다).
- 같은 칸·SKU·바코드·소비기한의 열린 조사가 있으면 새로 만들지 않고 `seenCount`·`lastSessionId`·`qty`(마지막 계수)만 바뀐다.

## PDA(mfc-app)
- 화면·API 변화 없음. 목록 밖 줄을 못 정했을 때 안내 문구만 권함: "판매 진열에 두지 말고 격리 칸에 옮겨 두세요 — 센터 관리자가 확인합니다." (서버는 실물 위치를 따로 받지 않는다 — 찾은 칸이 기록된다.)

## 센터 관리자 웹(mfc-backoffice)
- 목록 `GET /api/v1/mfcs/{mfcId}/count-found-items?state=OPEN|RESOLVED&size=&cursor=`(기본 OPEN, 오래된 순), 한 건 `GET …/count-found-items/{foundId}` — 그 센터 운영자.
  - 항목 `foundId`(CFI-…)·`sessionId`·`lastSessionId`·`seenCount`·`locationCode`·`sku`·`barcode`·`expiryDate`·`qty`·`cause`(SKU_UNKNOWN 바코드 미등록 / LOT_UNKNOWN 그 기한 LOT 없음 / LOT_AMBIGUOUS 같은 기한 LOT 여럿)·`state`·`outcome`·`outcomeRef`·`lotId`·`note`·`resolvedBy`·`resolvedAt`.
- 결론 `POST …/count-found-items/{foundId}/resolutions`(MFC_SUPERVISOR, Idempotency-Key·X-Source-System) — **기록만, 재고는 안 바뀜**. 실제 처리를 먼저 하고 결론에 그 참조를 적는다:
  | outcome | 뜻 | 필수 |
  |---|---|---|
  | `EXISTING_LOT` | 기존 LOT 의 물건(칸·LOT 을 잘못 둠) — 재고 정정·이동으로 맞춤 | `outcomeRef`(정정·이동 참조), `lotId`(SKU 를 알면 같은 SKU 의 LOT) |
  | `SOURCE_RECOVERED` | 빠졌던 입고·반품 등록을 복구함 | `outcomeRef`(원사건) |
  | `DISPOSED` | 원인 불명 — 팔지 않고 폐기(장부에 없던 물건이라 재고·손실·본사 승인 없음) | 없음 |
  - 모두 `note`(확인한 내용) 필수. 끝난 건 다시 → 409 `FOUND_ITEM_ALREADY_RESOLVED`. 작업자 403.
