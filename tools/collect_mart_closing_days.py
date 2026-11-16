#!/usr/bin/env python3
"""대형마트 의무휴업일 전국 수집기 (표준 라이브러리만 사용).

실행:  python -I tools/collect_mart_closing_days.py
옵션:  --assets-dir DIR   산출물 폴더 (기본: <repo>/app/src/main/assets)
       --today YYYY-MM-DD 기준일(테스트용, 기본: 오늘 KST)
       --allow-partial    한 체인이 0개여도 파일을 씀 (기본: 거부)
       --no-write         수집/검증만 하고 파일은 쓰지 않음

출처(모두 각 체인의 공식 사이트):
  이마트·트레이더스  store.emart.com /branch/listAll.do + /branch/view.do?id=…  ("휴점일")
  롯데마트           company.lottemart.com /shop/shop_search.asp?list_num=1000   (휴무일 열)
  홈플러스           my.homeplus.co.kr /store/get_list + /store/store_detail?storId=… ("휴무일")
  코스트코           수집하지 않음(소유자 제공 mart_closing_days_costco.json을 지역 패턴 산출에만 읽음)

산출물:
  mart_closing_days.json               점포별 휴무일
  mart_closing_rules_by_district.json  시군구별 관찰 패턴 (점포 안내에서 '추정'한 값, 조례 원문 아님)
"""
import argparse
import collections
import datetime as dt
import html
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
MIN_INTERVAL = 0.27   # 초당 4건 이하
TIMEOUT = 20
RETRIES = 4

KST = dt.timezone(dt.timedelta(hours=9))
LOG_LINES = []


def log(msg):
    LOG_LINES.append(msg)
    print(msg, flush=True)


# --------------------------------------------------------------------- HTTP
_last = [0.0]


def http(url, data=None, headers=None):
    """GET/POST (data가 dict면 POST). 속도 제한 + 재시도(지수 백오프) + 20초 타임아웃."""
    body = urllib.parse.urlencode(data).encode() if data is not None else None
    h = {"User-Agent": UA, "Accept-Language": "ko-KR,ko;q=0.9"}
    if headers:
        h.update(headers)
    last_err = None
    for attempt in range(RETRIES):
        wait = MIN_INTERVAL - (time.monotonic() - _last[0])
        if wait > 0:
            time.sleep(wait)
        _last[0] = time.monotonic()
        try:
            req = urllib.request.Request(url, data=body, headers=h)
            with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
                raw = r.read()
            return raw.decode("utf-8", errors="replace")
        except urllib.error.HTTPError as e:
            last_err = e
            if e.code in (400, 401, 403, 404):
                break
        except Exception as e:  # 네트워크 오류, 타임아웃 등
            last_err = e
        time.sleep(1.5 * (2 ** attempt))
    raise RuntimeError("fetch failed: %s (%s)" % (url, last_err))


# ------------------------------------------------------------------ helpers
def clean(s):
    s = re.sub(r"<[^>]+>", " ", s or "")
    return re.sub(r"\s+", " ", html.unescape(s)).strip()


MD_RE = re.compile(r"(\d{1,2})\s*/\s*(\d{1,2})")


def infer_dates(text, today):
    """'11/22(일), 12/13(일)' -> ['2026-11-22', ...]. 현재 월보다 앞선 월이면 다음 해."""
    out = []
    for m, d in MD_RE.findall(text or ""):
        m, d = int(m), int(d)
        year = today.year if m >= today.month else today.year + 1
        try:
            iso = dt.date(year, m, d).isoformat()
        except ValueError:
            continue
        if iso not in out:
            out.append(iso)
    return sorted(out)


ORD = r"(?:첫째|둘째|셋째|넷째|다섯째)"
DOW = r"[월화수목금토일]요일"
RULE_RE = re.compile(
    r"(" + ORD + r"[^<>※*。.]*?" + DOW +
    r"(?:\s*,\s*" + ORD + r"[^<>※*,]*?" + DOW + r")*)")


def find_rule(text):
    m = RULE_RE.search(clean(text))
    return m.group(1).strip() if m else None


NEVER_RE = re.compile(r"연중\s*무휴|휴무\s*없|휴점\s*없|무휴")

SIDO_MAP = [
    ("서울", "서울"), ("부산", "부산"), ("대구", "대구"), ("인천", "인천"),
    ("광주", "광주"), ("대전", "대전"), ("울산", "울산"), ("세종", "세종"),
    ("경기", "경기"), ("강원", "강원"), ("충청북", "충북"), ("충북", "충북"),
    ("충청남", "충남"), ("충남", "충남"), ("전라북", "전북"), ("전북", "전북"),
    ("전라남", "전남"), ("전남", "전남"), ("경상북", "경북"), ("경북", "경북"),
    ("경상남", "경남"), ("경남", "경남"), ("제주", "제주"),
]


def parse_region(address, fallback_sido=None):
    toks = (address or "").replace(",", " ").split()
    sido = None
    if toks:
        for pre, name in SIDO_MAP:
            if toks[0].startswith(pre):
                sido = name
                break
    if sido is None:
        sido = fallback_sido
        rest = toks
    else:
        rest = toks[1:]
    if sido is None:
        return None, None
    if sido == "세종":
        return sido, "세종시"
    sigungu = None
    for t in rest[:3]:
        if len(t) >= 2 and t[-1] in "시군구" and not re.search(r"\d", t):
            sigungu = t
            break
    return sido, sigungu


def make_store(brand, branch, address, rule, dates, url, quote, today, fallback_sido=None):
    sido, sigungu = parse_region(address, fallback_sido)
    return {
        "brand": brand,
        "branch": branch,
        "address": address,
        "sido": sido,
        "sigungu": sigungu,
        "rule": rule,
        "closedDates": dates,
        "sourceUrl": url,
        "sourceQuote": quote,
        "fetchedAt": today.isoformat(),
    }


# ---------------------------------------------------------------- 이마트 계열
EMART = "https://store.emart.com"
SF_PREFIX = ("스타필드 마켓 ", "스타필드마켓 ")


def collect_emart(today, nodata, errors):
    raw = http(EMART + "/branch/listAll.do")
    branches = json.loads(raw)["branchList"]
    log("[이마트] 점포 목록 %d건 (노브랜드·에브리데이·몰리스 등 비대상 포함)" % len(branches))
    stores = []
    for b in branches:
        name = b["jijumName"].strip()
        if name.startswith("이마트 "):
            brand, branch = "이마트", name[len("이마트 "):]
        elif name.startswith("트레이더스 홀세일 클럽 "):
            brand, branch = "이마트 트레이더스", name[len("트레이더스 홀세일 클럽 "):]
        elif name.startswith(SF_PREFIX):
            brand, branch = "이마트", name  # 스타필드 마켓(이마트 운영 대형마트)은 표기 그대로
        else:
            continue
        jid = b["jijumId"]
        url = "%s/branch/view.do?id=%s" % (EMART, jid)
        try:
            page = http(url)
        except RuntimeError as e:
            errors.append(str(e))
            continue
        m = re.search(r'<li class="closed-day">(.*?)</li>', page, re.S)
        day_txt = clean(re.search(r"<p>(.*?)</p>", m.group(1), re.S).group(1)) if m and re.search(r"<p>", m.group(1)) else ""
        addr = None
        for cls in ("road-name", "lot-number"):
            am = re.search(r'<dt class="lab %s">.*?</dt>\s*<dd class="data">(.*?)</dd>' % cls, page, re.S)
            if am and clean(am.group(1)):
                addr = clean(am.group(1))
                break
        rule_sentence = re.search(r"※[^<]*휴점[^<]*", page)
        rule = find_rule(rule_sentence.group(0)) if rule_sentence else None
        dates = infer_dates(day_txt, today)
        if not dates:
            nodata.append(("never" if NEVER_RE.search(day_txt) else "unknown", brand, branch, addr, url, day_txt))
            continue
        stores.append(make_store(brand, branch, addr or "", rule, dates, url, day_txt, today, b.get("areaName")))
    return stores


# ------------------------------------------------------------------ 롯데마트
LOTTE_URL = "https://company.lottemart.com/shop/shop_search.asp?list_num=1000"
LOTTE_BRANDS = ("롯데마트", "MAXX")


def collect_lotte(today, nodata, errors):
    page = http(LOTTE_URL)
    rows = re.findall(r'<tr>\s*<td><img src="/img/shop/logo[^>]*alt="([^"]*)"[^>]*></td>(.*?)</tr>', page, re.S)
    log("[롯데마트] 목록 %d행 (토이저러스·보틀벙커 등 비대상 포함)" % len(rows))
    stores = []
    for alt, body in rows:
        if alt not in LOTTE_BRANDS:
            continue
        name = clean(re.search(r"<td>(.*?)</td>", body, re.S).group(1))
        memo = clean((re.search(r'class="memo">(.*?)</span>', body, re.S) or [None, ""])[1])
        off = clean((re.search(r'class="day-off">(.*?)</span>', body, re.S) or [None, ""])[1])
        addrs = [clean(a) for a in re.findall(r'<div class="address">(.*?)</div>', body, re.S)]
        addr = addrs[0] if addrs else ""
        if len(addrs) > 1 and addrs[1] and re.match(r"\d", addrs[1]):
            addr = addr + " " + addrs[1]
        dates = infer_dates(off, today)
        if not dates:
            kind = "never" if NEVER_RE.search(memo + " " + off) else "unknown"
            nodata.append((kind, "롯데마트", name, addr, LOTTE_URL, (memo + " | " + off).strip(" |")))
            continue
        rule = find_rule(memo) if memo else None
        stores.append(make_store("롯데마트", name, addr, rule, dates, LOTTE_URL, off, today))
    return stores


# ------------------------------------------------------------------- 홈플러스
HP = "https://my.homeplus.co.kr"


def collect_homeplus(today, nodata, errors):
    raw = http(HP + "/store/get_list", data={
        "viewHomeplus": "H", "viewExpress": "", "viewPlus": "", "viewSpecial": "",
        "draw": "1", "pageSize": "1000", "sortFlag": "", "latitude": "", "longitude": "",
        "searchRegion": "", "locatePage": "N", "searchStoreNm": ""},
        headers={"X-Requested-With": "XMLHttpRequest"})
    js = json.loads(raw)
    items = js.get("data") or []
    log("[홈플러스] 점포 목록 %d건 (사이트 표기 총수 %s)" % (len(items), items[0].get("storListCount") if items else "?"))
    stores = []
    for it in items:
        sid = it["storId"]
        name = it["storKorNm"].strip()
        url = "%s/store/store_detail?storId=%s" % (HP, sid)
        addr = clean(it.get("storAddr"))
        off = clean(it.get("storDayoffCntt"))
        try:
            page = http(url)
            m = re.search(r'id="dayOffHoliday"[^>]*>(.*?)</dd>', page, re.S)
            if m is not None:
                off = clean(m.group(1))
            am = re.search(r"<dt>주소</dt>\s*<dd>(.*?)(?:<a |</dd>)", page, re.S)
            if am and clean(am.group(1)):
                addr = clean(am.group(1))
        except RuntimeError as e:
            errors.append(str(e))
        dates = infer_dates(off, today)
        if not dates:
            nodata.append(("never" if NEVER_RE.search(off) else "unknown", "홈플러스", name, addr, url, off))
            continue
        stores.append(make_store("홈플러스", name, addr, None, dates, url, off, today))
    return stores


# --------------------------------------------------------------------- 코스트코
# 코스트코는 소유자가 제공한 mart_closing_days_costco.json을 그대로 두고, 지역 패턴 산출에만 읽어서 쓴다.
# (해당 파일에는 주소가 없어 점포명 -> 시도/시군구 표를 사용. 공식 매장찾기 JSON의 주소 기준.)
COSTCO_REGION = {
    "천안점": ("충남", "천안시"), "양재점": ("서울", "서초구"), "일산점": ("경기", "고양시"),
    "의정부점": ("경기", "의정부시"), "대구점": ("대구", "북구"), "대전점": ("대전", "중구"),
    "광명점": ("경기", "광명시"), "상봉점": ("서울", "중랑구"), "공세점": ("경기", "용인시"),
    "부산점": ("부산", "수영구"), "양평점": ("서울", "영등포구"), "울산점": ("울산", "북구"),
    "송도점": ("인천", "연수구"), "대구 혁신도시점": ("대구", "동구"), "대구혁신점": ("대구", "동구"), "세종점": ("세종", "세종시"),
    "하남점": ("경기", "하남시"), "김해점": ("경남", "김해시"), "고척점": ("서울", "구로구"),
    "청라점": ("인천", "서해구"), "평택점": ("경기", "평택시"),
}


def load_costco(assets_dir):
    import os
    p = os.path.join(assets_dir, "mart_closing_days_costco.json")
    try:
        with open(p, encoding="utf-8") as f:
            js = json.load(f)
    except Exception as e:
        log("코스트코 파일을 읽지 못함(%s): %s - 지역 패턴에서 제외" % (p, e))
        return []
    out = []
    for s in js.get("stores", []):
        reg = COSTCO_REGION.get(s["branch"])
        if not reg:
            log("코스트코 %s: 지역 표에 없어 지역 패턴에서 제외" % s["branch"])
            continue
        out.append({"brand": "코스트코", "branch": s["branch"], "sido": reg[0], "sigungu": reg[1],
                    "closedDates": s["closedDates"]})
    return out


# ------------------------------------------------------------------- 지역 패턴
WD_NAMES = "일월화수목금토"  # 0=일 ... 6=토
ORD_NAMES = {1: "첫째", 2: "둘째", 3: "셋째", 4: "넷째"}


def wd_of(iso):
    return (dt.date.fromisoformat(iso).weekday() + 1) % 7


def ord_of(iso):
    return (dt.date.fromisoformat(iso).day - 1) // 7 + 1


def upcoming(wd, weeks, today, n=3):
    out, d = [], today
    for _ in range(400):
        iso = d.isoformat()
        if wd_of(iso) == wd and ord_of(iso) in weeks:
            out.append(iso)
            if len(out) == n:
                break
        d += dt.timedelta(days=1)
    return out


BASIS = "공식 점포 안내의 휴무일에서 관찰한 패턴(조례·고시 원문 아님)"


def derive_districts(stores, nodata, today):
    groups = collections.defaultdict(list)
    for s in stores:
        groups[(s["sido"], s["sigungu"])].append(s)
    never = collections.defaultdict(list)
    for kind, brand, branch, addr, url, txt in nodata:
        if kind == "never":
            sido, sg = parse_region(addr)
            never[(sido, sg)].append("%s %s" % (brand, branch))
    keys = sorted(set(groups) | set(never), key=lambda k: (str(k[0]), str(k[1])))
    out = []
    for key in keys:
        sido, sg = key
        ss = groups.get(key, [])
        rec = {"sido": sido, "sigungu": sg}
        info = {s["brand"] + " " + s["branch"]: s["closedDates"] for s in ss}

        def raw(rec):
            rec["observed"] = [{"store": k, "dates": v} for k, v in sorted(info.items())]

        if never.get(key):
            rec.update(pattern=None, weekday=None, weeks=None, storesObserved=len(ss),
                       storesAgreeing=0, exampleDates=[], basis=BASIS,
                       reason="연중무휴 등 휴무 없음으로 표기된 점포가 있어 패턴 미산출",
                       neverClosingStores=sorted(never[key]))
            raw(rec)
            out.append(rec)
            continue
        # 점포별 (요일 집합, 주차 집합)
        prof = {}
        for s in ss:
            ds = s["closedDates"]
            prof[s["brand"] + " " + s["branch"]] = (
                {wd_of(d) for d in ds}, {ord_of(d) for d in ds})
        single = [k for k, (w, o) in prof.items() if len(w) == 1]
        cnt = collections.Counter(next(iter(prof[k][0])) for k in single)
        weekday, weeks = None, None
        if cnt:
            weekday = cnt.most_common(1)[0][0]
            sets = collections.Counter(
                tuple(sorted(prof[k][1])) for k in single
                if next(iter(prof[k][0])) == weekday and len(prof[k][1]) >= 2)
            if sets:
                weeks = list(max(sets.items(), key=lambda kv: (kv[1], -len(kv[0])))[0])
        observed = len(ss)

        def fits(k):
            w, o = prof[k]
            return weekday is not None and weeks is not None and w == {weekday} and o <= set(weeks)

        agreeing = [k for k in prof if fits(k)] if weeks else []
        rec.update(storesObserved=observed, storesAgreeing=len(agreeing), basis=BASIS)
        ok = (weeks is not None and observed >= 2 and len(agreeing) / observed >= 0.8
              and all(1 <= x <= 4 for x in weeks) and len(weeks) <= 2)
        if ok:
            rec.update(
                pattern="·".join(ORD_NAMES[x] for x in weeks) + " " + WD_NAMES[weekday] + "요일",
                weekday=weekday, weeks=weeks, exampleDates=upcoming(weekday, weeks, today))
            diss = sorted(k for k in prof if k not in agreeing)
            if diss:
                rec["dissentingStores"] = [{"store": k, "dates": info[k]} for k in diss]
        else:
            if observed < 2:
                why = "관찰 점포 2곳 미만"
            elif weeks is None:
                why = "2개 이상 주차가 확인되는 점포가 없어 주차 특정 불가(단일 회차 날짜뿐)"
            else:
                why = "일치율 80%% 미만(%d/%d)이거나 주차·요일 패턴에 맞지 않음" % (len(agreeing), observed)
            rec.update(pattern=None, weekday=None, weeks=None, exampleDates=[], reason=why)
            raw(rec)
        out.append(rec)
    return out


WD_CODES = ["SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"]


def store_pattern(dates, district_rec):
    """점포 자신의 날짜에서 (요일, n번째) 쌍을 모아 [{weekday, weeks}] 로. 주차가 하나뿐이면
    시군구 확정 패턴과 맞을 때만 그 패턴으로 보강, 아니면 None."""
    pairs = {(wd_of(d), ord_of(d)) for d in dates}
    if not pairs or any(o > 4 for _, o in pairs):
        return None
    def pack(ps):
        by = collections.defaultdict(set)
        for w, o in ps:
            by[w].add(o)
        return [{"weekday": WD_CODES[w], "weeks": sorted(by[w])} for w in sorted(by)]
    if len(pairs) >= 2 and not (len({w for w, _ in pairs}) == len(pairs)):
        return pack(pairs)           # 같은 요일에서 2개 이상 주차 확인
    # 요일이 서로 다른 쌍만 있거나(예: 화 2째 + 일 4째) 쌍이 1개뿐: 다른 근거 없이는 단정하기 어려움
    if district_rec and district_rec.get("pattern"):
        w, wk = district_rec["weekday"], set(district_rec["weeks"])
        if all(pw == w and po in wk for pw, po in pairs):
            return [{"weekday": WD_CODES[w], "weeks": sorted(wk)}]
    if len(pairs) >= 2:
        return pack(pairs)           # 서로 다른 요일 조합: 날짜 자체가 근거 (예: 목 2째 + 일 4째)
    return None


def months_of(dates):
    return sorted({d[:7] for d in dates})


# ---------------------------------------------------------------------- main
def main():
    import os
    ap = argparse.ArgumentParser()
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    ap.add_argument("--assets-dir", default=os.path.join(root, "app", "src", "main", "assets"))
    ap.add_argument("--today")
    ap.add_argument("--allow-partial", action="store_true")
    ap.add_argument("--no-write", action="store_true")
    a = ap.parse_args()
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass
    today = dt.date.fromisoformat(a.today) if a.today else dt.datetime.now(KST).date()
    log("기준일(KST): %s" % today)

    nodata, errors, stores = [], [], []
    per_chain = {}
    for label, fn in (("이마트/트레이더스", collect_emart), ("롯데마트", collect_lotte),
                      ("홈플러스", collect_homeplus)):
        try:
            got = fn(today, nodata, errors)
        except Exception as e:
            errors.append("%s 전체 실패: %s" % (label, e))
            got = []
        per_chain[label] = len(got)
        stores.extend(got)
    stores.sort(key=lambda s: (s["brand"], str(s["sido"]), str(s["sigungu"]), s["branch"]))

    log("")
    log("== 브랜드별 점포 수 ==")
    for b, n in collections.Counter(s["brand"] for s in stores).most_common():
        log("  %s: %d" % (b, n))
    log("== 시도별 점포 수 ==")
    for k, n in collections.Counter(str(s["sido"]) for s in stores).most_common():
        log("  %s: %d" % (k, n))
    unparsed = [s for s in stores if not s["sido"] or not s["sigungu"]]
    if unparsed:
        log("== 주소 파싱 실패 %d건 ==" % len(unparsed))
        for s in unparsed:
            log("  %s %s | %s" % (s["brand"], s["branch"], s["address"]))
    closed_known = set()
    try:
        with open(os.path.join(a.assets_dir, "mart_closed_stores.json"), encoding="utf-8") as f:
            closed_known = {(x["brand"], x["branch"]) for x in json.load(f)["stores"]}
    except Exception as e:
        log("mart_closed_stores.json 읽기 실패(%s) - 폐점 구분 없이 기록" % e)
    log("== 휴무일이 페이지에 명시되지 않아 JSON에서 제외한 점포 %d곳 ==" % len(nodata))
    for kind, brand, branch, addr, url, txt in nodata:
        if kind == "never":
            tag = "휴무없음표기"
        elif (brand, branch) in closed_known:
            tag = "폐점 확인됨(mart_closed_stores.json)"
        else:
            tag = "확인 필요(폐점 가능성, 사이트에 휴무일 없음)"
        log("  [%s] %s %s | %s | %r" % (tag, brand, branch, addr, txt))
    if errors:
        log("== 오류 %d건 ==" % len(errors))
        for e in errors:
            log("  " + e)

    empty = [k for k, n in per_chain.items() if n == 0]
    if empty and not a.allow_partial:
        log("다음 체인 수집 결과가 0건이라 파일을 쓰지 않음: %s (--allow-partial로 강제)" % ", ".join(empty))
        return 2

    costco = load_costco(a.assets_dir)
    districts = derive_districts(stores + costco, nodata, today)
    dmap = {(d["sido"], d["sigungu"]): d for d in districts}
    for s in stores:
        s["pattern"] = store_pattern(s["closedDates"], dmap.get((s["sido"], s["sigungu"])))
        s["coveredMonths"] = months_of(s["closedDates"])
    log("== 점포별 pattern: 확정 %d / null %d ==" % (sum(1 for s in stores if s["pattern"]), sum(1 for s in stores if not s["pattern"])))
    confirmed = sum(1 for d in districts if d["pattern"])
    log("== 시군구 %d곳: 패턴 확정 %d, null %d ==" % (len(districts), confirmed, len(districts) - confirmed))

    if a.no_write:
        return 0
    import os as _os
    p1 = _os.path.join(a.assets_dir, "mart_closing_days.json")
    p2 = _os.path.join(a.assets_dir, "mart_closing_rules_by_district.json")
    with open(p1, "w", encoding="utf-8", newline="\n") as f:
        json.dump({"collectedAt": today.isoformat(),
                   "note": "각 체인 공식 점포 안내에서 수집. 변경될 수 있으니 방문 전 확인.",
                   "stores": stores}, f, ensure_ascii=False, indent=1)
        f.write("\n")
    with open(p2, "w", encoding="utf-8", newline="\n") as f:
        json.dump({"generatedAt": today.isoformat(),
                   "weekdayConvention": "0=일요일, 1=월, ... 6=토. weeks는 해당 월의 n번째 해당 요일(1~4)",
                   "note": BASIS + ". 법적 근거는 해당 지자체 조례·고시 확인 필요.",
                   "districts": districts}, f, ensure_ascii=False, indent=1)
        f.write("\n")
    log("작성: %s, %s" % (p1, p2))
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
