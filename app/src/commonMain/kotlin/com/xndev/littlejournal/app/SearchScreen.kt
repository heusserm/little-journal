package com.xndev.littlejournal.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SearchScreen(state: JournalState) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = state::runSearch,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Search entries") },
            singleLine = true,
        )
        Spacer(Modifier.height(12.dp))

        when {
            state.searchQuery.isBlank() -> Text(
                "Type to search everything you've written.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            state.searchResults.isEmpty() -> Text(
                "No entries match \"${state.searchQuery}\".",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text(
                        "${state.searchResults.size} " +
                            if (state.searchResults.size == 1) "match" else "matches",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(state.searchResults, key = { it.id }) { entry ->
                    EntryCard(entry, showDate = true, onClick = { state.editEntry(entry) })
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}
