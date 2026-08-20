#!/usr/bin/env python3
"""SCRAP — a test-quality score for Kotlin and Swift test suites.

CRAP asks "how risky is this production method?". SCRAP asks the mirror
question: "how much should I trust these tests?"

Each test is measured for size, branching, assertion density and a handful of
well-known test smells. Smells are graded rather than boolean -- a test with
four assertions is not suddenly Assertion Roulette when three was fine -- so
each contributes a penalty scaled by fuzzy membership between a "clean" and a
"clearly bad" threshold. A file's SCRAP is the mean per-test penalty plus
file-level penalties.

    SCRAP 0-10    healthy
    SCRAP 10-25   worth a look
    SCRAP 25+     the suite is probably lying to you

Usage:
    python3 tools/scrap.py [path ...]        # defaults to this repo's tests
    python3 tools/scrap.py --detail          # per-test breakdown

Heuristics, not a compiler: bodies are found by brace matching. Treat it as a
smell detector, which is all it claims to be.
"""
import re
import sys
from pathlib import Path

# ---------------------------------------------------------------- fuzzy bits

def ramp(x, lo, hi):
    """0 below lo, 1 above hi, linear between. The 'fuzzy' in fuzzy logic."""
    if x <= lo:
        return 0.0
    if x >= hi:
        return 1.0
    return (x - lo) / (hi - lo)


# Each smell: (weight, description). Penalty = weight * membership.
SMELLS = {
    "empty":        (30, "no assertions at all — passes no matter what"),
    "roulette":     (12, "many unexplained assertions — a failure won't say which"),
    "conditional":  (14, "branching inside the test — it tests different things per run"),
    "long":         (10, "long enough that its intent is hard to see"),
    "complex":      (12, "control flow of its own, so the test itself can be wrong"),
    "sleepy":       (16, "sleeps — slow and flaky"),
    "exception":    (8,  "hand-rolled try/catch instead of an assertion"),
    "print":        (4,  "prints instead of asserting"),
    "vague_name":   (6,  "name does not describe the behaviour under test"),
    "duplicate":    (10, "setup repeated across tests instead of shared"),
}

# ------------------------------------------------------------------ parsing

KOTLIN_TEST = re.compile(r'@Test\b')
FUNC = re.compile(r'^\s*(?:override\s+|private\s+|internal\s+|public\s+)*(?:fun|func)\s+(`[^`]+`|[A-Za-z_]\w*)')
SETUP = re.compile(r'@(BeforeTest|Before|BeforeEach)\b|func\s+setUp\s*\(|fun\s+setUp\s*\(')

ASSERT = re.compile(r'\b(assert\w*|fail|XCTAssert\w*|XCTFail|XCTUnwrap)\s*\(')
BRANCH = re.compile(r'\b(if|when|for|while)\s*[\(\{]')
DECISION = re.compile(r'\bif\b|\bfor\b|\bwhile\b|\bcatch\b|&&|\|\|')
SLEEP = re.compile(r'\b(Thread\.sleep|sleep\s*\(|usleep|delay\s*\()')
TRYCATCH = re.compile(r'\bcatch\s*[\(\{]')
PRINTING = re.compile(r'\b(println|print)\s*\(')


def strip_comments(lines):
    out, blk = [], False
    for ln in lines:
        s = ln
        if blk:
            if '*/' in s:
                s = s.split('*/', 1)[1]
                blk = False
            else:
                continue
        if '/*' in s:
            b, _, a = s.partition('/*')
            if '*/' in a:
                s = b + a.split('*/', 1)[1]
            else:
                s, blk = b, True
        out.append(re.sub(r'//.*', '', s))
    return out


def split_args(text):
    """Top-level comma split, so nested calls and generics don't confuse us."""
    args, depth, cur, instr = [], 0, "", False
    for ch in text:
        if ch == '"':
            instr = not instr
        if not instr:
            if ch in '([<':
                depth += 1
            elif ch in ')]>':
                depth -= 1
            elif ch == ',' and depth == 0:
                args.append(cur)
                cur = ""
                continue
        cur += ch
    if cur.strip():
        args.append(cur)
    return args


def assertions_in(body):
    """Returns (total, number carrying an explanatory message)."""
    total = explained = 0
    for m in ASSERT.finditer(body):
        start = m.end()
        depth, i = 1, start
        while i < len(body) and depth:
            if body[i] == '(':
                depth += 1
            elif body[i] == ')':
                depth -= 1
            i += 1
        inner = body[start:i - 1]
        total += 1
        args = split_args(inner)
        if args and args[-1].strip().startswith('"'):
            explained += 1
    return total, explained


def is_vague(name):
    n = name.strip('`')
    n = re.sub(r'^test', '', n)
    words = re.findall(r'[a-z]+|[A-Z][a-z]*', n)
    return len(words) < 3


def extract_tests(path):
    raw = path.read_text(errors='ignore').splitlines()
    src = strip_comments(raw)
    swift = path.suffix == '.swift'
    tests, has_setup, i = [], False, 0
    while i < len(src):
        if SETUP.search(src[i]):
            has_setup = True
        m = FUNC.match(src[i])
        if not m:
            i += 1
            continue
        name = m.group(1)
        annotated = any(KOTLIN_TEST.search(src[j]) for j in range(max(0, i - 4), i))
        is_test = annotated or (swift and name.startswith("test"))
        j, depth, opened, body = i, 0, False, []
        while j < len(src):
            body.append(src[j])
            for ch in src[j]:
                if ch == '{':
                    depth += 1
                    opened = True
                elif ch == '}':
                    depth -= 1
            if opened and depth == 0:
                break
            j += 1
        if is_test:
            text = '\n'.join(body)
            lines = [b.strip() for b in body[1:-1] if b.strip()]
            total, explained = assertions_in(text)
            tests.append({
                "name": name,
                "loc": len(lines),
                "lines": lines,
                "assertions": total,
                "explained": explained,
                "branches": len(BRANCH.findall(text)),
                "complexity": 1 + len(DECISION.findall(text)),
                "sleeps": len(SLEEP.findall(text)),
                "catches": len(TRYCATCH.findall(text)),
                "prints": len(PRINTING.findall(text)),
            })
        i = j + 1
    return tests, has_setup


# ------------------------------------------------------------------ scoring

def score_test(t):
    """Returns (penalty, {smell: membership})."""
    unexplained = t["assertions"] - t["explained"]
    hits = {
        "empty":       1.0 if t["assertions"] == 0 else 0.0,
        "roulette":    ramp(unexplained, 3, 8),
        "conditional": ramp(t["branches"], 0, 3),
        "long":        ramp(t["loc"], 20, 45),
        "complex":     ramp(t["complexity"], 2, 6),
        "sleepy":      ramp(t["sleeps"], 0, 1),
        "exception":   ramp(t["catches"], 0, 2),
        "print":       ramp(t["prints"], 0, 2),
        "vague_name":  1.0 if is_vague(t["name"]) else 0.0,
    }
    penalty = sum(SMELLS[k][0] * v for k, v in hits.items())
    return penalty, {k: v for k, v in hits.items() if v > 0}


def duplicate_setup(tests, has_setup):
    """How much identical opening code is repeated instead of shared.

    Requires a shared prefix of at least two lines. A single repeated factory
    call -- `val s = state()` at the top of every test -- is deliberate
    isolation, not duplication, and flagging it punishes the better pattern.
    """
    if has_setup or len(tests) < 3:
        return 0.0, 0
    prefixes = {}
    for t in tests:
        if len(t["lines"]) >= 2:
            prefixes.setdefault(tuple(t["lines"][:2]), []).append(t["name"])
    worst = max((len(v) for v in prefixes.values()), default=0)
    if worst < 3:
        return 0.0, worst
    return ramp(worst, 2, len(tests)), worst


def analyse(path):
    tests, has_setup = extract_tests(path)
    if not tests:
        return None
    scored = [(t, *score_test(t)) for t in tests]
    dup_mem, dup_n = duplicate_setup(tests, has_setup)
    mean = sum(s[1] for s in scored) / len(scored)
    scrap = mean + SMELLS["duplicate"][0] * dup_mem
    return {
        "path": path, "tests": scored, "scrap": scrap, "has_setup": has_setup,
        "dup": (dup_mem, dup_n),
        "assertions": sum(t["assertions"] for t in tests),
        "loc": sum(t["loc"] for t in tests),
    }


# -------------------------------------------------------------------- report

def band(s):
    return "healthy" if s < 10 else ("look" if s < 25 else "POOR")


def main(argv):
    detail = "--detail" in argv
    args = [a for a in argv if not a.startswith("--")]
    globs = args or ["app/src/*Test/**/*.kt", "storage/src/*Test/**/*.kt",
                     "iosApp/iosAppTests/*.swift"]
    files = []
    for g in globs:
        p = Path(g)
        files.extend([p] if p.is_file() else sorted(Path('.').glob(g)))

    results = [r for r in (analyse(f) for f in sorted(set(files))) if r]
    if not results:
        print("no test files found")
        return 1
    results.sort(key=lambda r: -r["scrap"])

    print(f"{'SCRAP':>6} {'band':<8} {'tests':>5} {'asserts':>7} {'a/test':>6}  file")
    print("-" * 92)
    for r in results:
        n = len(r["tests"])
        print(f"{r['scrap']:>6.1f} {band(r['scrap']):<8} {n:>5} {r['assertions']:>7} "
              f"{r['assertions']/n:>6.1f}  {r['path']}")

    total_tests = sum(len(r["tests"]) for r in results)
    total_asserts = sum(r["assertions"] for r in results)
    overall = sum(r["scrap"] * len(r["tests"]) for r in results) / total_tests
    print("-" * 92)
    print(f"{len(results)} files | {total_tests} tests | {total_asserts} assertions | "
          f"{total_asserts/total_tests:.1f} per test | weighted SCRAP {overall:.1f} ({band(overall)})")

    findings = {}
    for r in results:
        for t, pen, hits in r["tests"]:
            for k in hits:
                findings.setdefault(k, []).append((r["path"].name, t["name"], hits[k]))
        if r["dup"][0] > 0:
            findings.setdefault("duplicate", []).append((r["path"].name, f"{r['dup'][1]} tests share a first line", r["dup"][0]))

    if findings:
        print("\nSmells found")
        print("-" * 92)
        for k in sorted(findings, key=lambda k: -SMELLS[k][0]):
            items = sorted(findings[k], key=lambda x: -x[2])
            print(f"\n  {k}  ({SMELLS[k][1]})")
            for f, name, mem in items[:6]:
                print(f"      {mem:4.0%}  {name.strip('`')[:56]:<58} {f}")
            if len(items) > 6:
                print(f"      … and {len(items)-6} more")

    if detail:
        print("\nPer-test detail")
        print("-" * 92)
        for r in results:
            print(f"\n{r['path']}")
            for t, pen, hits in sorted(r["tests"], key=lambda x: -x[1]):
                tag = " ".join(sorted(hits)) or "clean"
                print(f"  {pen:5.1f}  loc {t['loc']:>3}  cx {t['complexity']:>2}  "
                      f"asserts {t['assertions']:>2} ({t['explained']} explained)  "
                      f"{t['name'].strip('`')[:44]:<46} {tag}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
