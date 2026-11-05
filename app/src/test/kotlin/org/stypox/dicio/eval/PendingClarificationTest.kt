package org.stypox.dicio.eval

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.stypox.dicio.llm.AssistantIntent
import org.stypox.dicio.llm.ClarificationContext
import org.stypox.dicio.llm.SettingsSection

class PendingClarificationTest : StringSpec({
    "one use, replacement, expiry and cancellation" {
        val job = Job()
        var time = 100L
        val pending = PendingClarification(CoroutineScope(job + Dispatchers.Unconfined)) { time }
        pending.save("first", "question")
        pending.save("second", "new question")
        pending.consume()?.context shouldBe ClarificationContext("second", "new question")
        pending.consume() shouldBe null
        pending.save("first", "question")
        time += 59_999
        pending.consume()?.context shouldBe ClarificationContext("first", "question")
        pending.save("first", "question")
        time += 60_000
        pending.isAvailable() shouldBe false
        pending.consume() shouldBe null
        pending.save("first", "question")
        pending.clear()
        pending.consume() shouldBe null
        pending.save("x".repeat(8001), "question")
        pending.consume() shouldBe null
        job.cancel()
    }
    "context never authorizes a device action" {
        AssistantIntent.Reply("ok").allowedAfterClarification() shouldBe true
        AssistantIntent.Clarify("question").allowedAfterClarification() shouldBe true
        AssistantIntent.OpenApp("app_0").allowedAfterClarification() shouldBe false
        AssistantIntent.ConnectHapp.allowedAfterClarification() shouldBe false
        AssistantIntent.ChooseSearch("query").allowedAfterClarification() shouldBe false
        AssistantIntent.Time.allowedAfterClarification() shouldBe false
    }
    "consumed context can expire while preparing the network request" {
        val job = Job()
        var time = 10L
        val pending = PendingClarification(CoroutineScope(job + Dispatchers.Unconfined)) { time }
        pending.save("request", "question")
        time += 59_999
        val snapshot = checkNotNull(pending.consume())
        snapshot.isFresh(time) shouldBe true
        time++
        snapshot.isFresh(time) shouldBe false
        pending.consume() shouldBe null
        job.cancel()
    }
    "each clarification replaces the consumed pair and starts a fresh one-use TTL" {
        val job = Job()
        var time = 100L
        val pending = PendingClarification(CoroutineScope(job + Dispatchers.Unconfined)) { time }
        pending.save("original request", "first question")
        time += 59_999
        pending.consume()?.context shouldBe ClarificationContext("original request", "first question")
        pending.save("short answer", "second question")
        time += 59_999
        pending.consume()?.context shouldBe ClarificationContext("short answer", "second question")
        pending.consume() shouldBe null
        pending.save("next answer", "third question")
        time += 60_000
        pending.consume() shouldBe null
        job.cancel()
    }
    "only unambiguous cancellation phrases are intercepted" {
        isClarificationCancellation(" Отмена! ") shouldBe true
        isClarificationCancellation("cancel") shouldBe true
        isClarificationCancellation("отмени подписку") shouldBe false
    }
    "complete commands bind to specific settings or one fresh app" {
        val apps = listOf(StandaloneApp("app_1", "YouTube"), StandaloneApp("app_2", "Rutube"))
        for ((text, section) in listOf("открой настройки Wi-Fi" to SettingsSection.WIFI,
            "Открой настройки Wi‑Fi!" to SettingsSection.WIFI,
            "пожалуйста, открой настройки вай-фай" to SettingsSection.WIFI,
            "открой настройки WiFi пожалуйста" to SettingsSection.WIFI,
            "открой настройки" to SettingsSection.GENERAL,
            "открой настройки приложений" to SettingsSection.APPS,
            "открой настройки изображения" to SettingsSection.DISPLAY,
            "открой настройки звука" to SettingsSection.SOUND)) {
            standaloneCommand(text, apps) shouldBe StandaloneCommand.Settings(section)
        }
        standaloneCommand("открой YouTube", apps) shouldBe StandaloneCommand.App("app_1")
        standaloneCommand("открой приложение youtube!", apps) shouldBe StandaloneCommand.App("app_1")
        for (text in listOf("нет, не открывай настройки Wi-Fi", "не открой настройки Wi-Fi",
            "не открывай настройки Wi-Fi", "открой настройки Wi-Fi?", "можешь открыть настройки Wi-Fi",
            "зачем открой настройки Wi-Fi", "настройки Wi-Fi", "Wi-Fi", "да", "нет",
            "открой настройки Wi-Fi и подключи VPN", "открой YouTube и Rutube",
            "открой YouTube?", "нет, не открывай YouTube", "открой неизвестное",
            "подключи VPN", "подключи Happ VPN", "открой настройки Wi-Fi и не открывай")) {
            standaloneCommand(text, apps) shouldBe null
        }
        val previous = ClarificationSnapshot(ClarificationContext("старый запрос", "старый вопрос"), 0L)
        val independent = resolveClarificationInput(previous, "открой YouTube", apps)
        independent.context shouldBe null
        independent.standalone shouldBe StandaloneCommand.App("app_1")
        val answer = resolveClarificationInput(previous, "Wi-Fi", apps)
        answer.context shouldBe previous
        answer.standalone shouldBe null
        resolveClarificationInput(null, "открой YouTube", apps).standalone shouldBe null
    }
    "ambiguous or opaque catalog names never authorize an action" {
        val previous = ClarificationSnapshot(ClarificationContext("old", "question"), 0L)
        val duplicates = listOf(StandaloneApp("app_1", "YouTube"), StandaloneApp("app_2", "youtube"))
        val ambiguous = resolveClarificationInput(previous, "открой YouTube", duplicates)
        ambiguous.context shouldBe previous
        ambiguous.standalone shouldBe null
        standaloneCommand("открой My App", listOf(StandaloneApp("app_3", "My App"))) shouldBe null
        standaloneCommand("открой YouТube", listOf(StandaloneApp("app_3", "YouТube"))) shouldBe null
        standaloneCommand("открой YouTube", listOf(StandaloneApp("a", "YouTube"),
            StandaloneApp("b", "YouТube"))) shouldBe null
        standaloneCommand("открой YouTube", listOf(StandaloneApp("a", "YouTube"),
            StandaloneApp("b", "YouTube\u200b"))) shouldBe null
        standaloneCommand("открой настройки звука", listOf(StandaloneApp("app_4", "настройки звука"))) shouldBe null
        standaloneCommand("открой настройки", listOf(StandaloneApp("app_4", "настройки"))) shouldBe null
    }
    "bound command authorizes only its exact action and object" {
        val command = StandaloneCommand.Settings(SettingsSection.WIFI)
        command.allows(AssistantIntent.OpenSettings(SettingsSection.WIFI)) shouldBe true
        command.allows(AssistantIntent.Reply("ответ")) shouldBe true
        command.allows(AssistantIntent.Clarify("уточнение")) shouldBe true
        command.allows(AssistantIntent.OpenSettings(SettingsSection.GENERAL)) shouldBe false
        command.allows(AssistantIntent.OpenApp("app_0")) shouldBe false
        command.allows(AssistantIntent.ConnectHapp) shouldBe false
        command.allows(AssistantIntent.ChooseSearch("query")) shouldBe false
        command.allows(AssistantIntent.Time) shouldBe false
        val app = StandaloneCommand.App("app_1")
        app.allows(AssistantIntent.OpenApp("app_1")) shouldBe true
        app.allows(AssistantIntent.OpenApp("app_2")) shouldBe false
        app.allows(AssistantIntent.OpenSettings(SettingsSection.WIFI)) shouldBe false
        app.allows(AssistantIntent.ConnectHapp) shouldBe false
    }
})
