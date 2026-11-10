package org.stypox.dicio.llm

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class RutubeVideo(val id: String, val title: String, val author: String, val thumbnail: String?)

internal object RutubeResultsParser {
    private val idPattern = Regex("[a-fA-F0-9]{32}")
    fun parse(body: String): List<RutubeVideo> {
        val entries = Json.parseToJsonElement(body).jsonObject["results"]?.jsonArray ?: error("Missing results")
        return entries.mapNotNull { entry ->
            runCatching {
                val obj = entry.jsonObject
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
                val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim()?.take(200) ?: return@runCatching null
                if (!idPattern.matches(id) || title.isBlank()) return@runCatching null
                val author = obj["author"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull?.trim()?.take(100).orEmpty()
                val image = obj["thumbnail_url"]?.jsonPrimitive?.contentOrNull?.toHttpUrlOrNull()
                val thumbnail = image?.takeIf { it.isHttps && it.port == 443 && it.host in setOf("pic.rtbcdn.ru", "pic.rutube.ru") }?.toString()
                RutubeVideo(id.lowercase(), title, author, thumbnail)
            }.getOrNull()
        }.distinctBy { it.id }.take(10)
    }
}

internal class RutubeResults {
    private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    suspend fun search(query: String): List<RutubeVideo> {
        val url = "https://rutube.ru/api/search/video/".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("query", ContentSearch.validateQuery(query))
            .addQueryParameter("page", "1").addQueryParameter("limit", "10").build()
        val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0")
            .header("Accept", "application/json").build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            if (!it.isSuccessful) throw IOException("Rutube HTTP ${it.code}")
                            val source = it.body?.source() ?: throw IOException("Empty response")
                            val buffer = okio.Buffer()
                            while (buffer.size <= 1_048_576) {
                                if (source.read(buffer, 8192) == -1L) break
                            }
                            if (buffer.size > 1_048_576) throw IOException("Response too large")
                            RutubeResultsParser.parse(buffer.readUtf8())
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }
    }
}
