package com.xndev.littlejournal.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xndev.littlejournal.storage.EntrySource
import com.xndev.littlejournal.storage.JournalEntry

@Composable
fun EntryCard(
    entry: JournalEntry,
    showDate: Boolean = false,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(14.dp)) {
            if (showDate) {
                Text(
                    entry.entryDate.longLabel(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                entry.body,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            val bits = buildList {
                add("${entry.wordCount} words")
                if (entry.source == EntrySource.DICTATED) add("spoken")
                entry.mood?.let { add("mood $it") }
                addAll(entry.tags.map { "#$it" })
            }
            Text(
                bits.joinToString("  ·  "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
