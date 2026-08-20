package com.xndev.littlejournal.app

import androidx.compose.runtime.Composable

/**
 * Renders whatever screen the state currently points at, without the tab bar.
 * Lets a test click a control on one screen and assert on the next, which is
 * where navigation bugs actually live.
 */
@Composable
fun CurrentScreenForTest(state: JournalState) {
    when (val screen = state.screen) {
        is Screen.Today -> TodayScreen(state)
        is Screen.Calendar -> CalendarScreen(state)
        is Screen.Search -> SearchScreen(state)
        is Screen.Day -> DayScreen(state, screen.date)
        is Screen.Edit -> EditorScreen(state, screen.date, state.editing)
    }
}
