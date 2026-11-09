package org.stypox.dicio.llm

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
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
    private val searchCatalog = SearchCatalog(context)
    private val vpnDispatchStatus = VpnDispatchStatus()
    val vpnStatus = vpnDispatchStatus.state
    private val vpnDispatcher = VpnDispatcher(AndroidVpnBackend(context), onDispatched = vpnDispatchStatus::record)

    internal suspend fun beginVpn(userRequest: String): VpnDispatch {
        vpnDispatchStatus.clear()
        return vpnDispatcher.begin(userRequest)
    }

    internal suspend fun chooseVpn(optionId: String): VpnDispatch.Message = vpnDispatcher.choose(optionId)

    internal suspend fun disconnectVpn(userRequest: String): VpnDispatch.Message {
        vpnDispatchStatus.clear()
        return vpnDispatcher.disconnect(userRequest)
    }

    fun clearVpnStatus() = vpnDispatchStatus.clear()

    internal suspend fun clearVpn() = vpnDispatcher.clear()

    internal suspend fun searchTargets(): List<SearchTarget> = searchCatalog.targets()

    internal suspend fun executeSearchTarget(target: SearchTarget, query: String): Execution =
        searchCatalog.execute(target, query)

    internal suspend fun catalog(forceRefresh: Boolean = false): List<App> = withContext(Dispatchers.IO) { catalogMutex.withLock {
        val now = android.os.SystemClock.elapsedRealtime()
        val locale = androidx.core.os.ConfigurationCompat.getLocales(context.resources.configuration).toLanguageTags()
        cachedApps?.let { apps ->
            if (!forceRefresh && now - cachedAt < 30_000 && locale == cachedLocale) return@withLock apps
        }
        val pm = context.packageManager
        val entries = listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)
            .flatMap { category -> pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0) }
        val apps = buildCatalog(entries, context.packageName) { entry ->
            val activity = entry.activityInfo ?: return@buildCatalog null
            val info = activity.applicationInfo ?: return@buildCatalog null
            if (!activity.enabled || !info.enabled) return@buildCatalog null
            val packageName = info.packageName ?: return@buildCatalog null
            val label = try { pm.getApplicationLabel(info).toString() }
                catch (e: CancellationException) { throw e }
                catch (_: RuntimeException) { packageName }
            packageName to label
        }
        cachedApps = apps
        cachedAt = now
        cachedLocale = locale
        apps
    } }

    internal companion object {
        internal fun <T> buildCatalog(entries: List<T>, ownPackage: String,
            describe: (T) -> Pair<String, String>?): List<App> = entries.mapNotNull { entry ->
            try { describe(entry) } catch (e: CancellationException) { throw e }
            catch (_: RuntimeException) { null }
        }.filter { (name, _) ->
            name.isNotBlank() && name != ownPackage &&
                listOf("factory", "facmenu", "fac.menu", "engineering", "engineermode", "productiontest")
                    .none { it in name.lowercase(Locale.ROOT) }
        }.distinctBy { it.first }.sortedBy { it.first }
            .also { check(it.size <= 300) { "Слишком большой каталог приложений." } }
            .mapIndexed { index, (name, label) -> App("app_$index", name, label.take(120)) }
    }

    internal data class Execution(val text: String, val speak: Boolean)

    internal suspend fun mediaSnapshot() = mediaActions.snapshot()

    internal suspend fun execute(decision: AssistantIntent, apps: List<App>, locale: Locale,
        media: MediaActions.Snapshot = MediaActions.Snapshot(emptyList())): Execution {
        coroutineContext.ensureActive()
        return when (decision) {
            AssistantIntent.ConnectHapp -> withContext(Dispatchers.Main) {
                vpnDispatchStatus.clear()
                val result = HappConnect.executeDecision(AndroidHappLauncher(context))
                vpnDispatchStatus.record("Последний запрос отправлен в Happ. Подключение не подтверждено; проверьте его в Happ. Другой VPN мог быть прерван.")
                result
            }
            is AssistantIntent.Media -> mediaActions.execute(decision.command, media)
            is AssistantIntent.Reply -> Execution(decision.text, true)
            is AssistantIntent.Clarify -> Execution(decision.text, true)
            is AssistantIntent.ChooseSearch -> error("Необходимо выбрать сервис поиска.")
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
                vpnDispatcher.ensureModelMayOpen(app.packageName)
                coroutineContext.ensureActive()
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
