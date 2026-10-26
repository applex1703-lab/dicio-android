package org.stypox.dicio.llm

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*

class MediaCommandTest : StringSpec({
    val target = MediaTarget("media_0", "Player", "paused", setOf(MediaOperation.RESUME,
        MediaOperation.SEEK_RELATIVE, MediaOperation.SEEK_ABSOLUTE))
    fun decision(op: String = "RESUME", seconds: JsonElement = JsonPrimitive(0), id: String = "media_0") = buildJsonObject {
        put("action", "media"); put("app_id", ""); put("settings_section", "")
        put("text", ""); put("search_query", ""); put("media_id", id)
        put("media_operation", op); put("media_seconds", seconds)
    }.toString()

    "only snapshot session and supported operation can execute" {
        IntentProtocol.parse(decision(), emptySet(), listOf(target)) shouldBe
            AssistantIntent.Media(MediaCommand("media_0", MediaOperation.RESUME, 0))
        for (body in listOf(decision(id = "media_1"), decision(op = "PAUSE"), decision(op = "VOLUME"),
                decision(seconds = JsonPrimitive(1)), decision(seconds = JsonPrimitive("0")),
                decision(seconds = JsonPrimitive(0.5)), decision(seconds = JsonNull))) {
            shouldThrow<IllegalStateException> { IntentProtocol.parse(body, emptySet(), listOf(target)) }
        }
        shouldThrow<IllegalStateException> { IntentProtocol.parse(decision(), emptySet()) }
    }
    "seek parameters are bounded integer seconds" {
        for (seconds in listOf(-86401L, 86401L, Long.MAX_VALUE, 0L)) {
            shouldThrow<IllegalStateException> {
                IntentProtocol.parse(decision("SEEK_RELATIVE", JsonPrimitive(seconds)), emptySet(), listOf(target))
            }
        }
        shouldThrow<IllegalStateException> {
            IntentProtocol.parse(decision("SEEK_ABSOLUTE", JsonPrimitive(-1)), emptySet(), listOf(target))
        }
    }
    "relative seek clamps while absolute seek outside duration fails" {
        MediaSeek.destination(MediaOperation.SEEK_RELATIVE, -60, 10000, 120000) shouldBe 0L
        MediaSeek.destination(MediaOperation.SEEK_RELATIVE, 60, 100000, 120000) shouldBe 120000L
        MediaSeek.destination(MediaOperation.SEEK_ABSOLUTE, 60, 10000, 120000) shouldBe 60000L
        MediaSeek.destination(MediaOperation.SEEK_RELATIVE, 60, Long.MAX_VALUE, Long.MAX_VALUE) shouldBe Long.MAX_VALUE
        shouldThrow<IllegalStateException> { MediaSeek.destination(MediaOperation.SEEK_ABSOLUTE, 121, 0, 120000) }
        shouldThrow<IllegalStateException> { MediaSeek.destination(MediaOperation.SEEK_RELATIVE, 10, 0, 0) }
        shouldThrow<IllegalStateException> { MediaSeek.destination(MediaOperation.SEEK_RELATIVE, 10, -1, 100000) }
    }
    "media fields cannot hitchhike on other actions" {
        val body = Json.parseToJsonElement(decision()).jsonObject
        shouldThrow<IllegalStateException> {
            IntentProtocol.parse(JsonObject(body + ("action" to JsonPrimitive("get_time"))).toString(), emptySet(), listOf(target))
        }
    }
    "provider sees application and capabilities but no package token title or notification" {
        val payload = Json.parseToJsonElement(IntentProtocol.request("model", "продолжай", emptyMap(), listOf(target))).jsonObject
        val user = Json.parseToJsonElement(payload["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonPrimitive.content).jsonObject
        val session = user["media_sessions"]!!.jsonArray.single().jsonObject
        session.keys shouldBe setOf("id", "app", "state", "operations")
        session["id"]!!.jsonPrimitive.content shouldBe "media_0"
    }
})
