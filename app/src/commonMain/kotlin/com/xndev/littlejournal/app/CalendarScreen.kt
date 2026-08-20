package com.xndev.littlejournal.app

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/**
 * Month grid. Days with entries are tinted; the dot count shows how many,
 * capped at three so a heavy day does not turn into a smear.
 */
@Composable
fun CalendarScreen(state: JournalState) {
    val month = state.visibleMonth
    val today = todayLocal()

    // Monday-first grid. DayOfWeek.MONDAY.ordinal == 0.
    val leadingBlanks = month.dayOfWeek.ordinal
    val daysInMonth = month.plus(1, DateTimeUnit.MONTH).plus(-1, DateTimeUnit.DAY).dayOfMonth
    val cells: List<LocalDate?> =
        List(leadingBlanks) { null } +
            (1..daysInMonth).map { LocalDate(month.year, month.monthNumber, it) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { state.previousMonth() }) { Text("‹") }
            TextButton(onClick = { state.jumpToCurrentMonth() }) {
                Text(month.monthLabel(), style = MaterialTheme.typography.titleMedium)
            }
            TextButton(onClick = { state.nextMonth() }) { Text("›") }
        }

        Row(Modifier.fillMaxWidth()) {
            weekdayHeaders.forEach { label ->
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    Box(Modifier.weight(1f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                        if (date != null) {
                            DayCell(
                                date = date,
                                count = state.monthCounts[date] ?: 0,
                                isToday = date == today,
                                onClick = { state.openDay(date) },
                            )
                        }
                    }
                }
                // Pad the final short week so cells keep their width.
                repeat(7 - week.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun DayCell(date: LocalDate, count: Int, isToday: Boolean, onClick: () -> Unit) {
    val hasEntries = count > 0
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(3.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (hasEntries) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surface
            )
            .clickable(onClick = onClick)
            .padding(top = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            date.dayOfMonth.toString(),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
            color = if (isToday) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(3.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            repeat(minOf(count, 3)) {
                Box(
                    Modifier.size(4.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}
