package com.xndev.littlejournal.app

/**
 * Live speech-to-text, supplied by the platform.
 *
 * This is a plain interface rather than expect/actual on purpose. The iOS
 * implementation *cannot* be written in Kotlin: `SpeechAnalyzer` is a Swift-only
 * API -- an actor exposing AsyncSequence -- and Kotlin/Native interops through
 * Objective-C headers, which never see Swift-only declarations. So Swift
 * implements this protocol and injects itself at startup via MainViewController.
 *
 * Implementations must deliver every callback on the main thread; Compose state
 * is read there and updating it from an audio thread will crash or tear.
 */
interface TranscriberListener {
    /** Text the recognizer is still revising. Replaces any previous partial. */
    fun onPartial(text: String)

    /** Text that has settled. Append it. */
    fun onFinal(text: String)

    /** Human-readable progress, e.g. "Downloading language model". */
    fun onStatus(message: String)

    fun onError(message: String)
}

interface Transcriber {
    /** False on platforms with no on-device recognizer wired up. */
    val isAvailable: Boolean

    fun start(listener: TranscriberListener)

    fun stop()
}

/** Used by desktop and Android until each grows a real implementation. */
object NoopTranscriber : Transcriber {
    override val isAvailable: Boolean = false

    override fun start(listener: TranscriberListener) {
        listener.onError("Dictation is not available on this platform yet.")
    }

    override fun stop() {}
}
