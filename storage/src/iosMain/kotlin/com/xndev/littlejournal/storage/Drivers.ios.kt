package com.xndev.littlejournal.storage

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.xndev.littlejournal.db.JournalDatabase

fun iosDriver(name: String = "littlejournal.db"): SqlDriver =
    NativeSqliteDriver(JournalDatabase.Schema, name)

fun iosRepository(deviceId: String = "ios"): JournalRepository =
    JournalRepository(JournalDatabase(iosDriver()), deviceId).also { it.ensureIndexed() }
