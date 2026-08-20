package com.xndev.littlejournal.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import com.xndev.littlejournal.storage.JournalEntry

/**
 * Quick capture for today, plus what is already written and what happened on
 * this day in previous years.
 *
 * Sections are LazyListScope extensions rather than composables so they stay
 * part of the same scrolling list instead of nesting scrollers.
 */
@Composable
fun TodayScreen(state: JournalState) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        captureSection(state)
        entriesSection(state)
        memoriesSection(state)
        item { Spacer(Modifier.height(24.dp)) }
    }
}

private fun LazyListScope.captureSection(state: JournalState) {
    item {
        Spacer(Modifier.height(16.dp))
        Text(todayLocal().longLabel(), style = MaterialTheme.typography.headlineSmall)
    }
    item {
        OutlinedTextField(
            value = state.draft,
            onValueChange = state::updateDraft,
            modifier = Modifier.fillMaxWidth().height(180.dp),
            placeholder = { Text(if (state.canDictate) "Talk, or type" else "What happened today?") },
        )
    }
    item { LiveTranscript(state) }
    item { CaptureActions(state) }
}

/** The still-being-revised tail and any recognizer status. Never stored. */
@Composable
private fun LiveTranscript(state: JournalState) {
    if (state.partial.isNotEmpty()) {
        Text(
            state.partial,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (state.dictationStatus.isNotEmpty()) {
        Text(
            state.dictationStatus,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun CaptureActions(state: JournalState) {
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

private fun LazyListScope.entriesSection(state: JournalState) {
    if (state.dayEntries.isEmpty()) return
    sectionHeader(countLabel(state.dayEntries, "Today"))
    entryCards(state, state.dayEntries, showDate = false)
}

private fun LazyListScope.memoriesSection(state: JournalState) {
    if (state.memories.isEmpty()) return
    sectionHeader("On this day")
    entryCards(state, state.memories, showDate = true)
}

private fun LazyListScope.sectionHeader(label: String) {
    item {
        HorizontalDivider()
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun LazyListScope.entryCards(
    state: JournalState,
    entries: List<JournalEntry>,
    showDate: Boolean,
) {
    items(entries, key = { it.id }) { entry ->
        EntryCard(entry, showDate = showDate, onClick = { state.editEntry(entry) })
    }
}

private fun countLabel(entries: List<JournalEntry>, prefix: String): String {
    val noun = if (entries.size == 1) "entry" else "entries"
    return "$prefix, ${entries.size} $noun"
}
