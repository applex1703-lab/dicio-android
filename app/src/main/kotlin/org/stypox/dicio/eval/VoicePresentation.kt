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
    val videoRows: List<VideoRow> = emptyList(),
    val videoSourceMessages: Map<VideoSource, String> = emptyMap(),
) {
    val videoRow: VideoRow? get() = videoRows.firstOrNull()
}

/** Only the current request is retained; replacing or clearing it discards earlier text. */
internal class CurrentVoicePresentation {
    private val mutable = kotlinx.coroutines.flow.MutableStateFlow(VoicePresentation())
    val state: kotlinx.coroutines.flow.StateFlow<VoicePresentation> = mutable

    fun clear() { mutable.value = VoicePresentation() }
    fun listening(text: String) { mutable.value = VoicePresentation(request = text, status = "Слушаю…") }
    fun thinking(text: String) { mutable.value = VoicePresentation(request = text, status = "Думаю…") }
    fun answer(text: String, silent: Boolean = false) {
        mutable.value = mutable.value.copy(status = if (silent) "Выполнено" else "Ответ",
            answer = if (silent) "" else text, dismiss = silent, choice = null, videoRows = emptyList(), videoSourceMessages = emptyMap())
    }
    fun error(message: String) {
        mutable.value = mutable.value.copy(status = "Не удалось выполнить запрос", answer = message, dismiss = false, choice = null, videoRows = emptyList(), videoSourceMessages = emptyMap())
    }
    fun choice(id: Long, options: List<ChoiceOption>, question: String, status: String = "Выберите сервис поиска") {
        mutable.value = mutable.value.copy(status = status, answer = question,
            dismiss = false, choice = ChoicePresentation(id, options.toList()), videoRows = emptyList(), videoSourceMessages = emptyMap())
    }
    fun choosing() { mutable.value = mutable.value.copy(choice = null, answer = "", status = "Выполняю…") }
    fun videos(id: Long, cards: List<VideoCard>, hasNext: Boolean = false, source: VideoSource = VideoSource.RUTUBE) {
        mutable.value = mutable.value.copy(status = "", answer = "", dismiss = false, choice = null,
            videoRows = listOf(VideoRow(id, cards.toList(), hasNext = hasNext, source = source)), videoSourceMessages = emptyMap())
    }
    fun addVideos(id: Long, cards: List<VideoCard>, hasNext: Boolean = false, source: VideoSource = VideoSource.RUTUBE): Boolean =
        upsertVideos(id, cards, hasNext, source)

    /** IDs must be unique across sources and never reused for a later row. */
    fun upsertVideos(id: Long, cards: List<VideoCard>, hasNext: Boolean = false, source: VideoSource = VideoSource.RUTUBE): Boolean {
        val rows = mutable.value.videoRows
        if (rows.any { it.id == id && it.source != source }) return false
        val row = VideoRow(id, cards.toList(), hasNext = hasNext, source = source)
        mutable.value = mutable.value.copy(status = "", answer = "", dismiss = false, choice = null,
            videoRows = (rows.filterNot { it.source == source } + row).sortedBy { it.source.ordinal },
            videoSourceMessages = mutable.value.videoSourceMessages - source)
        return true
    }
    fun videoSourceMessage(source: VideoSource, message: String) {
        mutable.value = mutable.value.copy(videoSourceMessages = if (message.isBlank())
            mutable.value.videoSourceMessages - source else mutable.value.videoSourceMessages + (source to message))
    }
    fun clearVideoSourceMessage(source: VideoSource) {
        mutable.value = mutable.value.copy(videoSourceMessages = mutable.value.videoSourceMessages - source)
    }
    fun finishVideoSearch() {
        mutable.value = mutable.value.copy(status = "")
    }
    fun videoPageLoading(id: Long): Boolean {
        val row = mutable.value.videoRows.find { it.id == id && it.hasNext && !it.loading } ?: return false
        updateRow(row.copy(loading = true, loadError = false))
        return true
    }
    fun videoPageLoaded(id: Long, cards: List<VideoCard>, hasNext: Boolean) {
        val row = mutable.value.videoRows.find { it.id == id && it.loading } ?: return
        val existing = row.cards.mapTo(HashSet()) { it.id }
        val added = cards.filter { existing.add(it.id) }
        updateRow(row.copy(cards = row.cards + added,
            hasNext = hasNext, loading = false, loadError = added.isEmpty() && hasNext))
    }
    fun videoPageFailed(id: Long) {
        val row = mutable.value.videoRows.find { it.id == id && it.loading } ?: return
        updateRow(row.copy(loading = false, loadError = true))
    }
    private fun updateRow(row: VideoRow) {
        mutable.value = mutable.value.copy(videoRows = mutable.value.videoRows.map { if (it.id == row.id) row else it })
    }
    fun openingVideo() { mutable.value = mutable.value.copy(videoRows = emptyList(), videoSourceMessages = emptyMap(), status = "Открываю…", answer = "") }
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
