"""One parser for Kotlin and Swift declarations, shared by the analyzers.

There is no reader for Kotlin or Swift here, so structure is recovered by
brace matching. That is a smell detector, not a compiler, and it is wrong in
ways that are easy to miss -- which is exactly why it lives in one file.

It used to live in three. `crap.py`, `scrap.py` and `health.py` each carried
their own copy, the copies drifted, and every one of them was wrong somewhere
the others were right:

  * `scrap.py` learned to stop at the next declaration, so a helper stopped
    swallowing the test after it. `crap.py` never got that fix and was still
    losing whole functions -- `idsExactly` ate `idsStartingWith`, `byId` ate
    `forDate`, and both then vanished from the report rather than showing a
    wrong number.
  * `scrap.py`'s version checked for a brace *before* checking for the next
    declaration, so `fun main() = application {` following an
    expression-bodied function was read as that function's body. `health.py`
    inherited it and quietly dropped `main` and `fixDotted`.
  * `crap.py` ended an expression body at the first `= value` it saw, which
    is also what a defaulted parameter looks like. `create` was measured at
    5 lines instead of 27, so its complexity -- and therefore its CRAP score
    -- was computed from a fifth of the function.

Three tools disagreeing about how many functions a project has is the tell
that the counting is the problem, not the code. Fix it here, once.
"""

import re

# Modifiers are matched as a set rather than a fixed order because Kotlin and
# Swift both allow several, and an unrecognised one silently turns a
# declaration into "not a declaration" -- which is how bodies run on.
#
# The extension receiver is captured apart from the name: Kover and xccov both
# record `fun Instant.truncatedToMillis()` under the bare name, so looking it
# up as `Instant` misses, and a missed lookup used to delete the function from
# the report.
FUNC = re.compile(
    r'^\s*(?:@\w+\s+)*'
    r'(?:(?:public|private|internal|protected|fileprivate|override|suspend|inline|'
    r'final|static|open|operator|infix|tailrec|expect|actual|external|class|'
    r'mutating|nonisolated|convenience|required)\s+)*'
    r'\b(?:fun|func)\s+'
    r'(?:<[^>]*>\s*)?'                        # generic parameters
    r'(?:([A-Za-z_][\w.]*(?:<[^>]*>)?)\.)?'   # extension receiver, if any
    r'(`[^`]+`|[A-Za-z_]\w*)'                 # name; tests may be backticked
)

DECISION = re.compile(r'\bif\b|\bfor\b|\bwhile\b|\bcatch\b|&&|\|\||\?:')


def declaration(line):
    """(receiver, name) if `line` declares a function, else None.

    Returned rather than a match object so callers never depend on group
    numbering -- the thing that made these regexes so awkward to keep in step.
    """
    m = FUNC.match(line)
    if not m:
        return None
    return m.group(1), m.group(2).strip('`')


def strip_comments(lines):
    """Blank out comments, preserving line numbering."""
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

    Handles braced bodies and expression bodies alike. An expression body has
    no braces of its own and may wrap across lines:

        private fun freshState(canDictate: Boolean = true) =
            JournalState(inMemoryRepository(), FakeTranscriber(...))

    Naive brace matching runs past that to the next `{` it can find -- which
    belongs to whatever comes next -- and swallows it whole. So scan forward
    for the opening brace, and give up first on anything that proves the body
    already ended.

    Order matters in that scan. The end-of-body checks run *before* the brace
    check, because a following declaration can carry its own opening brace
    (`fun main() = application {`) and would otherwise look like this body's.
    """
    j = i
    while j < len(src):
        if j > i and (FUNC.match(src[j])
                      or src[j].strip().startswith('@')
                      # A `}` reached before any `{` closes the *enclosing*
                      # scope -- the end of an interface, say -- so an
                      # abstract or expression body ended on the line before.
                      or src[j].lstrip().startswith('}')):
            return src[i:j], j - 1
        if '{' in src[j]:
            break
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


def complexity(text):
    """Cyclomatic complexity: one, plus every branch. `when` counts its arms."""
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
    return cx


def functions(path):
    """Every function declared in `path`, in source order."""
    src = strip_comments(path.read_text(errors='ignore').splitlines())
    out, i = [], 0
    while i < len(src):
        decl = declaration(src[i])
        if not decl:
            i += 1
            continue
        receiver, name = decl
        lines, end = body_of(src, i)
        out.append({
            "name": name,
            "shown": f"{receiver}.{name}" if receiver else name,
            "file": str(path),
            "line": i + 1,
            "loc": sum(1 for l in lines if l.strip()),
            "cx": complexity("\n".join(lines)),
        })
        i = end + 1
    return out
