# LAN 시험 계정 (직원) — 2026-11-16 확인

LAN 시험에 쓰는 직원 계정과 역할·센터를 한곳에 모은 목록이다. **LAN 전용 — 운영 금지.** 비밀번호는 여기에 적지 않는다(파일 위치만).

- 비밀번호 파일: PC 1 `deploy/lan/.secrets/`(Git 아님). 다른 PC 는 필요한 파일만 같은 경로로 받는다(`dev-on-other-pcs.md` §2). `keycloak-admin-password` 는 옮기지 않는다.
- 직원 토큰: `deploy/lan/checks/staff_auth.py` 의 `staff_token(<아이디>)`(Keycloak 로그인 → employee-auth-service 교환). 토큰·비밀번호는 출력·커밋하지 않는다.
- 역할 설명: `docs/architecture/auth/staff-roles.md`.
- 2026-11-16 확인: 아래 MFC 작업자 70명 모두 인사 등록·계정 발급 끝(DONE)·재직(ACTIVE) — hr-service 조회. 관리·운영 계정 중 그날 LAN 로그인으로 확인한 것: `claude-dev`·`claude-approver`·`claude-mfc-sup`(나머지 둘은 11-13~15 기록 기준).

## 1. 관리·운영 시험 계정

| 아이디(Keycloak) | 역할 | 센터 | 비밀번호 파일 | 쓰는 곳 |
|---|---|---|---|---|
| `claude-dev` | `OPERATOR_ADMIN` | 모든 센터 | `keycloak-claude-dev-password` | 관리자 시험, 백오피스 관리자 메뉴, RAG 확인(`rag_e2e.py`) |
| `test-cs-rider@pmart.com` | `CS_AGENT`·`RIDER_OPS` | 모든 센터 | `keycloak-test-cs-rider@pmart.com-password` | 고객센터·라이더 운영·지원센터 메뉴 |
| `claude-approver` | 승인 역할들 + `HR_ADMIN` | — | `keycloak-claude-approver-password` | 승인함, 인사팀 앱(직원 등록·권한 관리) |
| `claude-mfc-sup` | `MFC_SUPERVISOR` | `MFC-SEED-01` | `keycloak-claude-mfc-sup-password` | MFC 백오피스(:8445), 센터 관리자 시험 — 직원 번호 `OP-88399b05-87b7-48e2-aa33-229a3ab36f93`(2026-11-16 로그인 확인) |
| `claude-mfc-wrk` | `MFC_WORKER` | `MFC-SEED-01` | `keycloak-claude-mfc-wrk-password` | 시뮬레이터 작업자·`mfc_e2e.py` — 직원 번호 `OP-d696814f-4cd7-4263-86a6-032b591e5b42` |

- 실제 직원 계정(예: 인사팀 `heeji`)은 시험용이 아니다 — 시험에 쓰지 않는다.
- 배송 쪽 직원 시험 계정은 따로 없다(필요하면 인사팀 등록으로 만든다).

## 2. MFC 시험 작업자 70명 — 센터 `MFC-SEED-01`

- 만든 스크립트: `deploy/lan/seed/mfc_test_workers.py`(2026-11-13, 다시 돌려도 안전 — 있는 것은 건너뜀, `--check` 는 상태만). 인사 등록(hr-service) → 계정 발급 → 공용 비밀번호 → MFC 작업자 업무(센터 관리자가 지정).
- 아이디 = 메일 주소 `mfcw-<분야>-NN@pmart.com`. 역할 `MFC_WORKER` · 권한 센터 `MFC-SEED-01`(소속 센터라 자동 승인).
- 비밀번호: 70명 **공용** — 파일 `keycloak-mfc-test-workers-password`.
- 고용 형태: 번호 **홀수 = 정규직, 짝수 = 알바**(계약 종료 2026-12-31). 일일크루는 하루만 유효해 시험 계정으로 두지 않았다.

| 분야 | 아이디 범위 | 인원 | 업무(작업 유형) |
|---|---|---|---|
| 피킹·포장 | `mfcw-pp-01` ~ `mfcw-pp-30` | 30 | 피킹·포장 (`PICK·PACK`) |
| 입고 | `mfcw-rcv-01` ~ `mfcw-rcv-10` | 10 | 입고·적치 (`RECEIVE·PUTAWAY`) |
| 품질검사 | `mfcw-qc-01` ~ `mfcw-qc-10` | 10 | 품질검사 (`INSPECT`) |
| 재고 이동 | `mfcw-mv-01` ~ `mfcw-mv-10` | 10 | 이동·보충 (`MOVE·REPLENISH`) |
| 반품 | `mfcw-rtn-01` ~ `mfcw-rtn-10` | 10 | 반품 (`RETURN`) |
| 전체 업무(2026-11-20) | `mfcw-all-01` | 1 | 배정 가능한 작업 유형 12개 전부 — `PICK·PACK·RETURN·DISPATCH·RECEIVE·INSPECT·PUTAWAY·REPLENISH·MOVE·RETURN_RECEIPT·RETURN_INSPECT·CYCLE_COUNT`(자격·업무 같게, `QUARANTINE_MOVE` 는 업무에 넣을 수 없어 뺌). 정규직, 직원 번호 `OP-44b5c9e6-7f6e-46b3-bd34-784ee9c12e23`. **공용 비밀번호는 PC 1 이 맞춰야 로그인된다**(Keycloak 관리자 비밀번호가 PC 1 에만 있음) |

### 전체 목록

| 아이디 | 표시 이름 | 고용 형태 | 업무 | 직원 번호 |
|---|---|---|---|---|
| `mfcw-pp-01@pmart.com` | MFC시험 피킹포장 01 | 정규직 | 피킹·포장 | `OP-a666a1be-b3f8-412e-9f40-0137ef8d4abd` |
| `mfcw-pp-02@pmart.com` | MFC시험 피킹포장 02 | 알바(~2026-12-31) | 피킹·포장 | `OP-117cc350-233a-48a9-aefe-9d9db41d0c7f` |
| `mfcw-pp-03@pmart.com` | MFC시험 피킹포장 03 | 정규직 | 피킹·포장 | `OP-06c9d0b9-1f4d-4e88-9e68-178810e6d1ae` |
| `mfcw-pp-04@pmart.com` | MFC시험 피킹포장 04 | 알바(~2026-12-31) | 피킹·포장 | `OP-13984fb1-af71-42a1-81fa-ebf29bb20d47` |
| `mfcw-pp-05@pmart.com` | MFC시험 피킹포장 05 | 정규직 | 피킹·포장 | `OP-8038ac76-edf2-4937-9e45-59671e679797` |
| `mfcw-pp-06@pmart.com` | MFC시험 피킹포장 06 | 알바(~2026-12-31) | 피킹·포장 | `OP-080aeae8-e5c4-427f-90fa-104fe45c5836` |
| `mfcw-pp-07@pmart.com` | MFC시험 피킹포장 07 | 정규직 | 피킹·포장 | `OP-4579139c-5f6a-4a34-9e8f-0f058c722d95` |
| `mfcw-pp-08@pmart.com` | MFC시험 피킹포장 08 | 알바(~2026-12-31) | 피킹·포장 | `OP-3bee6f7f-cafa-4d28-b2d4-a86dd82b7bf6` |
| `mfcw-pp-09@pmart.com` | MFC시험 피킹포장 09 | 정규직 | 피킹·포장 | `OP-37b29428-0c75-4797-8215-a181d882819a` |
| `mfcw-pp-10@pmart.com` | MFC시험 피킹포장 10 | 알바(~2026-12-31) | 피킹·포장 | `OP-e2690ec8-1b97-4c58-93d8-56d0c7b03138` |
| `mfcw-pp-11@pmart.com` | MFC시험 피킹포장 11 | 정규직 | 피킹·포장 | `OP-990d2c09-a657-43d5-9080-59576dc7a59e` |
| `mfcw-pp-12@pmart.com` | MFC시험 피킹포장 12 | 알바(~2026-12-31) | 피킹·포장 | `OP-f6a62074-484b-4cc8-9e61-35c78afff6a3` |
| `mfcw-pp-13@pmart.com` | MFC시험 피킹포장 13 | 정규직 | 피킹·포장 | `OP-14a39c59-e152-4fad-8edb-f11140fd2428` |
| `mfcw-pp-14@pmart.com` | MFC시험 피킹포장 14 | 알바(~2026-12-31) | 피킹·포장 | `OP-e848a746-0b42-49a1-90ee-ddd4a52e77de` |
| `mfcw-pp-15@pmart.com` | MFC시험 피킹포장 15 | 정규직 | 피킹·포장 | `OP-69cc412a-b60f-4419-a968-28930653b230` |
| `mfcw-pp-16@pmart.com` | MFC시험 피킹포장 16 | 알바(~2026-12-31) | 피킹·포장 | `OP-f4d34240-11b2-4c51-9848-5e572814ad06` |
| `mfcw-pp-17@pmart.com` | MFC시험 피킹포장 17 | 정규직 | 피킹·포장 | `OP-7bf754c3-7b2c-4ede-ab96-80239ac5f203` |
| `mfcw-pp-18@pmart.com` | MFC시험 피킹포장 18 | 알바(~2026-12-31) | 피킹·포장 | `OP-45b79928-d367-4fd1-a8d6-c2cb5406ee3c` |
| `mfcw-pp-19@pmart.com` | MFC시험 피킹포장 19 | 정규직 | 피킹·포장 | `OP-f4b18451-ec2f-4e72-90c5-e620cd16d84c` |
| `mfcw-pp-20@pmart.com` | MFC시험 피킹포장 20 | 알바(~2026-12-31) | 피킹·포장 | `OP-25215bda-6700-458b-ae27-177f03d28786` |
| `mfcw-pp-21@pmart.com` | MFC시험 피킹포장 21 | 정규직 | 피킹·포장 | `OP-d375a6b7-08f0-44cc-bbb6-d72af25e514d` |
| `mfcw-pp-22@pmart.com` | MFC시험 피킹포장 22 | 알바(~2026-12-31) | 피킹·포장 | `OP-3587f416-1111-4899-92c7-15b8c061cdd8` |
| `mfcw-pp-23@pmart.com` | MFC시험 피킹포장 23 | 정규직 | 피킹·포장 | `OP-d7d3172e-c03c-4970-9686-194b67792d30` |
| `mfcw-pp-24@pmart.com` | MFC시험 피킹포장 24 | 알바(~2026-12-31) | 피킹·포장 | `OP-d5385ced-db80-4cb6-a4d5-fcbde550cc2a` |
| `mfcw-pp-25@pmart.com` | MFC시험 피킹포장 25 | 정규직 | 피킹·포장 | `OP-c5d4f580-ab53-4367-a7da-62189d81427e` |
| `mfcw-pp-26@pmart.com` | MFC시험 피킹포장 26 | 알바(~2026-12-31) | 피킹·포장 | `OP-c9749cb7-2ad8-4872-8bce-4470aa45458e` |
| `mfcw-pp-27@pmart.com` | MFC시험 피킹포장 27 | 정규직 | 피킹·포장 | `OP-12b36412-adab-43ad-8512-e53f797a35ef` |
| `mfcw-pp-28@pmart.com` | MFC시험 피킹포장 28 | 알바(~2026-12-31) | 피킹·포장 | `OP-3446d900-11a3-4a21-b4c3-36f00fa0f543` |
| `mfcw-pp-29@pmart.com` | MFC시험 피킹포장 29 | 정규직 | 피킹·포장 | `OP-0c581dbd-65f6-4463-92f5-71a08518a27b` |
| `mfcw-pp-30@pmart.com` | MFC시험 피킹포장 30 | 알바(~2026-12-31) | 피킹·포장 | `OP-a923cf43-c40d-4079-b808-9ddf1b1a0173` |
| `mfcw-rcv-01@pmart.com` | MFC시험 입고 01 | 정규직 | 입고·적치 | `OP-abcdbfca-d78c-47d3-945d-793d2ad3263a` |
| `mfcw-rcv-02@pmart.com` | MFC시험 입고 02 | 알바(~2026-12-31) | 입고·적치 | `OP-64c84da2-5a6f-46ad-ac23-c7a7dd2386a4` |
| `mfcw-rcv-03@pmart.com` | MFC시험 입고 03 | 정규직 | 입고·적치 | `OP-3a2d2d80-2568-4306-9cbe-cae15c215177` |
| `mfcw-rcv-04@pmart.com` | MFC시험 입고 04 | 알바(~2026-12-31) | 입고·적치 | `OP-0077bc39-2af9-4450-b134-df8b7683836d` |
| `mfcw-rcv-05@pmart.com` | MFC시험 입고 05 | 정규직 | 입고·적치 | `OP-db955d92-fdfe-422a-95b8-a35b046f158a` |
| `mfcw-rcv-06@pmart.com` | MFC시험 입고 06 | 알바(~2026-12-31) | 입고·적치 | `OP-cba2527d-9176-44d2-9db6-80b766d9f39e` |
| `mfcw-rcv-07@pmart.com` | MFC시험 입고 07 | 정규직 | 입고·적치 | `OP-6462942a-7943-46bc-8964-cbee2eeb5f6d` |
| `mfcw-rcv-08@pmart.com` | MFC시험 입고 08 | 알바(~2026-12-31) | 입고·적치 | `OP-2d869e49-fe42-4129-be1c-a7bcde614044` |
| `mfcw-rcv-09@pmart.com` | MFC시험 입고 09 | 정규직 | 입고·적치 | `OP-606cc5c5-a415-4e66-a2a9-023354197e58` |
| `mfcw-rcv-10@pmart.com` | MFC시험 입고 10 | 알바(~2026-12-31) | 입고·적치 | `OP-8644e1eb-5385-40bb-84b3-2e2a76e1a196` |
| `mfcw-qc-01@pmart.com` | MFC시험 품질검사 01 | 정규직 | 품질검사 | `OP-070143fc-9048-4670-821b-eefeae8bfe61` |
| `mfcw-qc-02@pmart.com` | MFC시험 품질검사 02 | 알바(~2026-12-31) | 품질검사 | `OP-72be6776-21ca-4210-b65d-5ee07318d053` |
| `mfcw-qc-03@pmart.com` | MFC시험 품질검사 03 | 정규직 | 품질검사 | `OP-bd5252ab-3296-4d76-9a9a-457fc8240ce5` |
| `mfcw-qc-04@pmart.com` | MFC시험 품질검사 04 | 알바(~2026-12-31) | 품질검사 | `OP-44b350f3-9217-45af-a0ea-85a176572966` |
| `mfcw-qc-05@pmart.com` | MFC시험 품질검사 05 | 정규직 | 품질검사 | `OP-4d205f70-53f2-4468-b2a7-525378640e66` |
| `mfcw-qc-06@pmart.com` | MFC시험 품질검사 06 | 알바(~2026-12-31) | 품질검사 | `OP-a6d84019-8422-432d-a62e-88a8681fa1c0` |
| `mfcw-qc-07@pmart.com` | MFC시험 품질검사 07 | 정규직 | 품질검사 | `OP-0440ac7a-3dbc-4e2d-9933-fc3ecbcac6eb` |
| `mfcw-qc-08@pmart.com` | MFC시험 품질검사 08 | 알바(~2026-12-31) | 품질검사 | `OP-3101baf4-743d-4bdb-b42d-5ee198785bc8` |
| `mfcw-qc-09@pmart.com` | MFC시험 품질검사 09 | 정규직 | 품질검사 | `OP-83a43077-e4cc-4e8e-bb81-51056fd26c5e` |
| `mfcw-qc-10@pmart.com` | MFC시험 품질검사 10 | 알바(~2026-12-31) | 품질검사 | `OP-4aa604d5-1163-4dc8-a05b-da1d6ce02d13` |
| `mfcw-mv-01@pmart.com` | MFC시험 재고이동 01 | 정규직 | 이동·보충 | `OP-4ce6d304-9495-48a9-bd2b-6768c37f766d` |
| `mfcw-mv-02@pmart.com` | MFC시험 재고이동 02 | 알바(~2026-12-31) | 이동·보충 | `OP-385e00d8-9e03-489e-b49a-f4dcdfeed06b` |
| `mfcw-mv-03@pmart.com` | MFC시험 재고이동 03 | 정규직 | 이동·보충 | `OP-521f559d-5379-420b-b0e7-495259a14898` |
| `mfcw-mv-04@pmart.com` | MFC시험 재고이동 04 | 알바(~2026-12-31) | 이동·보충 | `OP-8bf536f2-4003-4746-966e-38c434cfd1d1` |
| `mfcw-mv-05@pmart.com` | MFC시험 재고이동 05 | 정규직 | 이동·보충 | `OP-a1f3393e-822c-4b76-bae2-642bb8a3e945` |
| `mfcw-mv-06@pmart.com` | MFC시험 재고이동 06 | 알바(~2026-12-31) | 이동·보충 | `OP-a24ac546-768e-4e79-aa66-55be60765e89` |
| `mfcw-mv-07@pmart.com` | MFC시험 재고이동 07 | 정규직 | 이동·보충 | `OP-b48dc76b-7e7b-40ae-9247-f3e980675460` |
| `mfcw-mv-08@pmart.com` | MFC시험 재고이동 08 | 알바(~2026-12-31) | 이동·보충 | `OP-fc198d05-fe90-488a-8740-c657fe8863a1` |
| `mfcw-mv-09@pmart.com` | MFC시험 재고이동 09 | 정규직 | 이동·보충 | `OP-1c5751f5-a320-49a6-8ccd-ca86c02f061f` |
| `mfcw-mv-10@pmart.com` | MFC시험 재고이동 10 | 알바(~2026-12-31) | 이동·보충 | `OP-257adb59-1d0c-4b68-b4ff-c294649d3711` |
| `mfcw-rtn-01@pmart.com` | MFC시험 반품 01 | 정규직 | 반품 | `OP-91fbe3e5-6877-4afc-bdfb-c5b726db0ebb` |
| `mfcw-rtn-02@pmart.com` | MFC시험 반품 02 | 알바(~2026-12-31) | 반품 | `OP-ff3c37ea-7ed4-4b6d-806f-00efe9a3897a` |
| `mfcw-rtn-03@pmart.com` | MFC시험 반품 03 | 정규직 | 반품 | `OP-fc11135d-2339-4e7f-aa69-dd0519ca3e80` |
| `mfcw-rtn-04@pmart.com` | MFC시험 반품 04 | 알바(~2026-12-31) | 반품 | `OP-f7de2320-2006-4c7b-afeb-f6c0b7a61344` |
| `mfcw-rtn-05@pmart.com` | MFC시험 반품 05 | 정규직 | 반품 | `OP-092ab89c-0638-4c60-a1c2-5ba4034169b9` |
| `mfcw-rtn-06@pmart.com` | MFC시험 반품 06 | 알바(~2026-12-31) | 반품 | `OP-8293d8f5-a32d-4b90-b3eb-337030031948` |
| `mfcw-rtn-07@pmart.com` | MFC시험 반품 07 | 정규직 | 반품 | `OP-3fdc9724-9ec3-45b2-b5ba-def7ce6e6703` |
| `mfcw-rtn-08@pmart.com` | MFC시험 반품 08 | 알바(~2026-12-31) | 반품 | `OP-8ac742b7-5d98-4065-a111-21cc1a3c2908` |
| `mfcw-rtn-09@pmart.com` | MFC시험 반품 09 | 정규직 | 반품 | `OP-ab09b1b5-5f20-4f5d-96d0-ebca434d4add` |
| `mfcw-rtn-10@pmart.com` | MFC시험 반품 10 | 알바(~2026-12-31) | 반품 | `OP-aa4238cd-013d-4e76-939e-2c54bdf7677c` |

## 3. 고객·라이더 시험 계정

- 고객: 실서버 로그인은 Google 만이라 고정 시험 계정이 없다. 고객 RAG 평가 등에서 쓰는 가상 계정 `ACC-claude-rag-eval` 토큰은 PC 1 이 auth-service LAN 서명키로 만든다(**쓸 때마다 사용자 허락**, 15~20분, `.secrets/customer-token`).
- 라이더: rider-auth 로그인도 Google — 고정 시험 계정 없음.

## 4. 고칠 때
- 계정을 더하거나 역할·센터를 바꾸면 이 문서를 같이 고친다. 70명을 다시 만들거나 확인하려면 위 스크립트(`--check`).
