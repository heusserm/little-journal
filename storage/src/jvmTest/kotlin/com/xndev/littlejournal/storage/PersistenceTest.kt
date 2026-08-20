package com.xndev.littlejournal.storage

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The file-backed driver creates the schema only when the file is new. Getting
 * that wrong in either direction is quiet and destructive: create always and
 * every launch wipes the journal; create never and the first launch crashes.
 */
class PersistenceTest {

    private val path: String = File.createTempFile("littlejournal-test", ".db")
        .also { it.delete() }   // fileDriver must see a path that does not exist yet
        .absolutePath

    @AfterTest
    fun cleanup() {
        File(path).delete()
    }

    @Test
    fun `entries survive closing and reopening the database`() {
        val first = JournalRepository(
            com.xndev.littlejournal.db.JournalDatabase(fileDriver(path)),
            deviceId = "device-a",
        )
        first.create(id = "a", body = "written before the restart", date = LocalDate.parse("2026-05-01"))

        // A second driver over the same file is what a relaunch looks like.
        val second = JournalRepository(
            com.xndev.littlejournal.db.JournalDatabase(fileDriver(path)),
            deviceId = "device-a",
        )
        val loaded = second.byId("a")

        assertNotNull(loaded, "reopening must not wipe the database")
        assertEquals("written before the restart", loaded.body)
    }

    @Test
    fun `reopening does not duplicate or reset existing rows`() {
        val db = { JournalRepository(com.xndev.littlejournal.db.JournalDatabase(fileDriver(path)), "d") }
        db().create(id = "a", body = "one", date = LocalDate.parse("2026-05-01"))
        db().create(id = "b", body = "two", date = LocalDate.parse("2026-05-01"))

        assertEquals(2, db().liveCount())
    }

    @Test
    fun `the database file is actually created on disk`() {
        JournalRepository(com.xndev.littlejournal.db.JournalDatabase(fileDriver(path)), "d")
            .create(id = "a", body = "x", date = LocalDate.parse("2026-05-01"))

        assertTrue(File(path).exists() && File(path).length() > 0)
    }

    @Test
    fun `device id is recorded on entries so sync can attribute them later`() {
        val repo = JournalRepository(
            com.xndev.littlejournal.db.JournalDatabase(fileDriver(path)),
            deviceId = "phone-7",
        )
        repo.create(id = "a", body = "x", date = LocalDate.parse("2026-05-01"))

        assertEquals("phone-7", repo.byId("a")?.deviceId)
    }

    /**
     * The upgrade path, end to end: a journal written against schema 1 has no
     * word index and no `user_version` stamp. Opening it must add the table,
     * backfill it from the entries already there, and lose nothing.
     *
     * Simulated by taking a current database apart: dropping the index table
     * and winding `user_version` back to 0, which is what the real desktop
     * `journal.db` reads today — schema 1 never stamped a version, so the
     * repository has to read 0 as "1", not as "empty".
     */
    @Test
    fun `a journal written before the word index upgrades without losing anything`() {
        desktopRepository(path, deviceId = "d")
            .create(id = "a", body = "the rain in spain", date = LocalDate.parse("2026-05-01"))

        fileDriver(path).apply {
            execute(null, "DROP TABLE entry_word", 0)
            execute(null, "PRAGMA user_version = 0", 0)
        }

        val upgraded = desktopRepository(path, deviceId = "d")

        assertEquals("the rain in spain", upgraded.byId("a")?.body, "the entry must survive")
        assertEquals(listOf("a"), upgraded.search("spain").map { it.id }, "and become searchable")
    }

    /** Opening an already-current database must not re-run the migration. */
    @Test
    fun `reopening a current database leaves the index intact`() {
        desktopRepository(path, deviceId = "d")
            .create(id = "a", body = "unchanged", date = LocalDate.parse("2026-05-01"))

        assertEquals(listOf("a"), desktopRepository(path, "d").search("unchanged").map { it.id })
    }

    @Test
    fun `changed since returns rows in the order they were touched`() {
        val repo = JournalRepository(
            com.xndev.littlejournal.db.JournalDatabase(fileDriver(path)), "d",
        )
        repo.create(id = "a", body = "first", date = LocalDate.parse("2026-05-01"))
        repo.create(id = "b", body = "second", date = LocalDate.parse("2026-05-01"))
        repo.create(id = "c", body = "third", date = LocalDate.parse("2026-05-01"))

        val changed = repo.changedSince(Instant.fromEpochMilliseconds(0))

        assertEquals(3, changed.size)
        assertTrue(
            changed.zipWithNext().all { (a, b) -> a.updatedAt <= b.updatedAt },
            "sync needs a monotonic watermark to page through",
        )
    }
}

/** The convenience wrapper the desktop app actually calls. */
class DesktopRepositoryTest {

    @Test
    fun `desktopRepository opens a working file-backed journal`() {
        val f = File.createTempFile("lj-desktop", ".db").also { it.delete() }
        try {
            val repo = desktopRepository(f.absolutePath, deviceId = "mac")
            repo.create(id = "a", body = "from the desktop", date = LocalDate.parse("2026-07-04"))

            assertEquals("from the desktop", repo.byId("a")?.body)
            assertEquals("mac", repo.byId("a")?.deviceId)
            assertTrue(f.exists())
        } finally {
            f.delete()
        }
    }

    @Test
    fun `inMemoryRepository starts empty every time`() {
        inMemoryRepository().create(id = "a", body = "x", date = LocalDate.parse("2026-07-04"))
        assertEquals(0, inMemoryRepository().liveCount(), "each call must be a fresh database")
    }
}
