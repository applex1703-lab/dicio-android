package org.stypox.dicio.eval

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.dicio.skill.skill.SkillOutput

/** Ephemeral presentation, independent of the history/settings Activity. No persisted transcript. */
data class VoicePresentation(
    val request: String = "",
    val status: String = "Подготовка микрофона…",
    val answer: String = "",
    val dismiss: Boolean = false,
)

/** Only the current request is retained; replacing or clearing it discards earlier text. */
internal class CurrentVoicePresentation {
    private val mutable = kotlinx.coroutines.flow.MutableStateFlow(VoicePresentation())
    val state: kotlinx.coroutines.flow.StateFlow<VoicePresentation> = mutable

    fun clear() { mutable.value = VoicePresentation() }
    fun listening(text: String) { mutable.value = VoicePresentation(request = text, status = "Слушаю…") }
    fun thinking(text: String) { mutable.value = VoicePresentation(request = text, status = "Думаю…") }
    fun answer(text: String, silent: Boolean = false) {
        mutable.value = mutable.value.copy(status = if (silent) "Выполнено" else "Ответ",
            answer = if (silent) "" else text, dismiss = silent)
    }
    fun error(message: String) {
        mutable.value = mutable.value.copy(status = "Не удалось выполнить запрос", answer = message, dismiss = false)
    }
    fun noInput() {
        mutable.value = VoicePresentation(status = "Речь не распознана",
            answer = "Нажмите кнопку Ассистента, чтобы попробовать снова.")
    }
}

/** Single replaceable slot; it does not own the lifetime of actions such as timers. */
internal class CurrentLegacyOutput {
    private val mutable = MutableStateFlow<SkillOutput?>(null)
    val state: StateFlow<SkillOutput?> = mutable
    fun replace(output: SkillOutput?) { mutable.value = output }
    fun clear() { mutable.value = null }
}
