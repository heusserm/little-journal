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

The "suites" column is real execution attribution, not name matching: each test
class is run in isolation under coverage by tools/attribute.py, and this counts
how many of them actually execute the function. Run that first, or the column
is omitted.
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
parse = load("parse")

PROD_GLOBS = ["app/src/commonMain/**/*.kt", "app/src/androidMain/**/*.kt",
              "app/src/iosMain/**/*.kt", "app/src/desktopMain/**/*.kt",
              "storage/src/commonMain/**/*.kt", "storage/src/jvmMain/**/*.kt",
              "storage/src/androidMain/**/*.kt", "storage/src/iosMain/**/*.kt",
              "iosApp/iosApp/*.swift"]
TEST_GLOBS = scrap.DEFAULT_GLOBS

def functions(path):
    """Delegates to tools/parse.py, which owns the one brace matcher."""
    return parse.functions(path)


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

    # which test classes actually execute which methods
    attribution = {}
    attr_file = HERE / ".attribution.json"
    if attr_file.exists():
        for cls, methods in json.loads(attr_file.read_text()).items():
            for m in methods:
                attribution.setdefault(m, set()).add(cls.split(".")[-1])

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
            key = f'{Path(fn["file"]).name}::{fn["name"]}'
            fn["suites"] = sorted(attribution.get(key, ()))
            fn["cov"] = pct
            fn["crap"] = crap(fn["cx"], pct)
            prod.append(fn)

    def short(p):
        return (p.replace("src/commonMain/kotlin/com/xndev/littlejournal/", "")
                 .replace("src/", "").replace("app/app/", "app/"))

    print("# Health report — per function\n")
    print("Generated by `python3 tools/health.py`. Regenerate rather than trust; "
          "every number here is reproducible.\n")
    print("`suites` is **real execution attribution**: each test class was run in "
          "isolation under coverage by `tools/attribute.py`, and the column names the "
          "classes that actually execute the function. Not name matching.\n")

    scored = [f for f in prod if f["crap"] is not None]
    print(f"**{len(prod)} production functions** · "
          f"{sum(f['loc'] for f in prod)} lines · "
          f"mean complexity {sum(f['cx'] for f in prod)/len(prod):.1f} · "
          f"mean CRAP {sum(f['crap'] for f in scored)/len(scored):.1f} · "
          f"max CRAP {max(f['crap'] for f in scored):.1f}\n")

    print("## Production functions\n")
    print("Sorted by CRAP: the ones most likely to break quietly come first.\n")
    print("| CRAP | cx | lines | cover | suites | function | exercised by |")
    print("|---:|---:|---:|---:|---:|---|---|")
    for f in sorted(prod, key=lambda f: (-(f["crap"] if f["crap"] is not None else -1), -f["cx"])):
        c = f"{f['cov']:.0f}%" if f["cov"] is not None else "—"
        cr = f"{f['crap']:.1f}" if f["crap"] is not None else "—"
        by = ", ".join(s.replace("Test", "").replace("iosAppTests/", "") for s in f["suites"][:3])
        if len(f["suites"]) > 3:
            by += f" +{len(f['suites']) - 3}"
        print(f"| {cr} | {f['cx']} | {f['loc']} | {c} | {len(f['suites'])} | "
              f"`{f['shown']}` <br><sub>{short(f['file'])}</sub> | {by or '—'} |")

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
