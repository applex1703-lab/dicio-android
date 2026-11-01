package org.stypox.dicio.llm

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.Locale
import kotlin.coroutines.coroutineContext

internal enum class SearchTargetKind { YOUTUBE, RUTUBE, GOOGLE, WEB_SEARCH }

/** Component and kind are local data, never fields supplied by the model. */
internal data class SearchTarget(
    val id: String,
    val label: String,
    val kind: SearchTargetKind,
    val component: ComponentName,
)

/** Pure catalog policy, shared with unit tests. */
internal object SearchCatalogPolicy {
    const val LIMIT = 50
    const val STUB_PACKAGE = "com.google.android.tv.frameworkpackagestubs"

    data class Candidate(val kind: SearchTargetKind, val packageName: String, val componentName: String, val label: String)

    fun cleanLabel(label: String): String = label
        .filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }
        .replace(Regex("\\s+"), " ").trim().take(80).trim()

    fun select(candidates: List<Candidate>, ownPackage: String): List<Candidate> = candidates
        .asSequence()
        .filter { it.packageName != ownPackage && it.packageName != STUB_PACKAGE }
        .filterNot { candidate ->
            val name = candidate.packageName.lowercase(Locale.ROOT)
            listOf("factory", "facmenu", "fac.menu", "engineering", "engineermode", "productiontest")
                .any { it in name }
        }
        .map { it.copy(label = cleanLabel(it.label)) }
        .filter { it.label.isNotEmpty() && it.packageName.isNotBlank() && it.componentName.isNotBlank() }
        .sortedWith(compareBy<Candidate>({ it.kind.ordinal }, { it.packageName }, { it.componentName }, { it.label }))
        // Only one entry per application; native integrations take priority over generic search.
        .distinctBy { if (it.kind == SearchTargetKind.GOOGLE) "@google" else it.packageName }
        .take(LIMIT).toList()
}

internal class SearchCatalog(private val context: Context) {
    private val mutex = Mutex()
    private var issued: Map<String, SearchTarget> = emptyMap()

    private fun probe(kind: SearchTargetKind, query: String = "test"): Intent = when (kind) {
        SearchTargetKind.YOUTUBE -> Intent(Intent.ACTION_VIEW, Uri.Builder().scheme("https").authority("www.youtube.com")
            .path("/results").appendQueryParameter("search_query", query).build()).setPackage(ContentSearch.YOUTUBE_PACKAGE)
        SearchTargetKind.RUTUBE -> Intent(Intent.ACTION_VIEW, Uri.Builder().scheme("rutube").authority("rutube.ru")
            .path("/search").appendQueryParameter("query", query).build()).setPackage(ContentSearch.RUTUBE_PACKAGE)
        SearchTargetKind.GOOGLE -> Intent(Intent.ACTION_VIEW, Uri.Builder().scheme("https").authority("www.google.com")
            .path("/search").appendQueryParameter("q", query).build()).addCategory(Intent.CATEGORY_BROWSABLE)
        SearchTargetKind.WEB_SEARCH -> Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)
    }

    private fun browserProbe(scheme: String) = Intent(Intent.ACTION_VIEW, Uri.parse("$scheme://example.com/"))
        .addCategory(Intent.CATEGORY_BROWSABLE)

    private fun handlers(intent: Intent): List<android.content.pm.ResolveInfo> =
        context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).filter { result ->
            val info = result.activityInfo ?: return@filter false
            val app = info.applicationInfo ?: return@filter false
            val pm = context.packageManager
            val component = ComponentName(info.packageName, info.name)
            val disabled = setOf(PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED)
            info.enabled && app.enabled && info.exported && info.packageName != context.packageName &&
                info.packageName != SearchCatalogPolicy.STUB_PACKAGE &&
                pm.getApplicationEnabledSetting(info.packageName) !in disabled &&
                pm.getComponentEnabledSetting(component) !in disabled &&
                (info.permission.isNullOrEmpty() || ContextCompat.checkSelfPermission(context, info.permission) == PackageManager.PERMISSION_GRANTED)
        }

    private fun component(result: android.content.pm.ResolveInfo) = ComponentName(result.activityInfo.packageName, result.activityInfo.name)

    private fun candidates(): List<SearchCatalogPolicy.Candidate> {
        val pm = context.packageManager
        val http = handlers(browserProbe("http")).map(::component).toSet()
        val https = handlers(browserProbe("https")).map(::component).toSet()
        return SearchTargetKind.entries.flatMap { kind ->
            handlers(probe(kind)).filter { kind != SearchTargetKind.GOOGLE || component(it) in http && component(it) in https }
                .mapNotNull { result ->
                    val name = component(result)
                    val label = if (kind == SearchTargetKind.GOOGLE) "Google" else
                        runCatching { result.loadLabel(pm).toString() }.getOrNull() ?: return@mapNotNull null
                    SearchCatalogPolicy.Candidate(kind, name.packageName, name.className, label)
                }
        }
    }

    suspend fun targets(): List<SearchTarget> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val targets = SearchCatalogPolicy.select(candidates(), context.packageName).map {
                SearchTarget(UUID.randomUUID().toString(), it.label, it.kind, ComponentName(it.packageName, it.componentName))
            }
            issued = targets.associateBy { it.id }
            targets.toList()
        }
    }

    suspend fun execute(target: SearchTarget, query: String): TvActions.Execution = withContext(Dispatchers.Main) {
        mutex.withLock {
            coroutineContext.ensureActive()
            check(issued[target.id] == target) { "Сервис поиска больше недоступен." }
            val validated = ContentSearch.validateQuery(query)
            val intent = probe(target.kind, validated)
            check(handlers(intent).any { component(it) == target.component }) { "Сервис поиска больше недоступен." }
            if (target.kind == SearchTargetKind.GOOGLE) {
                check(listOf("http", "https").all { scheme ->
                    handlers(browserProbe(scheme)).any { component(it) == target.component }
                }) { "Браузер больше недоступен." }
            }
            coroutineContext.ensureActive()
            context.startActivity(intent.setComponent(target.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            TvActions.Execution("Открываю поиск: $validated", false)
        }
    }
}
