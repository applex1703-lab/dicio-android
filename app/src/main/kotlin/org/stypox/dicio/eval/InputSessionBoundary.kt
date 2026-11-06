package org.stypox.dicio.eval

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/** Attempt both operations; preserve the first failure for the synchronous caller. */
internal fun stopAndCleanup(stop: () -> Unit, cleanup: () -> Unit) {
    var failure: Throwable? = null
    try {
        stop()
    } catch (error: Throwable) {
        failure = error
    } finally {
        try {
            cleanup()
        } catch (error: Throwable) {
            if (failure == null) failure = error
            else if (failure !== error) failure.addSuppressed(error)
        }
    }
    failure?.let { throw it }
}

/** Main-thread confined. A superseded waiter must not clear a newer VPN choice. */
internal class InputSessionBoundary(
    private val scope: CoroutineScope,
    private val clearVpn: suspend () -> Unit,
) {
    private var waiter: Job? = null
    private var generation = 0L
    private var teardownDepth = 0
    val isEnding: Boolean get() = teardownDepth != 0

    fun afterVpnClear(next: () -> Unit = {}) {
        generation++
        waiter?.cancel()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            clearVpn()
            coroutineContext.ensureActive()
            next()
        }
        waiter = job
        job.start()
    }

    fun end(teardown: () -> Unit) {
        val endingGeneration = ++generation
        waiter?.cancel()
        waiter = null
        teardownDepth++
        try {
            teardown()
        } finally {
            teardownDepth--
            // Reentrant work owns its own waiter; an older teardown must not cancel it.
            if (generation == endingGeneration) afterVpnClear()
        }
    }
}
