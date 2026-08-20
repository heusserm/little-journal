package com.xndev.littlejournal.storage

import com.xndev.littlejournal.db.JournalDatabase
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import com.xndev.littlejournal.db.Entry as DbEntry

/**
 * Everything the app is allowed to do to stored entries.
 *
 * Deletes are soft on purpose: a row that simply vanished would come back the
 * first time a second device synced, so removal is recorded as a tombstone.
 */
class JournalRepository(
    private val db: JournalDatabase,
    private val deviceId: String,
    private val clock: Clock = Clock.System,
) {
    private val entries get() = db.entryQueries
    private val tagQueries get() = db.entryTagQueries

    // MARK: writes

    /**
     * Insert or update. Stamps [JournalEntry.updatedAt] so sync has a watermark.
     *
     * Timestamps are truncated to milliseconds to match what the schema stores.
     * Without this, `save(e) != byId(e.id)` — the returned object would carry
     * nanoseconds the database silently dropped, and any later equality check
     * (sync reconciliation, "is this dirty?") would see spurious differences.
     */
    fun save(entry: JournalEntry): JournalEntry {
        val stamped = entry.copy(
            createdAt = entry.createdAt.truncatedToMillis(),
            updatedAt = clock.now().truncatedToMillis(),
            deletedAt = entry.deletedAt?.truncatedToMillis(),
        )
        db.transaction {
            entries.upsert(
                id = stamped.id,
                entry_date = stamped.entryDate.toString(),
                created_at = stamped.createdAt.toEpochMilliseconds(),
                updated_at = stamped.updatedAt.toEpochMilliseconds(),
                deleted_at = stamped.deletedAt?.toEpochMilliseconds(),
                device_id = stamped.deviceId,
                body = stamped.body,
                mood = stamped.mood?.toLong(),
                prompt_id = stamped.promptId,
                audio_path = stamped.audioPath,
                source = stamped.source.wire,
            )
            tagQueries.clearTags(stamped.id)
            stamped.tags.forEach { tagQueries.addTag(stamped.id, it) }
        }
        return stamped
    }

    /** Creates a new entry for [date], defaulting to today. */
    fun create(
        id: String,
        body: String,
        date: LocalDate,
        mood: Int? = null,
        promptId: String? = null,
        audioPath: String? = null,
        source: EntrySource = EntrySource.TYPED,
        tags: List<String> = emptyList(),
    ): JournalEntry {
        val now = clock.now()
        return save(
            JournalEntry(
                id = id,
                entryDate = date,
                createdAt = now,
                updatedAt = now,
                deviceId = deviceId,
                body = body,
                mood = mood,
                promptId = promptId,
                audioPath = audioPath,
                source = source,
                tags = tags,
            )
        )
    }

    /** Moves an entry to a different day — "this actually happened on Tuesday". */
    fun reassign(id: String, to: LocalDate): JournalEntry? =
        byId(id)?.let { save(it.copy(entryDate = to)) }

    fun delete(id: String) {
        val now = clock.now().toEpochMilliseconds()
        entries.softDelete(deleted_at = now, updated_at = now, id = id)
    }

    // MARK: reads

    fun byId(id: String): JournalEntry? =
        entries.selectById(id).executeAsOneOrNull()?.hydrate()

    fun forDate(date: LocalDate): List<JournalEntry> =
        entries.selectForDate(date.toString()).executeAsList().map { it.hydrate() }

    fun inRange(start: LocalDate, end: LocalDate): List<JournalEntry> =
        entries.selectInRange(start.toString(), end.toString()).executeAsList().map { it.hydrate() }

    /** Day -> number of entries, for the calendar grid's density dots. */
    fun countsInRange(start: LocalDate, end: LocalDate): Map<LocalDate, Int> =
        entries.countsInRange(start.toString(), end.toString())
            .executeAsList()
            .associate { LocalDate.parse(it.entry_date) to it.entry_count.toInt() }

    /** Same month and day, earlier years. */
    fun onThisDay(today: LocalDate): List<JournalEntry> {
        val monthDay = today.toString().substring(5) // 'MM-DD'
        return entries.onThisDay(monthDay, today.toString()).executeAsList().map { it.hydrate() }
    }

    fun search(query: String, limit: Int = 100): List<JournalEntry> =
        entries.search(query, limit.toLong()).executeAsList().map { it.hydrate() }

    fun withTag(tag: String): List<JournalEntry> =
        tagQueries.entriesWithTag(tag).executeAsList().map { it.hydrate() }

    fun allTags(): Map<String, Int> =
        tagQueries.allTags().executeAsList().associate { it.tag to it.usage_count.toInt() }

    fun liveCount(): Int = entries.countLive().executeAsOne().toInt()

    /** Everything touched since [watermark], tombstones included. For sync, later. */
    fun changedSince(watermark: Instant): List<JournalEntry> =
        entries.changedSince(watermark.toEpochMilliseconds()).executeAsList().map { it.hydrate() }

    // MARK: mapping

    /** The schema stores epoch millis; the clock offers nanoseconds. Meet in the middle. */
    private fun Instant.truncatedToMillis(): Instant =
        Instant.fromEpochMilliseconds(toEpochMilliseconds())

    private fun DbEntry.hydrate(): JournalEntry = JournalEntry(
        id = id,
        entryDate = LocalDate.parse(entry_date),
        createdAt = Instant.fromEpochMilliseconds(created_at),
        updatedAt = Instant.fromEpochMilliseconds(updated_at),
        deletedAt = deleted_at?.let { Instant.fromEpochMilliseconds(it) },
        deviceId = device_id,
        body = body,
        mood = mood?.toInt(),
        promptId = prompt_id,
        audioPath = audio_path,
        source = EntrySource.from(source),
        tags = tagQueries.tagsFor(id).executeAsList(),
    )
}
