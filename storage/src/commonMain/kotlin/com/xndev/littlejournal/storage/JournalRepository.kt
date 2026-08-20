package com.xndev.littlejournal.storage

import com.xndev.littlejournal.db.JournalDatabase
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import com.xndev.littlejournal.db.Entry as DbEntry

private const val ISO_YEAR_PREFIX = 5   // length of "YYYY-"

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
    private val words get() = db.entryWordQueries

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
            reindexBody(stamped.id, stamped.body)
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

    /**
     * A soft delete leaves the entry's words in the index on purpose. Search
     * hydrates through `byIds`, which filters tombstones, so they are already
     * invisible — and leaving them means an undelete would not need a
     * reindex. Removing them would cost a write to buy nothing.
     */
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

    /**
     * Same month and day, earlier years.
     *
     * Both sides of this comparison slice an ISO date, and they must agree.
     * Kotlin's substring is 0-based, SQLite's substr is 1-based, so the same
     * offset appears here as 5 and in Entry.sq's onThisDay as 6. Change one
     * without the other and the query silently matches nothing.
     */
    fun onThisDay(today: LocalDate): List<JournalEntry> {
        val monthDay = today.toString().substring(ISO_YEAR_PREFIX) // 'MM-DD'
        return entries.onThisDay(monthDay, today.toString()).executeAsList().map { it.hydrate() }
    }

    /**
     * Word search over every entry body, newest first.
     *
     * Every term must match — "rain spain" finds entries containing both,
     * which the old substring match could not do at all. Terms match whole
     * words, so "run" no longer finds "grunt".
     *
     * The final term is matched by prefix rather than exactly, because it is
     * the one the user is still typing: three keystrokes into "running", a
     * search that waited for the whole word would show nothing and read as
     * broken. Earlier terms are already finished words and are matched as
     * such.
     *
     * Intersecting in Kotlin rather than in one SQL statement is deliberate:
     * the number of terms is a handful, each lookup is an index hit, and the
     * alternative is a GROUP BY ... HAVING COUNT(DISTINCT) that mixes an
     * exact set with one range and stops being readable.
     *
     * An empty intersection needs no guard of its own: SQLite is explicit
     * that `x IN ()` is legal and always false, where most other engines
     * reject it. Checked on an API 28 emulator as well as on the desktop
     * driver, because a search that found nothing is exactly the path that
     * would crash if this were wrong.
     *
     * The matches are ordered and cut to a page *before* they are handed to
     * `byIds`, which is what keeps the bound id list short enough to bind at
     * all — see liveIdsByDate in Entry.sq.
     */
    fun search(query: String, limit: Int = 100): List<JournalEntry> {
        val terms = searchTokens(query)
        if (terms.isEmpty()) return emptyList()

        val matches = terms
            .mapIndexed { index, term ->
                if (index == terms.lastIndex) idsStartingWith(term) else idsExactly(term)
            }
            .reduce { found, next -> found intersect next }

        val page = entries.liveIdsByDate().executeAsList().filter { it in matches }.take(limit)
        return entries.byIds(page).executeAsList().map { it.hydrate() }
    }

    private fun idsExactly(term: String): Set<String> =
        words.idsWithWord(term).executeAsList().toSet()

    private fun idsStartingWith(term: String): Set<String> =
        words.idsWithPrefix(term, term + PREFIX_SENTINEL).executeAsList().toSet()

    fun withTag(tag: String): List<JournalEntry> =
        tagQueries.entriesWithTag(tag).executeAsList().map { it.hydrate() }

    fun allTags(): Map<String, Int> =
        tagQueries.allTags().executeAsList().associate { it.tag to it.usage_count.toInt() }

    fun liveCount(): Int = entries.countLive().executeAsOne().toInt()

    /** Everything touched since [watermark], tombstones included. For sync, later. */
    fun changedSince(watermark: Instant): List<JournalEntry> =
        entries.changedSince(watermark.toEpochMilliseconds()).executeAsList().map { it.hydrate() }

    // MARK: the word index

    /**
     * Rewrites one entry's slice of the word index. Clear-then-insert rather
     * than a diff: an entry holds tens of distinct words, and the tidy
     * version would need the old set read back first.
     *
     * Callers supply the transaction — this is only ever part of a larger
     * write.
     */
    private fun reindexBody(id: String, body: String) {
        words.clearWords(id)
        searchTokens(body).toSet().forEach { words.indexWord(id, it) }
    }

    /**
     * Builds the word index for entries that predate it, and does nothing
     * otherwise.
     *
     * The 1.sqm migration can only create the table empty, because tokenising
     * is a Kotlin function and not something SQL can express. So the backfill
     * happens here, on the first open after upgrading, and the check that
     * guards it — an empty index over a non-empty journal — also repairs the
     * index if it is ever lost some other way.
     *
     * Called by every platform's repository factory.
     */
    fun ensureIndexed() {
        if (words.countIndexed().executeAsOne() > 0L) return
        val all = entries.allBodies().executeAsList()
        if (all.isEmpty()) return
        db.transaction {
            all.forEach { reindexBody(it.id, it.body) }
        }
    }

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
