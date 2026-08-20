package com.xndev.littlejournal.app

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.xndev.littlejournal.storage.desktopRepository
import java.io.File

fun main() = application {
    val dir = File(System.getProperty("user.home"), ".littlejournal").apply { mkdirs() }
    val repo = desktopRepository(File(dir, "journal.db").absolutePath)
    Window(onCloseRequest = ::exitApplication, title = "Little Journal") {
        App(repo)
    }
}
