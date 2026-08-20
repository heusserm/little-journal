#!/usr/bin/env python3
"""dry.py — find repeated code across Kotlin and Swift sources.

Reports duplicated blocks: how long, how many copies, and where. Blocks are
grown greedily, so a 20-line clone is reported once at its full length rather
than as sixteen overlapping 5-line fragments.

    python3 tools/dry.py                  # whole repo, exact matches
    python3 tools/dry.py --loose          # ignore literals: catches near-copies
    python3 tools/dry.py --min 8          # only longer blocks
    python3 tools/dry.py --tests          # include test sources too
    python3 tools/dry.py --show           # print the duplicated code

Exact mode compares code with whitespace collapsed. Loose mode also replaces
string and numeric literals with placeholders, which finds the common case of
copy-paste-then-tweak-the-values.

Duplication is a smell, not a sin. Three copies of a two-line idiom is usually
fine; one 30-line block pasted twice usually is not. The point is to make the
choice visible.
"""
import argparse
import re
import sys
from collections import defaultdict
from pathlib import Path

SOURCE_GLOBS = ["app/src/**/*.kt", "storage/src/**/*.kt", "iosApp/iosApp/*.swift"]
TEST_GLOBS = ["app/src/*Test/**/*.kt", "storage/src/*Test/**/*.kt",
              "iosApp/iosAppTests/*.swift"]

STRING = re.compile(r'"(?:[^"\\]|\\.)*"')
NUMBER = re.compile(r'\b\d+(?:\.\d+)?\b')
NOISE = re.compile(r'^\s*(import|package|@file:)')


def strip_comments(lines):
    out, blk = [], False
    for ln in lines:
        s = ln
        if blk:
            if '*/' in s:
                s = s.split('*/', 1)[1]
                blk = False
            else:
                out.append("")
                continue
        if '/*' in s:
            b, _, a = s.partition('/*')
            if '*/' in a:
                s = b + a.split('*/', 1)[1]
            else:
                s, blk = b, True
        out.append(re.sub(r'//.*', '', s))
    return out


def normalise(line, loose):
    s = re.sub(r'\s+', ' ', line.strip())
    if loose:
        s = STRING.sub('""', s)
        s = NUMBER.sub('0', s)
    return s


def load(paths, loose):
    """Returns a flat list of (file, lineno, normalised) skipping blanks/imports."""
    rows = []
    for p in paths:
        raw = p.read_text(errors='ignore').splitlines()
        for i, line in enumerate(strip_comments(raw), start=1):
            if not line.strip() or NOISE.match(line):
                continue
            n = normalise(line, loose)
            # Lone braces and closers make everything look duplicated.
            if len(n) <= 2:
                continue
            rows.append((p, i, n))
    return rows


def find_clones(rows, minimum):
    index = defaultdict(list)
    for i in range(len(rows) - minimum + 1):
        # only index runs that stay inside one file
        window = rows[i:i + minimum]
        if len({r[0] for r in window}) != 1:
            continue
        if window[-1][1] - window[0][1] != minimum - 1:
            continue          # non-contiguous (blank/comment lines were dropped)
        key = tuple(r[2] for r in window)
        index[key].append(i)

    clones, claimed = [], set()
    for key, starts in sorted(index.items(), key=lambda kv: -len(kv[1])):
        starts = [s for s in starts if s not in claimed]
        if len(starts) < 2:
            continue
        # grow while every copy still agrees and stays contiguous in its file
        length = minimum
        while True:
            nxt = length
            ok = True
            for s in starts:
                a, b = s + nxt, starts[0] + nxt
                if a >= len(rows) or b >= len(rows):
                    ok = False
                    break
                if rows[a][0] != rows[s][0] or rows[a][1] != rows[a - 1][1] + 1:
                    ok = False
                    break
                if rows[a][2] != rows[b][2]:
                    ok = False
                    break
            if not ok:
                break
            length += 1
        for s in starts:
            claimed.update(range(s, s + length))
        clones.append((length, starts))
    return clones


def main(argv=None):
    ap = argparse.ArgumentParser(description="find repeated code")
    ap.add_argument("paths", nargs="*", help="files or globs (default: repo sources)")
    ap.add_argument("--min", type=int, default=5, help="minimum block length (default 5)")
    ap.add_argument("--loose", action="store_true", help="ignore string and number literals")
    ap.add_argument("--tests", action="store_true", help="include test sources")
    ap.add_argument("--show", action="store_true", help="print the duplicated lines")
    a = ap.parse_args(argv)

    globs = a.paths or (SOURCE_GLOBS + TEST_GLOBS if a.tests else SOURCE_GLOBS)
    files = []
    for g in globs:
        p = Path(g)
        files.extend([p] if p.is_file() else sorted(Path('.').glob(g)))
    files = [f for f in sorted(set(files)) if '/build/' not in str(f) and '/dd' not in str(f)]
    if not a.tests and not a.paths:
        files = [f for f in files if 'Test' not in f.name and '/test' not in str(f).lower()]
    if not files:
        print("no source files found")
        return 1

    rows = load(files, a.loose)
    clones = find_clones(rows, a.min)
    clones.sort(key=lambda c: -(c[0] * (len(c[1]) - 1)))

    dup_lines = sum(length * (len(starts) - 1) for length, starts in clones)
    mode = "loose" if a.loose else "exact"
    print(f"{len(files)} files | {len(rows)} significant lines | {mode} match | "
          f"blocks of {a.min}+ lines")
    print(f"{len(clones)} duplicated blocks | {dup_lines} redundant lines "
          f"({100.0 * dup_lines / max(len(rows), 1):.1f}% of the codebase)")
    if not clones:
        print("\nNothing repeated at this threshold.")
        return 0

    print("-" * 88)
    for length, starts in clones:
        print(f"\n  {length} lines x {len(starts)} copies")
        for s in starts:
            f, first = rows[s][0], rows[s][1]
            last = rows[s + length - 1][1]
            print(f"      {f}:{first}-{last}")
        if a.show:
            print()
            for k in range(length):
                print(f"      | {rows[starts[0] + k][2][:80]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
