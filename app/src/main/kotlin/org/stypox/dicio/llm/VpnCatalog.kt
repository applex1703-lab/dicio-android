package org.stypox.dicio.llm

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.Locale
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.coroutineContext

internal data class VpnCandidate(val packageName: String, val label: String, val supported: Boolean, val signerFingerprint: String)
internal data class VpnOption(val id: String, val label: String)

/** In-memory last dispatch only; not a VPN connection-state observation. */
internal class VpnDispatchStatus(private val now: () -> LocalDateTime = { LocalDateTime.now() }) {
    private val mutable = MutableStateFlow<String?>(null)
    val state: StateFlow<String?> = mutable.asStateFlow()
    fun clear() { mutable.value = null }
    fun record(text: String) {
        val timestamp = now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss", Locale.ROOT))
        mutable.value = "Последний запрос ($timestamp, местное время; это не состояние VPN): $text"
    }
}

internal class VpnOpenBlockedException : IllegalStateException("Для VPN скажите явно: подключи VPN или подключи Happ VPN.")

/** Fingerprints identify the current signer set within one selection, not a trusted vendor. */
internal object VpnSigners {
    fun fingerprint(certificates: List<ByteArray>): String {
        check(certificates.isNotEmpty() && certificates.all { it.isNotEmpty() }) { "Не удалось проверить подпись VPN-приложения." }
        return certificates.map { bytes ->
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }.sorted().joinToString(":")
    }
}

internal object VpnDiscovery {
    const val HAPP = "com.happproxy"
    fun packages(servicePackages: List<String>, happInstalled: Boolean): Set<String> =
        servicePackages.toMutableSet().apply { if (happInstalled) add(HAPP) }
}

/** Inspect only the selected package; failures propagate and therefore block generic launch. */
internal fun isSelectedVpnPackage(packageName: String, servicePermissions: (String) -> List<String?>): Boolean {
    if (packageName == VpnDiscovery.HAPP) return true
    return servicePermissions(packageName).any { it == "android.permission.BIND_VPN_SERVICE" }
}

/** Disambiguate display names locally without exposing package names or signatures. */
internal object VpnLabels {
    fun disambiguate(labels: List<String>): List<String> {
        val clean = labels.map { SearchCatalogPolicy.cleanLabel(it) }
        check(clean.none { it.isEmpty() }) { "Не удалось определить название VPN-приложения." }
        val counts = clean.groupingBy { it.lowercase(Locale.ROOT) }.eachCount()
        val used = mutableSetOf<String>()
        return clean.map { label ->
            var suffix = if (counts.getValue(label.lowercase(Locale.ROOT)) > 1) 1 else 0
            var display = if (suffix == 0) label else "$label ($suffix)"
            while (!used.add(display.lowercase(Locale.ROOT))) {
                suffix++
                display = "$label ($suffix)"
            }
            display
        }
    }
}

internal interface VpnBackend {
    fun candidates(): List<VpnCandidate>
    fun isVpnPackage(packageName: String): Boolean
    fun connectHapp(): Boolean
    fun disconnectHapp(): Boolean = false
    fun open(packageName: String): Boolean
}

internal fun ensureNotVpnForGenericOpen(backend: VpnBackend, packageName: String) {
    if (backend.isVpnPackage(packageName)) throw VpnOpenBlockedException()
}

/** The list describes visible candidates only, never all installed VPNs. */
internal class AndroidVpnBackend(private val context: Context) : VpnBackend {
    override fun isVpnPackage(packageName: String): Boolean {
        // Inspect the selected package too: implicit service queries may omit non-exported services.
        return isSelectedVpnPackage(packageName) { target ->
            val services = try {
                context.packageManager.getPackageInfo(target, PackageManager.GET_SERVICES).services
            } catch (_: PackageManager.NameNotFoundException) {
                error("Не удалось проверить выбранное приложение.")
            } catch (_: SecurityException) {
                error("Не удалось проверить выбранное приложение.")
            }
            services?.map { it.permission } ?: emptyList()
        }
    }

    private fun signerFingerprint(packageName: String): String {
        val pm = context.packageManager
        try {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(packageName, if (Build.VERSION.SDK_INT >= 28)
                PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            val signatures = if (Build.VERSION.SDK_INT >= 28) {
                val signing = info.signingInfo ?: error("Не удалось проверить подпись VPN-приложения.")
                if (signing.hasMultipleSigners()) signing.apkContentsSigners?.toList()
                else signing.signingCertificateHistory?.lastOrNull()?.let { listOf(it) }
            } else info.signatures?.toList()
            return VpnSigners.fingerprint(signatures?.map { it.toByteArray() } ?: emptyList())
        } catch (_: PackageManager.NameNotFoundException) {
            error("Не удалось проверить подпись VPN-приложения.")
        } catch (_: SecurityException) {
            error("Не удалось проверить подпись VPN-приложения.")
        }
    }

    override fun candidates(): List<VpnCandidate> {
        val pm = context.packageManager
        val servicePackages = pm.queryIntentServices(Intent(VpnService.SERVICE_INTERFACE), 0)
            .mapNotNull { it.serviceInfo }
            .filter { it.enabled && it.applicationInfo.enabled && it.permission == android.Manifest.permission.BIND_VPN_SERVICE }
            .map { it.packageName }
        // Some devices omit Happ's non-exported service from implicit queries.
        val happInstalled = try { pm.getApplicationInfo(VpnDiscovery.HAPP, 0).enabled }
            catch (_: PackageManager.NameNotFoundException) { false }
        val packages = VpnDiscovery.packages(servicePackages, happInstalled)
        return packages.filter { it != context.packageName }.mapNotNull { packageName ->
            try {
                val info = pm.getApplicationInfo(packageName, 0)
                if (!info.enabled) return@mapNotNull null
                val label = SearchCatalogPolicy.cleanLabel(pm.getApplicationLabel(info).toString())
                check(label.isNotEmpty()) { "Не удалось определить VPN-приложение." }
                VpnCandidate(packageName, label, packageName == VpnDiscovery.HAPP, signerFingerprint(packageName))
            } catch (_: PackageManager.NameNotFoundException) { error("Не удалось определить VPN-приложение.") }
        }.sortedBy { it.packageName }
    }

    override fun connectHapp(): Boolean {
        val launcher = AndroidHappLauncher(context)
        if (!launcher.available()) return false
        launcher.launch()
        return true
    }

    override fun disconnectHapp(): Boolean {
        val launcher = AndroidHappLauncher(context, "disconnect_without_ui")
        if (!launcher.available()) return false
        launcher.launch()
        return true
    }

    override fun open(packageName: String): Boolean {
        val pm = context.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(packageName)
            ?: pm.getLaunchIntentForPackage(packageName) ?: return false
        val component = intent.component ?: return false
        if (component.packageName != packageName) return false
        val activity = try { pm.getActivityInfo(ComponentName(packageName, component.className), 0) }
            catch (_: PackageManager.NameNotFoundException) { return false }
        val disabled = setOf(PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED)
        if (!activity.exported || !activity.enabled || !activity.applicationInfo.enabled ||
            pm.getApplicationEnabledSetting(packageName) in disabled ||
            pm.getComponentEnabledSetting(component) in disabled ||
            (!activity.permission.isNullOrEmpty() && ContextCompat.checkSelfPermission(context, activity.permission) != PackageManager.PERMISSION_GRANTED)) return false
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}

internal sealed interface VpnDispatch {
    data class Choice(val options: List<VpnOption>, val prompt: String = "Выберите VPN-приложение. Список видимых приложений может быть неполным.") : VpnDispatch
    data class Message(val text: String, val actionSent: Boolean = false) : VpnDispatch
}

/** One dispatcher instance per UI owner. Model output never supplies packages or URIs. */
internal class VpnDispatcher(private val backend: VpnBackend, private val main: CoroutineDispatcher = Dispatchers.Main,
    private val onDispatched: (String) -> Unit = {}) {
    private val mutex = Mutex()
    private var issued: Map<String, VpnCandidate> = emptyMap()

    companion object {
        private val request = Regex("^(?:(?:пожалуйста|сейчас)[,]?\\s+){0,2}(?:подключи|включи|connect)\\s+(?:(?:пожалуйста|сейчас)[,]?\\s+){0,2}(?:vpn|впн|ви\\s+пи\\s+эн)(?:[,]?\\s+(?:пожалуйста|сейчас)){0,2}[.!]?${'$'}", RegexOption.IGNORE_CASE)
        private const val VPN = "(?:vpn|впн|ви\\s+пи\\s+эн)"
        private const val HAPP = "(?:happ|хэпп)"
        private val disconnect = Regex("^(?:выключи|отключи)\\s+$VPN[.!]?${'$'}|^$VPN\\s+(?:выключи|отключи)[.!]?${'$'}", RegexOption.IGNORE_CASE)
        private val disconnectHapp = Regex("^(?:выключи|отключи)\\s+(?:$HAPP(?:\\s+$VPN)?|$VPN\\s+$HAPP)[.!]?${'$'}|^(?:$HAPP(?:\\s+$VPN)?|$VPN\\s+$HAPP)\\s+(?:выключи|отключи)[.!]?${'$'}", RegexOption.IGNORE_CASE)
        private val vpnMention = Regex("vpn|\\bhapp\\b|(?<![а-яё])(?:впн|хэпп|ви\\s+пи\\s+эн)(?![а-яё])", RegexOption.IGNORE_CASE)
        fun isExplicitRequest(text: String) = request.matches(text.trim())
        fun isDisconnectRequest(text: String) = disconnect.matches(text.trim()) || disconnectHapp.matches(text.trim())
        fun mentionsVpn(text: String) = vpnMention.containsMatchIn(text)
    }

    private fun snapshot(): List<VpnCandidate> {
        val candidates = backend.candidates()
        check(candidates.all { it.signerFingerprint.isNotBlank() }) { "Не удалось проверить подпись VPN-приложения." }
        check(candidates.groupBy { it.packageName }.values.all { it.distinct().size == 1 }) { "Не удалось определить VPN-приложение." }
        return candidates.distinctBy { it.packageName }
    }

    suspend fun ensureModelMayOpen(packageName: String) = withContext(main) {
        mutex.withLock {
            coroutineContext.ensureActive()
            ensureNotVpnForGenericOpen(backend, packageName)
        }
    }

    suspend fun begin(userRequest: String): VpnDispatch = withContext(main) {
        mutex.withLock {
            issued = emptyMap()
            coroutineContext.ensureActive()
            check(isExplicitRequest(userRequest)) { "Скажите явно: подключи VPN." }
            val candidates = snapshot()
            check(candidates.size <= 50) { "Слишком много видимых VPN-приложений для безопасного выбора." }
            coroutineContext.ensureActive()
            if (candidates.isEmpty()) return@withLock VpnDispatch.Message("Не найдены видимые VPN-приложения. Список может быть неполным.")
            if (candidates.size == 1 && candidates.single().supported) {
                val target = candidates.single()
                check(snapshot() == listOf(target)) { "Список VPN-приложений изменился." }
                coroutineContext.ensureActive()
                return@withLock launch(target)
            }
            issued = candidates.associateBy { UUID.randomUUID().toString() }
            val labels = VpnLabels.disambiguate(issued.values.map { it.label })
            VpnDispatch.Choice(issued.keys.zip(labels) { id, label -> VpnOption(id, label) })
        }
    }

    suspend fun disconnect(userRequest: String): VpnDispatch.Message = withContext(main) {
        mutex.withLock {
            issued = emptyMap()
            coroutineContext.ensureActive()
            check(isDisconnectRequest(userRequest)) { "Скажите явно: выключи VPN или выключи Happ VPN." }
            val candidates = snapshot()
            coroutineContext.ensureActive()
            val explicitHapp = disconnectHapp.matches(userRequest.trim())
            if (!explicitHapp && candidates.size > 1)
                return@withLock VpnDispatch.Message("Видно несколько VPN-приложений. Уточните: выключи Happ VPN.")
            val target = candidates.singleOrNull { it.packageName == VpnDiscovery.HAPP && it.supported }
                ?: return@withLock VpnDispatch.Message("Видимый поддерживаемый Happ не найден. Список VPN может быть неполным.")
            val refreshed = snapshot()
            check(refreshed.any { it == target } && (explicitHapp || refreshed == listOf(target))) { "Список VPN-приложений изменился. Повторите запрос." }
            coroutineContext.ensureActive()
            val sent = try { backend.disconnectHapp() } catch (_: SecurityException) { false }
                catch (_: android.content.ActivityNotFoundException) { false }
            check(sent) { "Действие отключения Happ недоступно." }
            val result = VpnDispatch.Message("Запрос на отключение отправлен в Happ. Состояние VPN не подтверждено; проверьте его в Happ.", actionSent = true)
            onDispatched(result.text)
            result
        }
    }

    suspend fun choose(optionId: String): VpnDispatch.Message = withContext(main) {
        mutex.withLock {
            val target = issued[optionId]
            issued = emptyMap() // A choice is single-use, including a failed choice.
            checkNotNull(target) { "Выбор VPN устарел." }
            coroutineContext.ensureActive()
            check(snapshot().any { it == target }) { "VPN-приложение больше недоступно." }
            coroutineContext.ensureActive()
            launch(target)
        }
    }

    suspend fun clear() = mutex.withLock { issued = emptyMap() }

    private fun launch(target: VpnCandidate): VpnDispatch.Message {
        val success = try {
            if (target.supported) backend.connectHapp() else backend.open(target.packageName)
        } catch (_: SecurityException) { false }
          catch (_: android.content.ActivityNotFoundException) { false }
        check(success) { "VPN-приложение или его действие недоступно." }
        val result = if (target.supported) VpnDispatch.Message("Запрос отправлен в Happ. Подключение не подтверждено; проверьте его в Happ. Другой VPN мог быть прерван. Список видимых VPN может быть неполным.", actionSent = true)
        else VpnDispatch.Message("Запрос на открытие ${target.label} отправлен. Приложение может подключиться автоматически и прервать другой VPN; подключение не подтверждено.", actionSent = true)
        onDispatched(result.text)
        return result
    }
}
