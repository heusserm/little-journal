package com.xndev.littlejournal.app

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.xndev.littlejournal.storage.desktopRepository
import java.io.File

/**
 * Desktop is a development target, not something anyone ships, so the database
 * lives in the project directory where it is easy to inspect with sqlite3 and
 * easy to throw away. A real user-facing build would write to the platform's
 * application-support directory instead.
 *
 * The Gradle run task pins the working directory to the project root; override
 * the location with -Dlittlejournal.db=/some/path.
 */
private fun databasePath(): String =
    System.getProperty("littlejournal.db")
        ?: File(System.getProperty("user.dir"), "journal.db").absolutePath

fun main() = application {
    val path = databasePath()
    println("Little Journal database: $path")
    val repo = desktopRepository(path)
    Window(onCloseRequest = ::exitApplication, title = "Little Journal") {
        App(repo)
    }
}
