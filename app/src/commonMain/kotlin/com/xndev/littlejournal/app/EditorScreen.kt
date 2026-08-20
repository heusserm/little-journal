package com.xndev.littlejournal.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xndev.littlejournal.storage.JournalEntry
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/**
 * Edit an entry, or write a new one for [initialDate].
 *
 * The date stepper is the feature the schema was built for: moving an entry to
 * another day rewrites entry_date and leaves created_at alone, so "when I wrote
 * it" stays true while "what day it is about" becomes correct.
 */
@Composable
fun EditorScreen(state: JournalState, initialDate: LocalDate, existing: JournalEntry?) {
    var body by remember(existing) { mutableStateOf(existing?.body ?: "") }
    var date by remember(existing) { mutableStateOf(existing?.entryDate ?: initialDate) }
    var mood by remember(existing) { mutableStateOf(existing?.mood) }
    var tagText by remember(existing) { mutableStateOf(existing?.tags?.joinToString(", ") ?: "") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { state.back() }) { Text("‹ Back") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (existing != null) {
                    TextButton(onClick = {
                        state.delete(existing.id)
                        state.back()
                    }) { Text("Delete") }
                }
                Button(
                    onClick = {
                        state.save(body, date, existing?.id, mood, parseTags(tagText))
                        state.back()
                    },
                    enabled = body.isNotBlank(),
                ) { Text("Save") }
            }
        }

        Text("Entry date", style = MaterialTheme.typography.labelMedium)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(onClick = { date = date.plus(-1, DateTimeUnit.DAY) }) { Text("‹") }
            Text(date.longLabel(), style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = { date = date.plus(1, DateTimeUnit.DAY) }) { Text("›") }
            TextButton(onClick = { date = todayLocal() }) { Text("Today") }
        }
        if (existing != null && date != existing.entryDate) {
            Text(
                "Moving from ${existing.entryDate.longLabel()}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = body,
            onValueChange = { body = it },
            modifier = Modifier.fillMaxWidth().height(260.dp),
            placeholder = { Text("Write here") },
        )

        Spacer(Modifier.height(12.dp))

        Text("Mood", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..5).forEach { value ->
                FilterChip(
                    selected = mood == value,
                    onClick = { mood = if (mood == value) null else value },
                    label = { Text(value.toString()) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = tagText,
            onValueChange = { tagText = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Tags, comma separated") },
            singleLine = true,
        )

        Spacer(Modifier.height(32.dp))
    }
}

internal fun parseTags(raw: String): List<String> =
    raw.split(',')
        .map { it.trim().removePrefix("#").lowercase() }
        .filter { it.isNotEmpty() }
        .distinct()
