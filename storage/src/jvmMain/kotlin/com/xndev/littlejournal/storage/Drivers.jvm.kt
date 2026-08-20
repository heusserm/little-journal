package com.xndev.littlejournal.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.xndev.littlejournal.db.JournalDatabase
import java.io.File

/**
 * Schema 1 predates this file stamping `user_version`, so an untouched
 * database from that era reads back 0. Version 1 was the only schema that
 * ever shipped unstamped, so 0 means 1 — not "empty".
 *
 * Reading it as 0 would be quiet and wrong: the migration from 0 would try to
 * create tables that are already there.
 */
private const val UNSTAMPED_SCHEMA_VERSION = 1L

/** Fresh in-memory database. Used by tests and by desktop scratch runs. */
fun inMemoryDriver(): SqlDriver =
    JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { driver ->
        JournalDatabase.Schema.create(driver)
        driver.setUserVersion(JournalDatabase.Schema.version)
    }

/**
 * File-backed database: created when the file is new, migrated when it is
 * old, left alone when it is current.
 *
 * Android and iOS get this for free — `AndroidSqliteDriver` and
 * `NativeSqliteDriver` are both handed the schema and run migrations
 * themselves. The JDBC driver is not, so the version bookkeeping is here.
 */
fun fileDriver(path: String): SqlDriver {
    val isNew = !File(path).exists()
    val driver = JdbcSqliteDriver("jdbc:sqlite:$path")
    val schema = JournalDatabase.Schema

    if (isNew) {
        schema.create(driver)
    } else {
        val current = maxOf(driver.userVersion(), UNSTAMPED_SCHEMA_VERSION)
        if (current < schema.version) schema.migrate(driver, current, schema.version)
    }
    driver.setUserVersion(schema.version)
    return driver
}

private fun SqlDriver.userVersion(): Long =
    executeQuery(
        identifier = null,
        sql = "PRAGMA user_version",
        parameters = 0,
        mapper = { cursor ->
            cursor.next()
            QueryResult.Value(cursor.getLong(0) ?: 0L)
        },
    ).value

private fun SqlDriver.setUserVersion(version: Long) {
    execute(identifier = null, sql = "PRAGMA user_version = $version", parameters = 0)
}

/**
 * Ready-to-use repository backed by a file. Keeps SqlDriver and the generated
 * database type out of the app module's API surface.
 */
fun desktopRepository(path: String, deviceId: String = "desktop"): JournalRepository =
    JournalRepository(JournalDatabase(fileDriver(path)), deviceId).also { it.ensureIndexed() }

/**
 * Throwaway repository backed by an in-memory database. For tests, so callers
 * never have to know about SqlDriver or the generated database type.
 */
fun inMemoryRepository(deviceId: String = "test-device"): JournalRepository =
    JournalRepository(JournalDatabase(inMemoryDriver()), deviceId)
