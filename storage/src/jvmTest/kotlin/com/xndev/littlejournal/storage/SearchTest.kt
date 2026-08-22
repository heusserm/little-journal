package com.xndev.littlejournal.storage

import com.xndev.littlejournal.db.JournalDatabase
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Comfortably past SQLite's 999-parameter ceiling. */
private const val TOO_MANY_TO_BIND = 1_050

/**
 * What the word index buys over the substring match it replaced. Each test
 * here is something `body LIKE '%q%'` got wrong.
 */
class SearchTest {

    private fun repo() = inMemoryRepository()

    private val day = LocalDate.parse("2026-03-01")

    @Test
    fun `terms match whole words, so run does not find grunt`() {
        val r = repo()
        r.create(id = "a", body = "I went for a run", date = day)
        r.create(id = "b", body = "he grunted and left", date = day)

        assertEquals(listOf("a"), r.search("run").map { it.id })
    }

    @Test
    fun `several terms all have to match, in any order`() {
        val r = repo()
        r.create(id = "a", body = "the rain in spain", date = day)
        r.create(id = "b", body = "rain everywhere", date = day)

        assertEquals(listOf("a"), r.search("spain rain").map { it.id })
    }

    @Test
    fun `words need not be adjacent, which the old substring match required`() {
        val r = repo()
        r.create(id = "a", body = "the rain in spain", date = day)

        assertEquals(listOf("a"), r.search("rain spain").map { it.id })
    }

    @Test
    fun `the term still being typed matches by prefix`() {
        val r = repo()
        r.create(id = "a", body = "went running today", date = day)

        assertEquals(listOf("a"), r.search("runn").map { it.id })
    }

    @Test
    fun `a finished term is matched exactly, not by prefix`() {
        val r = repo()
        r.create(id = "a", body = "running late", date = day)

        assertTrue(r.search("run late").isEmpty(), "only the last term is a prefix")
    }

    @Test
    fun `search ignores case on both sides`() {
        val r = repo()
        r.create(id = "a", body = "Dinner with Ada", date = day)

        assertEquals(listOf("a"), r.search("ADA").map { it.id })
    }

    @Test
    fun `a query with no indexable characters finds nothing rather than everything`() {
        val r = repo()
        r.create(id = "a", body = "something", date = day)

        assertTrue(r.search("!!!").isEmpty())
    }

    @Test
    fun `editing an entry drops the words it no longer contains`() {
        val r = repo()
        val e = r.create(id = "a", body = "kayaking", date = day)

        r.save(e.copy(body = "cycling"))

        assertTrue(r.search("kayaking").isEmpty(), "the old word must leave the index")
        assertEquals(listOf("a"), r.search("cycling").map { it.id })
    }

    @Test
    fun `results come back newest first`() {
        val r = repo()
        r.create(id = "old", body = "coffee", date = LocalDate.parse("2026-01-01"))
        r.create(id = "new", body = "coffee", date = LocalDate.parse("2026-02-01"))

        assertEquals(listOf("new", "old"), r.search("coffee").map { it.id })
    }

    @Test
    fun `a word repeated in one entry still yields that entry once`() {
        val r = repo()
        r.create(id = "a", body = "rain rain rain", date = day)

        assertEquals(listOf("a"), r.search("rain").map { it.id })
    }

    /**
     * More matches than SQLite will accept as host parameters.
     *
     * `IN ?` binds one parameter per id and SQLite allows 999 of them before
     * 3.32, so handing a raw match set to `byIds` breaks once a journal holds
     * a thousand entries sharing a word — which is a couple of years of daily
     * writing and the word "the".
     *
     * Be clear about what this test does and does not do: it pins the
     * behavior (ordered, cut to a page), but it cannot reproduce the crash.
     * The desktop driver's SQLite allows 32,766 parameters, so the broken
     * version passed here too. Only Android's 3.19 would have failed, and
     * Android has no test suite in this project at all.
     */
    @Test
    fun `a word in more entries than SQLite can bind still returns a page`() {
        val r = repo()
        val start = LocalDate.parse("2024-01-01")
        repeat(TOO_MANY_TO_BIND) { r.create(id = "e$it", body = "the day", date = start.plus(it, DateTimeUnit.DAY)) }

        val found = r.search("the", limit = 100)

        assertEquals(100, found.size, "a page, not everything and not a crash")
        assertEquals(start.plus(TOO_MANY_TO_BIND - 1, DateTimeUnit.DAY), found.first().entryDate)
    }

    /**
     * Losing the index is what an upgrade looks like from the repository's
     * side, so it is simulated the honest way — by emptying the table behind
     * its back rather than by adding a method that only tests call.
     */
    @Test
    fun `backfilling builds the index for entries that predate it`() {
        val driver = inMemoryDriver()
        val r = JournalRepository(JournalDatabase(driver), deviceId = "d")
        r.create(id = "a", body = "written before the index existed", date = day)
        driver.execute(null, "DELETE FROM entry_word", 0)

        r.ensureIndexed()

        assertEquals(listOf("a"), r.search("index").map { it.id })
    }

    @Test
    fun `backfilling a journal that is already indexed leaves it working`() {
        val r = repo()
        r.create(id = "a", body = "already indexed", date = day)

        r.ensureIndexed()

        assertEquals(listOf("a"), r.search("indexed").map { it.id })
    }

    @Test
    fun `backfilling an empty journal is harmless`() {
        val r = repo()

        r.ensureIndexed()

        assertTrue(r.search("anything").isEmpty())
    }
}
