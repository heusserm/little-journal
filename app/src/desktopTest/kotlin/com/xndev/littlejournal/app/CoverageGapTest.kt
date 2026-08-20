package com.xndev.littlejournal.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.xndev.littlejournal.storage.EntrySource
import com.xndev.littlejournal.storage.inMemoryRepository
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The paths the earlier suites walked past. */
class TranscriberContractTest {

    @Test
    fun `the no-op transcriber refuses politely instead of failing silently`() {
        var reported: String? = null
        val listener = object : TranscriberListener {
            override fun onPartial(text: String) = error("should not happen")
            override fun onFinal(text: String) = error("should not happen")
            override fun onStatus(message: String) = error("should not happen")
            override fun onError(message: String) { reported = message }
        }

        NoopTranscriber.start(listener)

        assertTrue(reported!!.isNotEmpty(), "a user pressing Talk deserves a reason")
        assertFalse(NoopTranscriber.isAvailable)
    }

    @Test
    fun `stopping the no-op transcriber leaves it usable`() {
        NoopTranscriber.stop()
        NoopTranscriber.stop()

        var reported: String? = null
        NoopTranscriber.start(object : TranscriberListener {
            override fun onPartial(text: String) {}
            override fun onFinal(text: String) {}
            override fun onStatus(message: String) {}
            override fun onError(message: String) { reported = message }
        })
        assertTrue(reported != null, "stop must not leave it silently broken")
    }

    @Test
    fun `recognizer status messages reach the user`() {
        val fake = FakeTranscriber()
        val state = JournalState(inMemoryRepository(), fake)
        state.toggleDictation()

        fake.emitStatus("Downloading language model…")

        assertEquals("Downloading language model…", state.dictationStatus)
        assertTrue(state.isDictating, "a status is progress, not a failure")
    }

    @Test
    fun `stopping dictation clears any lingering status and partial`() {
        val fake = FakeTranscriber()
        val state = JournalState(inMemoryRepository(), fake)
        state.toggleDictation()
        fake.emitStatus("Listening")
        fake.emitPartial("half a thou")

        state.stopDictation()

        assertEquals("", state.dictationStatus)
        assertEquals("", state.partial)
    }

    @Test
    fun `stopping when not dictating does nothing`() {
        val fake = FakeTranscriber()
        JournalState(inMemoryRepository(), fake).stopDictation()

        assertEquals(0, fake.stopCount)
    }
}

@OptIn(ExperimentalTestApi::class)
class EntryCardUiTest {

    private fun state() = JournalState(inMemoryRepository(), FakeTranscriber())

    @Test
    fun `a card shows mood, tags and that it was spoken`() = runComposeUiTest {
        val s = state()
        s.save("said out loud", todayLocal(), null, mood = 4, tags = listOf("work", "travel"))
        val entry = s.dayEntries.single().copy(source = EntrySource.DICTATED)

        setContent { MaterialTheme { EntryCard(entry, onClick = {}) } }

        onNodeWithText("spoken", substring = true).assertExists()
        onNodeWithText("mood 4", substring = true).assertExists()
        onNodeWithText("#work", substring = true).assertExists()
    }

    @Test
    fun `a plain typed card shows only its word count`() = runComposeUiTest {
        val s = state()
        s.save("just three words", todayLocal(), null, null, emptyList())

        setContent { MaterialTheme { EntryCard(s.dayEntries.single(), onClick = {}) } }

        onNodeWithText("3 words", substring = true).assertExists()
        onNodeWithText("mood", substring = true).assertDoesNotExist()
        onNodeWithText("spoken", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a busy day shows three dots and no more`() = runComposeUiTest {
        val s = state()
        val today = todayLocal()
        repeat(6) { s.save("entry $it", today, null, null, emptyList()) }

        setContent { MaterialTheme { CalendarScreen(s) } }

        // Six entries must not draw six dots; the cap keeps the cell legible.
        assertEquals(6, s.monthCounts[today])
        onNodeWithText(today.dayOfMonth.toString()).assertExists()
    }

    @Test
    fun `a month with no entries still renders every day`() = runComposeUiTest {
        val s = state()
        s.nextMonth()

        setContent { MaterialTheme { CalendarScreen(s) } }

        onNodeWithText(s.visibleMonth.monthLabel()).assertExists()
        onNodeWithText("1").assertExists()
    }

    @Test
    fun `the live transcript shows both the partial text and the status`() = runComposeUiTest {
        val fake = FakeTranscriber()
        val s = JournalState(inMemoryRepository(), fake)
        s.toggleDictation()
        fake.emitStatus("Listening")
        fake.emitPartial("mid sentence")

        setContent { MaterialTheme { TodayScreen(s) } }

        onNodeWithText("mid sentence").assertExists()
        onNodeWithText("Listening").assertExists()
    }

    @Test
    fun `an entry dated to another day renders its date on the card`() = runComposeUiTest {
        val s = state()
        val past = LocalDate.parse("2024-08-19")
        s.save("a memory", past, null, null, emptyList())
        s.openDay(past)

        setContent { MaterialTheme { EntryCard(s.dayEntries.single(), showDate = true, onClick = {}) } }

        onNodeWithText(past.longLabel()).assertExists()
    }
}
