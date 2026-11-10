package org.stypox.dicio.llm

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class HappConnectTest : StringSpec({
    val command = "подключи Happ VPN"
    "only explicit positive requests pass the local gate" {
        for (text in listOf(command, "включи впн happ", "connect happ", "пожалуйста, подключи сейчас хэпп", "включи хэпп впн, пожалуйста сейчас"))
            HappConnect.isExplicitRequest(text) shouldBe true
        for (text in listOf("подключить happ", "пожалуйста подключить happ", "отключи happ", "переключи happ", "не подключай happ", "не подключи happ", "можно подключить happ?", "подключи happ и отключи vpn", "я предлагаю включи happ", "не надо включи happ", "подключи happ?"))
            HappConnect.isExplicitRequest(text) shouldBe false
    }
    "executor independently rejects unapproved requests" {
        var checks = 0
        val launcher = object : HappLauncher {
            override fun available(): Boolean { checks++; return true }
            override fun launch() { error("must not launch") }
        }
        shouldThrow<IllegalStateException> { runBlocking { HappConnect.execute("не подключи happ", launcher) } }
        checks shouldBe 0
    }
    "unavailable component fails before launch" {
        var calls = 0
        val launcher = object : HappLauncher {
            override fun available() = false
            override fun launch() { calls++ }
        }
        shouldThrow<IllegalStateException> { runBlocking { HappConnect.execute(command, launcher) } }
        calls shouldBe 0
    }
    "explicit command dispatches once without claiming connection" {
        var calls = 0
        val launcher = object : HappLauncher {
            override fun available() = true
            override fun launch() { calls++ }
        }
        val result = runBlocking { HappConnect.execute(command, launcher) }
        calls shouldBe 1
        result.text.contains("подтвердить не удалось") shouldBe true
    }
    "new Happ commands use the same gate as execution" {
        var calls = 0
        val launcher = object : HappLauncher {
            override fun available() = true
            override fun launch() { calls++ }
        }
        for (text in listOf("вруби happ", "запусти VPN Happ", "запусти хэпп ви пи эн")) {
            HappConnect.isExplicitRequest(text) shouldBe true
            runBlocking { HappConnect.execute(text, launcher) }
        }
        calls shouldBe 3
        for (text in listOf("запусти vpn", "открой happ", "не вруби happ", "запусти happ?", "запусти happ и музыку", "найди запусти happ")) {
            HappConnect.isExplicitRequest(text) shouldBe false
            shouldThrow<IllegalStateException> { runBlocking { HappConnect.execute(text, launcher) } }
        }
        calls shouldBe 3
    }
    "selected action dispatches once without requiring a phrase template" {
        var calls = 0
        val launcher = object : HappLauncher {
            override fun available() = true
            override fun launch() { calls++ }
        }
        val result = runBlocking { HappConnect.executeDecision(launcher) }
        calls shouldBe 1
        result.text.contains("подтвердить не удалось") shouldBe true
    }
    "selected action still fails when the handler is unavailable" {
        var calls = 0
        val launcher = object : HappLauncher {
            override fun available() = false
            override fun launch() { calls++ }
        }
        shouldThrow<IllegalStateException> { runBlocking { HappConnect.executeDecision(launcher) } }
        calls shouldBe 0
    }
    "handler selection requires one exact accessible non UI match" {
        val handler = HappHandler("com.happproxy", "com.happproxy.ConnectActivity", true, true, true, true)
        HappHandlerPolicy.select(listOf(handler)) shouldBe handler
        for (rejected in listOf(
            handler.copy(packageName = "other"), handler.copy(className = ""),
            handler.copy(className = "com.happproxy.MainActivity"),
            handler.copy(targetClassName = "com.happproxy.MainActivity"),
            handler.copy(exported = false), handler.copy(enabled = false),
            handler.copy(permitted = false), handler.copy(exactAuthority = false)
        )) HappHandlerPolicy.select(listOf(rejected)) shouldBe null
        HappHandlerPolicy.select(emptyList()) shouldBe null
        HappHandlerPolicy.select(listOf(handler, handler.copy(className = "com.happproxy.OtherActivity"))) shouldBe null
    }
    "launch failure is not retried or reported as success" {
        var calls = 0
        val launcher = object : HappLauncher {
            override fun available() = true
            override fun launch() { calls++; throw SecurityException() }
        }
        shouldThrow<IllegalStateException> { runBlocking { HappConnect.execute(command, launcher) } }
        calls shouldBe 1
    }
    "cancellation after capability check prevents dispatch" {
        var calls = 0
        val job = Job()
        val launcher = object : HappLauncher {
            override fun available(): Boolean { job.cancel(); return true }
            override fun launch() { calls++ }
        }
        shouldThrow<CancellationException> { runBlocking { withContext(job) { HappConnect.execute(command, launcher) } } }
        calls shouldBe 0
    }
    "cancellation before capability check prevents all work" {
        var checks = 0
        val job = Job().apply { cancel() }
        val launcher = object : HappLauncher {
            override fun available(): Boolean { checks++; return true }
            override fun launch() { error("must not launch") }
        }
        shouldThrow<CancellationException> { runBlocking { withContext(job) { HappConnect.execute(command, launcher) } } }
        checks shouldBe 0
    }
})
