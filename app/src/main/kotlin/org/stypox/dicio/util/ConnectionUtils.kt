package org.stypox.dicio.util

import android.net.Uri
import org.json.JSONException
import org.json.JSONObject
import org.stypox.dicio.util.ConnectionUtils.percentEncode
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

object ConnectionUtils {
    internal const val TIMEOUT_MS = 15_000
    internal const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    @Throws(IOException::class)
    fun getPage(
        url: String,
        headers: Map<String, String?>
    ): String {
        val connection = URL(url).openConnection()
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        try {
            for ((key, value) in headers) {
                connection.setRequestProperty(key, value)
            }
            return connection.getInputStream().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    if (count > MAX_RESPONSE_BYTES - output.size()) {
                        throw IOException("Response exceeds $MAX_RESPONSE_BYTES bytes")
                    }
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
        } finally {
            (connection as? HttpURLConnection)?.disconnect()
        }
    }

    @Throws(IOException::class)
    fun getPage(url: String): String {
        return getPage(url, emptyMap())
    }

    @Throws(IOException::class, JSONException::class)
    fun getPageJson(url: String): JSONObject {
        return JSONObject(getPage(url))
    }

    /**
     * Encodes [s] as `application/x-www-form-urlencoded` so that it can be used as a parameter in
     * URL query strings. Note: space will be encoded as `+` which makes sense for URL query strings
     * but not for the URL path, in that case use [percentEncode] instead.
     */
    fun urlEncode(s: String): String {
        return URLEncoder.encode(s, "utf8")
    }

    /**
     * Decodes [s] from `application/x-www-form-urlencoded`.
     */
    fun urlDecode(s: String): String {
        return URLDecoder.decode(s, "utf8")
    }

    /**
     * Percent-encodes [s] so that it can be used in a URL path. Note: space will be encoded as
     * `%20`.
     */
    fun percentEncode(s: String): String {
        return Uri.encode(s)
    }

    /**
     * Percent-decodes [s].
     */
    fun percentDecode(s: String): String {
        return Uri.decode(s)
    }
}
