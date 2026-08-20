package com.xndev.littlejournal.storage

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.xndev.littlejournal.db.JournalDatabase

fun androidDriver(context: Context, name: String = "littlejournal.db"): SqlDriver =
    AndroidSqliteDriver(JournalDatabase.Schema, context, name)

fun androidRepository(context: Context, deviceId: String = "android"): JournalRepository =
    JournalRepository(JournalDatabase(androidDriver(context)), deviceId)
