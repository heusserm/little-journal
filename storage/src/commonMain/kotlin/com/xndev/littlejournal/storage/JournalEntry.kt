package com.xndev.littlejournal.storage

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

enum class EntrySource {
    TYPED,
    DICTATED;

    val wire: String get() = name.lowercase()

    companion object {
        fun from(wire: String): EntrySource =
            entries.firstOrNull { it.wire == wire } ?: TYPED
    }
}

/**
 * A single journal entry.
 *
 * [entryDate] is the day the entry is *about*; [createdAt] is when it was
 * actually written. They are usually the same day and occasionally are not,
 * which is the entire point — backfilling Tuesday on Thursday just means
 * writing a different [entryDate].
 */
data class JournalEntry(
    val id: String,
    val entryDate: LocalDate,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null,
    val deviceId: String,
    val body: String,
    val mood: Int? = null,
    val promptId: String? = null,
    val audioPath: String? = null,
    val source: EntrySource = EntrySource.TYPED,
    val tags: List<String> = emptyList(),
) {
    val isDeleted: Boolean get() = deletedAt != null

    val wordCount: Int
        get() = body.split(' ', '\n', '\t').count { it.isNotBlank() }
}
