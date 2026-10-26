package org.stypox.dicio.llm

import kotlinx.serialization.json.*

/** Pure protocol validation: never include remote payloads in exceptions or logs. */
internal object PolzaProtocol {
    const val MAX_RESPONSE_BYTES = 128 * 1024L

    fun validateInput(config: PolzaSettings.Config, text: String) {
        require(config.apiKey.isNotBlank()) { "Укажите API-ключ RouterAI в настройках." }
        require(config.apiKey.all { it.code in 33..126 }) { "API-ключ содержит недопустимые символы." }
        require(config.model.isNotBlank() && config.model.length <= 200) { "Укажите корректный ID модели RouterAI." }
        require(text.isNotBlank() && text.length <= 8000) { "Запрос пустой или слишком длинный." }
    }

    fun request(model: String, text: String): String = buildJsonObject {
        put("model", model)
        put("max_tokens", 512)
        put("messages", buildJsonArray {
            add(buildJsonObject {
                put("role", "system")
                put("content", "Ты голосовой помощник на телевизоре. Отвечай на языке пользователя. " +
                    "По умолчанию дай прямой ответ в 1–2 коротких предложениях, не более 40 слов. " +
                    "Подробности давай только по явной просьбе. Пиши обычным текстом для озвучки: " +
                    "без Markdown, списков, формул, ссылок и эмодзи. " +
                    "У тебя пока нет инструментов управления устройством. Не утверждай, что открыл " +
                    "приложение или выполнил действие. Не выдумывай текущее время и свежие данные.")
            })
            add(buildJsonObject { put("role", "user"); put("content", text) })
        })
    }.toString()

    fun httpError(code: Int): String = when (code) {
        401, 403 -> "RouterAI: проверьте ключ и права доступа."
        402 -> "RouterAI: недостаточно средств."
        408, 504 -> "RouterAI: время ожидания истекло."
        429 -> "RouterAI: превышен лимит запросов."
        else -> "RouterAI: ошибка HTTP $code."
    }

    fun answer(body: String): String {
        val choice = try {
            Json.parseToJsonElement(body).jsonObject["choices"]!!.jsonArray.first().jsonObject
        } catch (_: Exception) {
            throw IllegalStateException("RouterAI: неверный формат ответа.")
        }
        check((choice["finish_reason"] as? JsonPrimitive)?.content == "stop") {
            "RouterAI: ответ не завершён или отклонён. Повторите запрос."
        }
        val message = choice["message"] as? JsonObject
            ?: error("RouterAI: неверный формат сообщения.")
        check(message["tool_calls"] == null || message["tool_calls"] == JsonNull) {
            "Вызовы инструментов пока не поддерживаются."
        }
        check(message["function_call"] == null || message["function_call"] == JsonNull) {
            "Вызовы функций пока не поддерживаются."
        }
        val content = message["content"] as? JsonPrimitive
        check(content != null && content.isString && content.content.isNotBlank()) {
            "RouterAI: нет текста ответа."
        }
        return content.content.trim()
    }
}
