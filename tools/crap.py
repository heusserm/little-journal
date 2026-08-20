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
from parse import functions  # noqa: E402

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
        for fn in functions(p):
            c = cov.get((p.name, fn["name"]))
            pct = (100.0*c[0]/(c[0]+c[1]) if c and (c[0]+c[1]) else None)
            rows.append((fn["cx"], fn["loc"], pct, fn["shown"], s))

def crap(cx, pct):
    """CRAP = comp^2 * (1 - cov)^3 + comp.  <=30 is the conventional pass mark."""
    if pct is None:
        return None
    cov = pct / 100.0
    return cx ** 2 * (1 - cov) ** 3 + cx

scored = [(crap(cx, pct), cx, loc, pct, name, f) for cx, loc, pct, name, f in rows]
# Anything the coverage reports do not mention is held back rather than
# dropped. Silently deleting it would shrink the denominator and hide the gap,
# which is the failure mode this tool exists to catch in other people's code.
unscored = [r for r in scored if r[0] is None]
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

if unscored:
    print()
    print(f"{len(unscored)} parsed but absent from the coverage reports -- not scored above:")
    for _, cx, loc, _, name, f in sorted(unscored, key=lambda r: r[4]):
        print(f"       cx {cx:>3} loc {loc:>4}  {name:<28} {f}")
    print("Expected for code Kover excludes. Anything else here is a parser or")
    print("a coverage gap, and the number above is measuring less than you think.")
