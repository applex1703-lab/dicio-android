package org.stypox.dicio.llm

import java.util.Locale

/** Pure, deliberately narrow recognition; the caller owns the catalog snapshot and execution. */
internal object LocalOpenAppRequest {
    internal data class Entry(val id: String, val label: String)

    // Only plain names can be proved safe here. Punctuation, conjunctions and content/action
    // words are intentionally left to the normal request handler, even in real app labels.
    private val plainName = Regex("[\\p{L}\\p{N}]+(?: [\\p{L}\\p{N}]+)*")
    private val excludedWords = setOf(
        "и", "или", "а", "затем", "потом", "после", "перед", "через", "в", "на", "с",
        "найди", "найти", "поиск", "ищи", "покажи", "показать", "смотри", "смотреть",
        "включи", "включить", "выключи", "выключить", "открой", "открыть", "запусти", "запустить",
        "фильм", "фильмы", "кино", "сериал", "сериалы", "видео", "ролик", "ролики",
        "and", "or", "then", "after", "before", "search", "find", "watch", "play", "open",
        "launch", "video", "videos", "movie", "movies", "film", "films",
    )

    /** Syntax filter only: true does not establish that an app exists or may be opened. */
    fun isCandidate(text: String): Boolean = candidateName(text) != null

    private fun candidateName(text: String): String? {
        val normalized = text.trim().lowercase(Locale.ROOT)
        val name = listOf("открой ", "открыть ", "запусти ", "запустить ",
            "включи ", "включить ", "open ", "launch ")
            .firstOrNull { normalized.startsWith(it) }
            ?.let { normalized.removePrefix(it) } ?: return null
        if (!plainName.matches(name) || name.split(' ').any { it in excludedWords }) return null
        return name
    }

    /** Returns an ID only for a unique exact label in this particular catalog snapshot. */
    fun parse(text: String, catalog: List<Entry>): String? {
        val name = candidateName(text) ?: return null
        val matches = catalog.filter { it.label.lowercase(Locale.ROOT) == name }
        val id = matches.singleOrNull()?.id?.takeIf { it.isNotBlank() } ?: return null
        return id.takeIf { candidate -> catalog.count { it.id == candidate } == 1 }
    }
}
