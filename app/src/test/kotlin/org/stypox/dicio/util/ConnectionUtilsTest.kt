package org.stypox.dicio.util

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.IOException
import java.net.ServerSocket
import kotlin.concurrent.thread

class ConnectionUtilsTest : StringSpec({
    fun withResponse(body: ByteArray, check: (String) -> Unit) {
        ServerSocket(0).use { server ->
            val worker = thread {
                server.accept().use { socket ->
                    socket.getInputStream().bufferedReader().apply {
                        while (!readLine().isNullOrEmpty()) { }
                    }
                    try {
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(body)
                            flush()
                        }
                    } catch (_: IOException) { }
                }
            }
            try { check("http://127.0.0.1:${server.localPort}/") } finally { worker.join(5000) }
        }
    }
    "empty and UTF-8 responses are read correctly" {
        withResponse(byteArrayOf()) { ConnectionUtils.getPage(it) shouldBe "" }
        withResponse("café".toByteArray(Charsets.UTF_8)) { ConnectionUtils.getPage(it) shouldBe "café" }
    }
    "oversized responses are rejected" {
        withResponse(ByteArray(ConnectionUtils.MAX_RESPONSE_BYTES + 1)) {
            shouldThrow<IOException> { ConnectionUtils.getPage(it) }
        }
    }
})
