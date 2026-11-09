package org.stypox.dicio.llm

import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** The observed Happ 4.8.0 deep link is not a documented compatibility guarantee. */
internal object HappConnect {
    private val request = Regex("^(?:(?:пожалуйста|сейчас)[,]?\\s+){0,2}(?:подключи|включи|connect)\\s+(?:(?:пожалуйста|сейчас)[,]?\\s+){0,2}(?:(?:vpn|впн)\\s+)?(?:happ|хэпп)(?:\\s+(?:vpn|впн))?(?:[,]?\\s+(?:пожалуйста|сейчас)){0,2}[.!]?$", RegexOption.IGNORE_CASE)

    fun isExplicitRequest(text: String): Boolean = request.matches(text.trim())

    suspend fun execute(userRequest: String, launcher: HappLauncher): TvActions.Execution {
        coroutineContext.ensureActive()
        check(isExplicitRequest(userRequest)) { "Скажите явно: подключи Happ VPN." }
        return executeDecision(launcher)
    }

    /** Execute an already selected action, regardless of the wording used to select it. */
    suspend fun executeDecision(launcher: HappLauncher): TvActions.Execution {
        coroutineContext.ensureActive()
        val available = try { launcher.available() } catch (_: SecurityException) { false }
        check(available) { "Подключение Happ недоступно: приложение или его действие не найдено." }
        coroutineContext.ensureActive()
        try {
            launcher.launch()
        } catch (_: android.content.ActivityNotFoundException) {
            error("Не удалось открыть подключение Happ.")
        } catch (_: SecurityException) {
            error("Нет разрешения открыть подключение Happ.")
        }
        return TvActions.Execution("Запрос на подключение отправлен в Happ. Другой VPN мог быть прерван. Состояние VPN подтвердить не удалось; проверьте его в Happ.", true)
    }
}

internal interface HappLauncher {
    fun available(): Boolean
    fun launch()
}

internal data class HappHandler(
    val packageName: String,
    val className: String,
    val exported: Boolean,
    val enabled: Boolean,
    val permitted: Boolean,
    val exactAuthority: Boolean,
    val targetClassName: String? = null,
)

internal object HappHandlerPolicy {
    const val PACKAGE = "com.happproxy"
    const val HOST = "connect_without_ui"

    fun select(handlers: List<HappHandler>): HappHandler? = handlers.filter {
        it.packageName == PACKAGE && it.className.isNotBlank() &&
            !it.className.substringAfterLast('.').equals("MainActivity", ignoreCase = true) &&
            it.targetClassName?.substringAfterLast('.')?.equals("MainActivity", ignoreCase = true) != true &&
            it.exported && it.enabled && it.permitted && it.exactAuthority
    }.singleOrNull()
}

internal class AndroidHappLauncher(private val context: Context, private val host: String = HappHandlerPolicy.HOST) : HappLauncher {
    private var intent: Intent? = null

    override fun available(): Boolean {
        intent = null
        val pm = context.packageManager
        val candidate = Intent(Intent.ACTION_VIEW, Uri.parse("happ://$host"))
            .setPackage(HappHandlerPolicy.PACKAGE)
            .addCategory(Intent.CATEGORY_DEFAULT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val disabled = setOf(PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED)
        val matches = pm.queryIntentActivities(candidate, PackageManager.GET_RESOLVED_FILTER).mapNotNull { result ->
            val activity = result.activityInfo ?: return@mapNotNull null
            val filter = result.filter
            val exactAuthority = filter != null && (0 until filter.countDataAuthorities()).any { index ->
                val authority = filter.getDataAuthority(index)
                authority.host == host && authority.port == -1
            }
            val component = ComponentName(activity.packageName, activity.name)
            HappHandler(activity.packageName, activity.name, activity.exported,
                activity.enabled && activity.applicationInfo.enabled &&
                    pm.getApplicationEnabledSetting(activity.packageName) !in disabled &&
                    pm.getComponentEnabledSetting(component) !in disabled,
                activity.permission == null || ContextCompat.checkSelfPermission(context, activity.permission) == PackageManager.PERMISSION_GRANTED,
                exactAuthority, activity.targetActivity)
        }
        val activity = HappHandlerPolicy.select(matches) ?: return false
        intent = candidate.setComponent(ComponentName(activity.packageName, activity.className))
        return true
    }

    override fun launch() {
        val selected = checkNotNull(intent)
        intent = null
        context.startActivity(selected)
    }
}
