package org.stypox.dicio.eval

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.dicio.skill.skill.SkillOutput

data class ChoiceOption(val id: String, val label: String)
data class ChoicePresentation(val id: Long, val options: List<ChoiceOption>)
data class VideoCard(val id: String, val title: String, val author: String, val thumbnail: String?)
enum class VideoSource(val label: String) { RUTUBE("Rutube"), YOUTUBE("YouTube") }
data class VideoRow(
    val id: Long,
    val cards: List<VideoCard>,
    val hasNext: Boolean = false,
    val loading: Boolean = false,
    val loadError: Boolean = false,
    val source: VideoSource = VideoSource.RUTUBE,
)

/** Ephemeral presentation, independent of the history/settings Activity. No persisted transcript. */
data class VoicePresentation(
    val request: String = "",
    val status: String = "Подготовка микрофона…",
    val answer: String = "",
    val dismiss: Boolean = false,
    val choice: ChoicePresentation? = null,
    val videoRow: VideoRow? = null,
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
            answer = if (silent) "" else text, dismiss = silent, choice = null, videoRow = null)
    }
    fun error(message: String) {
        mutable.value = mutable.value.copy(status = "Не удалось выполнить запрос", answer = message, dismiss = false, choice = null, videoRow = null)
    }
    fun choice(id: Long, options: List<ChoiceOption>, question: String, status: String = "Выберите сервис поиска") {
        mutable.value = mutable.value.copy(status = status, answer = question,
            dismiss = false, choice = ChoicePresentation(id, options.toList()), videoRow = null)
    }
    fun choosing() { mutable.value = mutable.value.copy(choice = null, answer = "", status = "Выполняю…") }
    fun videos(id: Long, cards: List<VideoCard>, hasNext: Boolean = false, source: VideoSource = VideoSource.RUTUBE) {
        mutable.value = mutable.value.copy(status = "", answer = "", dismiss = false, choice = null,
            videoRow = VideoRow(id, cards.toList(), hasNext = hasNext, source = source))
    }
    fun videoPageLoading(id: Long): Boolean {
        val row = mutable.value.videoRow?.takeIf { it.id == id && it.hasNext && !it.loading } ?: return false
        mutable.value = mutable.value.copy(videoRow = row.copy(loading = true, loadError = false))
        return true
    }
    fun videoPageLoaded(id: Long, cards: List<VideoCard>, hasNext: Boolean) {
        val row = mutable.value.videoRow?.takeIf { it.id == id && it.loading } ?: return
        val existing = row.cards.mapTo(HashSet()) { it.id }
        val added = cards.filter { existing.add(it.id) }
        mutable.value = mutable.value.copy(videoRow = row.copy(cards = row.cards + added,
            hasNext = hasNext, loading = false, loadError = added.isEmpty() && hasNext))
    }
    fun videoPageFailed(id: Long) {
        val row = mutable.value.videoRow?.takeIf { it.id == id && it.loading } ?: return
        mutable.value = mutable.value.copy(videoRow = row.copy(loading = false, loadError = true))
    }
    fun openingVideo() { mutable.value = mutable.value.copy(videoRow = null, status = "Открываю…", answer = "") }
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

    /** Extend only the still-current snapshot; consumed or superseded choices remain invalid. */
    fun extend(id: Long, options: Map<String, T>): Boolean {
        val snapshot = current ?: return false
        if (snapshot.id != id) return false
        current = snapshot.copy(options = snapshot.options + options)
        return true
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
