package org.stypox.dicio.eval

/** Ephemeral presentation, independent of the history/settings Activity. No persisted transcript. */
data class VoicePresentation(
    val request: String = "",
    val status: String = "Подготовка микрофона…",
    val answer: String = "",
    val dismiss: Boolean = false,
)
