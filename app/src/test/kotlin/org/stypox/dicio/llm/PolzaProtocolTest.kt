package org.stypox.dicio.llm

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*

class PolzaProtocolTest : StringSpec({
    fun response(content: String, finish: String = "stop") =
        """{"choices":[{"finish_reason":"$finish","message":{"content":$content}}]}"""

    "complete text is returned" {
        PolzaProtocol.answer(response("\" Привет! \"")) shouldBe "Привет!"
    }
    "malformed or empty choices are rejected" {
        for (body in listOf("not json", "{}", "{\"choices\":[]}")) {
            shouldThrow<IllegalStateException> { PolzaProtocol.answer(body) }
        }
    }
    "content must be a nonempty string" {
        for (content in listOf("null", "123", "[]", "{}", "\" \"")) {
            shouldThrow<IllegalStateException> { PolzaProtocol.answer(response(content)) }
        }
    }
    "truncated and tool responses are not spoken as complete answers" {
        for (reason in listOf("length", "tool_calls", "content_filter")) {
            shouldThrow<IllegalStateException> { PolzaProtocol.answer(response("\"partial\"", reason)) }
        }
        shouldThrow<IllegalStateException> {
            PolzaProtocol.answer("""{"choices":[{"finish_reason":"stop","message":{"content":"text","tool_calls":[{}]}}]}""")
        }
    }
    "request escapes text and never embeds credentials" {
        val text = "Русский текст: \"цитата\"\nстрока"
        val json = Json.parseToJsonElement(PolzaProtocol.request("test/model", text)).jsonObject
        json["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonPrimitive.content shouldBe text
        json["max_tokens"]!!.jsonPrimitive.int shouldBe 512
        json.containsKey("tools") shouldBe false
    }
    "configuration cannot reveal credentials through toString" {
        PolzaSettings.Config(true, "test/model", "SECRET").toString().contains("SECRET") shouldBe false
    }
    "invalid header input is rejected before request construction" {
        shouldThrow<IllegalArgumentException> {
            PolzaProtocol.validateInput(PolzaSettings.Config(true, "test/model", "key\nsecret"), "hello")
        }
    }
    "provider status errors remain distinguishable" {
        PolzaProtocol.httpError(402).contains("средств") shouldBe true
        PolzaProtocol.httpError(429).contains("лимит") shouldBe true
        PolzaProtocol.httpError(504).contains("ожидания") shouldBe true
    }
})
