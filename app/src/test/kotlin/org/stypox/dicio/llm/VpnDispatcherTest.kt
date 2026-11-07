package org.stypox.dicio.llm

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

class VpnDispatcherTest : StringSpec({
    class Fake : VpnBackend {
        var entries = emptyList<VpnCandidate>()
        var available = true
        var signatureError = false
        var targetReadError = false
        var calls = mutableListOf<String>()
        override fun candidates(): List<VpnCandidate> {
            check(!signatureError) { "Не удалось проверить подпись VPN-приложения." }
            return entries
        }
        override fun isVpnPackage(packageName: String): Boolean = isSelectedVpnPackage(packageName) { target ->
            check(!targetReadError) { "Ошибка чтения выбранного пакета." }
            if (entries.any { it.packageName == target }) listOf("android.permission.BIND_VPN_SERVICE") else emptyList()
        }
        override fun connectHapp(): Boolean { calls.add("happ"); return available }
        override fun disconnectHapp(): Boolean { calls.add("disconnect"); return available }
        override fun open(packageName: String): Boolean { calls.add(packageName); return available }
    }
    val happ = VpnCandidate("com.happproxy", "Happ", true, "signer-a")
    val other = VpnCandidate("example.vpn", "Other", false, "signer-b")
    fun dispatcher(fake: Fake) = VpnDispatcher(fake, Dispatchers.Unconfined)

    "disconnect targets only Happ and never opens another VPN" {
        val fake = Fake().apply { entries = listOf(happ) }
        val result = runBlocking { dispatcher(fake).disconnect("VPN выключи") }
        result.actionSent shouldBe true
        result.text.contains("не подтверждено") shouldBe true
        fake.calls shouldBe listOf("disconnect")
        fake.calls.clear()
        fake.entries = listOf(happ, other)
        runBlocking { dispatcher(fake).disconnect("выключи VPN") }.actionSent shouldBe false
        fake.calls shouldBe emptyList()
        runBlocking { dispatcher(fake).disconnect("выключи Happ VPN") }.actionSent shouldBe true
        fake.calls shouldBe listOf("disconnect")
        fake.calls.clear()
        fake.entries = listOf(other)
        runBlocking { dispatcher(fake).disconnect("отключи VPN") }.actionSent shouldBe false
        fake.calls shouldBe emptyList()
        fake.entries = emptyList()
        runBlocking { dispatcher(fake).disconnect("отключи Happ") }.actionSent shouldBe false
        fake.calls shouldBe emptyList()
    }
    "disconnect fails closed on unavailable integration and cancellation" {
        val fake = Fake().apply { entries = listOf(happ); available = false }
        shouldThrow<IllegalStateException> { runBlocking { dispatcher(fake).disconnect("отключи VPN") } }
        fake.calls shouldBe listOf("disconnect")
        fake.calls.clear()
        val job = Job().apply { cancel() }
        shouldThrow<CancellationException> { runBlocking { withContext(job) { dispatcher(fake).disconnect("отключи VPN") } } }
        fake.calls shouldBe emptyList()
        shouldThrow<IllegalStateException> { runBlocking { dispatcher(fake).disconnect("не выключи VPN") } }
        fake.calls shouldBe emptyList()
    }

    "only a direct positive general command passes" {
        for (s in listOf("подключи VPN", "включи впн пожалуйста", "connect vpn")) VpnDispatcher.isExplicitRequest(s) shouldBe true
        for (s in listOf("не подключи vpn", "подключи vpn?", "отключи vpn", "подключи vpn и отключи другой", "подключить vpn", "подключи Happ VPN")) VpnDispatcher.isExplicitRequest(s) shouldBe false
        val fake = Fake()
        shouldThrow<IllegalStateException> { runBlocking { dispatcher(fake).begin("не подключи vpn") } }
        fake.calls.size shouldBe 0
    }
    "zero candidates reports incomplete visibility" {
        val fake = Fake()
        val result = runBlocking { dispatcher(fake).begin("подключи vpn") } as VpnDispatch.Message
        result.text.contains("неполн") shouldBe true
        result.actionSent shouldBe false
        fake.calls.size shouldBe 0
    }
    "single supported candidate sends request without claiming success" {
        val fake = Fake().apply { entries = listOf(happ) }
        val result = runBlocking { dispatcher(fake).begin("подключи vpn") } as VpnDispatch.Message
        fake.calls shouldBe listOf("happ")
        result.text.contains("не подтверждено") shouldBe true
        result.text.contains("список видимых vpn может быть неполным", ignoreCase = true) shouldBe true
        result.actionSent shouldBe true
    }
    "single unsupported candidate requires an explicit card selection" {
        val fake = Fake().apply { entries = listOf(other) }
        val dispatcher = dispatcher(fake)
        val choice = runBlocking { dispatcher.begin("подключи vpn") } as VpnDispatch.Choice
        fake.calls.size shouldBe 0
        val result = runBlocking { dispatcher.choose(choice.options.single().id) }
        fake.calls shouldBe listOf(other.packageName)
        result.text.contains("не подтверждено") shouldBe true
        result.actionSent shouldBe true
    }
    "multiple candidates require a single use choice and revalidation" {
        val fake = Fake().apply { entries = listOf(happ, other) }
        val dispatcher = dispatcher(fake)
        val choice = runBlocking { dispatcher.begin("подключи vpn") } as VpnDispatch.Choice
        choice.options.size shouldBe 2
        choice.prompt.contains("неполн") shouldBe true
        fake.calls.size shouldBe 0
        fake.entries = listOf(other)
        shouldThrow<IllegalStateException> { runBlocking { dispatcher.choose(choice.options.first().id) } }
        shouldThrow<IllegalStateException> { runBlocking { dispatcher.choose(choice.options.first().id) } }
        fake.calls.size shouldBe 0
    }
    "unavailable integration never reports success" {
        val fake = Fake().apply { entries = listOf(happ); available = false }
        shouldThrow<IllegalStateException> { runBlocking { dispatcher(fake).begin("подключи vpn") } }
        fake.calls shouldBe listOf("happ")
    }
    "cleared or forged option cannot launch" {
        val fake = Fake().apply { entries = listOf(other) }
        val dispatcher = dispatcher(fake)
        val choice = runBlocking { dispatcher.begin("подключи vpn") } as VpnDispatch.Choice
        runBlocking { dispatcher.clear() }
        shouldThrow<IllegalStateException> { runBlocking { dispatcher.choose(choice.options.single().id) } }
        shouldThrow<IllegalStateException> { runBlocking { dispatcher.choose("forged") } }
        fake.calls.size shouldBe 0
    }
    "same package and label with a different signer is rejected" {
        val fake = Fake().apply { entries = listOf(happ, other) }
        val dispatcher = dispatcher(fake)
        val choice = runBlocking { dispatcher.begin("подключи vpn") } as VpnDispatch.Choice
        fake.entries = listOf(happ.copy(signerFingerprint = "replacement"), other)
        shouldThrow<IllegalStateException> { runBlocking { dispatcher.choose(choice.options.first().id) } }
        fake.calls.size shouldBe 0
    }
    "missing signer fails closed without silently choosing another VPN" {
        val fake = Fake().apply { entries = listOf(happ, other.copy(signerFingerprint = "")) }
        shouldThrow<IllegalStateException> { runBlocking { dispatcher(fake).begin("подключи vpn") } }
        fake.calls.size shouldBe 0
        fake.entries = listOf(happ, other)
        val dispatcher = dispatcher(fake)
        val choice = runBlocking { dispatcher.begin("подключи vpn") } as VpnDispatch.Choice
        fake.entries = listOf(happ.copy(signerFingerprint = ""), other)
        shouldThrow<IllegalStateException> { runBlocking { dispatcher.choose(choice.options.first().id) } }
        fake.calls.size shouldBe 0
    }
    "new request invalidates previous card" {
        val fake = Fake().apply { entries = listOf(other) }
        val dispatcher = dispatcher(fake)
        val old = runBlocking { dispatcher.begin("подключи vpn") } as VpnDispatch.Choice
        runBlocking { dispatcher.begin("подключи vpn") }
        shouldThrow<IllegalStateException> { runBlocking { dispatcher.choose(old.options.single().id) } }
        fake.calls.size shouldBe 0
    }
    "signature lookup error after card presentation prevents launch" {
        val fake = Fake().apply { entries = listOf(happ, other) }
        val dispatcher = dispatcher(fake)
        val choice = runBlocking { dispatcher.begin("подключи vpn") } as VpnDispatch.Choice
        fake.signatureError = true
        shouldThrow<IllegalStateException> { runBlocking { dispatcher.choose(choice.options.first().id) } }
        fake.signatureError = false
        shouldThrow<IllegalStateException> { runBlocking { dispatcher.choose(choice.options.first().id) } }
        fake.calls.size shouldBe 0
    }
    "fingerprint is stable for reordered signer sets and rejects missing certificates" {
        val a = byteArrayOf(1, 2); val b = byteArrayOf(3, 4)
        VpnSigners.fingerprint(listOf(a, b)) shouldBe VpnSigners.fingerprint(listOf(b, a))
        (VpnSigners.fingerprint(listOf(a)) == VpnSigners.fingerprint(listOf(b))) shouldBe false
        shouldThrow<IllegalStateException> { VpnSigners.fingerprint(emptyList()) }
        shouldThrow<IllegalStateException> { VpnSigners.fingerprint(listOf(byteArrayOf())) }
    }
    "installed Happ fallback survives absent non-exported service in query" {
        VpnDiscovery.packages(listOf(other.packageName), true) shouldBe setOf(other.packageName, happ.packageName)
        VpnDiscovery.packages(emptyList(), false) shouldBe emptySet()
        VpnDiscovery.packages(listOf(happ.packageName), true) shouldBe setOf(happ.packageName)
    }
    "cards display sanitized distinct app names without package or signer data" {
        val fake = Fake().apply { entries = listOf(
            happ.copy(label = " Happ\u202e "),
            other.copy(label = "happ"),
            VpnCandidate("private.third", "Happ (1)", false, "secret-signature")
        ) }
        val choice = runBlocking { dispatcher(fake).begin("подключи vpn") } as VpnDispatch.Choice
        val labels = choice.options.map { it.label }
        labels shouldBe listOf("Happ (1)", "happ (2)", "Happ (1) (1)")
        labels.any { it.contains("private.third") || it.contains("secret-signature") } shouldBe false
        choice.options.map { it.id }.distinct().size shouldBe 3
    }
    "normal single app card displays its name" {
        val fake = Fake().apply { entries = listOf(other) }
        val choice = runBlocking { dispatcher(fake).begin("подключи vpn") } as VpnDispatch.Choice
        choice.options.single().label shouldBe "Other"
    }
    "same dispatcher begins chooses and clears independent successive choices" {
        val fake = Fake().apply { entries = listOf(happ, other) }
        val dispatcher = dispatcher(fake)
        val first = runBlocking { dispatcher.begin("подключи vpn") } as VpnDispatch.Choice
        val result = runBlocking { dispatcher.choose(first.options.first().id) }
        result.text.contains("не подтверждено") shouldBe true
        fake.calls shouldBe listOf("happ")
        val second = runBlocking { dispatcher.begin("подключи vpn") } as VpnDispatch.Choice
        runBlocking { dispatcher.clear() }
        shouldThrow<IllegalStateException> { runBlocking { dispatcher.choose(second.options.last().id) } }
        fake.calls shouldBe listOf("happ")
    }
    "model cannot open identified VPN regardless of request wording" {
        val fake = Fake().apply { entries = listOf(happ, other) }
        val dispatcher = dispatcher(fake)
        for (request in listOf("не открывай", "можно открыть?", "любая транслитерация")) {
            request.isNotEmpty() shouldBe true
            shouldThrow<IllegalStateException> { runBlocking { dispatcher.ensureModelMayOpen(other.packageName) } }
        }
        runBlocking { dispatcher.ensureModelMayOpen("ordinary.app") }
        fake.signatureError = true
        runBlocking { dispatcher.ensureModelMayOpen("ordinary.app") }
        fake.targetReadError = true
        shouldThrow<IllegalStateException> { runBlocking { dispatcher.ensureModelMayOpen("ordinary.app") } }
        fake.calls.size shouldBe 0
    }
    "dispatch status survives clearing the choice but records no failed launch" {
        val fake = Fake().apply { entries = listOf(happ) }
        val status = VpnDispatchStatus()
        val dispatcher = VpnDispatcher(fake, Dispatchers.Unconfined, status::record)
        runBlocking { dispatcher.begin("подключи vpn") }
        status.state.value!!.contains("не подтверждено") shouldBe true
        runBlocking { dispatcher.clear() }
        status.state.value!!.contains("не подтверждено") shouldBe true
        status.clear()
        fake.available = false
        shouldThrow<IllegalStateException> { runBlocking { dispatcher.begin("подключи vpn") } }
        status.state.value shouldBe null
    }
    "generic open checks the selected package and fails closed on errors" {
        val fake = Fake().apply { entries = listOf(other) }
        shouldThrow<VpnOpenBlockedException> { ensureNotVpnForGenericOpen(fake, other.packageName) }
        ensureNotVpnForGenericOpen(fake, "ordinary.application")
        fake.signatureError = true
        ensureNotVpnForGenericOpen(fake, "ordinary.application")
        fake.targetReadError = true
        shouldThrow<IllegalStateException> { ensureNotVpnForGenericOpen(fake, "ordinary.application") }
        fake.calls.size shouldBe 0
    }
    "last dispatch shows local timestamp and explicitly is not VPN state" {
        val status = VpnDispatchStatus { LocalDateTime.of(2026, 11, 6, 9, 8, 7) }
        status.record("Запрос отправлен; подключение не подтверждено.")
        status.state.value!!.contains("06.11.2026 09:08:07") shouldBe true
        status.state.value!!.contains("это не состояние VPN") shouldBe true
        status.clear()
        status.state.value shouldBe null
    }
    "selected package classifier reads only target and fails closed on target errors" {
        val reads = mutableListOf<String>()
        fun inspect(pkg: String): List<String?> {
            reads.add(pkg)
            return when (pkg) {
                "normal" -> listOf(null, "other.permission")
                "vpn" -> listOf("android.permission.BIND_VPN_SERVICE")
                else -> error("target unavailable")
            }
        }
        isSelectedVpnPackage("normal", ::inspect) shouldBe false
        reads shouldBe listOf("normal")
        isSelectedVpnPackage("vpn", ::inspect) shouldBe true
        isSelectedVpnPackage("com.happproxy", ::inspect) shouldBe true
        reads shouldBe listOf("normal", "vpn")
        shouldThrow<IllegalStateException> { isSelectedVpnPackage("missing", ::inspect) }
    }
    "opening unsupported application can trigger automatic connection" {
        val fake = Fake().apply { entries = listOf(other) }
        val dispatcher = dispatcher(fake)
        val choice = runBlocking { dispatcher.begin("подключи vpn") } as VpnDispatch.Choice
        val result = runBlocking { dispatcher.choose(choice.options.single().id) }
        result.text.contains("автоматически") shouldBe true
        result.text.contains("прервать другой VPN") shouldBe true
        result.text.contains("не подтверждено") shouldBe true
    }
})
