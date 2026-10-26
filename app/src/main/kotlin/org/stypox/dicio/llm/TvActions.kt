package org.stypox.dicio.llm

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

@Singleton
class TvActions @Inject constructor(@ApplicationContext private val context: Context, private val mediaActions: MediaActions) {
    internal data class App(val id: String, val packageName: String, val label: String)
    private val catalogMutex = Mutex()
    private var cachedApps: List<App>? = null
    private var cachedAt = 0L
    private var cachedLocale = ""

    internal suspend fun catalog(): List<App> = withContext(Dispatchers.IO) { catalogMutex.withLock {
        val now = android.os.SystemClock.elapsedRealtime()
        val locale = androidx.core.os.ConfigurationCompat.getLocales(context.resources.configuration).toLanguageTags()
        cachedApps?.let { apps ->
            if (now - cachedAt < 30_000 && locale == cachedLocale) return@withLock apps
        }
        val pm = context.packageManager
        val apps = listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)
            .flatMap { category -> pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0) }
            .filter { it.activityInfo.enabled && it.activityInfo.applicationInfo.enabled }
            .map { it.activityInfo.applicationInfo }.distinctBy { it.packageName }
            .filter { it.packageName != context.packageName }
            // Never offer engineering/factory entry points to a remote model.
            .filterNot { info ->
                val name = info.packageName.lowercase(Locale.ROOT)
                listOf("factory", "facmenu", "fac.menu", "engineering", "engineermode", "productiontest")
                    .any { it in name }
            }
            .sortedBy { it.packageName }
            .also { check(it.size <= 300) { "Слишком большой каталог приложений." } }
            .mapIndexed { index, info -> App("app_$index", info.packageName,
                pm.getApplicationLabel(info).toString().take(120)) }
        cachedApps = apps
        cachedAt = now
        cachedLocale = locale
        apps
    } }

    internal data class Execution(val text: String, val speak: Boolean)

    internal suspend fun mediaSnapshot() = mediaActions.snapshot()

    internal suspend fun execute(decision: AssistantIntent, apps: List<App>, locale: Locale,
        media: MediaActions.Snapshot = MediaActions.Snapshot(emptyList())): Execution {
        coroutineContext.ensureActive()
        return when (decision) {
            is AssistantIntent.Media -> mediaActions.execute(decision.command, media)
            is AssistantIntent.Reply -> Execution(decision.text, true)
            is AssistantIntent.Clarify -> Execution(decision.text, true)
            is AssistantIntent.SearchYoutube -> withContext(Dispatchers.Main) {
                coroutineContext.ensureActive()
                val query = ContentSearch.validateQuery(decision.query)
                val uri = android.net.Uri.Builder().scheme("https").authority("www.youtube.com")
                    .path("/results").appendQueryParameter("search_query", query).build()
                val intent = Intent(Intent.ACTION_VIEW, uri).setPackage(ContentSearch.YOUTUBE_PACKAGE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                check(intent.resolveActivity(context.packageManager) != null) { "Поиск YouTube недоступен." }
                context.startActivity(intent)
                Execution("Открываю поиск в YouTube: $query", false)
            }
            is AssistantIntent.SearchRutube -> withContext(Dispatchers.Main) {
                coroutineContext.ensureActive()
                val query = ContentSearch.validateQuery(decision.query)
                val uri = android.net.Uri.Builder().scheme("rutube").authority("rutube.ru")
                    .path("/search").appendQueryParameter("query", query).build()
                val intent = Intent(Intent.ACTION_VIEW, uri).setPackage(ContentSearch.RUTUBE_PACKAGE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                check(intent.resolveActivity(context.packageManager) != null) { "Поиск Rutube недоступен." }
                context.startActivity(intent)
                Execution("Открываю поиск в Rutube: $query", false)
            }
            AssistantIntent.Time -> Execution("Сейчас ${LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm", locale))}", true)
            AssistantIntent.Date -> Execution(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", locale)), true)
            is AssistantIntent.OpenApp -> withContext(Dispatchers.Main) {
                coroutineContext.ensureActive()
                val app = apps.singleOrNull { it.id == decision.id } ?: error("Неизвестное приложение.")
                val pm = context.packageManager
                val info = pm.getApplicationInfo(app.packageName, 0)
                check(info.enabled && pm.getApplicationEnabledSetting(app.packageName) !in setOf(
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED)) { "Приложение отключено." }
                val launch = pm.getLeanbackLaunchIntentForPackage(app.packageName)
                    ?: pm.getLaunchIntentForPackage(app.packageName)
                    ?: error("Приложение нельзя запустить на этом ТВ.")
                context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Execution("Открываю ${app.label}.", false)
            }
            is AssistantIntent.OpenSettings -> withContext(Dispatchers.Main) {
                coroutineContext.ensureActive()
                val intent = Intent(decision.section.action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                check(intent.resolveActivity(context.packageManager) != null) { "Этот раздел настроек недоступен на телевизоре." }
                context.startActivity(intent)
                Execution("Открываю ${decision.section.label}.", false)
            }
        }
    }
}
