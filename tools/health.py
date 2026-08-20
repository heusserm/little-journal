#!/usr/bin/env python3
"""health.py — one row per function: size, complexity, coverage, CRAP, tests.

Merges four sources into a single table and writes Markdown to stdout:

    source            lines and cyclomatic complexity   (brace matching)
    Kover             Kotlin per-method line coverage
    xccov             Swift per-function line coverage
    tools/scrap.py    per-test SCRAP for the test suites

Run the tests with coverage first, then:

    ./gradlew :app:koverXmlReport :storage:koverXmlReport
    xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \\
      -destination 'platform=iOS Simulator,name=iPhone 17 Pro,OS=26.5' \\
      -derivedDataPath iosApp/dd -enableCodeCoverage YES \\
      -resultBundlePath iosApp/cov.xcresult
    python3 tools/health.py > HEALTH.md

The "tests" column is a *static reference count*: how many test methods mention
the function by name. It is a proxy, not execution tracing, and it over-counts
common names like `save` or `start`. Treat a 0 there as a question, not a fact.
"""
import importlib.util
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

HERE = Path(__file__).parent


def load(name):
    spec = importlib.util.spec_from_file_location(name, HERE / f"{name}.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


scrap = load("scrap")

PROD_GLOBS = ["app/src/commonMain/**/*.kt", "app/src/androidMain/**/*.kt",
              "app/src/iosMain/**/*.kt", "app/src/desktopMain/**/*.kt",
              "storage/src/commonMain/**/*.kt", "storage/src/jvmMain/**/*.kt",
              "storage/src/androidMain/**/*.kt", "storage/src/iosMain/**/*.kt",
              "iosApp/iosApp/*.swift"]
TEST_GLOBS = scrap.DEFAULT_GLOBS

FUNC = re.compile(r'^\s*(?:@\w+\s+)*(?:public |private |internal |protected |override |suspend |inline |final |static |open |fileprivate |fun |func )*\b(?:fun|func)\s+([A-Za-z_]\w*)')
DECISION = re.compile(r'\bif\b|\bfor\b|\bwhile\b|\bcatch\b|&&|\|\||\?:')


def functions(path):
    src = scrap.strip_comments(path.read_text(errors='ignore').splitlines())
    out, i = [], 0
    while i < len(src):
        m = FUNC.match(src[i])
        if not m:
            i += 1
            continue
        lines, end = scrap.body_of(src, i)
        text = "\n".join(lines)
        cx = 1 + len(DECISION.findall(text))
        for wm in re.finditer(r'\bwhen\b\s*(\([^)]*\))?\s*\{', text):
            k, d = wm.end() - 1, 0
            while k < len(text):
                if text[k] == '{':
                    d += 1
                elif text[k] == '}':
                    d -= 1
                    if d == 0:
                        break
                k += 1
            cx += text[wm.end():k].count('->')
        out.append({"name": m.group(1), "file": str(path), "line": i + 1,
                    "loc": sum(1 for l in lines if l.strip()), "cx": cx})
        i = end + 1
    return out


def coverage():
    cov = {}
    for mod in ("storage", "app"):
        f = Path(mod) / "build/reports/kover/report.xml"
        if not f.exists():
            continue
        for pkg in ET.parse(f).getroot().findall("package"):
            for cls in pkg.findall("class"):
                sf = cls.get("sourcefilename")
                for meth in cls.findall("method"):
                    for c in meth.findall("counter"):
                        if c.get("type") == "LINE":
                            k = (sf, meth.get("name"))
                            p = cov.setdefault(k, [0, 0])
                            p[0] += int(c.get("covered"))
                            p[1] += int(c.get("missed"))
    res = Path("iosApp/cov.xcresult")
    if res.exists():
        out = subprocess.run(["xcrun", "xccov", "view", "--report", "--json", str(res)],
                             capture_output=True, text=True).stdout
        for t in json.loads(out or "{}").get("targets", []):
            for fl in t.get("files", []):
                sf = Path(fl["name"]).name
                for fn in fl.get("functions", []):
                    if "closure" in fn["name"] or "initialization" in fn["name"]:
                        continue
                    bare = fn["name"].split("(")[0].split(".")[-1]
                    p = cov.setdefault((sf, bare), [0, 0])
                    p[0] += fn["coveredLines"]
                    p[1] += fn["executableLines"] - fn["coveredLines"]
    return cov


def crap(cx, pct):
    if pct is None:
        return None
    return cx ** 2 * (1 - pct / 100.0) ** 3 + cx


def collect(globs):
    files = []
    for g in globs:
        files.extend(sorted(Path('.').glob(g)))
    return [f for f in sorted(set(files)) if '/build/' not in str(f) and '/dd' not in str(f)]


def main():
    cov = coverage()

    # test bodies, for the reference count and for SCRAP
    test_bodies, scrap_rows = [], []
    for f in collect(TEST_GLOBS):
        tests, helpers = scrap.parse_file(f)
        for t in tests:
            test_bodies.append("\n".join(t["lines"]))
            m = scrap.measure(t, helpers)
            m["file"] = f.name
            scrap_rows.append(m)

    prod = []
    for f in collect(PROD_GLOBS):
        for fn in functions(f):
            c = cov.get((Path(fn["file"]).name, fn["name"]))
            pct = 100.0 * c[0] / (c[0] + c[1]) if c and sum(c) else None
            word = re.compile(r'\b' + re.escape(fn["name"]) + r'\b')
            fn["tests"] = sum(1 for b in test_bodies if word.search(b))
            fn["cov"] = pct
            fn["crap"] = crap(fn["cx"], pct)
            prod.append(fn)

    def short(p):
        return (p.replace("src/commonMain/kotlin/com/xndev/littlejournal/", "")
                 .replace("src/", "").replace("app/app/", "app/"))

    print("# Health report — per function\n")
    print("Generated by `python3 tools/health.py`. Regenerate rather than trust; "
          "every number here is reproducible.\n")
    print("`tests` is a **static reference count** — how many test methods mention the "
          "function by name. It is a proxy, not execution tracing, and it over-counts "
          "common names like `save` or `start`. Read a 0 as a question, not a fact.\n")

    scored = [f for f in prod if f["crap"] is not None]
    print(f"**{len(prod)} production functions** · "
          f"{sum(f['loc'] for f in prod)} lines · "
          f"mean complexity {sum(f['cx'] for f in prod)/len(prod):.1f} · "
          f"mean CRAP {sum(f['crap'] for f in scored)/len(scored):.1f} · "
          f"max CRAP {max(f['crap'] for f in scored):.1f}\n")

    print("## Production functions\n")
    print("Sorted by CRAP: the ones most likely to break quietly come first.\n")
    print("| CRAP | cx | lines | cover | tests | function | file |")
    print("|---:|---:|---:|---:|---:|---|---|")
    for f in sorted(prod, key=lambda f: (-(f["crap"] if f["crap"] is not None else -1), -f["cx"])):
        c = f"{f['cov']:.0f}%" if f["cov"] is not None else "—"
        cr = f"{f['crap']:.1f}" if f["crap"] is not None else "—"
        print(f"| {cr} | {f['cx']} | {f['loc']} | {c} | {f['tests']} | `{f['name']}` | {short(f['file'])} |")

    print("\n## Test functions\n")
    print(f"**{len(scrap_rows)} tests** · "
          f"{sum(t['assertions'] for t in scrap_rows)} assertions · "
          f"mean SCRAP {sum(t['scrap'] for t in scrap_rows)/len(scrap_rows):.1f}\n")
    print("| SCRAP | cx | lines | hidden | asserts | test | file | smells |")
    print("|---:|---:|---:|---:|---:|---|---|---|")
    for t in sorted(scrap_rows, key=lambda t: -t["scrap"]):
        print(f"| {t['scrap']} | {t['complexity']} | {t['line_count']} | {t['hidden_lines']} | "
              f"{t['assertions']} | {t['name'][:52]} | {t['file']} | {', '.join(t['smells']) or '—'} |")
    return 0


if __name__ == "__main__":
    sys.exit(main())
