package org.stypox.dicio.llm

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
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
        PolzaProtocol.validateInput(config, text)
        return complete(config, PolzaProtocol.request(config.model, text))
    }

    internal suspend fun decide(config: PolzaSettings.Config, text: String,
        apps: Map<String, String>): AssistantIntent {
        PolzaProtocol.validateInput(config, text)
        return IntentProtocol.parse(complete(config, IntentProtocol.request(config.model, text, apps)), apps.keys)
    }

    private suspend fun complete(config: PolzaSettings.Config, payload: String): String {
        val request = Request.Builder().url("https://polza.ai/api/v1/chat/completions")
            .header("Authorization", "Bearer ${config.apiKey}")
            .post(payload.toRequestBody("application/json".toMediaType())).build()
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(
                        IOException(if (e is java.io.InterruptedIOException)
                            "polza.ai: время ожидания истекло."
                        else "Не удалось связаться с polza.ai. Проверьте сеть или повторите позже."))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val answer = response.use {
                            check(it.isSuccessful) { PolzaProtocol.httpError(it.code) }
                            val body = it.body ?: error("polza.ai: пустой ответ.")
                            val source = body.source()
                            source.request(PolzaProtocol.MAX_RESPONSE_BYTES + 1)
                            check(source.buffer.size <= PolzaProtocol.MAX_RESPONSE_BYTES) { "polza.ai: ответ слишком большой." }
                            PolzaProtocol.answer(source.readUtf8())
                        }
                        if (continuation.isActive) continuation.resume(answer)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(
                            IllegalStateException(if (error is IOException)
                                "polza.ai: не удалось прочитать ответ."
                            else if (error is IllegalStateException) error.message ?: "Ошибка polza.ai."
                            else "polza.ai: ошибка обработки ответа."))
                    }
                }
            })
        }
    }
}
