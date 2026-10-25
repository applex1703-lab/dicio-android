package org.stypox.dicio.llm

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Chat Completions transport adapted from PR #370; no tools or Android actions are exposed. */
@Singleton
class PolzaClient @Inject constructor(client: OkHttpClient) {
    private val http = client.newBuilder().callTimeout(40, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(35, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()

    suspend fun answer(config: PolzaSettings.Config, text: String): String {
        require(config.apiKey.isNotBlank()) { "Укажите API-ключ polza.ai в настройках." }
        require(config.model.isNotBlank()) { "Укажите ID модели polza.ai в настройках." }
        require(text.length <= 8000) { "Запрос слишком длинный." }
        val payload = JSONObject().put("model", config.model).put("max_tokens", 512)
            .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content",
                "Ты голосовой помощник на телевизоре. Отвечай кратко на языке пользователя. " +
                    "У тебя пока нет инструментов управления устройством. Не утверждай, что открыл " +
                    "приложение или выполнил действие. Не выдумывай текущее время и свежие данные."))
                .put(JSONObject().put("role", "user").put("content", text)))
        val request = Request.Builder().url("https://polza.ai/api/v1/chat/completions")
            .header("Authorization", "Bearer ${config.apiKey}")
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(
                        IOException("Не удалось связаться с polza.ai. Проверьте сеть или повторите позже."))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val answer = response.use {
                            check(it.isSuccessful) { when (it.code) {
                                401, 403 -> "polza.ai: проверьте ключ и права доступа."
                                402 -> "polza.ai: недостаточно средств."
                                429 -> "polza.ai: превышен лимит запросов."
                                else -> "polza.ai: ошибка HTTP ${it.code}."
                            } }
                            val body = it.body ?: error("polza.ai: пустой ответ.")
                            val source = body.source()
                            source.request(128 * 1024L + 1)
                            check(source.buffer.size <= 128 * 1024L) { "polza.ai: ответ слишком большой." }
                            val message = JSONObject(source.readUtf8()).getJSONArray("choices")
                                .getJSONObject(0).getJSONObject("message")
                            check(message.isNull("tool_calls")) { "Вызовы инструментов пока не поддерживаются." }
                            message.optString("content").trim().also { value ->
                                check(value.isNotEmpty() && value != "null") { "polza.ai: нет текста ответа." }
                            }
                        }
                        if (continuation.isActive) continuation.resume(answer)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(
                            IllegalStateException(if (error is org.json.JSONException)
                                "polza.ai: неверный формат ответа." else error.message ?: "Ошибка polza.ai."))
                    }
                }
            })
        }
    }
}
