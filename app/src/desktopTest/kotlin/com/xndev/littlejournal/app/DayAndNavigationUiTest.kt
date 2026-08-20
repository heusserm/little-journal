package com.xndev.littlejournal.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.xndev.littlejournal.storage.inMemoryRepository
import kotlinx.datetime.LocalDate
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class DayScreenUiTest {

    private val day = LocalDate.parse("2026-04-07")

    private fun stateOn(day: LocalDate, vararg bodies: String): JournalState {
        val state = JournalState(inMemoryRepository(), FakeTranscriber())
        bodies.forEachIndexed { i, body -> state.save(body, day, null, null, emptyList()) }
        state.openDay(day)
        return state
    }

    @Test
    fun `an empty day says so rather than showing nothing at all`() = runComposeUiTest {
        val state = stateOn(day)
        setContent { MaterialTheme { DayScreen(state, day) } }

        onNodeWithText("Nothing written for this day.").assertExists()
    }

    @Test
    fun `a day lists every entry written for it`() = runComposeUiTest {
        val state = stateOn(day, "the morning one", "the evening one")
        setContent { MaterialTheme { DayScreen(state, day) } }

        onNodeWithText("the morning one").assertExists()
        onNodeWithText("the evening one").assertExists()
    }

    @Test
    fun `the day header names the date being viewed`() = runComposeUiTest {
        val state = stateOn(day)
        setContent { MaterialTheme { DayScreen(state, day) } }

        onNodeWithText(day.longLabel()).assertExists()
    }

    @Test
    fun `New opens an editor already pointed at this day`() = runComposeUiTest {
        val state = stateOn(day)
        setContent { MaterialTheme { CurrentScreenForTest(state) } }

        onNodeWithText("New").performClick()

        onNodeWithText("Entry date").assertExists()
        onNodeWithText(day.longLabel()).assertExists()
    }

    @Test
    fun `back from a day returns to the calendar`() = runComposeUiTest {
        val state = stateOn(day)
        setContent { MaterialTheme { CurrentScreenForTest(state) } }

        onNodeWithText("‹ Calendar").performClick()

        onNodeWithText(state.visibleMonth.monthLabel()).assertExists()
    }
}

@OptIn(ExperimentalTestApi::class)
class AppNavigationUiTest {

    @Test
    fun `the app opens on today`() = runComposeUiTest {
        setContent { App(inMemoryRepository()) }

        onNodeWithText(todayLocal().longLabel()).assertExists()
    }

    @Test
    fun `the calendar tab shows the current month`() = runComposeUiTest {
        setContent { App(inMemoryRepository()) }

        onNodeWithText("Calendar").performClick()

        onNodeWithText(todayLocal().monthLabel()).assertExists()
    }

    @Test
    fun `the search tab invites a query`() = runComposeUiTest {
        setContent { App(inMemoryRepository()) }

        onNodeWithText("Search").performClick()

        onNodeWithText("Type to search everything you've written.").assertExists()
    }

    @Test
    fun `an entry written on today is reachable from the calendar`() = runComposeUiTest {
        setContent { App(inMemoryRepository()) }

        onNodeWithText("Calendar").performClick()
        onNodeWithText("Today").performClick()

        onNodeWithText(todayLocal().longLabel()).assertExists()
    }
}
