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
  stock-plan  원본 재고 스냅샷(SKU·LOT·칸·수량)을 요약하고 파일(--out)로 쓴다. 재고는 넣지 않는다 —
              실서버 재고 등록이라 사용자 승인(INITIAL_STOCK 흐름 → 현장 수령·검사·적치 → 위치 정정 approvalRef)이 필요하다.

  python -I deploy/lan/seed/mfc_clone_center.py locations                # 계획만(대상 센터를 읽고 만들 칸 수)
  python -I deploy/lan/seed/mfc_clone_center.py locations --apply        # 등록·닫기
  python -I deploy/lan/seed/mfc_clone_center.py stock-plan --out stock-plan-MFC-MOKPO-01.json
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
    print("재고는 넣지 않았다 — 실서버 재고 등록은 사용자 승인 뒤 따로 한다.")
    return 0


def main():
    for f in (sys.stdout, sys.stderr):
        if hasattr(f, "reconfigure"):
            f.reconfigure(encoding="utf-8", errors="replace")
    p = argparse.ArgumentParser()
    p.add_argument("step", choices=("locations", "stock-plan"))
    p.add_argument("--apply", action="store_true")
    p.add_argument("--all", action="store_true")
    p.add_argument("--out")
    a = p.parse_args()
    if SOURCE == TARGET:
        raise SystemExit("원본과 대상 센터가 같다")
    if a.step == "locations":
        sys.exit(1 if locations_step(a.apply, a.all) else 0)
    sys.exit(stock_plan_step(a.out))


if __name__ == "__main__":
    main()
