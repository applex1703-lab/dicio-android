package org.stypox.dicio.eval

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class DirectSpeechRequestsTest : StringSpec({
    "late callbacks cannot speak or stop after a replacement" {
        val requests = DirectSpeechRequests()
        val old = requests.next()
        val calls = mutableListOf<String>()
        val lateSpeak = { requests.ifCurrent(old) { calls += "old speak" } }
        val lateStop = { requests.ifCurrent(old) { calls += "old stop" } }
        val current = requests.next()
        lateSpeak()
        lateStop()
        requests.ifCurrent(current) { calls += "new speak" }
        calls shouldBe listOf("new speak")
    }

    "reentrant replacement between stop and speak fences the old continuation" {
        val requests = DirectSpeechRequests()
        val old = requests.next()
        val calls = mutableListOf<String>()
        requests.ifCurrent(old) {
            calls += "stop"
            val current = requests.next()
            requests.ifCurrent(current) { calls += "new speak" }
        }
        requests.ifCurrent(old) { calls += "old speak" }
        calls shouldBe listOf("stop", "new speak")
    }
})
