package com.xndev.littlejournal.app

import com.xndev.littlejournal.storage.inMemoryRepository
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JournalStatePagingTest {

    private fun state() = JournalState(inMemoryRepository(), FakeTranscriber())

    @Test
    fun `the calendar opens on the current month`() {
        val t = todayLocal()
        assertEquals(LocalDate(t.year, t.monthNumber, 1), state().visibleMonth)
    }

    @Test
    fun `paging back and forward returns to where it started`() {
        val s = state()
        val start = s.visibleMonth

        s.previousMonth()
        assertEquals(1, monthsBetween(s.visibleMonth, start))

        s.nextMonth()
        assertEquals(start, s.visibleMonth)
    }

    @Test
    fun `paging across a year boundary works`() {
        val s = state()
        val start = s.visibleMonth

        repeat(13) { s.previousMonth() }

        assertEquals(13, monthsBetween(s.visibleMonth, start))
        assertEquals(start.year - 1, s.visibleMonth.year, "13 months back lands in the previous year")
    }

    @Test
    fun `jumping to the current month undoes any amount of paging`() {
        val s = state()
        val start = s.visibleMonth
        repeat(7) { s.nextMonth() }

        s.jumpToCurrentMonth()

        assertEquals(start, s.visibleMonth)
    }

    @Test
    fun `month counts only describe the month being shown`() {
        val s = state()
        val today = todayLocal()
        s.updateDraft("an entry for today")
        s.commitDraft()

        assertEquals(1, s.monthCounts[today])

        s.nextMonth()
        assertTrue(s.monthCounts.isEmpty(), "next month has nothing in it")

        s.jumpToCurrentMonth()
        assertEquals(1, s.monthCounts[today])
    }

    // MARK: editing

    @Test
    fun `editing an entry loads it and opens the editor on its date`() {
        val s = state()
        s.updateDraft("something")
        s.commitDraft()
        val entry = s.dayEntries.single()

        s.editEntry(entry)

        assertEquals(entry, s.editing)
        assertEquals(Screen.Edit(entry.id, entry.entryDate), s.screen)
    }

    @Test
    fun `saving an existing entry updates it rather than creating a second`() {
        val s = state()
        s.updateDraft("first wording")
        s.commitDraft()
        val entry = s.dayEntries.single()

        s.save("second wording", entry.entryDate, entry.id, mood = 3, tags = listOf("x"))

        assertEquals(1, s.dayEntries.size)
        val updated = s.dayEntries.single()
        assertEquals("second wording", updated.body)
        assertEquals(3, updated.mood)
        assertEquals(listOf("x"), updated.tags)
    }

    @Test
    fun `deleting removes the entry from the day and from the counts`() {
        val s = state()
        s.updateDraft("regrettable")
        s.commitDraft()
        val entry = s.dayEntries.single()

        s.delete(entry.id)

        assertTrue(s.dayEntries.isEmpty())
        assertNull(s.monthCounts[todayLocal()])
    }

    @Test
    fun `a blank body is refused even when an entry id is supplied`() {
        val s = state()
        s.updateDraft("real content")
        s.commitDraft()
        val entry = s.dayEntries.single()

        s.save("   ", entry.entryDate, entry.id, null, emptyList())

        assertEquals("real content", s.dayEntries.single().body, "a blank save must not wipe an entry")
    }

    @Test
    fun `search results refresh after an entry is edited`() {
        val s = state()
        s.updateDraft("mentions penguins")
        s.commitDraft()
        val entry = s.dayEntries.single()
        s.runSearch("penguins")
        assertEquals(1, s.searchResults.size)

        s.save("mentions walruses", entry.entryDate, entry.id, null, emptyList())

        assertTrue(s.searchResults.isEmpty(), "stale results would show text that no longer exists")
    }

    @Test
    fun `opening a day loads exactly that day's entries`() {
        val s = state()
        val other = LocalDate.parse("2026-02-02")
        s.save("belongs to february", other, null, null, emptyList())
        s.updateDraft("belongs to today")
        s.commitDraft()

        s.openDay(other)

        assertEquals(listOf("belongs to february"), s.dayEntries.map { it.body })
        assertNotNull(s.screen as? Screen.Day)
    }

    private fun monthsBetween(earlier: LocalDate, later: LocalDate): Int =
        (later.year - earlier.year) * 12 + (later.monthNumber - earlier.monthNumber)
}
