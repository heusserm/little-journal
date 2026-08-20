#!/usr/bin/env python3
"""Per-method complexity, LOC, coverage and CRAP.

CRAP = comp^2 * (1 - cov)^3 + comp. Above 30 is the conventional fail mark:
it means code both branchy and unverified, where a change is most likely to
break something silently.

Run the tests with coverage first, then this:

    ./gradlew :app:koverXmlReport :storage:koverXmlReport
    xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \\
      -destination 'platform=iOS Simulator,name=iPhone 17 Pro,OS=26.5' \\
      -derivedDataPath iosApp/dd -enableCodeCoverage YES \\
      -resultBundlePath iosApp/cov.xcresult
    python3 tools/crap.py

Complexity and LOC come from brace-matching the source -- a smell detector,
not a compiler. Extension functions are reported by receiver type, so a few
rows join imperfectly.

Complexity/LOC come from brace-matching the source; coverage from Kover
(Kotlin) and xccov (Swift). Joined on (source file, method name).
"""
import json, re, subprocess, sys, xml.etree.ElementTree as ET
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
FUNC = re.compile(r'^\s*(?:@\w+\s+)*(?:public |private |internal |protected |override |suspend |inline |final |static |open |fun |func )*\b(?:fun|func)\s+([A-Za-z_]\w*)')
DECISION = re.compile(r'\bif\b|\bfor\b|\bwhile\b|\bcatch\b|&&|\|\||\?:')

def strip_comments(lines):
    out, blk = [], False
    for ln in lines:
        s = ln
        if blk:
            if '*/' in s: s = s.split('*/',1)[1]; blk = False
            else: continue
        if '/*' in s:
            b,_,a = s.partition('/*')
            if '*/' in a: s = b + a.split('*/',1)[1]
            else: s = b; blk = True
        out.append(re.sub(r'//.*','',s))
    return out

def analyze(path):
    src = strip_comments(path.read_text(errors='ignore').splitlines())
    res, i = [], 0
    while i < len(src):
        m = FUNC.match(src[i])
        if not m: i += 1; continue
        j, opened, depth, body = i, False, 0, []
        while j < len(src):
            line = src[j]; body.append(line)
            for ch in line:
                if ch == '{': depth += 1; opened = True
                elif ch == '}': depth -= 1
            if opened and depth == 0: break
            if not opened and j == i and re.search(r'\)\s*(:\s*[\w<>?., ]+)?\s*=\s*\S', line): break
            if not opened and j > i and re.search(r'=\s*\S', line): break
            j += 1
        text = '\n'.join(body)
        cx = 1 + len(DECISION.findall(text))
        for wm in re.finditer(r'\bwhen\b\s*(\([^)]*\))?\s*\{', text):
            k, d = wm.end()-1, 0
            while k < len(text):
                if text[k] == '{': d += 1
                elif text[k] == '}':
                    d -= 1
                    if d == 0: break
                k += 1
            cx += text[wm.end():k].count('->')
        res.append((m.group(1), sum(1 for b in body if b.strip()), cx))
        i = j + 1
    return res

# ---- coverage: Kotlin
cov = {}
for mod in ("storage", "app"):
    f = Path(mod)/"build/reports/kover/report.xml"
    if not f.exists(): continue
    for pkg in ET.parse(f).getroot().findall("package"):
        for cls in pkg.findall("class"):
            sf = cls.get("sourcefilename")
            for meth in cls.findall("method"):
                for c in meth.findall("counter"):
                    if c.get("type") != "LINE": continue
                    key = (sf, meth.get("name"))
                    p = cov.setdefault(key, [0,0])
                    p[0] += int(c.get("covered")); p[1] += int(c.get("missed"))

# ---- coverage: Swift
res = Path("iosApp/cov.xcresult")
if res.exists():
    out = subprocess.run(["xcrun","xccov","view","--report","--json",str(res)],
                         capture_output=True, text=True).stdout
    for t in json.loads(out).get("targets", []):
        for fl in t.get("files", []):
            sf = Path(fl["name"]).name
            for fn in fl.get("functions", []):
                nm = fn["name"]
                if "closure" in nm or "initialization" in nm: continue
                bare = nm.split("(")[0].split(".")[-1]
                p = cov.setdefault((sf, bare), [0,0])
                p[0] += fn["coveredLines"]
                p[1] += fn["executableLines"] - fn["coveredLines"]

rows = []
for pat in ("app/src/**/*.kt", "storage/src/**/*.kt", "iosApp/iosApp/*.swift"):
    for p in sorted(Path('.').glob(pat)):
        s = str(p)
        if '/build/' in s or '/dd' in s or 'Test' in s: continue
        for name, loc, cx in analyze(p):
            c = cov.get((p.name, name))
            pct = (100.0*c[0]/(c[0]+c[1]) if c and (c[0]+c[1]) else None)
            rows.append((cx, loc, pct, name, s))

def crap(cx, pct):
    """CRAP = comp^2 * (1 - cov)^3 + comp.  <=30 is the conventional pass mark."""
    if pct is None:
        return None
    cov = pct / 100.0
    return cx ** 2 * (1 - cov) ** 3 + cx

scored = [(crap(cx, pct), cx, loc, pct, name, f) for cx, loc, pct, name, f in rows]
scored = [r for r in scored if r[0] is not None]
scored.sort(key=lambda r: -r[0])

print(f"{'CRAP':>6} {'cx':>3} {'loc':>4} {'cover':>6}  {'method':<28} file")
print("-" * 92)
for c, cx, loc, pct, name, f in scored:
    flag = "  FAIL" if c > 30 else ("  watch" if c > 10 else "")
    short = f.replace('src/commonMain/kotlin/com/xndev/littlejournal/', '…/').replace('src/', '')
    print(f"{c:>6.1f} {cx:>3} {loc:>4} {pct:>5.0f}%  {name:<28} {short}{flag}")
print("-" * 92)
over = [r for r in scored if r[0] > 30]
watch = [r for r in scored if 10 < r[0] <= 30]
print(f"{len(scored)} methods scored | mean CRAP {sum(r[0] for r in scored)/len(scored):.1f} | "
      f"max {max(r[0] for r in scored):.1f} | {len(over)} over 30 | {len(watch)} between 10 and 30")
