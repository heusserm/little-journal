package com.xndev.littlejournal.storage

import com.xndev.littlejournal.db.JournalDatabase
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JournalRepositoryTest {

    private fun repo(): JournalRepository {
        val driver = inMemoryDriver()
        return JournalRepository(JournalDatabase(driver), deviceId = "test-device")
    }

    private val jan10 = LocalDate.parse("2026-01-10")
    private val jan11 = LocalDate.parse("2026-01-11")

    @Test
    fun `a day holds many entries, ordered by when they were written`() {
        val r = repo()
        r.create(id = "a", body = "morning", date = jan10)
        r.create(id = "b", body = "evening", date = jan10)
        r.create(id = "c", body = "next day", date = jan11)

        val day = r.forDate(jan10)
        assertEquals(2, day.size)
        assertContentEquals(listOf("morning", "evening"), day.map { it.body })
        assertEquals(1, r.forDate(jan11).size)
    }

    @Test
    fun `an entry can be moved to another day without changing when it was written`() {
        val r = repo()
        val original = r.create(id = "a", body = "this actually happened Tuesday", date = jan11)

        val moved = r.reassign("a", jan10)

        assertNotNull(moved)
        assertEquals(jan10, moved.entryDate)
        assertEquals(original.createdAt, moved.createdAt, "createdAt must not move with entryDate")
        assertEquals(1, r.forDate(jan10).size)
        assertEquals(0, r.forDate(jan11).size)
    }

    @Test
    fun `deleting leaves a tombstone rather than removing the row`() {
        val r = repo()
        r.create(id = "a", body = "regrettable", date = jan10)

        r.delete("a")

        assertNull(r.byId("a"), "a deleted entry is invisible to normal reads")
        assertEquals(0, r.liveCount())

        // ...but sync can still see it, which is what stops it resurrecting.
        val changes = r.changedSince(kotlinx.datetime.Instant.fromEpochMilliseconds(0))
        assertEquals(1, changes.size)
        assertTrue(changes.single().isDeleted)
    }

    @Test
    fun `calendar counts group by day and ignore deleted entries`() {
        val r = repo()
        r.create(id = "a", body = "one", date = jan10)
        r.create(id = "b", body = "two", date = jan10)
        r.create(id = "c", body = "three", date = jan11)
        r.delete("c")

        val counts = r.countsInRange(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-31"))

        assertEquals(mapOf(jan10 to 2), counts)
    }

    @Test
    fun `on this day finds the same date in earlier years only`() {
        val r = repo()
        r.create(id = "old", body = "2024", date = LocalDate.parse("2024-01-10"))
        r.create(id = "older", body = "2023", date = LocalDate.parse("2023-01-10"))
        r.create(id = "today", body = "2026", date = jan10)
        r.create(id = "other", body = "wrong day", date = LocalDate.parse("2024-03-10"))

        val memories = r.onThisDay(jan10)

        assertContentEquals(listOf("2024", "2023"), memories.map { it.body })
    }

    @Test
    fun `tags round-trip and are queryable`() {
        val r = repo()
        r.create(id = "a", body = "ran 5k", date = jan10, tags = listOf("health", "running"))
        r.create(id = "b", body = "shipped it", date = jan11, tags = listOf("work"))

        assertEquals(listOf("health", "running"), r.byId("a")?.tags)
        assertEquals(listOf("a"), r.withTag("health").map { it.id })
        assertEquals(mapOf("health" to 1, "running" to 1, "work" to 1), r.allTags())
    }

    @Test
    fun `editing an entry replaces its tags rather than accumulating them`() {
        val r = repo()
        val e = r.create(id = "a", body = "x", date = jan10, tags = listOf("old"))

        r.save(e.copy(tags = listOf("new")))

        assertEquals(listOf("new"), r.byId("a")?.tags)
    }

    @Test
    fun `search matches body text and skips deleted entries`() {
        val r = repo()
        r.create(id = "a", body = "the rain in spain", date = jan10)
        r.create(id = "b", body = "nothing relevant", date = jan10)
        r.create(id = "c", body = "more rain", date = jan11)
        r.delete("c")

        assertEquals(listOf("a"), r.search("rain").map { it.id })
    }

    /** Guards the precision trap: the clock has nanoseconds, the schema has millis. */
    @Test
    fun `what save returns is exactly what a later read returns`() {
        val r = repo()
        val saved = r.create(
            id = "a", body = "round trip", date = jan10,
            mood = 3, promptId = "p1", tags = listOf("x", "y"),
        )

        assertEquals(saved, r.byId("a"))
    }

    @Test
    fun `mood and source survive a round trip`() {
        val r = repo()
        r.create(id = "a", body = "spoken", date = jan10, mood = 4, source = EntrySource.DICTATED)

        val loaded = r.byId("a")

        assertEquals(4, loaded?.mood)
        assertEquals(EntrySource.DICTATED, loaded?.source)
        assertEquals(1, loaded?.wordCount)
    }
}
