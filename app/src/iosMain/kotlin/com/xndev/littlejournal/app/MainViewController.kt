package com.xndev.littlejournal.app

import androidx.compose.ui.window.ComposeUIViewController
import com.xndev.littlejournal.storage.iosRepository

@Suppress("unused", "FunctionName")
fun MainViewController() = ComposeUIViewController { App(iosRepository()) }
