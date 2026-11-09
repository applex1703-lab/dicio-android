package org.stypox.dicio.eval

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job

class ConversationHistoryTest : StringSpec({
    "retains four messages and expires each independently" {
        val job = Job()
        var now = 100L
        val history = ConversationHistory(CoroutineScope(job + Dispatchers.Unconfined)) { now }
        history.addExchange("one", now, "answer one")
        now += 1000
        history.addExchange("two", now, "answer two")
        history.addExchange("three", now, "answer three")
        history.snapshot().messages.map { it.text } shouldBe listOf("two", "answer two", "three", "answer three")
        now += 60_000
        history.snapshot().messages shouldBe emptyList()
        job.cancel()
    }
    "request and response expire from their own creation times" {
        val job = Job()
        var now = 1000L
        val history = ConversationHistory(CoroutineScope(job + Dispatchers.Unconfined)) { now }
        val started = now
        now += 10_000
        history.addExchange("question", started, "answer")
        now = started + 60_000
        history.snapshot().messages.map { it.text } shouldBe listOf("answer")
        now += 10_000
        history.snapshot().messages shouldBe emptyList()
        job.cancel()
    }
    "explicit credential words prevent retention" {
        val job = Job()
        val history = ConversationHistory(CoroutineScope(job + Dispatchers.Unconfined)) { 0L }
        history.addExchange("мой пароль 123", 0L, "ответ")
        history.snapshot().messages shouldBe emptyList()
        job.cancel()
    }
})
