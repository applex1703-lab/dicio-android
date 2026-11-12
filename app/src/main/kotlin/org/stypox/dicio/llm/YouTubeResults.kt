package org.stypox.dicio.llm

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.parser.Parser
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class YoutubeVideo(val id: String, val title: String, val author: String, val thumbnail: String?)
internal data class YoutubePage(val videos: List<YoutubeVideo>, val nextPageToken: String?)

private val videoIdPattern = Regex("[A-Za-z0-9_-]{11}")
private val pageTokenPattern = Regex("[A-Za-z0-9_-]{1,512}")

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)
    ?.takeIf { it.isString }?.content

internal object YoutubeResultsParser {
    fun parse(body: String): List<YoutubeVideo> = parsePage(body).videos

    fun parsePage(body: String): YoutubePage {
        val root = Json.parseToJsonElement(body) as? JsonObject ?: error("Invalid YouTube response")
        val entries = root["items"] as? JsonArray ?: error("Missing YouTube items")
        val token = if (root.containsKey("nextPageToken")) {
            root.string("nextPageToken")?.takeIf { pageTokenPattern.matches(it) }
                ?: error("Invalid YouTube page token")
        } else null
        val videos = entries.mapNotNull { entry ->
            val obj = entry as? JsonObject ?: return@mapNotNull null
            val idObject = obj["id"] as? JsonObject ?: return@mapNotNull null
            if (idObject.string("kind")?.let { it != "youtube#video" } == true) return@mapNotNull null
            val id = idObject.string("videoId")?.takeIf { videoIdPattern.matches(it) } ?: return@mapNotNull null
            val snippet = obj["snippet"] as? JsonObject ?: return@mapNotNull null
            val title = snippet.string("title")?.let { Parser.unescapeEntities(it.take(4096), false).trim().take(200) }
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val author = snippet.string("channelTitle")?.let { Parser.unescapeEntities(it.take(4096), false).trim().take(100) }.orEmpty()
            val thumbnails = snippet["thumbnails"] as? JsonObject
            val thumbnail = listOf("medium", "high", "default").firstNotNullOfOrNull { size ->
                val url = (thumbnails?.get(size) as? JsonObject)?.string("url")?.toHttpUrlOrNull()
                url?.takeIf { it.isHttps && it.host == "i.ytimg.com" && it.port == 443 && it.username.isEmpty() && it.password.isEmpty() }
                    ?.toString()
            }
            YoutubeVideo(id, title, author, thumbnail)
        }.distinctBy { it.id }.take(20)
        check(entries.isEmpty() || videos.isNotEmpty()) { "Invalid YouTube results" }
        return YoutubePage(videos, token)
    }
}

internal class YoutubeResults(private val apiKey: String) {
    private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    internal fun request(query: String, pageToken: String?): Request {
        require(apiKey.isNotBlank() && apiKey.length <= 512 && apiKey.all { it in '!'..'~' }) { "Invalid YouTube API key" }
        require(pageToken == null || pageTokenPattern.matches(pageToken)) { "Invalid YouTube page token" }
        val url = "https://www.googleapis.com/youtube/v3/search".toHttpUrl().newBuilder()
            .addQueryParameter("part", "snippet")
            .addQueryParameter("type", "video")
            .addQueryParameter("maxResults", "20")
            .addQueryParameter("q", ContentSearch.validateQuery(query))
            .apply { if (pageToken != null) addQueryParameter("pageToken", pageToken) }
            .build()
        return Request.Builder().url(url).header("X-Goog-Api-Key", apiKey)
            .header("Accept", "application/json").build()
    }

    suspend fun search(query: String): List<YoutubeVideo> = searchPage(query).videos

    suspend fun searchPage(query: String, pageToken: String? = null): YoutubePage {
        val request = request(query, pageToken)
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    // Do not propagate network exception messages that may contain request details.
                    if (continuation.isActive) continuation.resumeWithException(IOException("YouTube request failed"))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val page = response.use {
                            if (!it.isSuccessful) throw IOException("YouTube HTTP ${it.code}")
                            val source = it.body?.source() ?: throw IOException("Empty YouTube response")
                            val buffer = okio.Buffer()
                            while (buffer.size <= 1_048_576 && source.read(buffer, 8192) != -1L) { }
                            if (buffer.size > 1_048_576) throw IOException("YouTube response too large")
                            try { YoutubeResultsParser.parsePage(buffer.readUtf8()) }
                            catch (_: Exception) { throw IOException("Invalid YouTube response") }
                        }
                        if (continuation.isActive) continuation.resume(page)
                    } catch (_: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(IOException("YouTube response failed"))
                    }
                }
            })
        }
    }
}
