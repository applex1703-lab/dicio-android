package org.stypox.dicio.eval

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Accessed on the evaluator's main dispatcher only. Cancellation propagates to the HTTP call. */
internal class LatestInputRequest(private val scope: CoroutineScope) {
    private var active: Job? = null

    fun replace(block: suspend () -> Unit) {
        cancel()
        val job = scope.launch(start = CoroutineStart.LAZY) { block() }
        active = job
        job.start()
    }

    fun cancel() {
        val job = active
        active = null
        job?.cancel()
    }
}
