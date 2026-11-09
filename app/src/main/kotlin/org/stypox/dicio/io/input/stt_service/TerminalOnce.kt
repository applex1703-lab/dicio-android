package org.stypox.dicio.io.input.stt_service

/** RecognitionService callbacks are delivered on the main thread. */
internal class TerminalOnce {
    var finished = false
        private set

    fun finish(): Boolean {
        if (finished) return false
        finished = true
        return true
    }
}
