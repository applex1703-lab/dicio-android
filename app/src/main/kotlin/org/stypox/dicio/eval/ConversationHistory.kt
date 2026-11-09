package org.stypox.dicio.eval

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.stypox.dicio.llm.ConversationMessage

/** Main-thread confined, in-memory only. Each entry has its own expiry. */
internal class ConversationHistory(private val scope: CoroutineScope, private val now: () -> Long) {
    internal data class Snapshot(val messages: List<ConversationMessage>, val oldest: Long?) {
        fun isFresh(time: Long): Boolean = oldest?.let { (time - it) in 0 until TTL_MS } ?: true
    }
    private data class Entry(val message: ConversationMessage, val created: Long)
    private val entries = ArrayDeque<Entry>()
    private var expiry: Job? = null

    fun addExchange(request: String, requestedAt: Long, response: String) {
        if (request.isBlank() || response.isBlank() || request.length > 8000 || response.length > 6000) return
        if (sensitive.containsMatchIn(request) || sensitive.containsMatchIn(response)) return
        val created = now()
        prune(created)
        entries.addLast(Entry(ConversationMessage("user", request), requestedAt))
        entries.addLast(Entry(ConversationMessage("assistant", response), created))
        prune(created)
        while (entries.size > 4) entries.removeFirst()
        schedule()
    }

    fun snapshot(): Snapshot {
        prune(now())
        schedule()
        return Snapshot(entries.map { it.message }, entries.firstOrNull()?.created)
    }

    private fun prune(time: Long) {
        entries.removeAll { (time - it.created).let { age -> age < 0 || age >= TTL_MS } }
    }

    private fun schedule() {
        expiry?.cancel()
        expiry = null
        val first = entries.firstOrNull() ?: return
        val remaining = (TTL_MS - (now() - first.created)).coerceIn(0, TTL_MS)
        expiry = scope.launch {
            delay(remaining)
            prune(now())
            schedule()
        }
    }

    companion object {
        const val TTL_MS = 60_000L
        // Conservative filter for common explicit credentials; arbitrary secrets cannot be detected reliably.
        private val sensitive = Regex("(?i)(парол[ьяиюе]|password|passcode|api[ _-]?key|токен|token|секрет|secret|bearer\\s+|private[ _-]?key|код подтверждения)")
    }
}
