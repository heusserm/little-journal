package com.xndev.littlejournal.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.xndev.littlejournal.storage.JournalRepository

private val JournalColors = darkColorScheme(
    primary = Color(0xFFB9A6F2),
    onPrimary = Color(0xFF14121A),
    background = Color(0xFF121115),
    onBackground = Color(0xFFEDEAF2),
    surface = Color(0xFF1B1A20),
    onSurface = Color(0xFFEDEAF2),
    surfaceVariant = Color(0xFF26242C),
    onSurfaceVariant = Color(0xFFB6B2BF),
)

/**
 * How wide the content is ever allowed to get.
 *
 * On a phone this does nothing — there is less width than this to begin with.
 * On an iPad there is a great deal more, and without a cap every screen
 * stretches edge to edge: a compose box thirteen inches wide is unpleasant to
 * write in, and the calendar's day cells inflate into empty squares. Prose
 * wants a measure, not a canvas.
 *
 * The tab bar is deliberately left full-width, which is what iPad users expect
 * of it.
 */
private val ReadableWidth = 640.dp

/** The tabbed screens keep the bar; pushed screens hide it. */
private val Screen.isTab: Boolean
    get() = this is Screen.Today || this is Screen.Calendar || this is Screen.Search

@Composable
fun App(repo: JournalRepository, transcriber: Transcriber = NoopTranscriber) {
    val state = remember { JournalState(repo, transcriber) }

    MaterialTheme(colorScheme = JournalColors) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                bottomBar = { if (state.screen.isTab) JournalNavBar(state) },
            ) { insets ->
                Box(
                    modifier = Modifier.fillMaxSize().padding(insets),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Box(Modifier.fillMaxHeight().widthIn(max = ReadableWidth)) {
                        CurrentScreen(state)
                    }
                }
            }
        }
    }
}

@Composable
private fun JournalNavBar(state: JournalState) {
    NavigationBar {
        NavigationBarItem(
            selected = state.screen is Screen.Today,
            onClick = state::openToday,
            icon = { Text("✎") },
            label = { Text("Today") },
        )
        NavigationBarItem(
            selected = state.screen is Screen.Calendar,
            onClick = state::openCalendar,
            icon = { Text("▦") },
            label = { Text("Calendar") },
        )
        NavigationBarItem(
            selected = state.screen is Screen.Search,
            onClick = state::openSearch,
            icon = { Text("⌕") },
            label = { Text("Search") },
        )
    }
}

@Composable
internal fun CurrentScreen(state: JournalState) {
    when (val screen = state.screen) {
        is Screen.Today -> TodayScreen(state)
        is Screen.Calendar -> CalendarScreen(state)
        is Screen.Search -> SearchScreen(state)
        is Screen.Day -> DayScreen(state, screen.date)
        is Screen.Edit -> EditorScreen(state, screen.date, state.editing)
    }
}
