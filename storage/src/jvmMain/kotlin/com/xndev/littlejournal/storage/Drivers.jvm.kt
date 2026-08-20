package com.xndev.littlejournal.storage

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.xndev.littlejournal.db.JournalDatabase
import java.io.File

/** Fresh in-memory database. Used by tests and by desktop scratch runs. */
fun inMemoryDriver(): SqlDriver =
    JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { JournalDatabase.Schema.create(it) }

/** File-backed database; creates the schema only when the file is new. */
fun fileDriver(path: String): SqlDriver {
    val isNew = !File(path).exists()
    val driver = JdbcSqliteDriver("jdbc:sqlite:$path")
    if (isNew) JournalDatabase.Schema.create(driver)
    return driver
}

/**
 * Ready-to-use repository backed by a file. Keeps SqlDriver and the generated
 * database type out of the app module's API surface.
 */
fun desktopRepository(path: String, deviceId: String = "desktop"): JournalRepository =
    JournalRepository(JournalDatabase(fileDriver(path)), deviceId)

/**
 * Throwaway repository backed by an in-memory database. For tests, so callers
 * never have to know about SqlDriver or the generated database type.
 */
fun inMemoryRepository(deviceId: String = "test-device"): JournalRepository =
    JournalRepository(JournalDatabase(inMemoryDriver()), deviceId)
