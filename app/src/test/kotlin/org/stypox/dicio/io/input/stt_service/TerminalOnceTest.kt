package org.stypox.dicio.io.input.stt_service

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class TerminalOnceTest : StringSpec({
    "a synchronous terminal event suppresses a subsequent start failure or revocation" {
        val terminal = TerminalOnce()
        terminal.finish() shouldBe true
        terminal.finished shouldBe true
        terminal.finish() shouldBe false
        terminal.finish() shouldBe false
    }
    "a start failure is terminal even without an input event" {
        val terminal = TerminalOnce()
        terminal.finish() shouldBe true
        terminal.finish() shouldBe false
    }
})
