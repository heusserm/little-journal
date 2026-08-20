package com.xndev.littlejournal.storage

/**
 * The one place text is broken into search terms.
 *
 * Both sides of search go through here — the index built on write and the
 * query parsed on read — because a tokeniser used on only one side of an
 * index is a tokeniser that will eventually disagree with itself. Change the
 * rules and both sides change together.
 *
 * The rules: lowercase, and split on anything that is not a letter or a
 * digit. So "Don't — at 11:45!" yields don, t, at, 11, 45. Punctuation never
 * survives, which is why the query side needs no escaping and no FTS-syntax
 * guard: there is no syntax left to inject.
 *
 * [Char.isLetterOrDigit] rather than a `\p{L}` regex because it is defined
 * identically across Kotlin/JVM and Kotlin/Native; Unicode class support in
 * the common `Regex` is not something to bet an index on.
 *
 * Two known limits, both acceptable for a journal and neither silent:
 *   * Scripts written without spaces (Chinese, Japanese) collapse into one
 *     long token per run, so only whole-run matches work.
 *   * Letters outside the Basic Multilingual Plane arrive as surrogate
 *     pairs, which are not letters individually and so are dropped. They are
 *     dropped identically from the index and from the query, so search stays
 *     self-consistent; it simply cannot find them.
 */
internal fun searchTokens(text: String): List<String> =
    text.map { if (it.isLetterOrDigit()) it.lowercaseChar() else ' ' }
        .joinToString(separator = "")
        .split(' ')
        .filter { it.isNotEmpty() }

/**
 * The sentinel closing a prefix range: every token beginning with a given
 * prefix sorts at or below `prefix + PREFIX_SENTINEL`. See `idsWithPrefix` in
 * EntryWord.sq for why this is safe rather than merely usually-safe.
 */
internal const val PREFIX_SENTINEL: Char = '￿'
