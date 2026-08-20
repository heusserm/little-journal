package com.xndev.littlejournal.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Quick capture for today, plus what is already written and what happened on
 * this day in previous years.
 */
@Composable
fun TodayScreen(state: JournalState) {
    val today = todayLocal()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Spacer(Modifier.height(16.dp))
            Text(today.longLabel(), style = MaterialTheme.typography.headlineSmall)
        }

        item {
            OutlinedTextField(
                value = state.draft,
                onValueChange = state::updateDraft,
                modifier = Modifier.fillMaxWidth().height(180.dp),
                placeholder = { Text(if (state.canDictate) "Talk, or type" else "What happened today?") },
            )
        }

        // The still-being-revised tail, shown but never stored.
        if (state.partial.isNotEmpty()) {
            item {
                Text(
                    state.partial,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (state.dictationStatus.isNotEmpty()) {
            item {
                Text(
                    state.dictationStatus,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.canDictate) {
                    FilledTonalButton(onClick = state::toggleDictation) {
                        Text(if (state.isDictating) "◼  Stop" else "●  Talk")
                    }
                }
                Button(onClick = state::commitDraft, enabled = state.draft.isNotBlank()) {
                    Text("Save")
                }
            }
        }

        if (state.dayEntries.isNotEmpty()) {
            item {
                HorizontalDivider()
                Text(
                    "Today, ${state.dayEntries.size} " +
                        if (state.dayEntries.size == 1) "entry" else "entries",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(state.dayEntries, key = { it.id }) { entry ->
                EntryCard(entry, onClick = { state.editEntry(entry) })
            }
        }

        if (state.memories.isNotEmpty()) {
            item {
                HorizontalDivider()
                Text(
                    "On this day",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(state.memories, key = { it.id }) { entry ->
                EntryCard(entry, showDate = true, onClick = { state.editEntry(entry) })
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}
