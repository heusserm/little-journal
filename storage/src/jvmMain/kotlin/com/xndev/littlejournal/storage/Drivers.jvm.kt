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
