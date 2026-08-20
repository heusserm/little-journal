package com.xndev.littlejournal.app

import com.xndev.littlejournal.storage.EntrySource
import com.xndev.littlejournal.storage.inMemoryRepository
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JournalStateTest {

    private fun state(available: Boolean = true): Pair<JournalState, FakeTranscriber> {
        val fake = FakeTranscriber(isAvailable = available)
        return JournalState(inMemoryRepository(), fake) to fake
    }

    // MARK: dictation

    @Test
    fun `dictation is offered only when the platform supports it`() {
        assertTrue(state(available = true).first.canDictate)
        assertFalse(state(available = false).first.canDictate)
    }

    @Test
    fun `starting dictation asks the transcriber and marks itself active`() {
        val (s, fake) = state()

        s.toggleDictation()

        assertEquals(1, fake.startCount)
        assertTrue(s.isDictating)
    }

    @Test
    fun `toggling twice stops the transcriber`() {
        val (s, fake) = state()

        s.toggleDictation()
        s.toggleDictation()

        assertEquals(1, fake.stopCount)
        assertFalse(s.isDictating)
    }

    @Test
    fun `dictation is refused when the platform cannot do it`() {
        val (s, fake) = state(available = false)

        s.toggleDictation()

        assertEquals(0, fake.startCount)
        assertFalse(s.isDictating)
        assertTrue(s.dictationStatus.isNotEmpty(), "the user should be told why")
    }

    @Test
    fun `final results accumulate into the draft, separated by spaces`() {
        val (s, fake) = state()
        s.toggleDictation()

        fake.emitFinal("First sentence.")
        fake.emitFinal("Second sentence.")

        assertEquals("First sentence. Second sentence.", s.draft)
    }

    @Test
    fun `partial text is shown but never becomes part of the entry`() {
        val (s, fake) = state()
        s.toggleDictation()
        fake.emitFinal("Settled text.")

        fake.emitPartial("still being revi")

        assertEquals("still being revi", s.partial)
        assertEquals("Settled text.", s.draft, "the draft must not include volatile text")
    }

    @Test
    fun `a final result clears the outstanding partial`() {
        val (s, fake) = state()
        s.toggleDictation()
        fake.emitPartial("half a sen")

        fake.emitFinal("half a sentence.")

        assertEquals("", s.partial)
    }

    @Test
    fun `an error stops dictation and surfaces the message`() {
        val (s, fake) = state()
        s.toggleDictation()

        fake.emitError("Microphone denied")

        assertFalse(s.isDictating)
        assertEquals("Microphone denied", s.dictationStatus)
    }

    // MARK: committing

    @Test
    fun `a spoken draft gets its times cleaned up and is marked dictated`() {
        val (s, fake) = state()
        s.toggleDictation()
        fake.emitFinal("The release went out at 1145.")

        s.commitDraft()

        val saved = s.dayEntries.single()
        assertEquals("The release went out at 11:45.", saved.body)
        assertEquals(EntrySource.DICTATED, saved.source)
    }

    @Test
    fun `a typed draft is stored verbatim and marked typed`() {
        val (s, _) = state()

        s.updateDraft("The release went out at 1145.")
        s.commitDraft()

        val saved = s.dayEntries.single()
        assertEquals(
            "The release went out at 1145.", saved.body,
            "typing must never be second-guessed by the speech cleanup",
        )
        assertEquals(EntrySource.TYPED, saved.source)
    }

    @Test
    fun `committing clears the draft and stops dictation`() {
        val (s, fake) = state()
        s.toggleDictation()
        fake.emitFinal("Something.")

        s.commitDraft()

        assertEquals("", s.draft)
        assertFalse(s.isDictating)
        assertEquals(1, fake.stopCount)
    }

    @Test
    fun `an empty draft is not saved`() {
        val (s, _) = state()

        s.updateDraft("   ")
        s.commitDraft()

        assertTrue(s.dayEntries.isEmpty())
    }

    @Test
    fun `the next dictated entry does not inherit the previous one's speech flag`() {
        val (s, fake) = state()
        s.toggleDictation()
        fake.emitFinal("Spoken one.")
        s.commitDraft()

        s.updateDraft("Typed at 1145.")
        s.commitDraft()

        val typed = s.dayEntries.first { it.body.startsWith("Typed") }
        assertEquals(EntrySource.TYPED, typed.source)
        assertEquals("Typed at 1145.", typed.body)
    }

    // MARK: navigation

    @Test
    fun `back from an entry returns to the day it belongs to`() {
        val (s, _) = state()
        val day = LocalDate.parse("2026-03-04")
        s.newEntry(day)

        s.back()

        assertEquals(Screen.Day(day), s.screen)
    }

    @Test
    fun `back from a day returns to the calendar`() {
        val (s, _) = state()
        s.openDay(LocalDate.parse("2026-03-04"))

        s.back()

        assertEquals(Screen.Calendar, s.screen)
    }

    // MARK: search

    @Test
    fun `search finds saved entries and a blank query clears the results`() {
        val (s, _) = state()
        s.updateDraft("the rain in spain")
        s.commitDraft()

        s.runSearch("rain")
        assertEquals(1, s.searchResults.size)

        s.runSearch("")
        assertTrue(s.searchResults.isEmpty())
    }

    @Test
    fun `saving refreshes the calendar counts`() {
        val (s, _) = state()
        val today = todayLocal()

        s.updateDraft("something worth counting")
        s.commitDraft()

        assertEquals(1, s.monthCounts[today])
    }
}
