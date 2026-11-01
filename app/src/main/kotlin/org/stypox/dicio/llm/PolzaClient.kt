package org.stypox.dicio.llm

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Chat Completions transport adapted from PR #370; no tools or Android actions are exposed. */
@Singleton
class PolzaClient @Inject constructor(client: OkHttpClient) {
    private val http = client.newBuilder().callTimeout(40, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(35, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .eventListenerFactory { RouterNetworkTiming() }.build()

    suspend fun answer(config: PolzaSettings.Config, text: String): String {
        PolzaProtocol.validateInput(config, text)
        return complete(config, PolzaProtocol.request(config.model, text))
    }

    internal suspend fun decide(config: PolzaSettings.Config, text: String,
        apps: Map<String, String>, media: List<MediaTarget> = emptyList()): AssistantIntent {
        PolzaProtocol.validateInput(config, text)
        return IntentProtocol.parse(complete(config, IntentProtocol.request(config.model, text, apps, media)), apps.keys, media)
    }

    private suspend fun complete(config: PolzaSettings.Config, payload: String): String {
        val request = Request.Builder().url("https://routerai.ru/api/v1/chat/completions")
            .header("Authorization", "Bearer ${config.apiKey}")
            .post(payload.toRequestBody("application/json".toMediaType())).build()
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(
                        IOException(if (e is java.io.InterruptedIOException)
                            "RouterAI: время ожидания истекло."
                        else "Не удалось связаться с RouterAI. Проверьте сеть или повторите позже."))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val answer = response.use {
                            check(it.isSuccessful) { PolzaProtocol.httpError(it.code) }
                            val body = it.body ?: error("RouterAI: пустой ответ.")
                            val source = body.source()
                            source.request(PolzaProtocol.MAX_RESPONSE_BYTES + 1)
                            check(source.buffer.size <= PolzaProtocol.MAX_RESPONSE_BYTES) { "RouterAI: ответ слишком большой." }
                            PolzaProtocol.answer(source.readUtf8())
                        }
                        if (continuation.isActive) continuation.resume(answer)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(
                            IllegalStateException(if (error is IOException)
                                "RouterAI: не удалось прочитать ответ."
                            else if (error is IllegalStateException) error.message ?: "Ошибка RouterAI."
                            else "RouterAI: ошибка обработки ответа."))
                    }
                }
            })
        }
    }
}

/** Timings only: never log request or response contents, credentials, hosts, or addresses. */
private class RouterNetworkTiming : EventListener() {
    private val id = nextId.incrementAndGet()
    private val startedAt = SystemClock.elapsedRealtime()
    private fun event(stage: String) {
        Log.i("DicioRouterNet", "id=$id stage=$stage elapsedMs=${SystemClock.elapsedRealtime() - startedAt}")
    }

    override fun callStart(call: Call) = event("call_start")
    override fun dnsStart(call: Call, domainName: String) = event("dns_start")
    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) = event("dns_end")
    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) = event("connect_start")
    override fun secureConnectStart(call: Call) = event("tls_start")
    override fun secureConnectEnd(call: Call, handshake: okhttp3.Handshake?) = event("tls_end")
    override fun connectionAcquired(call: Call, connection: okhttp3.Connection) = event("connection_acquired")
    override fun requestHeadersEnd(call: Call, request: Request) = event("request_headers_end")
    override fun requestBodyEnd(call: Call, byteCount: Long) = event("request_body_end")
    override fun responseHeadersStart(call: Call) = event("response_headers_start")
    override fun responseHeadersEnd(call: Call, response: Response) = event("response_headers_end")
    override fun responseBodyStart(call: Call) = event("response_body_start")
    override fun responseBodyEnd(call: Call, byteCount: Long) = event("response_body_end")
    override fun callEnd(call: Call) = event("call_end")
    override fun callFailed(call: Call, ioe: IOException) = event(
        if (ioe is java.io.InterruptedIOException) "call_failed_timeout_or_interrupted" else "call_failed_io"
    )

    companion object { private val nextId = AtomicLong() }
}
