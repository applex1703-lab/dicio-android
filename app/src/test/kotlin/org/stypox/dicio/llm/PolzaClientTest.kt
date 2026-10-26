package org.stypox.dicio.llm

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import io.kotest.matchers.shouldBe

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
    "successful HTTP response is decoded without contacting provider" {
        val fake = OkHttpClient.Builder().addInterceptor { chain ->
            chain.request().url.host shouldBe "routerai.ru"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body("""{"choices":[{"finish_reason":"stop","message":{"content":"test answer"}}]}""".toResponseBody()).build()
        }.build()
        PolzaClient(fake).answer(PolzaSettings.Config(true, "test/model", "fake-key"), "hello") shouldBe "test answer"
    }
    "HTTP failures are surfaced and never retried by client" {
        var calls = 0
        val fake = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(429)
                .message("Too many requests").body("private provider error".toResponseBody()).build()
        }.build()
        shouldThrow<IllegalStateException> {
            PolzaClient(fake).answer(PolzaSettings.Config(true, "test/model", "fake-key"), "hello")
        }.message shouldBe PolzaProtocol.httpError(429)
        calls shouldBe 1
    }
})
