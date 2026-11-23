"""1호점(MFC-SEED-01)과 같은 칸 구성을 다른 센터에 만든다 — 2호점 목포 MFC-MOKPO-01(시뮬레이터 R-39, 2026-11-22).

사용자 결정(시뮬레이터 세션 전달): "여기랑 평수, 구조, sku 수, 랙 갯수 등 완전 똑같이 해서 목포 MFC 만들어줘".
LAN 전용, API 로만. 실행은 PC 1(PC 3 은 읽기만). 토큰·비밀번호는 출력하지 않는다.

먼저 있어야 하는 것(이 스크립트가 하지 않음):
  - center-master 센터 MFC-MOKPO-01(platform — Seed.java 를 목포 seed-data 로).
  - 목포 센터 관리자 계정(PMART_TARGET_SUP, 기본 claude-mfc-sup-mk — MFC_SUPERVISOR, 센터 MFC-MOKPO-01).

단계:
  locations   원본 센터의 모든 칸(RETIRED 빼고)을 대상 센터에 같은 코드·구역·온도대·고회전·보충재고·pickSequence·
              areaCode·categoryId·치수로 등록한다. 위치(xM·zM)는 위치 파일(location-positions.json, R-38 c7d4b7b)에 있으면
              그 값, 없으면 원본 값. 원본이 CLOSED 면 등록 뒤 닫는다(COUNT_LOCKED 는 ACTIVE 로 둔다).
              이미 있는 칸은 건너뛴다(속성을 덮어쓰지 않음 — 다시 돌려도 안전).
              만드는 칸(기본) = 원본 ACTIVE 칸 + 위치 파일에 있는 칸(원본에서 닫혔으면 대상도 닫음 — 넓은 칸 합침으로 닫힌 100칸 등).
              그래서 위치 파일 15,528칸이 모두 생기고, 옛 시험 선반·취소된 R-35 랙 19~21 같은 닫힌 찌꺼기 칸은 만들지 않는다.
              --all: 원본의 닫힌 칸도 모두 만든다(RETIRED 만 뺌).
  layout      (2026-11-23 세종 MFC-SEJ-01 — 랙 수가 1호점과 달라 locations 복사로 안 됨) 대상 센터 위치 파일(PMART_POSITIONS —
              시뮬레이터 docs/layout-export/sejong/ 사본)의 칸마다 등록. 1호점 규칙 그대로: 같은 코드가 1호점에 있으면 그 칸 속성
              (구역·온도대·보충재고·치수 — 넓은 칸 합침·닫힘 포함), 없으면 같은 모양 1호점 칸(선반 = 온도대·구역 글자·단·칸이 같은 칸의
              가장 흔한 치수, 보조 칸 = 접두어·온도대)을 본뜬다. 보충재고 칸은 PMART_RESERVE_RACKS·PMART_BULK_PALLETS 사본 치수.
              pickSequence = 파일의 선반 순서, 고회전은 끔(F 구역은 뒤에 mfc_layout.py r40). 본뜰 칸이 없으면 멈춘다.
  stock-plan  원본 재고 스냅샷(SKU·LOT·칸·수량)을 요약하고 파일(--out)로 쓴다. 재고는 넣지 않는다.
  stock       원본과 같은 SKU·수량·칸 분포를 **정식 입고 흐름**으로 넣는다(사용자 결정 2026-11-22 "정식 입고 흐름으로" —
              위치 정정 같은 직접 복사는 하지 않음). 기본 계획만, --apply 로 실행(실서버 재고 등록 — 사용자 확인 뒤에만).
              온도대마다 SKU 40개씩 한 묶음으로:
                1) LAN 시험 입고 예정 `POST /dev-initial-stock-loads`(SKU 당 원본 수량)
                2) 현장 수령 `POST /initial-stock-loads/{receiptId}/receipts`(RCV-A-01·RCV-C-01·RCV-F-01)
                3) 품질검사 `POST /inbound-tasks/{taskId}/inspections` — 원본 LOT 마다 한 번(NORMAL, 원본 소비기한·제조일·LOT 표기)
                4) 적치 `POST /inbound-tasks/{taskId}/putaways` — 원본 LOT 이 있던 칸마다 그 수량
              하는 사람은 스크립트(대상 센터 관리자 토큰). 작업에 담당(assigneeId)이 생기면 409 NOT_ASSIGNEE 로 멈추므로
              실행하는 동안 시뮬레이터의 목포 가상 작업자는 끈다. --max-loads N 으로 묶음 N 개만.
              --placement mixed(세종 — 칸 배치가 1호점과 다름): 1호점 칸 코드가 대상에 있으면 그 칸, 없으면 그 몫은 서버 나눠 넣기
              (suggestedPlacements)대로. 추천으로도 못 넣은 양은 수령 칸에 남는다(stock-retry). LOT 표기 앞부분 PMART_LOT_PREFIX(기본 R39).
              다시 돌리면 대상 보유(SKU·소비기한별, 칸·상태 무관)와 비교해 모자란 것만 넣는다.
              원본의 주문 취소 반납 대기 줄(CANCEL_RETURN_PENDING — 카트·인계 랙 위)은 그 SKU 의 가장 많은 선반 칸에 합친다
              (적치는 선반·피킹 칸만 받음). LOT ID 는 새로 생긴다(소비기한·제조일 같음, 표기 = "R39-" + 원본 표기).
  stock-retry 적치에서 거절돼 수령 칸에 남은 LOT 만 다시 적치 — 칸은 서버 나눠 넣기(suggestedPlacements: 그 상품 선반 남은
              용량 → 기타 칸 → 보충재고 칸) 그대로. 스크립트가 칸을 고르지 않는다(PDA·가상 작업자와 같음). 기본 계획만, --apply.
              1호점 칸이 용량을 넘겨 들어 있던 LOT(직접 조정으로 넣었던 것)과 출하 기준 미달 LOT(기타 칸)이 여기로 온다.

  python -I deploy/lan/seed/mfc_clone_center.py locations                # 계획만(대상 센터를 읽고 만들 칸 수)
  python -I deploy/lan/seed/mfc_clone_center.py locations --apply        # 등록·닫기
  python -I deploy/lan/seed/mfc_clone_center.py stock-plan --out stock-plan-MFC-MOKPO-01.json
  python -I deploy/lan/seed/mfc_clone_center.py stock                    # 계획만(묶음·LOT·수량)
  python -I deploy/lan/seed/mfc_clone_center.py stock --apply [--max-loads N]
  python -I deploy/lan/seed/mfc_clone_center.py stock-retry [--apply]
  PMART_TARGET_MFC=MFC-SEJ-01 PMART_TARGET_SUP=claude-mfc-sup-sj PMART_POSITIONS=<세종 위치 파일> PMART_RESERVE_RACKS=<세종 보충재고 사본>
    python -I deploy/lan/seed/mfc_clone_center.py layout [--apply]          # 세종 칸
  PMART_TARGET_MFC=MFC-SEJ-01 PMART_TARGET_SUP=claude-mfc-sup-sj PMART_LOT_PREFIX=SEJ
    python -I deploy/lan/seed/mfc_clone_center.py stock --placement mixed [--apply]  # 세종 재고
환경: PMART_SOURCE_MFC(기본 MFC-SEED-01), PMART_TARGET_MFC(기본 MFC-MOKPO-01), PMART_SOURCE_SUP(기본 claude-mfc-sup),
      PMART_TARGET_SUP(기본 claude-mfc-sup-mk), PMART_POSITIONS(위치 파일), PMART_GATEWAY·PMART_INVENTORY.
"""
import argparse
import collections
import json
import os
import sys
import time
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mfc_layout as v1  # noqa: E402  http()·pos_of()·same_pos()·GW·INV

SOURCE = os.environ.get("PMART_SOURCE_MFC", "MFC-SEED-01")
TARGET = os.environ.get("PMART_TARGET_MFC", "MFC-MOKPO-01")
SOURCE_SUP = os.environ.get("PMART_SOURCE_SUP", "claude-mfc-sup")
TARGET_SUP = os.environ.get("PMART_TARGET_SUP", "claude-mfc-sup-mk")
COPY_KEYS = ("areaCode", "pickSequence", "categoryId", "dimensions")
LOT_PREFIX = os.environ.get("PMART_LOT_PREFIX", "R39")  # 품질검사 LOT 표기 앞부분(목포 R39, 세종은 SEJ 등)


class Tok:
    """계정마다 토큰(15분) — 10분마다 다시 받는다."""

    def __init__(self, account):
        self.account, self.token, self.at = account, None, 0.0

    def __call__(self):
        if not self.token or time.time() - self.at > 600:
            self.token, _ = v1.staff_token(self.account)
            self.at = time.time()
        return self.token


def locations_of(mfc, tok):
    out, cursor = {}, None
    while True:
        u = f"{v1.GW}/locations?mfcId={mfc}&size=200" + (f"&cursor={urllib.parse.quote(cursor)}" if cursor else "")
        st, b = v1.http("GET", u, tok())
        if st != 200:
            raise SystemExit(f"{mfc} 위치 목록 실패 HTTP {st} {b.get('code')} {b.get('detail')}")
        for it in b.get("items", []):
            out[it["locationCode"]] = it
        cursor = (b.get("page") or {}).get("nextCursor")
        if not cursor:
            return out
        time.sleep(0.2)


def body_for(src):
    b = {"mfcId": TARGET, "locationCode": src["locationCode"], "zone": src["zone"],
         "temperatureZone": src["temperatureZone"], "highTurnover": bool(src.get("highTurnover")),
         "reserveStock": bool(src.get("reserveStock")),
         "position": v1.pos_of(src["locationCode"], src.get("position"))}
    for k in COPY_KEYS:
        if src.get(k) is not None:
            b[k] = src[k]
    if b["position"] is None:
        del b["position"]
    return b


def locations_step(apply, all_cells):
    src = locations_of(SOURCE, Tok(SOURCE_SUP))
    states = collections.Counter(v["state"] for v in src.values())
    print(f"원본 {SOURCE}: 칸 {len(src)} {dict(states)}")
    tgt_tok = Tok(TARGET_SUP)
    try:
        tgt = locations_of(TARGET, tgt_tok)
        print(f"대상 {TARGET}: 지금 칸 {len(tgt)}")
    except SystemExit as e:
        if apply:
            raise
        tgt = {}
        print(f"대상 {TARGET}: 읽지 못함({e}) — 빈 센터로 보고 계획만 낸다")
    want = {c: v for c, v in src.items() if v["state"] != "RETIRED"
            and (all_cells or v["state"] != "CLOSED" or v1.pos_of(c, None) is not None)}
    missing = [c for c in json_codes() if c not in src]
    if missing:
        print(f"  위치 파일에 있지만 원본에 없는 칸 {len(missing)}(만들지 않음):", missing[:10])
    todo = sorted(c for c in want if c not in tgt)
    close = sorted(c for c in want if want[c]["state"] == "CLOSED")
    by_zone = collections.Counter(want[c]["zone"] for c in todo)
    in_file = sum(1 for c in todo if v1.pos_of(c, None) is not None)
    print(f"만들 칸 {len(todo)} {dict(by_zone)} — 위치 파일 좌표 {in_file}, 원본 좌표 {len(todo) - in_file}")
    print(f"닫을 칸(원본 CLOSED) {len(close)}, 이미 있는 칸 {len(want) - len(todo)}(건드리지 않음)")
    diff = [c for c in want if c in tgt and (tgt[c]["zone"] != want[c]["zone"]
                                             or tgt[c]["temperatureZone"] != want[c]["temperatureZone"])]
    if diff:
        print(f"  이미 있지만 구역·온도대가 다른 칸 {len(diff)}(손대지 않음):", diff[:10])
    if not apply:
        print("계획만 — --apply 로 실행")
        return 0
    made = failed = 0
    for i, c in enumerate(todo, start=1):
        st, b = v1.http("POST", f"{v1.GW}/locations", tgt_tok(), body_for(want[c]))
        if st == 201 or (st == 409 and b.get("code") == "LOCATION_CODE_DUPLICATE"):
            made += st == 201
        else:
            failed += 1
            if failed <= 10:
                print("  등록 실패", c, st, b.get("code"), b.get("detail"))
        if i % 500 == 0:
            print(f"  … {i}/{len(todo)} (새로 {made}, 실패 {failed})", flush=True)
        time.sleep(0.05)
    print(f"등록: 새로 {made}, 실패 {failed}")
    now = locations_of(TARGET, tgt_tok)
    closed = 0
    for c in close:
        cur = now.get(c)
        if cur is None or cur["state"] == "CLOSED":
            continue
        st, b = v1.http("POST", f"{v1.GW}/locations/{cur['locationId']}/state-changes", tgt_tok(),
                        {"targetState": "CLOSED", "reason": f"{SOURCE} 와 같게(원본에서 닫힌 칸, R-39)"})
        if st == 202:
            closed += 1
        else:
            failed += 1
            print("  닫기 실패", c, st, b.get("code"), b.get("detail"))
        time.sleep(0.05)
    states = collections.Counter(v["state"] for v in locations_of(TARGET, tgt_tok).values())
    print(f"닫음 {closed}. 대상 {TARGET} 지금 {dict(states)}, 실패 합계 {failed}")
    return failed


WIDE_CELLS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "wide-cells-2026-11-18.json")
WIDE_CLOSED = set(json.load(open(WIDE_CELLS, encoding="utf-8"))["closed"]) if os.path.exists(WIDE_CELLS) else set()


def shelf_key(code):
    """선반 칸 모양 — (온도대 글자, 구역 글자, 단, 칸). 예 A-C3-29-2-05 → (A, C, 2, 05). 같은 모양이면 칸 치수가 같다."""
    p = code.split("-")
    return (p[0], p[1][0], p[3], p[4]) if len(p) == 5 else None


def support_key(code):
    """보조 칸 모양 — 접두어와 온도대 글자. 예 PAK-A-09 → PAK-A."""
    p = code.split("-")
    return "-".join(p[:2]) if len(p) == 3 else None


def layout_want(src):
    """대상 위치 파일(PMART_POSITIONS — 시뮬레이터가 낸 그 센터 칸, 예 세종)의 칸마다 등록 본문(2026-11-23 세종 MFC-SEJ-01).
    1호점 규칙 그대로·랙·칸 수만 다름: 같은 코드가 원본에 있으면 그 칸 속성(구역·온도대·보충재고·치수 — 넓은 칸 합침 포함, 닫힘)을,
    없으면 같은 모양의 원본 칸(선반 = 온도대·구역 글자·단·칸이 같은 칸 중 가장 흔한 치수, 보조 칸 = 접두어·온도대가 같은 칸)을 본뜬다.
    보충재고 랙·대형재고 칸은 그 센터 사본(PMART_RESERVE_RACKS·PMART_BULK_PALLETS)의 치수. pickSequence = 파일의 선반 순서.
    고회전은 모두 끈다(F 구역은 등록 뒤 mfc_layout.py r40). 반환 = (본문 {코드: body}, 닫을 코드, 본뜬 방식 개수, 못 만드는 칸)."""
    rows = json.load(open(v1.POSITIONS, encoding="utf-8"))
    extra = {}
    for path in (v1.RESERVE_RACKS, v1.BULK_PALLETS):
        if os.path.exists(path):
            for x in json.load(open(path, encoding="utf-8")):
                extra[x["locationCode"]] = x
    live = {c: v for c, v in src.items() if v["state"] != "RETIRED"}
    by_shelf = collections.defaultdict(collections.Counter)
    sample = {}
    for c, v in live.items():
        k = shelf_key(c)
        if k and v["zone"] in ("STORAGE", "PICKING") and not v.get("reserveStock") and v.get("dimensions"):
            d = v["dimensions"]
            dk = (d.get("widthMm"), d.get("depthMm"), d.get("heightMm"), d.get("slotCount") or 1)
            by_shelf[k][dk] += 1
            sample.setdefault((k, dk), v)
    by_support = {}
    for c, v in sorted(live.items()):
        k = support_key(c)
        if k:
            by_support.setdefault(k, v)
    bodies, close, how, bad = {}, [], collections.Counter(), []
    seq = 0
    for e in rows:
        code = e["locationCode"]
        if e["kind"] == "SHELF":
            seq += 1
        t = live.get(code)
        if t is not None:
            how["원본 같은 코드"] += 1
            # 닫는 것은 넓은 칸 합침으로 닫힌 칸만(wide-cells-2026-11-18.json closed). 1호점에서 다른 까닭으로 닫힌 칸
            # (취소된 R-35 랙 19~21, 지게차 통로로 빠진 A-R1-37~42 등)은 대상 평면에서는 쓰는 칸이다(세종 계획 11-23 — 1,221 → 100).
            if t["state"] == "CLOSED" and code in WIDE_CLOSED:
                close.append(code)
        elif e["kind"] == "SHELF" and shelf_key(code) in by_shelf:
            k = shelf_key(code)
            dk = by_shelf[k].most_common(1)[0][0]
            t = sample[(k, dk)]
            how["같은 모양 선반"] += 1
        elif e["kind"] in ("RESERVE_RACK", "BULK_PALLET") and code in extra:
            x = extra[code]
            dm = x["dimMm"]
            t = {"zone": x.get("zone", "STORAGE"), "temperatureZone": x["temperatureZone"], "reserveStock": True,
                 "dimensions": {"widthMm": dm["w"], "depthMm": dm["d"], "heightMm": dm["h"], "slotCount": 1}}
            how["보충재고 사본"] += 1
        elif e["kind"] == "SUPPORT" and support_key(code) in by_support:
            t = by_support[support_key(code)]
            how["같은 접두어 보조 칸"] += 1
        else:
            bad.append(code)
            continue
        b = {"mfcId": TARGET, "locationCode": code, "zone": t["zone"], "temperatureZone": t["temperatureZone"],
             "highTurnover": False, "reserveStock": bool(t.get("reserveStock")),
             "position": {"xM": e["xM"], "zM": e["zM"]}}
        if t.get("dimensions"):
            b["dimensions"] = t["dimensions"]
        if e["kind"] == "SHELF":
            b["pickSequence"] = seq
        bodies[code] = b
    return bodies, close, how, bad


def layout_step(apply):
    if not os.path.exists(v1.POSITIONS):
        raise SystemExit(f"위치 파일이 없다: {v1.POSITIONS}")
    src = locations_of(SOURCE, Tok(SOURCE_SUP))
    print(f"원본 {SOURCE}: 칸 {len(src)} — 위치 파일 {v1.POSITIONS}")
    bodies, close, how, bad = layout_want(src)
    kinds = collections.Counter(b["zone"] for b in bodies.values())
    temps = collections.Counter(b["temperatureZone"] for b in bodies.values())
    print(f"파일 칸 {len(bodies) + len(bad)}: 만들 본문 {len(bodies)} {dict(kinds)} {dict(temps)}, 본뜬 방식 {dict(how)}")
    if bad:
        print(f"  본뜰 원본 칸이 없어 못 만드는 칸 {len(bad)}(멈춤):", bad[:10])
        return 1
    tgt_tok = Tok(TARGET_SUP)
    try:
        tgt = locations_of(TARGET, tgt_tok)
    except SystemExit as e:
        if apply:
            raise
        tgt = {}
        print(f"대상 {TARGET}: 읽지 못함({e}) — 빈 센터로 보고 계획만 낸다")
    todo = [c for c in bodies if c not in tgt]
    print(f"대상 {TARGET}: 지금 칸 {len(tgt)}. 만들 칸 {len(todo)}, 닫을 칸(원본에서 닫힌 같은 코드 — 넓은 칸 합침) {len(close)}")
    if not apply:
        print("계획만 — --apply 로 실행")
        return 0
    return create_and_close(tgt_tok, [bodies[c] for c in todo], close, f"{SOURCE} 와 같게(원본에서 닫힌 칸)")


def create_and_close(tgt_tok, bodies, close, reason):
    made = failed = 0
    for i, b in enumerate(bodies, start=1):
        st, r = v1.http("POST", f"{v1.GW}/locations", tgt_tok(), b)
        if st == 201 or (st == 409 and r.get("code") == "LOCATION_CODE_DUPLICATE"):
            made += st == 201
        else:
            failed += 1
            if failed <= 10:
                print("  등록 실패", b["locationCode"], st, r.get("code"), r.get("detail"))
        if i % 500 == 0:
            print(f"  … {i}/{len(bodies)} (새로 {made}, 실패 {failed})", flush=True)
        time.sleep(0.05)
    print(f"등록: 새로 {made}, 실패 {failed}")
    now = locations_of(TARGET, tgt_tok)
    closed = 0
    for c in close:
        cur = now.get(c)
        if cur is None or cur["state"] == "CLOSED":
            continue
        st, r = v1.http("POST", f"{v1.GW}/locations/{cur['locationId']}/state-changes", tgt_tok(),
                        {"targetState": "CLOSED", "reason": reason})
        if st == 202:
            closed += 1
        else:
            failed += 1
            print("  닫기 실패", c, st, r.get("code"), r.get("detail"))
        time.sleep(0.05)
    states = collections.Counter(v["state"] for v in locations_of(TARGET, tgt_tok).values())
    print(f"닫음 {closed}. 대상 {TARGET} 지금 {dict(states)}, 실패 합계 {failed}")
    return failed


def json_codes():
    return [i["locationCode"] for i in json.load(open(v1.POSITIONS, encoding="utf-8"))] if os.path.exists(v1.POSITIONS) else []


def snapshot(mfc):
    rows, cursor = [], None
    while True:
        u = f"{v1.INV}/internal/snapshots?mfcId={mfc}&size=100" + (f"&cursor={urllib.parse.quote(cursor)}" if cursor else "")
        with urllib.request.urlopen(urllib.request.Request(u, headers={"Accept": "application/json"}), timeout=60) as r:
            b = json.loads(r.read())
        rows += b["items"]
        cursor = (b.get("page") or {}).get("nextCursor")
        if not cursor:
            return rows


def stock_plan_step(out):
    rows = [r for r in snapshot(SOURCE) if r["qty"] > 0]
    skus = {r["sku"] for r in rows}
    by_state = collections.Counter((r["qualityState"], r["workState"]) for r in rows)
    held = sum(1 for r in rows if (r.get("allocatedQty") or 0) or (r.get("reservedQty") or 0) or (r.get("heldQty") or 0))
    print(f"원본 {SOURCE} 재고: 줄 {len(rows)}, SKU {len(skus)}, 수량 {sum(r['qty'] for r in rows)}")
    print(f"  품질·작업 상태 {dict(by_state)}, 할당·예약·보류가 걸린 줄 {held}(복사 대상은 수량만)")
    tgt = snapshot(TARGET)
    print(f"대상 {TARGET} 재고 줄 {len(tgt)}")
    if out:
        keep = ("sku", "lotId", "expiryDate", "locationCode", "qty", "qualityState", "workState")
        with open(out, "w", encoding="utf-8", newline="\n") as f:
            json.dump({"source": SOURCE, "target": TARGET, "rows": [{k: r.get(k) for k in keep} for r in rows]},
                      f, ensure_ascii=False, indent=1)
        print("계획 파일:", out)
    print("재고는 넣지 않았다 — 넣기는 stock 단계(정식 입고 흐름, 사용자 확인 뒤).")
    return 0


RCV_OF = {"A": "RCV-A-01", "C": "RCV-C-01", "F": "RCV-F-01"}
LOAD_LINES = 40  # dev-initial-stock-loads 줄 수 한도(SKU 당 1~500)


def desired_stock(rows):
    """원본 줄 → LOT 묶음 {(sku, 원본 lotId): {"exp", "cells": {칸: 수량}}}.
    STORED 가 아닌 줄(카트·인계 랙 위 반납 대기)은 그 SKU 의 가장 많은 선반 칸에 합친다."""
    home = {}
    for r in sorted((r for r in rows if r["workState"] == "STORED"), key=lambda r: -r["qty"]):
        home.setdefault(r["sku"], r["locationCode"])
    merged, dropped, out = 0, [], []
    for r in rows:
        if r["workState"] == "STORED":
            out.append(r)
        elif r["sku"] in home:
            out.append(dict(r, locationCode=home[r["sku"]]))
            merged += 1
        else:
            dropped.append(r)
    lots = {}
    for r in out:
        g = lots.setdefault((r["sku"], r["lotId"]), {"exp": r.get("expiryDate"), "cells": collections.Counter()})
        g["cells"][r["locationCode"]] += r["qty"]
    return lots, merged, dropped


def have_stock(mfc):
    """대상 보유 — SKU·소비기한별 수량(칸·작업 상태 무관). 적치 전(수령 칸)이나 stock-retry 로 다른 칸에 넣은 것도 센다
    — 다시 돌릴 때 같은 재고를 두 번 넣지 않게(2026-11-22 R-39 ④ 뒤 고침, 전에는 칸별 정상·적치만 셈)."""
    have = collections.Counter()
    for r in snapshot(mfc):
        if r["qty"] > 0:
            have[(r["sku"], r.get("expiryDate"))] += r["qty"]
    return have


def remaining(lots, have):
    """대상에 이미 있는 양(SKU·소비기한)을 빼고 남은 LOT 묶음 — 다시 돌릴 때 모자란 것만."""
    have = collections.Counter(have)
    todo = {}
    for key, g in sorted(lots.items()):
        cells = collections.Counter()
        for loc, q in sorted(g["cells"].items()):
            k = (key[0], g["exp"])
            take = min(q, have[k])
            have[k] -= take
            if q - take > 0:
                cells[loc] = q - take
        if cells:
            todo[key] = {"exp": g["exp"], "cells": cells}
    return todo


def loads_of(todo):
    """온도대(칸 코드 첫 글자)마다 SKU 40개씩 한 묶음."""
    by_temp = collections.defaultdict(lambda: collections.defaultdict(list))
    for (sku, lot), g in todo.items():
        by_temp[next(iter(g["cells"]))[0]][sku].append((lot, g))
    loads = []
    for t in sorted(by_temp):
        skus = sorted(by_temp[t])
        for i in range(0, len(skus), LOAD_LINES):
            loads.append((t, {k: by_temp[t][k] for k in skus[i:i + LOAD_LINES]}))
    return loads


def lot_info(tok, lot_id):
    """원본 LOT 의 제조일·LOT 표기(라벨 조회)."""
    st, b = v1.http("GET", f"{v1.GW}/mfcs/{SOURCE}/lot-labels/{urllib.parse.quote(lot_id)}", tok())
    if st != 200:
        return {}
    return {"manufactureDate": b.get("manufactureDate"), "ref": b.get("sourceLotRef") or b.get("lotNo")}


def wait_tasks(tok, receipt_id, task_type, n, timeout=180):
    end = time.time() + timeout
    while True:
        u = (f"{v1.GW}/inbound-tasks?mfcId={TARGET}&receiptId={urllib.parse.quote(receipt_id)}"
             f"&taskType={task_type}&state=READY&withLabels=false")
        st, b = v1.http("GET", u, tok())
        items = b.get("items", []) if st == 200 else []
        if len(items) >= n or time.time() > end:
            return items
        time.sleep(2)


def run_load(tok, src_tok, t, group, stats, tgt_codes=None):
    lines = [{"sku": sku, "qty": sum(sum(g["cells"].values()) for _, g in lots)} for sku, lots in group.items()]
    st, b = v1.http("POST", f"{v1.GW}/dev-initial-stock-loads", tok(), {"mfcId": TARGET, "lines": lines})
    if st != 201:
        raise SystemExit(f"시험 입고 예정 실패 HTTP {st} {b.get('code')} {b.get('detail')} {b.get('details')}")
    rid = b["receiptId"]
    st, b = v1.http("POST", f"{v1.GW}/initial-stock-loads/{rid}/receipts", tok(),
                    {"toLocationCode": RCV_OF[t], "lines": [{"sku": x["sku"], "receivedQty": x["qty"]} for x in lines]})
    if st != 202:
        raise SystemExit(f"수령 실패 {rid} HTTP {st} {b.get('code')} {b.get('detail')}")
    tasks = wait_tasks(tok, rid, "INSPECT", len(lines))
    if len(tasks) < len(lines):
        raise SystemExit(f"품질검사 작업이 다 생기지 않음 {rid}: {len(tasks)}/{len(lines)} — 전기 대기일 수 있다. "
                         "작업이 생긴 뒤 다시 돌리면 남은 것만 넣는다(이 묶음의 수령분은 RCV 칸에 남음)")
    by_sku = {x["sku"]: x for x in tasks}
    for sku, lots in group.items():
        task = by_sku[sku]
        for lot, g in lots:
            info = lot_info(src_tok, lot)
            res = {"qty": sum(g["cells"].values()), "resultQuality": "NORMAL",
                   "sourceLotRef": f"{LOT_PREFIX}-{info.get('ref') or lot}"[:128]}
            if g["exp"]:
                res["expiryDate"] = g["exp"]
            if info.get("manufactureDate"):
                res["manufactureDate"] = info["manufactureDate"]
            st, b = v1.http("POST", f"{v1.GW}/inbound-tasks/{task['taskId']}/inspections", tok(), {"results": [res]})
            if b.get("code") == "NOT_ASSIGNEE":
                raise SystemExit(f"작업 {task['taskId']} 에 담당이 있다(NOT_ASSIGNEE) — 목포 가상 작업자를 끄고 다시")
            if st != 200 or b.get("applyStatus") != "APPLIED":
                stats["failed"] += 1
                print("  품질검사 실패", sku, lot, st, b.get("code") or b.get("applyStatus"), b.get("detail") or b.get("rejectReason"))
                continue
            stats["lots"] += 1
            put = list(b.get("createdTaskIds") or [])
            if len(put) != 1:
                stats["failed"] += 1
                print("  적치 작업 수가 1 이 아님", sku, lot, put)
                continue
            rest = 0
            for loc, q in sorted(g["cells"].items()):
                if tgt_codes is not None and loc not in tgt_codes:
                    rest += q  # 대상 센터에 없는 원본 칸 몫 — 아래 서버 나눠 넣기로
                    continue
                st, pb = v1.http("POST", f"{v1.GW}/inbound-tasks/{put[0]}/putaways", tok(), {"toLocationCode": loc, "qty": q})
                if st == 200 and pb.get("applyStatus") == "APPLIED":
                    stats["placed"] += q
                else:
                    stats["failed"] += 1
                    print("  적치 실패", sku, loc, q, st, pb.get("code") or pb.get("applyStatus"),
                          pb.get("detail") or pb.get("rejectReason"))
                time.sleep(0.05)
            if rest:
                place_suggested(tok, put[0], rest, sku, stats)
    stats["loads"] += 1


def place_suggested(tok, task_id, qty, sku, stats):
    """서버 나눠 넣기(suggestedPlacements — 그 상품 선반 남은 용량 → 기타 칸 → 보충재고 칸)대로 qty 까지 적치. 못 넣은 양은 수령 칸에 남는다."""
    st, t = v1.http("GET", f"{v1.GW}/inbound-tasks/{task_id}", tok())
    plan = [(p["locationCode"], p["qty"]) for p in (t.get("suggestedPlacements") or [])] if st == 200 else []
    left = qty
    for loc, q in plan:
        if left <= 0:
            break
        q = min(q, left)
        st, pb = v1.http("POST", f"{v1.GW}/inbound-tasks/{task_id}/putaways", tok(), {"toLocationCode": loc, "qty": q})
        if st == 200 and pb.get("applyStatus") == "APPLIED":
            stats["placed"] += q
            stats["suggested"] += q
            left -= q
        else:
            stats["failed"] += 1
            print("  추천 칸 적치 실패", sku, loc, q, st, pb.get("code") or pb.get("applyStatus"))
        time.sleep(0.05)
    if left > 0:
        stats["unplaced"] += left
        print("  추천으로도 못 넣음(수령 칸에 남김 — stock-retry)", sku, left)


def stock_step(apply, max_loads, mixed=False):
    rows = [r for r in snapshot(SOURCE) if r["qty"] > 0]
    lots, merged, dropped = desired_stock(rows)
    total = sum(sum(g["cells"].values()) for g in lots.values())
    print(f"원본 {SOURCE}: 줄 {len(rows)}, SKU {len({k[0] for k in lots})}, LOT {len(lots)}, 수량 {total}"
          f" — 반납 대기 줄 {merged} 은 선반 칸에 합침, 넣지 못하는 줄 {len(dropped)}")
    for r in dropped:
        print("  선반 칸이 없어 빠짐:", r["sku"], r["locationCode"], r["qty"])
    have = have_stock(TARGET)
    todo = remaining(lots, have)
    loads = loads_of(todo)
    q = collections.Counter()
    for t, group in loads:
        q[t] += sum(sum(g["cells"].values()) for lots_ in group.values() for _, g in lots_)
    print(f"대상 {TARGET}: 지금 보유 {sum(have.values())}. 남은 LOT {len(todo)}, 묶음 {len(loads)}, 온도대별 수량 {dict(q)}")
    print(f"  품질검사 {len(todo)}번, 적치 {sum(len(g['cells']) for g in todo.values())}번")
    if mixed:
        try:
            codes = set(locations_of(TARGET, Tok(TARGET_SUP)))
        except SystemExit as e:
            codes = None
            print(f"  대상 칸을 읽지 못해 원본 칸이 대상에 있는지는 못 셈({e})")
        if codes is not None:
            cells = [(loc, q) for g in todo.values() for loc, q in g["cells"].items()]
            hit = sum(q for loc, q in cells if loc in codes)
            print(f"  --placement mixed: 원본 칸이 대상에 있는 수량 {hit}, 없어 서버 추천으로 {sum(q for _, q in cells) - hit}")
    if not apply:
        print("계획만 — 사용자 확인 뒤 --apply")
        return 0
    tok, src_tok = Tok(TARGET_SUP), Tok(SOURCE_SUP)
    tgt_codes = locations_of(TARGET, tok)
    miss = sorted(({loc for g in todo.values() for loc in g["cells"] if loc not in tgt_codes} if not mixed else set())
                  | {c for c in RCV_OF.values() if c not in tgt_codes})
    if miss:
        raise SystemExit(f"대상에 없는 칸 {len(miss)} — 먼저 locations(또는 layout) --apply: {miss[:10]}")
    stats = collections.Counter()
    for i, (t, group) in enumerate(loads[:max_loads] if max_loads else loads, start=1):
        print(f"묶음 {i}/{len(loads)} 온도 {t} SKU {len(group)}", flush=True)
        run_load(tok, src_tok, t, group, stats, set(tgt_codes) if mixed else None)
    left = remaining(lots, have_stock(TARGET))
    print(f"끝: 묶음 {stats['loads']}, LOT {stats['lots']}, 적치 수량 {stats['placed']}(그중 서버 추천 {stats['suggested']}), "
          f"수령 칸에 남김 {stats['unplaced']}, 실패 {stats['failed']}. 남은 LOT {len(left)}")
    return stats["failed"]


LIST_LIMIT = 500  # GET /inbound-tasks 는 쪽 나눔(cursor)이 없고 만든 순서로 최대 500건(mfc-inbound InboundTaskStore.list)


def open_putaways(tok):
    """대상 센터의 READY 적치 작업 중 수령 칸(RCV-*)에서 나가는 것 — stock 이 넣다가 적치에서 거절된 LOT.
    (작업들, 목록이 500건으로 잘렸는지) — 잘렸으면 처리한 만큼 READY 가 줄어드니 다시 읽어 이어 간다(retry_step)."""
    st, b = v1.http("GET", f"{v1.GW}/inbound-tasks?mfcId={TARGET}&state=READY&taskType=PUTAWAY&withLabels=false", tok())
    if st != 200:
        raise SystemExit(f"적치 작업 목록 실패 HTTP {st} {b.get('code')} {b.get('detail')}")
    items = b.get("items", [])
    return [x for x in items if x["locationCode"] in RCV_OF.values()], len(items) >= LIST_LIMIT


def left_qty(task):
    return task["plannedQty"] - task["doneQty"] - (task.get("exceptedQty") or 0)


def retry_step(apply):
    """거절된 LOT 만 다시 적치 — 칸은 서버 나눠 넣기(suggestedPlacements: 그 상품 선반 남은 용량 → 기타 칸 → 보충재고 칸)를
    그대로 따른다. PDA·가상 작업자와 같은 방식이고 스크립트가 칸을 고르지 않는다(시뮬레이터 R-39 ④ 결과 뒤 요청, 2026-11-22).
    1호점 칸이 용량을 넘겨 들어 있던 LOT(직접 조정으로 넣었던 것)·출하 기준 미달 LOT(기타 칸) 이 여기로 온다."""
    tok = Tok(TARGET_SUP)
    tasks, cut = open_putaways(tok)
    print(f"대상 {TARGET}: 수령 칸에 남은 적치 작업 {len(tasks)}, 수량 {sum(left_qty(x) for x in tasks)}"
          + (f" — READY 적치 목록이 {LIST_LIMIT}건으로 잘림(뒤 작업은 처리하며 다시 읽어 이어 간다)" if cut else ""))
    stats, seen = collections.Counter(), set()
    while True:
        todo = [x for x in tasks if x["taskId"] not in seen]
        if not todo:
            break
        seen.update(x["taskId"] for x in todo)
        retry_tasks(tok, todo, apply, stats)
        if not (apply and cut):
            break
        tasks, cut = open_putaways(tok)
    if cut:
        print(f"  주의: READY 적치 목록이 아직 {LIST_LIMIT}건 이상이다 — 수령 칸 밖 작업(가상 작업자 몫)이 앞을 채우면"
              " 그 뒤의 수령 칸 작업은 이 목록으로 보이지 않는다. 그 작업들이 줄어든 뒤 다시 돌린다")
    if not apply:
        print("계획만 — 사용자 확인 뒤 --apply")
        return 0
    rest, _ = open_putaways(tok)
    print(f"끝: 적치 {stats['placed']}, 실패 {stats['failed']}, 추천으로도 못 넣은 양 {stats['unplaced']}. "
          f"수령 칸에 남은 작업 {len(rest)}, 수량 {sum(left_qty(x) for x in rest)}")
    return stats["failed"]


def retry_tasks(tok, tasks, apply, stats):
    for x in tasks:
        st, t = v1.http("GET", f"{v1.GW}/inbound-tasks/{x['taskId']}", tok())
        if st != 200:
            stats["failed"] += 1
            print("  작업 조회 실패", x["taskId"], st, t.get("code"))
            continue
        if t.get("assigneeId"):
            print("  담당이 있어 건너뜀(NOT_ASSIGNEE 가 됨)", t["taskId"], t["sku"])
            stats["failed"] += 1
            continue
        plan = [(p["locationCode"], p["qty"]) for p in t.get("suggestedPlacements") or []]
        unplaced = t.get("suggestedUnplacedQty") or 0
        print(f"  {t['sku']} {t['lotId']} 남은 {left_qty(t)} → {plan}" + (f", 못 넣는 양 {unplaced}" if unplaced else ""))
        if not plan:
            stats["failed"] += 1
            continue
        if not apply:
            continue
        for loc, q in plan:
            st, pb = v1.http("POST", f"{v1.GW}/inbound-tasks/{t['taskId']}/putaways", tok(), {"toLocationCode": loc, "qty": q})
            if st == 200 and pb.get("applyStatus") == "APPLIED":
                stats["placed"] += q
            else:
                stats["failed"] += 1
                print("    적치 실패", loc, q, st, pb.get("code") or pb.get("applyStatus"), pb.get("detail") or pb.get("rejectReason"))
            time.sleep(0.05)
        stats["unplaced"] += unplaced


def main():
    for f in (sys.stdout, sys.stderr):
        if hasattr(f, "reconfigure"):
            f.reconfigure(encoding="utf-8", errors="replace")
    p = argparse.ArgumentParser()
    p.add_argument("step", choices=("locations", "layout", "stock-plan", "stock", "stock-retry"))
    p.add_argument("--apply", action="store_true")
    p.add_argument("--all", action="store_true")
    p.add_argument("--out")
    p.add_argument("--max-loads", type=int)
    p.add_argument("--placement", choices=("source", "mixed"), default="source",
                   help="source = 원본 칸 그대로(목포), mixed = 원본 칸이 대상에 있으면 그 칸·없으면 서버 나눠 넣기(세종)")
    a = p.parse_args()
    if SOURCE == TARGET:
        raise SystemExit("원본과 대상 센터가 같다")
    if a.step == "locations":
        sys.exit(1 if locations_step(a.apply, a.all) else 0)
    if a.step == "layout":
        sys.exit(1 if layout_step(a.apply) else 0)
    if a.step == "stock":
        sys.exit(1 if stock_step(a.apply, a.max_loads, a.placement == "mixed") else 0)
    if a.step == "stock-retry":
        sys.exit(1 if retry_step(a.apply) else 0)
    sys.exit(stock_plan_step(a.out))


if __name__ == "__main__":
    main()
