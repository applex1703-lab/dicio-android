package org.stypox.dicio.eval

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InputSessionBoundaryTest : StringSpec({
    "reentrant new waiter is not cancelled by older teardown, even when teardown throws" {
        runBlocking {
            val owner = Job()
            val lock = Mutex(locked = true)
            val boundary = InputSessionBoundary(CoroutineScope(owner + Dispatchers.Unconfined)) {
                lock.withLock { }
            }
            var started = false
            val failure = IllegalStateException("teardown")
            shouldThrow<IllegalStateException> {
                boundary.end {
                    boundary.afterVpnClear { started = true }
                    throw failure
                }
            } shouldBe failure
            lock.unlock()
            started shouldBe true
            owner.cancel()
        }
    }

    "stop and cleanup failures are both retained and VPN clear is still started" {
        val owner = Job()
        var clears = 0
        val boundary = InputSessionBoundary(CoroutineScope(owner + Dispatchers.Unconfined)) { clears++ }
        val stop = IllegalStateException("stop")
        val cleanup = IllegalArgumentException("cleanup")
        val result = shouldThrow<IllegalStateException> {
            boundary.end { stopAndCleanup({ throw stop }, { throw cleanup }) }
        }
        result shouldBe stop
        result.suppressed.toList() shouldBe listOf(cleanup)
        clears shouldBe 1
        val cleanupOnly = shouldThrow<IllegalArgumentException> {
            stopAndCleanup({}, { throw cleanup })
        }
        cleanupOnly shouldBe cleanup
        owner.cancel()
    }
    "teardown is synchronous even when VPN clear waits, and cannot clean up a new session later" {
        runBlocking {
            val owner = Job()
            val scope = CoroutineScope(owner + Dispatchers.Unconfined)
            val lock = Mutex(locked = true)
            var lease = "old"
            val boundary = InputSessionBoundary(scope) { lock.withLock { lease = "" } }
            val events = mutableListOf<String>()
            val clarification = PendingClarification(scope) { 0L }
            clarification.save("request", "question")

            boundary.end {
                events += listOf("cancel", "presentation", "stop", "cleanup")
            }
            events shouldBe listOf("cancel", "presentation", "stop", "cleanup")
            clarification.isAvailable() shouldBe true
            boundary.afterVpnClear {
                events += "new session"
                lease = "new"
            }
            lock.unlock()
            events shouldBe listOf("cancel", "presentation", "stop", "cleanup", "new session")
            lease shouldBe "new"
            clarification.consume()!!.context.request shouldBe "request"
            clarification.consume() shouldBe null
            owner.cancel()
        }
    }

    "cancel clears context immediately while VPN is locked and supersedes pending input" {
        runBlocking {
            val owner = Job()
            val scope = CoroutineScope(owner + Dispatchers.Unconfined)
            val lock = Mutex(locked = true)
            val boundary = InputSessionBoundary(scope) { lock.withLock { } }
            val clarification = PendingClarification(scope) { 0L }
            clarification.save("request", "question")
            var started = false
            boundary.afterVpnClear { started = true }
            boundary.end { clarification.clear() }
            clarification.isAvailable() shouldBe false
            lock.unlock()
            started shouldBe false
            owner.cancel()
        }
    }
})
