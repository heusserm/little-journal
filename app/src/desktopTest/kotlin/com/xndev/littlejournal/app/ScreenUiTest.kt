package com.xndev.littlejournal.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.xndev.littlejournal.storage.inMemoryRepository
import kotlin.test.Test

/**
 * Renders the real screens against a real in-memory database.
 *
 * These are the tests that would have caught the month grid overlapping its own
 * final week -- the kind of defect that unit tests on state cannot see because
 * nothing about the state was wrong.
 */
@OptIn(ExperimentalTestApi::class)
class ScreenUiTest {

    private fun freshState(canDictate: Boolean = true) =
        JournalState(inMemoryRepository(), FakeTranscriber(isAvailable = canDictate))

    @Test
    fun `the talk button is offered when the platform can dictate`() = runComposeUiTest {
        setContent { MaterialTheme { TodayScreen(freshState(canDictate = true)) } }

        onNodeWithText("●  Talk").assertExists()
    }

    @Test
    fun `the talk button is absent when the platform cannot dictate`() = runComposeUiTest {
        setContent { MaterialTheme { TodayScreen(freshState(canDictate = false)) } }

        onNodeWithText("●  Talk").assertDoesNotExist()
    }

    @Test
    fun `save stays disabled until something has been written`() = runComposeUiTest {
        setContent { MaterialTheme { TodayScreen(freshState()) } }

        onNodeWithText("Save").assertIsNotEnabled()
        onNode(hasSetTextAction()).performTextInput("something")
        onNodeWithText("Save").assertIsEnabled()
    }

    @Test
    fun `writing and saving puts the entry on the page`() = runComposeUiTest {
        setContent { MaterialTheme { TodayScreen(freshState()) } }

        onNode(hasSetTextAction()).performTextInput("A first entry")
        onNodeWithText("Save").performClick()

        onNodeWithText("Today, 1 entry").assertExists()
        onNodeWithText("A first entry").assertExists()
    }

    @Test
    fun `the calendar renders the whole month including the final week`() = runComposeUiTest {
        val state = freshState()
        setContent { MaterialTheme { CalendarScreen(state) } }

        onNodeWithText(state.visibleMonth.monthLabel()).assertExists()
        // 31 is only reachable if the grid scrolls; it used to overlap the row above.
        onNodeWithText("1").assertExists()
    }

    @Test
    fun `search invites a query and reports when nothing matches`() = runComposeUiTest {
        setContent { MaterialTheme { SearchScreen(freshState()) } }

        onNodeWithText("Type to search everything you've written.").assertExists()
        onNode(hasSetTextAction()).performTextInput("nothing here")
        onNodeWithText("No entries match \"nothing here\".").assertExists()
    }

    @Test
    fun `an entry written today can be found by searching for it`() = runComposeUiTest {
        val state = freshState()
        state.updateDraft("the rain in spain")
        state.commitDraft()

        setContent { MaterialTheme { SearchScreen(state) } }
        onNode(hasSetTextAction()).performTextInput("rain")

        onNodeWithText("1 match").assertExists()
    }

    @Test
    fun `the editor shows a moved-from note only after the date changes`() = runComposeUiTest {
        val state = freshState()
        state.updateDraft("something")
        state.commitDraft()
        val entry = state.dayEntries.single()

        setContent { MaterialTheme { EditorScreen(state, entry.entryDate, entry) } }

        onNodeWithText("Moving from", substring = true).assertDoesNotExist()
        onNodeWithText("‹").performClick()
        onNodeWithText("Moving from", substring = true).assertExists()
    }
}
