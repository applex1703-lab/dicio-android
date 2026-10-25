package org.stypox.dicio.eval

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation

class LatestInputRequestTest : StringSpec({
    "new input cancels old work before any new speech result" {
        val owner = Job()
        val requests = LatestInputRequest(CoroutineScope(owner + Dispatchers.Unconfined))
        var cancelled = false
        requests.replace {
            try { awaitCancellation() } finally { cancelled = true }
        }
        var nextStarted = false
        requests.replace { nextStarted = true }
        cancelled shouldBe true
        nextStarted shouldBe true
        owner.cancel()
    }
    "cancelled late response cannot publish an answer" {
        val owner = Job()
        val response = CompletableDeferred<String>()
        val requests = LatestInputRequest(CoroutineScope(owner + Dispatchers.Unconfined))
        var published = false
        requests.replace { response.await(); published = true }
        requests.cancel()
        response.complete("late answer")
        published shouldBe false
        requests.cancel() // idempotent lifecycle cancellation
        owner.cancel()
    }
})
