package org.stypox.dicio.eval

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.stypox.dicio.llm.ClarificationContext
import org.stypox.dicio.llm.AssistantIntent
import org.stypox.dicio.llm.SettingsSection
import java.text.Normalizer
import java.util.Locale

internal fun isClarificationCancellation(text: String): Boolean =
    text.trim().trimEnd('.', '!', '?').trim().lowercase(Locale.ROOT) in
        setOf("отмена", "отмени", "отменить", "cancel")

/** Locally bound to one object from the same catalog snapshot used by the model and executor. */
internal sealed interface StandaloneCommand {
    data class Settings(val section: SettingsSection) : StandaloneCommand
    data class App(val id: String) : StandaloneCommand
}

internal data class StandaloneApp(val id: String, val label: String)

private val fullOpenCommand = Regex(
    "^(?:пожалуйста[, ]+)?открой[ \\t]+([^.!?\\r\\n]+?)(?:[, ]+пожалуйста)?[.!]?$",
    RegexOption.IGNORE_CASE,
)
private val transparentLabel = Regex("(?:[a-z][a-z0-9]{1,39}|[а-яё][а-яё0-9]{1,39})", RegexOption.IGNORE_CASE)
// Conservative collision detection, not an alias mechanism: a similar opaque label blocks opening.
private fun labelSkeleton(label: String): String =
    Normalizer.normalize(label, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        .filter { it.isLetterOrDigit() }
        .map { ch -> when (ch) {
            'а' -> 'a'; 'в' -> 'b'; 'с' -> 'c'; 'е' -> 'e'; 'н' -> 'h'; 'к' -> 'k'
            'м' -> 'm'; 'о' -> 'o'; 'р' -> 'p'; 'т' -> 't'; 'х' -> 'x'; 'у' -> 'y'
            else -> ch
        } }.joinToString("")
private val settingsNames = mapOf(
    "настройки" to SettingsSection.GENERAL, "настройки тв" to SettingsSection.GENERAL,
    "настройки wi-fi" to SettingsSection.WIFI, "настройки wi‑fi" to SettingsSection.WIFI,
    "настройки wifi" to SettingsSection.WIFI, "настройки wi fi" to SettingsSection.WIFI,
    "настройки вай-фай" to SettingsSection.WIFI, "настройки вай фай" to SettingsSection.WIFI,
    "настройки приложений" to SettingsSection.APPS,
    "настройки изображения" to SettingsSection.DISPLAY,
    "настройки звука" to SettingsSection.SOUND,
)

internal fun standaloneCommand(text: String, apps: List<StandaloneApp>): StandaloneCommand? {
    val objectName = fullOpenCommand.matchEntire(text.trim())?.groupValues?.get(1)
        ?.trim()?.lowercase(Locale.ROOT) ?: return null
    val candidates = mutableListOf<StandaloneCommand>()
    settingsNames[objectName]?.let { candidates.add(StandaloneCommand.Settings(it)) }
    val appName = objectName.removePrefix("приложение ")
    val names = setOf(labelSkeleton(appName), labelSkeleton(objectName))
    val matches = apps.filter {
        labelSkeleton(it.label) in names
    }
    if (matches.any { !transparentLabel.matches(it.label) }) return null
    // A skeleton collision can only reject; the actual match is always exact, ignoring case.
    if (matches.any { it.label.lowercase(Locale.ROOT) != appName }) return null
    matches.forEach { candidates.add(StandaloneCommand.App(it.id)) }
    return candidates.singleOrNull()
}

internal fun StandaloneCommand.allows(decision: AssistantIntent): Boolean =
    decision is AssistantIntent.Reply || decision is AssistantIntent.Clarify || when (this) {
        is StandaloneCommand.Settings -> decision is AssistantIntent.OpenSettings && decision.section == section
        is StandaloneCommand.App -> decision is AssistantIntent.OpenApp && decision.id == id
    }

internal data class ClarificationInput(val context: ClarificationSnapshot?, val standalone: StandaloneCommand?)

internal fun resolveClarificationInput(previous: ClarificationSnapshot?, text: String,
    apps: List<StandaloneApp>): ClarificationInput {
    val standalone = if (previous != null) standaloneCommand(text, apps) else null
    return ClarificationInput(if (standalone == null) previous else null, standalone)
}

internal data class ClarificationSnapshot(val context: ClarificationContext, val created: Long) {
    fun isFresh(now: Long): Boolean = (now - created).let { it >= 0 && it < PendingClarification.TTL_MS }
}

/** Main-thread only, one use, never persisted. The TTL gates acceptance, not actual network timing;
 * timer cleanup can be delayed while suspended. */
internal class PendingClarification(private val scope: CoroutineScope, private val now: () -> Long) {
    private var current: ClarificationSnapshot? = null
    private var expiry: Job? = null

    fun save(request: String, question: String) {
        clear()
        if (request.isBlank() || request.length > 8000 || question.isBlank() || question.length > 6000) return
        val snapshot = ClarificationSnapshot(ClarificationContext(request, question), now())
        current = snapshot
        expiry = scope.launch {
            delay(TTL_MS)
            if (current === snapshot) clear()
        }
    }

    fun consume(): ClarificationSnapshot? {
        val snapshot = current
        clear()
        return snapshot?.takeIf { it.isFresh(now()) }
    }

    fun isAvailable(): Boolean {
        val snapshot = current ?: return false
        if (!snapshot.isFresh(now())) {
            clear()
            return false
        }
        return true
    }

    fun clear() {
        current = null
        expiry?.cancel()
        expiry = null
    }

    companion object { const val TTL_MS = 60_000L }
}
