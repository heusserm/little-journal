package com.xndev.littlejournal.app

import androidx.compose.ui.window.ComposeUIViewController
import com.xndev.littlejournal.storage.iosRepository

/**
 * Entry point called from Swift. The recognizer is injected rather than
 * constructed here because SpeechAnalyzer is a Swift-only API that Kotlin
 * cannot see.
 */
@Suppress("unused", "FunctionName")
fun MainViewController(transcriber: Transcriber) =
    ComposeUIViewController { App(iosRepository(), transcriber) }
