"""LAN 시험 센터 칸을 임시 MFC 도면(시뮬레이터 평면)대로 맞춘다 — scope L-1(2026-11-09 사용자 결정). 모두 API 로(DB 직접 입력 없음).

단계(각각 다시 돌려도 된다 — 이미 된 것은 건너뜀):
  dims      치수가 없는 도면 칸에 치수(랙 규격으로 계산)를 넣는다 — 재고 이동 용량 계산에 필요.
  httest    E2E 시험용 고회전 칸(상온 H1 줄 빈 칸 5개) 지정, 옛 고회전 칸(A-S9)의 보충 기준·열린 보충 지시 정리.
  register  도면의 선반 칸(368 랙 × 단 × 칸 = 6,762)·보충재고 팔레트 자리(32)를 백엔드 코드 문법으로 등록(위치 position·동선 순서 포함).
            선반 칸은 일반 선반(highTurnover=false — 사용자 2026-11-09 "일단 임시로 일반 선반만", 특정 그룹 고회전은 나중).
  support   보조 위치(포장·카트·수령·격리·출고 대기)에 도면 좌표를 넣고 없는 것은 등록.
  reserve   보충재고 팔레트 자리(`{온도대}-R1-NN-1-01`)에 reserveStock 표시(2026-11-21, R-30 — mfc-location 0.9.0). 이미 표시된 칸은 건너뜀.
  move      옛 선반(A-S1/C-S1/F-S1 계열 38칸)의 재고를 새 칸으로 위치 정정(stock-corrections, 총량 불변). SKU 하나 = 새 칸 하나(같은 온도대).
            예약·배정이 걸린 SKU 는 옮기지 않는다(그 옛 칸은 열어 둔다).
  close     비게 된 옛 선반을 CLOSED(기록은 남음).
  r31       (2026-11-21 R-33 으로 취소 — 아무것도 닫지 않는다) 예전: C/F-R1-03·04·A-R1-19~24 팔레트 칸 CLOSED.
  r33       보충재고 랙 칸 990개(상온 42랙·냉동 12랙·냉장 12랙 × 5단 × 3칸, 시뮬레이터 R-33 — 사용자 결정 2026-11-21 "보충재고는 랙으로")
            + 대형 묶음 바닥 팔레트 20칸(A-R2-01~20-1-01, 보충재고 — 생수·음료·주류, 업무 문서 5-1 대형 적재) 등록·위치·치수 맞춤.
            같은 코드의 옛 팔레트 칸은 위치·치수만 바꾸고, r31 이 닫았던 칸은 다시 연다.
            칸 목록: docs/simulator/mfc/layout/reserve-rack-cells.json·bulk-pallet-cells.json(시뮬레이터 docs/layout-export/ 사본, f7e5d56 — 배치가 바뀌면 다시 복사).
실행: python -I deploy/lan/seed/mfc_layout.py [register support move close ...]   (단계 없이 = 계획만 출력)
다른 센터·배치: PMART_MFC=<센터 ID> PMART_LAYOUT=<layout.json 경로> 로 같은 단계(register·support)를 다시 돌린다.
필요: deploy/lan/.secrets/keycloak-admin-password. 센터 관리자 시험 계정 claude-mfc-sup 의 Keycloak 비밀번호를 새로 정해 직원 토큰을 받는다(checks/staff_auth.py).
코드 대응: 시뮬레이터 `A01-01-101`(그룹-랙-단·칸) → `F-A1-01-1-01`({온도대 A/C/F}-{그룹 글자+줄 번호}-{랙}-{단}-{칸}). 팔레트 → `{온도대}-R1-{번호}-1-01`.
좌표 = 도면의 작업자 서는 지점(m, x 동쪽·z 남쪽). 도면: docs/simulator/mfc/layout/ (layout.json).
"""
import json
import os
import secrets
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", ".."))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "checks"))
from staff_auth import staff_token  # noqa: E402
GW = os.environ.get("PMART_GATEWAY", "http://192.168.0.40:8080/api/v1")
INV = os.environ.get("PMART_INVENTORY", "http://192.168.0.222:8301/api/v1")
MFC = os.environ.get("PMART_MFC", "MFC-SEED-01")
# 배치 파일 — 배치가 바뀌거나 새 센터면 PMART_MFC 와 함께 바꿔 다시 돌린다(칸 코드로 맞춰 좌표만 덮어씀, 2026-11-21)
LAYOUT = os.environ.get("PMART_LAYOUT", os.path.join(ROOT, "docs", "simulator", "mfc", "layout", "layout.json"))
APPROVAL_REF = "layout-v1"
RESERVE_RACKS = os.environ.get("PMART_RESERVE_RACKS",
                               os.path.join(ROOT, "docs", "simulator", "mfc", "layout", "reserve-rack-cells.json"))
BULK_PALLETS = os.environ.get("PMART_BULK_PALLETS",
                              os.path.join(ROOT, "docs", "simulator", "mfc", "layout", "bulk-pallet-cells.json"))
TEMP_LETTER = {"FROZEN": "F", "CHILLED": "C", "AMBIENT": "A"}
OLD_SHELF_AREAS = ("S1", "S9")  # 옛 시험 선반 A-S1-*, C-S1-*, F-S1-* 와 옛 고회전 시험 칸 A-S9-01-1-01
HT_TEST_AREA, HT_TEST_COUNT = "H1", 5  # E2E 시험용 고회전 칸: 상온 H01 줄의 빈 칸 앞에서 5개(사용자 2026-11-09 — 일반 선반 중 시험용 몇 칸만)
RACK_LEN, RACK_HEIGHT, RACK_DEPTH_MM = 1.25, 2.1, 450  # 시뮬레이터 layout.ts 랙 규격(MFC 문서 1장 2,100×1,250×450)


def secret(name):
    with open(os.path.join(ROOT, "deploy", "lan", ".secrets", name), encoding="utf-8") as f:
        return f.read().strip()


def http(method, url, token=None, body=None, version=None):
    headers = {"Accept": "application/json, application/problem+json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if method != "GET":
        headers["Idempotency-Key"] = str(uuid.uuid4())
        headers["X-Source-System"] = "claude-mfc-layout"
    if version is not None:
        headers["X-Expected-Version"] = str(version)
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    for attempt in range(8):
        req = urllib.request.Request(url, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                raw = r.read()
                return r.status, (json.loads(raw) if raw else {})
        except urllib.error.HTTPError as e:
            raw = e.read()
            try:
                b = json.loads(raw)
            except Exception:
                b = {"raw": raw[:200].decode("utf-8", "replace")}
            if e.code in (429, 503) and attempt < 7:
                time.sleep(1.5 * (attempt + 1))
                continue
            return e.code, b
    return 0, {}


class Session:
    """센터 관리자 시험 계정 토큰(만료 전에 다시 받음) — employee-auth-service 직원 토큰(checks/staff_auth.py, 2026-11-13)."""

    def __init__(self):
        self.token = None
        self.at = 0.0

    def sup(self):
        if self.token and time.time() - self.at < 600:
            return self.token
        self.token, _ = staff_token("claude-mfc-sup")  # 역할·센터는 그대로(MFC_SUPERVISOR, MFC-SEED-01)
        self.at = time.time()
        return self.token


def current_locations(s):
    out, cursor = {}, None
    while True:
        u = f"{GW}/locations?mfcId={MFC}&size=200" + (f"&cursor={urllib.parse.quote(cursor)}" if cursor else "")
        st, b = http("GET", u, s.sup())
        if st != 200:
            raise SystemExit(f"위치 목록 실패 HTTP {st} {b}")
        for it in b.get("items", []):
            out[it["locationCode"]] = it
        cursor = (b.get("page") or {}).get("nextCursor")
        if not cursor:
            return out


def snapshot():
    rows, cursor = [], None
    while True:
        u = f"{INV}/internal/snapshots?mfcId={MFC}&size=100" + (f"&cursor={urllib.parse.quote(cursor)}" if cursor else "")
        with urllib.request.urlopen(urllib.request.Request(u, headers={"Accept": "application/json"}), timeout=60) as r:
            b = json.loads(r.read())
        rows += b["items"]
        cursor = (b.get("page") or {}).get("nextCursor")
        if not cursor:
            return rows


def center(rect):
    return round((rect["x0"] + rect["x1"]) / 2, 2), round((rect["z0"] + rect["z1"]) / 2, 2)


def plan():
    L = json.load(open(LAYOUT, encoding="utf-8"))
    racks = {r["id"]: r for r in L["racks"]}
    shelves = []
    for seq, loc in enumerate(L["locations"], start=1):
        r = racks[loc["rackId"]]
        t = TEMP_LETTER[loc["temp"]]
        area = r["group"][0] + str(int(r["group"][1:]))
        code = f"{t}-{area}-{r['rackNo']:02d}-{loc['level']}-{loc['slot']:02d}"
        shelves.append({"locationCode": code, "zone": "STORAGE", "temperatureZone": loc["temp"],
                        "pickSequence": seq, "highTurnover": False, "sim": loc["code"], "rackId": loc["rackId"],
                        "position": {"xM": loc["pickX"], "zM": loc["pickZ"]},
                        # 칸 치수 = 도면 랙 규격(길이 1.25 m 의 90 % 를 칸 수로, 높이 2.1 m 를 단 수로, 깊이 0.45 m) — 재고 이동 용량 계산 입력
                        "dimensions": {"widthMm": round(RACK_LEN * 900 / r["slotsPerLevel"]),
                                       "depthMm": RACK_DEPTH_MM, "heightMm": round(RACK_HEIGHT * 1000 / r["levels"]),
                                       "slotCount": 1}})
    n_by_temp = {}
    for rs in L["reserves"]:
        t = TEMP_LETTER[rs["temp"]]
        n_by_temp[t] = n_by_temp.get(t, 0) + 1
        rr = rs["rect"]
        shelves.append({"locationCode": f"{t}-R1-{n_by_temp[t]:02d}-1-01", "zone": "STORAGE",
                        "temperatureZone": rs["temp"], "pickSequence": None, "highTurnover": False,
                        "reserveStock": True, "sim": rs["id"],
                        "position": {"xM": round(rs["stand"]["x"], 2), "zM": round(rs["stand"]["z"], 2)},
                        # 팔레트 자리 = 도면 사각형, 높이 1.2 m(시뮬레이터 가정 없음 — 임시 구현값)
                        "dimensions": {"widthMm": round((rr["x1"] - rr["x0"]) * 1000),
                                       "depthMm": round((rr["z1"] - rr["z0"]) * 1000), "heightMm": 1200, "slotCount": 1}})
    zones = {z["id"]: z for z in L["zones"]}
    P = L["points"]
    support = []
    for st in L["stations"]:
        n = int(st["id"].split("-")[1])
        support.append({"locationCode": f"PAK-A-{n:02d}", "zone": "PACKING", "temperatureZone": "AMBIENT",
                        "position": {"xM": round(st["stand"]["x"], 2), "zM": round(st["stand"]["z"], 2)}})
    pq = {"xM": round(P["packQueue"]["x"], 2), "zM": round(P["packQueue"]["z"], 2)}
    support.append({"locationCode": "PAK-C-01", "zone": "PACKING", "temperatureZone": "CHILLED", "position": pq})
    support.append({"locationCode": "PAK-F-01", "zone": "PACKING", "temperatureZone": "FROZEN", "position": pq})
    for zid, code, temp in (("AQ", "QRT-F-01", "FROZEN"), ("BQ", "QRT-C-01", "CHILLED"), ("CQ", "QRT-A-01", "AMBIENT")):
        x, z = center(zones[zid]["rect"])
        support.append({"locationCode": code, "zone": "QUARANTINE", "temperatureZone": temp, "position": {"xM": x, "zM": z}})
    x, z = center(zones["RCV"]["rect"])
    support.append({"locationCode": "RCV-A-01", "zone": "RECEIVING", "temperatureZone": "AMBIENT", "position": {"xM": x, "zM": z}})
    # 저온 입고칸(2026-11-21, 시뮬레이터 R-31 — 사용자 결정: 냉장·냉동 입고재고는 각 저온구역 안, 저온 팔레트 오른쪽 줄 2개 자리).
    # 좌표는 시뮬레이터 평면 값(layout.json 갱신 전)
    for code, temp, xm in (("RCV-C-01", "CHILLED", 21.55), ("RCV-F-01", "FROZEN", 9.95)):
        support.append({"locationCode": code, "zone": "RECEIVING", "temperatureZone": temp, "position": {"xM": xm, "zM": 28.1}})
    # 상온 입고재고 칸(2026-11-21, R-32 — 사용자 "입고창고는 보충재고랑 입고재고 차이가 구별이 안나").
    # R-33 정정(시뮬레이터 f7e5d56): 사용자 "보충재고 앞, 입고재고 뒤" + 업무 문서 6-5 "상온 5자리" — 입고창고 북쪽 팔레트 5개
    # (x 39.05~46.15 · z 0.5~1.6)의 가운데. 옛 값 z 9.65(남쪽 줄 6개)
    support.append({"locationCode": "RCV-A-02", "zone": "RECEIVING", "temperatureZone": "AMBIENT", "position": {"xM": 42.6, "zM": 1.05}})
    # 회수(반품) 위치 — 시뮬레이터 평면에 회수 구역이 없어 입고·검수 구역 서쪽 아래(하차 적치 칸·입고 작업자 대기 자리 밖)에 둔다
    # (사용자 2026-11-09 "그렇게 해줘": 회수품도 입고처럼 그 문으로 들어온다). 백엔드가 정한 자리 — backend-points.json 으로 도면에 표시
    support.append({"locationCode": "RTN-A-01", "zone": "RETURN", "temperatureZone": "AMBIENT", "position": {"xM": 38.6, "zM": 22.0}})
    ship = [o for o in L["obstacles"] if o["kind"] == "SHIP_RACK"]
    for i, o in enumerate(ship, start=1):
        x, z = center(o["rect"])
        support.append({"locationCode": f"DSP-A-{i:02d}", "zone": "DISPATCH_STAGING", "temperatureZone": "AMBIENT",
                        "position": {"xM": x, "zM": z}})
    cart = {"xM": round(P["cartDepot"]["x"], 2), "zM": round(P["cartDepot"]["z"], 2)}
    for code, temp in (("CRT-A-01", "AMBIENT"), ("CRT-A-02", "AMBIENT"), ("CRT-C-01", "CHILLED"), ("CRT-F-01", "FROZEN")):
        support.append({"locationCode": code, "zone": "CART", "temperatureZone": temp, "position": cart})
    return shelves, support


def body_of(item):
    b = {"mfcId": MFC, "locationCode": item["locationCode"], "zone": item["zone"],
         "temperatureZone": item["temperatureZone"], "highTurnover": bool(item.get("highTurnover", False)),
         "reserveStock": bool(item.get("reserveStock", False)), "position": item["position"]}
    if item.get("pickSequence") is not None:
        b["pickSequence"] = item["pickSequence"]
    if item.get("dimensions"):
        b["dimensions"] = item["dimensions"]
    return b


def dims_step(s, shelves, existing):
    """치수가 없는 도면 칸에 치수를 넣는다(2026-11-09 — 처음 등록 때 빠뜨림, 재고 이동이 CONFIG_REQUIRED)."""
    done = same = failed = 0
    for i, it in enumerate(shelves):
        cur = existing.get(it["locationCode"])
        if cur is None:
            continue
        if cur.get("dimensions"):
            same += 1
            continue
        b = keep_body(cur, cur.get("position"))
        b["dimensions"] = it["dimensions"]
        st, bb = http("PUT", f"{GW}/locations/{cur['locationId']}", s.sup(), b, version=cur["version"])
        if st == 200:
            done += 1
        else:
            failed += 1
            if failed <= 5:
                print("  치수 넣기 실패", it["locationCode"], st, bb.get("code"), bb.get("detail"))
        if (i + 1) % 1000 == 0:
            print(f"  … {i + 1}/{len(shelves)} (넣음 {done}, 있음 {same}, 실패 {failed})", flush=True)
        time.sleep(0.05)
    print(f"치수: 넣음 {done}, 이미 있음 {same}, 실패 {failed}")


def keep_body(cur, position):
    """속성 변경(PUT 전체 교체)용 — 지금 값을 그대로 두고 position 만 넣는다."""
    b = {"mfcId": MFC, "locationCode": cur["locationCode"], "zone": cur["zone"],
         "temperatureZone": cur["temperatureZone"], "highTurnover": bool(cur.get("highTurnover")),
         "reserveStock": bool(cur.get("reserveStock")), "position": position}
    for k in ("areaCode", "pickSequence", "categoryId", "dimensions"):
        if cur.get(k) is not None:
            b[k] = cur[k]
    return b


def register(s, items, existing):
    made = skipped = failed = 0
    for i, it in enumerate(items):
        if it["locationCode"] in existing:
            skipped += 1
            continue
        st, b = http("POST", f"{GW}/locations", s.sup(), body_of(it))
        if st == 201:
            made += 1
        elif st == 409 and b.get("code") == "LOCATION_CODE_DUPLICATE":
            skipped += 1
        else:
            failed += 1
            if failed <= 5:
                print("  등록 실패", it["locationCode"], st, b.get("code"), b.get("detail"))
        if (i + 1) % 500 == 0:
            print(f"  … {i + 1}/{len(items)} (새로 {made}, 있음 {skipped}, 실패 {failed})", flush=True)
        time.sleep(0.05)
    print(f"등록: 새로 {made}, 이미 있음 {skipped}, 실패 {failed}")
    return failed


def support_step(s, support, existing):
    made = updated = same = failed = 0
    for it in support:
        cur = existing.get(it["locationCode"])
        if cur is None:
            st, b = http("POST", f"{GW}/locations", s.sup(), body_of(it))
            made += st == 201
            failed += st != 201
            if st != 201:
                print("  보조 위치 등록 실패", it["locationCode"], st, b.get("code"), b.get("detail"))
            continue
        if cur.get("position") == it["position"]:
            same += 1
            continue
        st, b = http("PUT", f"{GW}/locations/{cur['locationId']}", s.sup(), keep_body(cur, it["position"]),
                     version=cur["version"])
        updated += st == 200
        if st != 200:
            failed += 1
            print("  좌표 넣기 실패", it["locationCode"], st, b.get("code"), b.get("detail"))
    print(f"보조 위치: 새로 {made}, 좌표 넣음 {updated}, 그대로 {same}, 실패 {failed}")
    return failed


def reserve_step(s, shelves, existing):
    """보충재고 팔레트 자리에 reserveStock 표시(2026-11-21, R-30 — 사용자 결정 "선반칸이 가득차면 남는 건 입고 창고에").
    표시하면 한 상품 한 자리 규칙에서 빠지고, 선반이 모자랄 때만 피킹, 일반 선반 기본 보충의 출발지(mfc-inventory 0.44.0)."""
    done = same = failed = 0
    for it in shelves:
        if not it.get("reserveStock"):
            continue
        cur = existing.get(it["locationCode"])
        if cur is None:
            continue
        if cur.get("reserveStock"):
            same += 1
            continue
        b = keep_body(cur, cur.get("position"))
        b["reserveStock"] = True
        st, bb = http("PUT", f"{GW}/locations/{cur['locationId']}", s.sup(), b, version=cur["version"])
        if st == 200:
            done += 1
        else:
            failed += 1
            print("  보충재고 표시 실패", it["locationCode"], st, bb.get("code"), bb.get("detail"))
    print(f"보충재고 칸: 표시 {done}, 이미 {same}, 실패 {failed}")
    return failed


def old_shelf(code):
    parts = code.split("-")
    return len(parts) == 5 and parts[0] in ("A", "C", "F") and parts[1] in OLD_SHELF_AREAS


def httest_step(s, shelves, existing):
    """옛 고회전 칸(A-S9) 대신 도면 칸 몇 개를 시험용 고회전으로. 옛 칸에 걸린 보충 기준·열린 보충 지시는 지운다
    (보충 업무 작업자가 없어 지시가 미배정으로 남아 일반 선반 재고를 붙잡았다 — 2026-11-09)."""
    rows = snapshot()
    occupied = {r["locationCode"] for r in rows}
    cands = [x for x in shelves if x["pickSequence"] is not None and x["temperatureZone"] == "AMBIENT"
             and x["locationCode"].split("-")[1] == HT_TEST_AREA]
    already = [x["locationCode"] for x in cands if existing.get(x["locationCode"], {}).get("highTurnover")]
    need = HT_TEST_COUNT - len(already)
    for x in cands:
        if need <= 0:
            break
        cur = existing[x["locationCode"]]
        if cur.get("highTurnover") or x["locationCode"] in occupied:
            continue
        b = keep_body(cur, cur.get("position"))
        b["highTurnover"] = True
        st, bb = http("PUT", f"{GW}/locations/{cur['locationId']}", s.sup(), b, version=cur["version"])
        if st == 200:
            already.append(x["locationCode"])
            need -= 1
        else:
            print("  고회전 지정 실패", x["locationCode"], st, bb.get("code"), bb.get("detail"))
    print("시험용 고회전 칸:", already)
    # 도면에 강조할 목록(docs/simulator/mfc/layout/high-turnover.json → tools/draw.py 세 번째 인자)
    by_code = {x["locationCode"]: x for x in shelves}
    bins = [{"backend": c, "sim": by_code[c]["sim"], "rackId": by_code[c]["rackId"],
             "pick": [by_code[c]["position"]["xM"], by_code[c]["position"]["zM"]]} for c in sorted(already)]
    with open(os.path.join(os.path.dirname(LAYOUT), "high-turnover.json"), "w", encoding="utf-8") as f:
        json.dump({"_comment": "E2E 시험용 고회전 칸(사용자 2026-11-09 — 선반은 일반 선반, 시험용으로 이 칸만 고회전). "
                               "mfc_layout.py httest 가 지정·갱신. 도면 draw.py 가 강조해 그린다.", "bins": bins},
                  f, ensure_ascii=False, indent=1)
    st, b = http("GET", f"{GW}/mfcs/{MFC}/replenishment-policies", s.sup())
    for p in b.get("items", []) if st == 200 else []:
        if old_shelf(p["locationCode"]):
            st2, _ = http("DELETE", f"{GW}/mfcs/{MFC}/replenishment-policies?locationCode={urllib.parse.quote(p['locationCode'])}"
                          f"&sku={urllib.parse.quote(p['sku'])}", s.sup())
            print("  옛 칸 보충 기준 삭제", p["locationCode"], p["sku"], p.get("minQty"), p.get("targetQty"), p.get("unitQty"), st2)
    st, b = http("GET", f"{GW}/move-orders?mfcId={MFC}", s.sup())
    for mv in b.get("items", []) if st == 200 else []:
        if mv.get("state") == "ACTIVE" and (old_shelf(mv.get("fromLocation") or "") or old_shelf(mv.get("toLocation") or ""))                 and (mv.get("pickedQty") or 0) == 0:
            st2, bb = http("POST", f"{GW}/move-orders/{mv['moveId']}/cancellations", s.sup(),
                           {"reason": "옛 시험 칸 정리 — 도면 칸으로 이전(scope L-1)"})
            print("  옛 칸 이동 지시 취소", mv["moveId"], st2, bb.get("state") or bb.get("code"))


def move_step(s, shelves, existing, apply):
    rows = snapshot()
    src = [r for r in rows if old_shelf(r["locationCode"])]
    blocked_skus = {r["sku"] for r in src if (r.get("allocatedQty") or 0) + (r.get("heldQty") or 0) > 0}
    # 이미 새 칸에 있는 SKU 는 그 칸으로(다시 돌릴 때)
    new_codes = {x["locationCode"] for x in shelves if x["pickSequence"] is not None}
    target = {}
    for r in rows:
        if r["locationCode"] in new_codes:
            target[r["sku"]] = r["locationCode"]
    used = set(target.values())
    free = {t: [x["locationCode"] for x in shelves if x["pickSequence"] is not None and x["temperatureZone"] == t]
            for t in ("AMBIENT", "CHILLED", "FROZEN")}
    lines_by_src = {}
    temp_of = {c: v["temperatureZone"] for c, v in existing.items()}
    skipped = 0
    for r in src:
        if r["sku"] in blocked_skus:
            skipped += 1
            continue
        t = temp_of.get(r["locationCode"])
        if r["sku"] not in target:
            while free[t] and free[t][0] in used:
                free[t].pop(0)
            if not free[t]:
                raise SystemExit(f"{t} 빈 칸 부족")
            target[r["sku"]] = free[t].pop(0)
            used.add(target[r["sku"]])
        lines_by_src.setdefault(r["locationCode"], []).append(
            {"sku": r["sku"], "lotId": r["lotId"], "fromLocation": r["locationCode"],
             "qualityState": r["qualityState"], "workState": r["workState"], "qty": r["qty"],
             "toLocation": target[r["sku"]]})
    total = sum(len(v) for v in lines_by_src.values())
    print(f"옮길 줄 {total} (옛 칸 {len(lines_by_src)}개, 수량 {sum(l['qty'] for v in lines_by_src.values() for l in v)}), "
          f"예약·배정이 걸려 남기는 SKU {len(blocked_skus)}({skipped}줄)")
    if not apply:
        return 0
    failed = 0
    for code, lines in sorted(lines_by_src.items()):
        for i in range(0, len(lines), 200):
            chunk = lines[i:i + 200]
            st, b = http("POST", f"{GW}/mfcs/{MFC}/stock-corrections", s.sup(),
                         {"reason": "임시 MFC 도면 칸으로 이전(scope L-1)", "approvalRef": APPROVAL_REF, "lines": chunk})
            if st != 201:
                failed += 1
                print("  정정 실패", code, st, b.get("code"), b.get("detail"))
            else:
                print(f"  {code}: {len(chunk)}줄 옮김", flush=True)
    return failed


def close_step(s, existing):
    rows = snapshot()
    left = {r["locationCode"] for r in rows if r["qty"] > 0}
    closed = kept = 0
    for code, cur in sorted(existing.items()):
        if not old_shelf(code) or cur["state"] != "ACTIVE":
            continue
        if code in left:
            kept += 1
            print("  재고가 남아 열어 둠:", code)
            continue
        st, b = http("POST", f"{GW}/locations/{cur['locationId']}/state-changes", s.sup(),
                     {"targetState": "CLOSED", "reason": "임시 MFC 도면 칸으로 이전(scope L-1)"})
        closed += st == 202
        if st != 202:
            print("  닫기 실패", code, st, b.get("code"), b.get("detail"))
    print(f"옛 선반 닫음 {closed}, 열어 둠 {kept}")


# 2026-11-21 R-31·R-32 때 닫았던 팔레트 칸. R-33(보충재고 랙)에서 이 코드들이 보충재고 랙 칸이 되어 닫지 않는다 — r33 단계가 다시 연다.
R31_REOPEN = ("C-R1-03-1-01", "C-R1-04-1-01", "F-R1-03-1-01", "F-R1-04-1-01",
              "A-R1-19-1-01", "A-R1-20-1-01", "A-R1-21-1-01", "A-R1-22-1-01", "A-R1-23-1-01", "A-R1-24-1-01")
R31_CLOSE = ()  # R-33 으로 취소


def r31_step(s, existing):
    """(R-33 으로 취소 — R31_CLOSE 가 비어 아무것도 닫지 않는다. 닫았던 칸은 r33 이 다시 연다.)
    예전: 입고재고 자리가 된 보충재고 팔레트 칸을 닫았다(R-31·R-32, 2026-11-21). 재고가 남은 칸은 열어 둔다."""
    left = {r["locationCode"] for r in snapshot() if r["qty"] > 0}
    closed = kept = 0
    for code in R31_CLOSE:
        cur = existing.get(code)
        if cur is None or cur["state"] != "ACTIVE":
            continue
        if code in left:
            kept += 1
            print("  재고가 남아 열어 둠:", code)
            continue
        st, b = http("POST", f"{GW}/locations/{cur['locationId']}/state-changes", s.sup(),
                     {"targetState": "CLOSED", "reason": "저온 입고칸으로 바뀜(시뮬레이터 R-31)"})
        closed += st == 202
        if st != 202:
            print("  닫기 실패", code, st, b.get("code"), b.get("detail"))
    print(f"R-31·R-32 팔레트 칸 닫음 {closed}, 열어 둠 {kept}")


def r33_step(s, existing):
    """보충재고 랙 칸(R-33, 2026-11-21) — 990칸 + 대형 묶음 바닥 팔레트 20칸을 STORAGE·reserveStock 으로 등록하거나, 이미 있으면 위치·치수만 새 값으로.
    r31 이 닫은 칸(R31_REOPEN)은 다시 연다. 그 밖에 닫힌 칸은 그대로 두고 알린다. 재고는 건드리지 않는다 — 칸 용량을 넘는 칸은
    용량 계산이 0 으로 보고 새 적치를 다른 칸으로 보낸다(mfc-inventory LocationCapacity)."""
    cells = json.load(open(RESERVE_RACKS, encoding="utf-8")) + json.load(open(BULK_PALLETS, encoding="utf-8"))
    for c in cells:  # 두 파일 모두 보충재고 보관 칸이다(시뮬레이터 R-33) — 다른 값이 오면 멈춘다
        if c.get("zone", "STORAGE") != "STORAGE" or not c.get("reserveStock", True):
            raise SystemExit(f"보충재고 보관 칸이 아님: {c['locationCode']} {c.get('zone')} {c.get('reserveStock')}")
    made = updated = reopened = same = failed = 0
    for i, c in enumerate(cells):
        code = c["locationCode"]
        pos = {"xM": round(c["position"]["xM"], 3), "zM": round(c["position"]["zM"], 3)}
        dims = {"widthMm": c["dimMm"]["w"], "depthMm": c["dimMm"]["d"], "heightMm": c["dimMm"]["h"], "slotCount": 1}
        cur = existing.get(code)
        if cur is None:
            st, b = http("POST", f"{GW}/locations", s.sup(),
                         {"mfcId": MFC, "locationCode": code, "zone": "STORAGE", "temperatureZone": c["temperatureZone"],
                          "highTurnover": False, "reserveStock": True, "position": pos, "dimensions": dims})
            if st == 201:
                made += 1
            else:
                failed += 1
                print("  등록 실패", code, st, b.get("code"), b.get("detail"))
            time.sleep(0.05)
            if (i + 1) % 200 == 0:
                print(f"  … {i + 1}/{len(cells)} (새로 {made}, 바꿈 {updated}, 그대로 {same}, 실패 {failed})", flush=True)
            continue
        if cur["zone"] != "STORAGE" or cur["temperatureZone"] != c["temperatureZone"]:
            failed += 1
            print("  같은 코드가 다른 칸으로 있음(손대지 않음):", code, cur["zone"], cur["temperatureZone"], cur["state"])
            continue
        if cur["state"] == "CLOSED":
            if code not in R31_REOPEN:
                print("  닫힌 칸(r31 이 닫은 것이 아님 — 그대로):", code)
            else:
                st, b = http("POST", f"{GW}/locations/{cur['locationId']}/state-changes", s.sup(),
                             {"targetState": "ACTIVE", "reason": "보충재고 랙 칸으로 다시 씀(시뮬레이터 R-33)"})
                if st != 202:
                    failed += 1
                    print("  다시 열기 실패", code, st, b.get("code"), b.get("detail"))
                    continue
                reopened += 1
                st, cur = http("GET", f"{GW}/locations/{cur['locationId']}", s.sup())  # 버전이 바뀌었다
                if st != 200:
                    failed += 1
                    print("  다시 읽기 실패", code, st)
                    continue
        d = cur.get("dimensions") or {}
        if (cur.get("position") == pos and cur.get("reserveStock")
                and (d.get("widthMm"), d.get("depthMm"), d.get("heightMm")) == (dims["widthMm"], dims["depthMm"], dims["heightMm"])):
            same += 1
            continue
        b = keep_body(cur, pos)
        b["reserveStock"] = True
        b["dimensions"] = dims
        st, bb = http("PUT", f"{GW}/locations/{cur['locationId']}", s.sup(), b, version=cur["version"])
        if st == 200:
            updated += 1
        else:
            failed += 1
            print("  위치·치수 바꾸기 실패", code, st, bb.get("code"), bb.get("detail"))
        time.sleep(0.05)
    print(f"보충재고 칸(랙 + 대형 묶음) {len(cells)}: 새로 {made}, 위치·치수 바꿈 {updated}, 다시 엶 {reopened}, 그대로 {same}, 실패 {failed}")
    return failed


def main():
    steps = sys.argv[1:]
    shelves, support = plan()
    print(f"도면: 선반 칸 {sum(1 for x in shelves if x['pickSequence'] is not None)}, 팔레트 자리 "
          f"{sum(1 for x in shelves if x['pickSequence'] is None)}, 보조 위치 {len(support)}")
    print("예:", shelves[0]["sim"], "→", shelves[0]["locationCode"], shelves[0]["position"])
    s = Session()
    existing = current_locations(s)
    print(f"지금 위치 {len(existing)}개")
    if "register" in steps:
        register(s, shelves, existing)
        existing = current_locations(s)
    if "dims" in steps:
        dims_step(s, shelves, existing)
        existing = current_locations(s)
    if "httest" in steps:
        httest_step(s, shelves, existing)
        existing = current_locations(s)
    if "support" in steps:
        support_step(s, support, existing)
        existing = current_locations(s)
    if "reserve" in steps:
        reserve_step(s, shelves, existing)
        existing = current_locations(s)
    if "r31" in steps:
        r31_step(s, existing)
        existing = current_locations(s)
    if "r33" in steps:
        r33_step(s, existing)
        existing = current_locations(s)
    move_step(s, shelves, existing, "move" in steps)
    if "close" in steps:
        close_step(s, current_locations(s))


if __name__ == "__main__":
    main()
