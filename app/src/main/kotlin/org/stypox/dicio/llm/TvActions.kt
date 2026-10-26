package org.stypox.dicio.llm

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

@Singleton
class TvActions @Inject constructor(@ApplicationContext private val context: Context) {
    internal data class App(val id: String, val packageName: String, val label: String)

    internal suspend fun catalog(): List<App> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)
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
    }

    internal suspend fun execute(decision: AssistantIntent, apps: List<App>, locale: Locale): String {
        coroutineContext.ensureActive()
        return when (decision) {
            is AssistantIntent.Reply -> decision.text
            is AssistantIntent.Clarify -> decision.text
            AssistantIntent.Time -> "Сейчас ${LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm", locale))}"
            AssistantIntent.Date -> LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", locale))
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
                "Отправлен запрос на открытие ${app.label}."
            }
            is AssistantIntent.OpenSettings -> withContext(Dispatchers.Main) {
                coroutineContext.ensureActive()
                val intent = Intent(decision.section.action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                check(intent.resolveActivity(context.packageManager) != null) { "Этот раздел настроек недоступен на телевизоре." }
                context.startActivity(intent)
                "Открываю ${decision.section.label}."
            }
        }
    }
}
