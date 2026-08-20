package com.xndev.littlejournal.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xndev.littlejournal.storage.EntrySource
import com.xndev.littlejournal.storage.JournalEntry
import com.xndev.littlejournal.storage.JournalRepository
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

fun todayLocal(): LocalDate =
    Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date

sealed interface Screen {
    data object Today : Screen
    data object Calendar : Screen
    data object Search : Screen
    data class Day(val date: LocalDate) : Screen
    /** [entryId] null means "new entry on [date]". */
    data class Edit(val entryId: String?, val date: LocalDate) : Screen
}

/**
 * All app state in one place.
 *
 * Deliberately not a ViewModel or a DI graph -- the app is small enough that a
 * plain object held by `remember` is easier to follow, and it keeps commonMain
 * free of platform lifecycle types.
 *
 * Implements [TranscriberListener] directly, so the platform recognizer talks
 * straight to app state. Those callbacks must arrive on the main thread.
 *
 * Reads are synchronous. At journal scale that is genuinely fine: a month query
 * touches tens of rows against a local SQLite file.
 */
@OptIn(ExperimentalUuidApi::class)
class JournalState(
    private val repo: JournalRepository,
    private val transcriber: Transcriber = NoopTranscriber,
) : TranscriberListener {

    var screen by mutableStateOf<Screen>(Screen.Today)
        private set

    /** Always the first of the month currently shown by the calendar. */
    var visibleMonth by mutableStateOf(todayLocal().let { LocalDate(it.year, it.monthNumber, 1) })
        private set

    var monthCounts by mutableStateOf<Map<LocalDate, Int>>(emptyMap())
        private set

    var dayEntries by mutableStateOf<List<JournalEntry>>(emptyList())
        private set

    var memories by mutableStateOf<List<JournalEntry>>(emptyList())
        private set

    var editing by mutableStateOf<JournalEntry?>(null)
        private set

    // MARK: capture draft

    var draft by mutableStateOf("")
        private set

    /** Text the recognizer is still revising. Shown dimmed, never stored. */
    var partial by mutableStateOf("")
        private set

    var isDictating by mutableStateOf(false)
        private set

    var dictationStatus by mutableStateOf("")
        private set

    private var draftUsedSpeech = false

    val canDictate: Boolean get() = transcriber.isAvailable

    // MARK: search

    var searchQuery by mutableStateOf("")
        private set

    var searchResults by mutableStateOf<List<JournalEntry>>(emptyList())
        private set

    init {
        refreshMonth()
        refreshToday()
    }

    // MARK: navigation

    fun openToday() {
        screen = Screen.Today
        refreshToday()
    }

    fun openCalendar() {
        screen = Screen.Calendar
        refreshMonth()
    }

    fun openSearch() {
        screen = Screen.Search
    }

    fun openDay(date: LocalDate) {
        dayEntries = repo.forDate(date)
        screen = Screen.Day(date)
    }

    fun newEntry(date: LocalDate) {
        editing = null
        screen = Screen.Edit(null, date)
    }

    fun editEntry(entry: JournalEntry) {
        editing = entry
        screen = Screen.Edit(entry.id, entry.entryDate)
    }

    fun back() {
        when (val s = screen) {
            is Screen.Edit -> openDay(s.date)
            is Screen.Day -> openCalendar()
            else -> openToday()
        }
    }

    // MARK: dictation

    fun toggleDictation() {
        if (isDictating) stopDictation() else startDictation()
    }

    private fun startDictation() {
        if (!transcriber.isAvailable) {
            dictationStatus = "Dictation is not available here."
            return
        }
        isDictating = true
        dictationStatus = "Starting"
        transcriber.start(this)
    }

    fun stopDictation() {
        if (!isDictating) return
        transcriber.stop()
        isDictating = false
        partial = ""
        dictationStatus = ""
    }

    override fun onPartial(text: String) {
        partial = text
    }

    override fun onFinal(text: String) {
        val piece = text.trim()
        if (piece.isEmpty()) return
        draft = if (draft.isBlank()) piece else "$draft $piece"
        partial = ""
        draftUsedSpeech = true
    }

    override fun onStatus(message: String) {
        dictationStatus = message
    }

    override fun onError(message: String) {
        dictationStatus = message
        isDictating = false
        partial = ""
    }

    // MARK: draft

    fun updateDraft(text: String) {
        draft = text
    }

    fun clearDraft() {
        draft = ""
        partial = ""
        draftUsedSpeech = false
    }

    /** Saves the capture draft as a new entry for today. */
    fun commitDraft() {
        if (draft.isBlank()) return
        stopDictation()
        // Spoken times arrive mangled; only touch text that actually came from speech.
        val body = if (draftUsedSpeech) SpokenText.tidy(draft) else draft
        repo.create(
            id = Uuid.random().toString(),
            body = body.trim(),
            date = todayLocal(),
            source = if (draftUsedSpeech) EntrySource.DICTATED else EntrySource.TYPED,
        )
        clearDraft()
        refreshMonth()
        refreshToday()
    }

    // MARK: mutations

    fun save(body: String, date: LocalDate, existingId: String?, mood: Int?, tags: List<String>) {
        if (body.isBlank()) return
        val existing = existingId?.let { repo.byId(it) }
        if (existing == null) {
            repo.create(
                id = Uuid.random().toString(),
                body = body.trim(),
                date = date,
                mood = mood,
                tags = tags,
                source = EntrySource.TYPED,
            )
        } else {
            repo.save(existing.copy(body = body.trim(), entryDate = date, mood = mood, tags = tags))
        }
        refreshMonth()
        refreshToday()
        if (searchQuery.isNotBlank()) runSearch(searchQuery)
    }

    fun delete(id: String) {
        repo.delete(id)
        refreshMonth()
        refreshToday()
        if (searchQuery.isNotBlank()) runSearch(searchQuery)
    }

    // MARK: search

    fun runSearch(query: String) {
        searchQuery = query
        searchResults = if (query.isBlank()) emptyList() else repo.search(query)
    }

    // MARK: month paging

    fun previousMonth() {
        visibleMonth = visibleMonth.plus(-1, DateTimeUnit.MONTH)
        refreshMonth()
    }

    fun nextMonth() {
        visibleMonth = visibleMonth.plus(1, DateTimeUnit.MONTH)
        refreshMonth()
    }

    fun jumpToCurrentMonth() {
        val t = todayLocal()
        visibleMonth = LocalDate(t.year, t.monthNumber, 1)
        refreshMonth()
    }

    // MARK: loading

    private fun refreshMonth() {
        val start = visibleMonth
        val end = visibleMonth.plus(1, DateTimeUnit.MONTH).plus(-1, DateTimeUnit.DAY)
        monthCounts = repo.countsInRange(start, end)
        val s = screen
        if (s is Screen.Day) dayEntries = repo.forDate(s.date)
    }

    private fun refreshToday() {
        val t = todayLocal()
        dayEntries = repo.forDate(t)
        memories = repo.onThisDay(t)
    }
}
