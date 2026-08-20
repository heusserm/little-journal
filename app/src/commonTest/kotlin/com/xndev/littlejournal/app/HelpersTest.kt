package com.xndev.littlejournal.app

import com.xndev.littlejournal.storage.EntrySource
import com.xndev.littlejournal.storage.JournalEntry
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class HelpersTest {

    @Test
    fun `tags are trimmed, lowercased, de-hashed and de-duplicated`() {
        assertEquals(
            listOf("work", "health"),
            parseTags("  Work , #health,  work "),
        )
    }

    @Test
    fun `an empty tag string yields no tags`() {
        assertEquals(emptyList(), parseTags("  , ,, "))
    }

    @Test
    fun `entry counts are pluralised`() {
        assertEquals("Today, 1 entry", countLabel(listOf(entry()), "Today"))
        assertEquals("Today, 2 entries", countLabel(listOf(entry(), entry()), "Today"))
    }

    @Test
    fun `dates render as a readable label`() {
        assertEquals("Wed, 19 Aug 2026", LocalDate.parse("2026-08-19").longLabel())
        assertEquals("August 2026", LocalDate.parse("2026-08-01").monthLabel())
    }

    private var n = 0
    private fun entry() = JournalEntry(
        id = "id${n++}",
        entryDate = LocalDate.parse("2026-08-19"),
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
        deviceId = "test",
        body = "body",
        source = EntrySource.TYPED,
    )
}
