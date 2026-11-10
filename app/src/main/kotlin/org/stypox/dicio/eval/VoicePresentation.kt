package org.stypox.dicio.eval

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.dicio.skill.skill.SkillOutput

data class ChoiceOption(val id: String, val label: String)
data class ChoicePresentation(val id: Long, val options: List<ChoiceOption>)

/** Ephemeral presentation, independent of the history/settings Activity. No persisted transcript. */
data class VoicePresentation(
    val request: String = "",
    val status: String = "Подготовка микрофона…",
    val answer: String = "",
    val dismiss: Boolean = false,
    val choice: ChoicePresentation? = null,
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
            answer = if (silent) "" else text, dismiss = silent, choice = null)
    }
    fun error(message: String) {
        mutable.value = mutable.value.copy(status = "Не удалось выполнить запрос", answer = message, dismiss = false, choice = null)
    }
    fun choice(id: Long, options: List<ChoiceOption>, question: String, status: String = "Выберите сервис поиска") {
        mutable.value = mutable.value.copy(status = status, answer = question,
            dismiss = false, choice = ChoicePresentation(id, options.toList()))
    }
    fun choosing() { mutable.value = mutable.value.copy(choice = null, answer = "", status = "Выполняю…") }
    fun noInput() {
        mutable.value = VoicePresentation(status = "Речь не распознана",
            answer = "Речь не распознана.")
    }
}

/** Main-thread, in-memory one-shot snapshot. IDs are never reused after clear. */
internal class PendingChoice<T> {
    private data class Snapshot<T>(val id: Long, val query: String, val options: Map<String, T>)
    private var nextId = 0L
    private var current: Snapshot<T>? = null

    fun create(query: String, options: Map<String, T>): Long {
        require(options.isNotEmpty())
        check(nextId != Long.MAX_VALUE)
        val id = ++nextId
        current = Snapshot(id, query, options.toMap())
        return id
    }

    fun consume(id: Long, optionId: String): Pair<String, T>? {
        val snapshot = current ?: return null
        if (snapshot.id != id) return null
        val option = snapshot.options[optionId] ?: return null
        current = null
        return snapshot.query to option
    }

    fun clear() { current = null }
}

/** Single replaceable slot; it does not own the lifetime of actions such as timers. */
internal class CurrentLegacyOutput {
    private val mutable = MutableStateFlow<SkillOutput?>(null)
    val state: StateFlow<SkillOutput?> = mutable
    fun replace(output: SkillOutput?) { mutable.value = output }
    fun clear() { mutable.value = null }
}
