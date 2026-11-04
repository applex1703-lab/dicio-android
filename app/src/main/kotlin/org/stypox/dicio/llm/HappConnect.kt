package org.stypox.dicio.llm

import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import kotlinx.coroutines.ensureActive
import java.util.Locale
import kotlin.coroutines.coroutineContext

/** The observed Happ 4.8.0 deep link is not a documented compatibility guarantee. */
internal object HappConnect {
    private val request = Regex("^(?:(?:пожалуйста|сейчас)[,]?\\s+){0,2}(?:подключи|включи|connect)\\s+(?:(?:пожалуйста|сейчас)[,]?\\s+){0,2}(?:(?:vpn|впн)\\s+)?(?:happ|хэпп)(?:\\s+(?:vpn|впн))?(?:[,]?\\s+(?:пожалуйста|сейчас)){0,2}[.!]?$", RegexOption.IGNORE_CASE)

    fun isExplicitRequest(text: String): Boolean = request.matches(text.trim())

    suspend fun execute(userRequest: String, launcher: HappLauncher): TvActions.Execution {
        coroutineContext.ensureActive()
        check(isExplicitRequest(userRequest)) { "Скажите явно: подключи Happ VPN." }
        val available = try { launcher.available() } catch (_: SecurityException) { false }
        check(available) { "Подключение Happ недоступно: приложение или его действие не найдено." }
        coroutineContext.ensureActive()
        check(isExplicitRequest(userRequest)) { "Скажите явно: подключи Happ VPN." }
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

internal class AndroidHappLauncher(private val context: Context) : HappLauncher {
    private var intent: Intent? = null

    override fun available(): Boolean {
        intent = null
        val pm = context.packageManager
        val candidate = Intent(Intent.ACTION_VIEW, Uri.parse("happ://connect"))
            .setPackage("com.happproxy")
            .addCategory(Intent.CATEGORY_DEFAULT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val matches = pm.queryIntentActivities(candidate, 0).filter { result ->
            val activity = result.activityInfo
            activity != null && activity.packageName == "com.happproxy" &&
                activity.name.substringAfterLast('.').lowercase(Locale.ROOT) == "mainactivity" &&
                activity.exported && activity.enabled && activity.applicationInfo.enabled &&
                (activity.permission == null || ContextCompat.checkSelfPermission(context, activity.permission) == PackageManager.PERMISSION_GRANTED)
        }
        val activity = matches.singleOrNull()?.activityInfo ?: return false
        intent = candidate.setComponent(ComponentName(activity.packageName, activity.name))
        return true
    }

    override fun launch() {
        context.startActivity(checkNotNull(intent))
    }
}
