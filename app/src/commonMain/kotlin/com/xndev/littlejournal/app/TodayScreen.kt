package com.xndev.littlejournal.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    var draft by remember { mutableStateOf("") }

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
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth().height(180.dp),
                placeholder = { Text("What happened today?") },
            )
        }

        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        state.save(draft, today, existingId = null, mood = null, tags = emptyList())
                        draft = ""
                    },
                    enabled = draft.isNotBlank(),
                ) { Text("Save") }
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
