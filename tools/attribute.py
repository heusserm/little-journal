#!/usr/bin/env python3
"""attribute.py — which test classes actually execute which functions.

Runs each test class in isolation under coverage and records the methods it
touches. That turns the "tests" column in HEALTH.md from "how many tests
mention this name" into "how many test classes execute this code", which is the
question people think they are asking.

Slow by nature — one build per test class — so the result is cached in
tools/.attribution.json and only regenerated on demand.

    python3 tools/attribute.py            # regenerate
    python3 tools/attribute.py --show     # print what is cached
"""
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path

CACHE = Path("tools/.attribution.json")
MODULES = {"app": (":app:desktopTest", "desktopTest"),
           "storage": (":storage:jvmTest", "jvmTest")}


def test_classes(module):
    d = Path(module) / f"build/test-results/{MODULES[module][1]}"
    return sorted(p.stem.replace("TEST-", "") for p in d.glob("TEST-*.xml")) if d.exists() else []


def covered_methods(module):
    """Methods with at least one covered line in this module's Kover report."""
    f = Path(module) / "build/reports/kover/report.xml"
    if not f.exists():
        return set()
    out = set()
    for pkg in ET.parse(f).getroot().findall("package"):
        for cls in pkg.findall("class"):
            sf = cls.get("sourcefilename")
            for meth in cls.findall("method"):
                for c in meth.findall("counter"):
                    if c.get("type") == "LINE" and int(c.get("covered")) > 0:
                        out.add(f"{sf}::{meth.get('name')}")
    return out


def run(module, cls):
    task, _ = MODULES[module]
    subprocess.run(["rm", "-rf", f"{module}/build/kover",
                    f"{module}/build/reports/kover"], check=False)
    r = subprocess.run(
        ["./gradlew", task, "--tests", cls, f":{module}:koverXmlReport",
         "--rerun-tasks", "--console=plain", "-q"],
        capture_output=True, text=True)
    if r.returncode != 0:
        print(f"    ! {cls} failed", file=sys.stderr)
        return set()
    return covered_methods(module)


def swift_classes():
    return ["iosAppTests/IosTranscriberTests", "iosAppTests/TranscriberLifecycleTests"]


def run_swift(target):
    res = Path("iosApp/attr.xcresult")
    subprocess.run(["rm", "-rf", str(res)], check=False)
    r = subprocess.run(
        ["xcodebuild", "test", "-project", "iosApp/iosApp.xcodeproj", "-scheme", "iosApp",
         "-destination", "platform=iOS Simulator,name=iPhone 17 Pro,OS=26.5",
         "-derivedDataPath", "iosApp/dd", "-enableCodeCoverage", "YES",
         "-resultBundlePath", str(res), "-only-testing:" + target],
        capture_output=True, text=True)
    if not res.exists():
        return set()
    out = subprocess.run(["xcrun", "xccov", "view", "--report", "--json", str(res)],
                         capture_output=True, text=True).stdout
    got = set()
    for t in json.loads(out or "{}").get("targets", []):
        for fl in t.get("files", []):
            sf = Path(fl["name"]).name
            for fn in fl.get("functions", []):
                if fn["coveredLines"] > 0 and "closure" not in fn["name"]:
                    bare = fn["name"].split("(")[0].split(".")[-1]
                    got.add(f"{sf}::{bare}")
    subprocess.run(["rm", "-rf", str(res)], check=False)
    return got


def main():
    if "--show" in sys.argv:
        if not CACHE.exists():
            print("no cache; run without --show")
            return 1
        data = json.loads(CACHE.read_text())
        counts = defaultdict(list)
        for cls, methods in data.items():
            for m in methods:
                counts[m].append(cls.split(".")[-1])
        for m, cs in sorted(counts.items(), key=lambda kv: -len(kv[1]))[:25]:
            print(f"  {len(cs):>2}  {m:<52} {', '.join(sorted(cs))[:60]}")
        return 0

    result = {}
    for module in MODULES:
        for cls in test_classes(module):
            print(f"  {module}: {cls}", flush=True)
            result[cls] = sorted(run(module, cls))
    for target in swift_classes():
        print(f"  swift: {target}", flush=True)
        result[target] = sorted(run_swift(target))

    # Each per-class run overwrote the module's coverage report, so the last one
    # left behind is a single class's. Anything reading coverage afterwards --
    # health.py, crap.py -- would see near-zero and quietly report nonsense.
    # Restore the aggregate before returning.
    print("\n  restoring full coverage report", flush=True)
    for module, (task, _) in MODULES.items():
        subprocess.run(["rm", "-rf", f"{module}/build/kover", f"{module}/build/reports/kover"],
                       check=False)
    subprocess.run(["./gradlew", ":app:desktopTest", ":storage:jvmTest",
                    ":app:koverXmlReport", ":storage:koverXmlReport",
                    "--rerun-tasks", "--console=plain", "-q"],
                   capture_output=True, text=True)

    CACHE.write_text(json.dumps(result, indent=2, sort_keys=True))
    total = len({m for ms in result.values() for m in ms})
    print(f"\n{len(result)} test classes -> {total} distinct methods executed")
    print(f"cached in {CACHE}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
