package com.xndev.littlejournal.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

@Composable
fun App(repo: JournalRepository, transcriber: Transcriber = NoopTranscriber) {
    val state = remember { JournalState(repo, transcriber) }

    MaterialTheme(colorScheme = JournalColors) {
        Surface(modifier = Modifier.fillMaxSize()) {
            val showBottomBar = state.screen is Screen.Today ||
                state.screen is Screen.Calendar ||
                state.screen is Screen.Search

            Scaffold(
                bottomBar = {
                    if (showBottomBar) {
                        NavigationBar {
                            NavigationBarItem(
                                selected = state.screen is Screen.Today,
                                onClick = { state.openToday() },
                                icon = { Text("✎") },
                                label = { Text("Today") },
                            )
                            NavigationBarItem(
                                selected = state.screen is Screen.Calendar,
                                onClick = { state.openCalendar() },
                                icon = { Text("▦") },
                                label = { Text("Calendar") },
                            )
                            NavigationBarItem(
                                selected = state.screen is Screen.Search,
                                onClick = { state.openSearch() },
                                icon = { Text("⌕") },
                                label = { Text("Search") },
                            )
                        }
                    }
                }
            ) { insets ->
                Box(Modifier.fillMaxSize().padding(insets)) {
                    when (val screen = state.screen) {
                        is Screen.Today -> TodayScreen(state)
                        is Screen.Calendar -> CalendarScreen(state)
                        is Screen.Search -> SearchScreen(state)
                        is Screen.Day -> DayScreen(state, screen.date)
                        is Screen.Edit -> EditorScreen(state, screen.date, state.editing)
                    }
                }
            }
        }
    }
}
