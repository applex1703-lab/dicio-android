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
