#!/usr/bin/env python3
"""SCRAP — structural quality score for Kotlin and Swift test suites.

Adapted from Robert C. Martin's SCRAP for Speclj specs
(https://github.com/unclebob/scrap). The model, the smell set and the weights
are his; the parsing is rewritten for Kotlin and Swift, which have no reader
and so are measured by brace matching rather than by walking forms.

CRAP asks how risky a production method is. SCRAP asks the mirror question:
how much should these tests be trusted?

    SCRAP = complexity_score + smell_penalties

    complexity = 1 + branches + setup_depth + helper_calls + hidden_lines/8

`complexity_score` squares that and saturates, so one gnarly test cannot swamp
a file's average. Squaring mirrors CRAP: structure compounds.

Two ideas carried over from the original are worth stating, because both
correct mistakes an obvious implementation makes:

  * Hidden lines. A five-line test calling a forty-line helper is not a
    five-line test. Helper bodies count toward the example that calls them.

  * Extraction pressure. Duplication is measured by Jaccard similarity over
    each test's setup/assert/fixture feature sets, then clustered — not by
    comparing text. A repeated one-line factory call is deliberate isolation
    and must not be penalised; six tests that assemble the same elaborate
    fixture differently should be.

Exemptions matter as much as the rules. A table-driven test legitimately
branches. A contract test legitimately makes one assertion over many lines.
Flagging those trains people to ignore the tool.

Output is decision support, not instruction. Confirm against the actual test
before refactoring anything.

    python3 tools/scrap.py                  # report
    python3 tools/scrap.py --verbose        # per-test metric dump
    python3 tools/scrap.py --json           # machine readable
    python3 tools/scrap.py --write-baseline # save to tools/.scrap-baseline.json
    python3 tools/scrap.py --compare        # diff against that baseline
"""
import argparse
import json
import math
import re
import sys
from collections import defaultdict
from itertools import combinations
from pathlib import Path

DEFAULT_GLOBS = ["app/src/*Test/**/*.kt", "storage/src/*Test/**/*.kt",
                 "iosApp/iosAppTests/*.swift"]
BASELINE = Path("tools/.scrap-baseline.json")

# Weights are Uncle Bob's, unchanged.
SMELLS = {
    "no-assertions":            (10, "passes no matter what the code does"),
    "low-assertion-density":    (6,  "one assertion buried in a long example"),
    "multiple-phases":          (5,  "several act/assert cycles in one test"),
    "high-mocking":             (4,  "so many fakes the test may only test fakes"),
    "large-example":            (4,  "long enough that its intent is hard to see"),
    "helper-hidden-complexity": (4,  "most of the test lives somewhere else"),
    "temp-resource-work":       (3,  "files, threads or processes — slow and flaky"),
    "literal-heavy-setup":      (3,  "large inline data obscures the behaviour"),
}
COMPLEXITY_CAP = 49          # a test scoring 7 on structure is already the worst news
DUPLICATION_THRESHOLD = 0.62  # Jaccard, above which two tests are "the same shape"

ASSERT = re.compile(r'\b(assert\w*|fail|XCTAssert\w*|XCTFail|XCTUnwrap)\s*\(')
BRANCH = re.compile(r'\b(if|when|for|while|guard)\b|&&|\|\||\?:|\bcatch\b')
SCOPING = re.compile(r'\b(let|run|apply|also|with|forEach|repeat|setContent|runComposeUiTest)\s*[\({]')
FAKE = re.compile(r'\b(Fake\w*|Mock\w*|Stub\w*|object\s*:)\b')
TEMP = re.compile(r'\b(createTempFile|createTempDir|Thread|Runtime|ProcessBuilder|FileManager\.default)\b')
FUNC = re.compile(r'^\s*(?:override\s+|private\s+|internal\s+|public\s+|fileprivate\s+)*(?:fun|func)\s+(`[^`]+`|[A-Za-z_]\w*)')
TESTANNO = re.compile(r'@Test\b')
IDENT = re.compile(r'[A-Za-z_]\w*')
BIGSTRING = re.compile(r'"[^"\n]{60,}"')   # [^"] alone spans newlines and matches the gap between literals


def strip_comments(lines):
    out, blk = [], False
    for ln in lines:
        s = ln
        if blk:
            if '*/' in s:
                s, blk = s.split('*/', 1)[1], False
            else:
                out.append("")
                continue
        if '/*' in s:
            b, _, a = s.partition('/*')
            s, blk = (b + a.split('*/', 1)[1], False) if '*/' in a else (b, True)
        out.append(re.sub(r'//.*', '', s))
    return out


def body_of(src, i):
    """Body starting at the signature line i. Returns (lines, last_index).

    Handles both braced bodies and expression bodies. Expression bodies have no
    braces at all and may wrap across lines:

        private fun freshState(canDictate: Boolean = true) =
            JournalState(inMemoryRepository(), FakeTranscriber(...))

    Naive brace matching runs past those to the next `{` it can find, which is
    the following test — so the helper swallows a test and it vanishes from the
    report. Scan forward for the opening brace, but give up if another
    declaration or annotation appears first.
    """
    j = i
    while j < len(src) and '{' not in src[j]:
        if j > i and (FUNC.match(src[j]) or src[j].strip().startswith('@')):
            return src[i:j], j - 1
        j += 1
    if j >= len(src):
        return src[i:], len(src) - 1

    depth, out = 0, []
    for k in range(i, len(src)):
        out.append(src[k])
        if k >= j:
            depth += src[k].count('{') - src[k].count('}')
            if depth == 0:
                return out, k
    return out, len(src) - 1


def parse_file(path):
    """Split a test file into tests and local helpers."""
    src = strip_comments(path.read_text(errors='ignore').splitlines())
    swift = path.suffix == '.swift'
    tests, helpers = [], {}
    i = 0
    while i < len(src):
        m = FUNC.match(src[i])
        if not m:
            i += 1
            continue
        name = m.group(1).strip('`')
        annotated = any(TESTANNO.search(src[k]) for k in range(max(0, i - 4), i))
        lines, end = body_of(src, i)
        inner = [l.strip() for l in lines[1:-1] if l.strip()]
        rec = {"name": name, "line": i + 1, "lines": inner, "text": "\n".join(lines)}
        if annotated or (swift and name.startswith("test")):
            tests.append(rec)
        else:
            helpers[name] = rec
        i = end + 1
    return tests, helpers


def setup_depth(lines):
    """Deepest nesting of scoping constructs inside the test."""
    depth = best = 0
    for l in lines:
        if SCOPING.search(l):
            depth += 1
            best = max(best, depth)
        depth += l.count('{') - l.count('}')
        depth = max(depth, 0)
    return min(best, 6)


def assertion_clusters(lines):
    """Runs of assertions separated by other statements — act/assert cycles."""
    clusters, inside = 0, False
    for l in lines:
        if ASSERT.search(l):
            if not inside:
                clusters += 1
                inside = True
        elif l.strip():
            inside = False
    return clusters


def features(lines, kind):
    """Token bag describing a test's shape, for similarity comparison."""
    out = set()
    for l in lines:
        is_assert = bool(ASSERT.search(l))
        if (kind == "assert") != is_assert:
            continue
        toks = [t for t in IDENT.findall(l) if len(t) > 2]
        out.update(toks[:6])
    return out


def measure(t, helpers):
    lines = t["lines"]
    text = t["text"]
    called = [h for h in helpers if re.search(r'\b' + re.escape(h) + r'\s*\(', text)]
    hidden = sum(len(helpers[h]["lines"]) for h in called)
    line_count = len(lines) + hidden
    assertions = len(ASSERT.findall(text))
    branches = len(BRANCH.findall(text))
    table_driven = bool(re.search(r'\b(listOf|arrayOf|forEach|for\s*\()', text)) and assertions <= 2
    # A contract test states one fact about a long interaction; that is not a smell.
    contract = assertions == 1 and assertion_clusters(lines) == 1 and branches == 0
    m = {
        "name": t["name"], "line": t["line"],
        "line_count": line_count, "raw_lines": len(lines), "hidden_lines": hidden,
        "assertions": assertions, "branches": branches,
        "setup_depth": setup_depth(lines), "helper_calls": len(called),
        "fakes": len(FAKE.findall(text)), "temp": len(TEMP.findall(text)),
        "big_literals": len(BIGSTRING.findall(text)),
        "phases": assertion_clusters(lines),
        "table_driven": table_driven, "contract": contract,
        "assert_features": features(lines, "assert"),
        "setup_features": features(lines, "setup"),
    }
    scored_branches = 0 if table_driven else m["branches"]
    m["complexity"] = 1 + scored_branches + m["setup_depth"] + m["helper_calls"] + hidden // 8
    m["complexity_score"] = min(m["complexity"] ** 2, COMPLEXITY_CAP)

    smells = []
    if assertions == 0:
        smells.append("no-assertions")
    if assertions == 1 and line_count > 10 and not table_driven and not contract:
        smells.append("low-assertion-density")
    if m["phases"] > 1:
        smells.append("multiple-phases")
    if m["fakes"] > 3:
        smells.append("high-mocking")
    if line_count > 20 and not contract:
        smells.append("large-example")
    if hidden > 8:
        smells.append("helper-hidden-complexity")
    if m["temp"]:
        smells.append("temp-resource-work")
    if m["big_literals"]:
        smells.append("literal-heavy-setup")
    m["smells"] = smells
    m["smell_penalty"] = sum(SMELLS[s][0] for s in smells)
    m["scrap"] = m["complexity_score"] + m["smell_penalty"]
    return m


def jaccard(a, b):
    u = a | b
    return len(a & b) / len(u) if u else 0.0


def extraction_pressure(measured):
    """Clusters of tests with the same shape — where extraction would actually pay."""
    def shape(m):
        return m["assert_features"] | m["setup_features"]

    adj = defaultdict(set)
    for (i, a), (j, b) in combinations(list(enumerate(measured)), 2):
        if jaccard(shape(a), shape(b)) >= DUPLICATION_THRESHOLD:
            adj[i].add(j)
            adj[j].add(i)
    seen, groups = set(), []
    for i in range(len(measured)):
        if i in seen or i not in adj:
            continue
        stack, comp = [i], set()
        while stack:
            k = stack.pop()
            if k in comp:
                continue
            comp.add(k)
            stack.extend(adj[k] - comp)
        seen |= comp
        if len(comp) > 1:
            groups.append(sorted(comp))
    return groups


def analyse(path):
    tests, helpers = parse_file(path)
    if not tests:
        return None
    measured = [measure(t, helpers) for t in tests]
    groups = extraction_pressure(measured)
    scores = [m["scrap"] for m in measured]
    return {
        "path": str(path), "tests": measured,
        "mean": sum(scores) / len(scores), "max": max(scores),
        "assertions": sum(m["assertions"] for m in measured),
        "clusters": [[measured[i]["name"] for i in g] for g in groups],
    }


def band(s):
    return "healthy" if s < 10 else ("review" if s < 25 else "POOR")


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("paths", nargs="*")
    ap.add_argument("--verbose", action="store_true")
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--write-baseline", action="store_true")
    ap.add_argument("--compare", action="store_true")
    a = ap.parse_args(argv)

    files = []
    for g in (a.paths or DEFAULT_GLOBS):
        p = Path(g)
        files.extend([p] if p.is_file() else sorted(Path('.').glob(g)))
    results = [r for r in (analyse(f) for f in sorted(set(files))) if r]
    if not results:
        print("no test files found")
        return 1
    results.sort(key=lambda r: -r["mean"])

    if a.json or a.write_baseline:
        payload = {r["path"]: {"mean": round(r["mean"], 2), "max": r["max"],
                               "tests": {t["name"]: t["scrap"] for t in r["tests"]}}
                   for r in results}
        if a.write_baseline:
            BASELINE.write_text(json.dumps(payload, indent=2, sort_keys=True))
            print(f"baseline written to {BASELINE}")
            return 0
        print(json.dumps(payload, indent=2, sort_keys=True))
        return 0

    print(f"{'mean':>6} {'max':>5} {'band':<8} {'tests':>5} {'asrt':>5}  file")
    print("-" * 92)
    for r in results:
        print(f"{r['mean']:>6.1f} {r['max']:>5} {band(r['mean']):<8} {len(r['tests']):>5} "
              f"{r['assertions']:>5}  {r['path']}")
    allt = [t for r in results for t in r["tests"]]
    overall = sum(t["scrap"] for t in allt) / len(allt)
    print("-" * 92)
    print(f"{len(results)} files | {len(allt)} tests | mean SCRAP {overall:.1f} ({band(overall)}) "
          f"| worst {max(t['scrap'] for t in allt)}")

    worst = sorted(allt, key=lambda t: -t["scrap"])[:8]
    if worst and worst[0]["scrap"] > 4:
        print("\nWorst examples")
        print("-" * 92)
        for t in worst:
            if t["scrap"] <= 4:
                break
            print(f"  {t['scrap']:>4}  cx {t['complexity']:>2}  lines {t['line_count']:>3} "
                  f"({t['hidden_lines']} hidden)  asrt {t['assertions']:>2}  "
                  f"{t['name'][:44]:<46} {', '.join(t['smells']) or '—'}")

    found = defaultdict(list)
    for r in results:
        for t in r["tests"]:
            for s in t["smells"]:
                found[s].append((Path(r["path"]).name, t["name"]))
    if found:
        print("\nSmells")
        print("-" * 92)
        for s in sorted(found, key=lambda s: -SMELLS[s][0]):
            print(f"\n  {s} (+{SMELLS[s][0]}) — {SMELLS[s][1]}")
            for f, n in found[s][:5]:
                print(f"      {n[:56]:<58} {f}")
            if len(found[s]) > 5:
                print(f"      … and {len(found[s]) - 5} more")

    pressure = [(r, c) for r in results for c in r["clusters"]]
    if pressure:
        print("\nExtraction pressure — tests sharing a shape, where a fixture would pay")
        print("-" * 92)
        for r, c in pressure[:6]:
            print(f"  {Path(r['path']).name}: {len(c)} tests")
            for n in c[:4]:
                print(f"      {n[:70]}")
            if len(c) > 4:
                print(f"      … and {len(c) - 4} more")

    if a.compare:
        if not BASELINE.exists():
            print("\nno baseline; run --write-baseline first")
            return 0
        old = json.loads(BASELINE.read_text())
        print("\nAgainst baseline")
        print("-" * 92)
        moved = False
        for r in results:
            was = old.get(r["path"], {}).get("mean")
            if was is None:
                print(f"  new     {r['path']} ({r['mean']:.1f})")
                moved = True
            elif abs(was - r["mean"]) >= 0.05:
                arrow = "worse" if r["mean"] > was else "better"
                print(f"  {arrow:<7} {r['path']}  {was:.1f} -> {r['mean']:.1f}")
                moved = True
        if not moved:
            print("  unchanged")
    return 0


if __name__ == "__main__":
    sys.exit(main())
