package org.stypox.dicio.llm

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import okhttp3.OkHttpClient

class PolzaClientTest : StringSpec({
    val client = PolzaClient(OkHttpClient())
    "missing key is rejected before sending a request" {
        shouldThrow<IllegalArgumentException> {
            client.answer(PolzaSettings.Config(true, "test/model", ""), "hello")
        }
    }
    "missing model is rejected before sending a request" {
        shouldThrow<IllegalArgumentException> {
            client.answer(PolzaSettings.Config(true, "", "test-key"), "hello")
        }
    }
    "oversized input is rejected before sending a request" {
        shouldThrow<IllegalArgumentException> {
            client.answer(PolzaSettings.Config(true, "test/model", "test-key"), "a".repeat(8001))
        }
    }
})
