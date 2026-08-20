package com.xndev.littlejournal.app

/**
 * Stands in for the platform recognizer so dictation logic can be tested
 * without a microphone, a device, or Apple's speech stack.
 */
class FakeTranscriber(override val isAvailable: Boolean = true) : Transcriber {

    var startCount = 0
        private set
    var stopCount = 0
        private set

    private var listener: TranscriberListener? = null

    override fun start(listener: TranscriberListener) {
        startCount++
        this.listener = listener
    }

    override fun stop() {
        stopCount++
    }

    // Drive the callbacks the real recognizer would deliver.
    fun emitPartial(text: String) = listener!!.onPartial(text)
    fun emitFinal(text: String) = listener!!.onFinal(text)
    fun emitStatus(text: String) = listener!!.onStatus(text)
    fun emitError(text: String) = listener!!.onError(text)
}
