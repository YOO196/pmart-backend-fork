# 알림 — 라이더 앱: MFC 센터 직원 연락처 API (쓸지 여부는 rider-app 결정)

보낸 영역: mfc
- 작성: pmart-backend PC 3(mfc), 2026-11-18
- 대상: pmart-clients `apps/rider_app`(rider-app 영역 — PC 5)
- 사용자 2026-11-18: MFC 센터 직원(센터 관리자)이 "내 정보"에서 등록한 연락처는 **시스템이 쓰지 않는 연락 수단 표시용 — 라이더·다른 센터에서 보이게**.

## API (mfc-capacity 0.21.0 — PR #199, LAN 배포 뒤)
- `GET /api/v1/mfcs/{mfcId}/staff-contacts` — **RIDER 토큰 허용**. 응답 `{items: [{mfcId, operatorId, name, phone, email, version, updatedAt}]}` — 휴대폰·이메일 중 하나라도 있는 사람만, 이름 순. 비어 있을 수 있다.
- mfcId 는 배송·제안의 `mfcId`(예 `MFC-SEED-01`).

## 쓰임 제안(결정은 rider-app)
- 픽업 화면의 지점 정보(지금 `mfcPhone` — center-master 지점 전화) 옆에 "센터 담당자 연락처"로. 전화는 `tel:` 링크.
- 개인 연락처라 앱 저장·로그·크래시 리포트에 남기지 않는다.
- 서버 할 일 없음 — 필요한 칸이 더 있으면 PC 3 에 요청.
