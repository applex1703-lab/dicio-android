package org.stypox.dicio.llm

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private val config = PolzaSettings.Config(true, "test/model", "fake-key")
private const val timeoutMessage = "RouterAI: время ожидания истекло или запрос прерван."

/** One actual HTTP/1.1 connection; no external service or mock interceptor. */
private class LoopbackServer(handler: (Socket) -> Unit) : AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private val executor = Executors.newSingleThreadExecutor()
    private val activeSocket = AtomicReference<Socket?>()
    private val result = executor.submit {
        server.accept().use { socket ->
            activeSocket.set(socket)
            socket.soTimeout = 5000
            // Consume the complete request so that EOF later means the client closed its connection.
            val input = socket.getInputStream().buffered()
            fun line(): String {
                val bytes = ArrayList<Byte>()
                while (true) {
                    val b = input.read()
                    check(b != -1)
                    if (b == 10) return bytes.toByteArray().toString(Charsets.US_ASCII).trimEnd('\r')
                    bytes.add(b.toByte())
                }
            }
            line()
            var length = 0
            while (true) {
                val header = line()
                if (header.isEmpty()) break
                if (header.startsWith("Content-Length:", ignoreCase = true)) length = header.substringAfter(':').trim().toInt()
            }
            repeat(length) { check(input.read() != -1) }
            handler(socket)
        }
    }
    val port get() = server.localPort
    fun verify() { result.get(6, TimeUnit.SECONDS) }
    fun verifyNoSecondConnection() {
        server.soTimeout = 300
        try {
            server.accept().use { error("Unexpected second connection") }
        } catch (_: java.net.SocketTimeoutException) { /* expected */ }
    }
    override fun close() {
        try {
            server.close()
        } finally {
            try { activeSocket.get()?.close() } finally {
                executor.shutdownNow()
                executor.awaitTermination(6, TimeUnit.SECONDS)
            }
        }
    }
}

private class TestHttp : AutoCloseable {
    val client = OkHttpClient()
    override fun close() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdownNow()
        client.dispatcher.executorService.awaitTermination(6, TimeUnit.SECONDS)
    }
}

private fun Socket.headers(length: Int) {
    getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: $length\r\nConnection: close\r\n\r\n".toByteArray())
    getOutputStream().flush()
}

class PolzaClientSocketTest : StringSpec({
    "clarification expiring after enqueue sends no HTTP bytes" {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            TestHttp().use { http ->
                val checks = AtomicInteger()
                val client = PolzaClient.forLoopbackTest(http.client, server.localPort, 3000, 1000, 1000)
                withTimeout(3500) {
                    shouldThrow<java.io.IOException> {
                        client.decide(config, "answer", emptyMap(),
                            clarification = ClarificationContext("old", "question"),
                            contextIsFresh = { checks.incrementAndGet() == 1 })
                    }.message shouldBe "Срок уточнения истёк. Повторите запрос полностью."
                }
                checks.get() shouldBe 2
                server.soTimeout = 2000
                server.accept().use { socket ->
                    socket.soTimeout = 2000
                    socket.getInputStream().read() shouldBe -1
                }
            }
        }
    }
    "incomplete body times out after headers" {
        val release = CountDownLatch(1)
        val bodyStarted = CountDownLatch(1)
        TestHttp().use { http -> LoopbackServer { socket ->
            socket.headers(100)
            socket.getOutputStream().write(' '.code)
            socket.getOutputStream().flush()
            release.await(5, TimeUnit.SECONDS)
        }.use { server ->
            try {
                val client = PolzaClient.forLoopbackTest(http.client, server.port, 2000, 1000, 250) { bodyStarted.countDown() }
                withTimeout(3500) {
                    shouldThrow<IllegalStateException> { client.answer(config, "hello") }.message shouldBe timeoutMessage
                }
                bodyStarted.count shouldBe 0L
            } finally { release.countDown() }
            server.verify()
            server.verifyNoSecondConnection()
        } }
    }
    "frequent body bytes still hit the whole call timeout" {
        val disconnected = CountDownLatch(1)
        val chunks = AtomicInteger()
        TestHttp().use { http -> LoopbackServer { socket ->
            socket.headers(10000)
            try {
                while (true) {
                    socket.getOutputStream().write(' '.code)
                    socket.getOutputStream().flush()
                    chunks.incrementAndGet()
                    Thread.sleep(40)
                }
            } catch (_: java.io.IOException) { disconnected.countDown() }
        }.use { server ->
            val client = PolzaClient.forLoopbackTest(http.client, server.port, 600, 1000, 5000)
            val start = System.nanoTime()
            withTimeout(3500) {
                shouldThrow<IllegalStateException> { client.answer(config, "hello") }.message shouldBe timeoutMessage
            }
            (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 3000) shouldBe true
            (chunks.get() >= 3) shouldBe true
            disconnected.await(3, TimeUnit.SECONDS) shouldBe true
            server.verify()
            server.verifyNoSecondConnection()
        } }
    }
    "cancelling while reading closes the connection" {
        val bodyStarted = CountDownLatch(1)
        val closed = CountDownLatch(1)
        TestHttp().use { http -> LoopbackServer { socket ->
            socket.headers(100)
            socket.getOutputStream().write(' '.code)
            socket.getOutputStream().flush()
            if (socket.getInputStream().read() == -1) closed.countDown()
        }.use { server ->
            val client = PolzaClient.forLoopbackTest(http.client, server.port, 5000, 1000, 5000) { bodyStarted.countDown() }
            runBlocking {
                val request = async(Dispatchers.IO) { client.answer(config, "hello") }
                bodyStarted.await(3, TimeUnit.SECONDS) shouldBe true
                request.cancelAndJoin()
                request.isCancelled shouldBe true
            }
            closed.await(3, TimeUnit.SECONDS) shouldBe true
            server.verify()
            server.verifyNoSecondConnection()
        } }
    }
    "complete response succeeds with one connection" {
        TestHttp().use { http -> LoopbackServer { socket ->
            val body = """{"choices":[{"finish_reason":"stop","message":{"content":"test answer"}}]}""".toByteArray()
            socket.headers(body.size)
            socket.getOutputStream().write(body)
            socket.getOutputStream().flush()
        }.use { server ->
            val client = PolzaClient.forLoopbackTest(http.client, server.port, 2000, 1000, 1000)
            client.answer(config, "hello") shouldBe "test answer"
            server.verify()
            server.verifyNoSecondConnection()
        } }
    }
})
