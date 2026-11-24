"""UCI Online Retail II xlsx(두 시트) → 데모 입력 CSV. DuckDB 를 쓸 수 없을 때의 대체 도구(명세 6장).

2026-11-24 PC 3: DuckDB v2.0.1 의 excel 확장이 Windows 애플리케이션 제어 정책에 막혀(확장 DLL 로드 차단) 이 스크립트를 쓴다.
Python 표준 라이브러리만 쓴다(설치 없음). 데모 배치·운영에는 Python 이 없다 — 데이터 준비 단계 전용.

변환은 열 이름·형식만 바꾼다(정제·필터는 Java 데모). InvoiceDate 는 엑셀 일련값 → 'YYYY-MM-DD HH:MM:SS'.
고객 ID 는 비운다(열 이름만 남김).

    python -I data-prep/xlsx_to_csv.py data/raw/online_retail_II.xlsx data/csv/uci-online-retail-ii.csv
"""

import csv
import datetime as dt
import sys
import zipfile
import xml.etree.ElementTree as ET

NS = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
REL_NS = "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id"
SHEETS = ["Year 2009-2010", "Year 2010-2011"]
HEADER = ["Invoice", "StockCode", "Description", "Quantity", "InvoiceDate", "Price", "Customer ID", "Country"]
EPOCH = dt.datetime(1899, 12, 30)  # 엑셀 1900 날짜 체계


def shared_strings(z):
    out = []
    if "xl/sharedStrings.xml" not in z.namelist():
        return out
    with z.open("xl/sharedStrings.xml") as f:
        for _, el in ET.iterparse(f):
            if el.tag == "{%s}si" % NS["m"]:
                out.append("".join(t.text or "" for t in el.iter("{%s}t" % NS["m"])))
                el.clear()
    return out


def sheet_paths(z):
    wb = ET.fromstring(z.read("xl/workbook.xml"))
    rels = ET.fromstring(z.read("xl/_rels/workbook.xml.rels"))
    target = {r.get("Id"): r.get("Target") for r in rels}
    paths = {}
    for s in wb.find("m:sheets", NS):
        t = target[s.get(REL_NS)]
        paths[s.get("name")] = "xl/" + t.lstrip("/").removeprefix("xl/")
    return paths


def col_index(ref):
    n = 0
    for ch in ref:
        if ch.isalpha():
            n = n * 26 + (ord(ch.upper()) - 64)
        else:
            break
    return n - 1


def rows(z, path, strings):
    with z.open(path) as f:
        for _, el in ET.iterparse(f):
            if el.tag != "{%s}row" % NS["m"]:
                continue
            values = [""] * len(HEADER)
            for c in el.findall("m:c", NS):
                i = col_index(c.get("r"))
                if i >= len(HEADER):
                    continue
                t = c.get("t")
                if t == "inlineStr":
                    v = "".join(x.text or "" for x in c.iter("{%s}t" % NS["m"]))
                else:
                    ve = c.find("m:v", NS)
                    v = "" if ve is None else (ve.text or "")
                    if t == "s" and v != "":
                        v = strings[int(v)]
                values[i] = v
            el.clear()
            yield values


def excel_date(v):
    try:
        return EPOCH + dt.timedelta(seconds=round(float(v) * 86400))
    except ValueError:
        return None


def number(v):
    # 정수로 끝나는 실수(12.0)는 정수로, 그 밖은 그대로
    try:
        f = float(v)
    except ValueError:
        return v
    return str(int(f)) if f.is_integer() else repr(f)


def main(src, dst):
    counts = {}
    with zipfile.ZipFile(src) as z, open(dst, "w", newline="", encoding="utf-8") as out:
        w = csv.writer(out)
        w.writerow(HEADER)
        strings = shared_strings(z)
        paths = sheet_paths(z)
        for name in SHEETS:
            n = 0
            for i, r in enumerate(rows(z, paths[name], strings)):
                if i == 0:
                    if r[: len(HEADER)] != HEADER:
                        raise SystemExit(f"{name}: 머리줄이 다름 {r}")
                    continue
                d = excel_date(r[4])
                r[4] = d.strftime("%Y-%m-%d %H:%M:%S") if d else r[4]
                r[3] = number(r[3])
                r[5] = number(r[5])
                r[1] = number(r[1]) if r[1].replace(".", "", 1).isdigit() else r[1]
                r[0] = number(r[0]) if r[0].replace(".", "", 1).isdigit() else r[0]
                r[6] = ""  # 고객 ID 비움
                w.writerow(r)
                n += 1
            counts[name] = n
    for k, v in counts.items():
        print(f"{k}: {v}행")
    print(f"합계: {sum(counts.values())}행 → {dst}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("사용: python -I xlsx_to_csv.py <원본.xlsx> <출력.csv>")
    main(sys.argv[1], sys.argv[2])
