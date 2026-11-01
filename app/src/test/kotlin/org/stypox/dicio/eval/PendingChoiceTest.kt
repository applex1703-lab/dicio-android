package org.stypox.dicio.eval

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class PendingChoiceTest : StringSpec({
    "snapshot is one-shot and rejects unknown options" {
        val pending = PendingChoice<String>()
        val options = mutableMapOf("a" to "first")
        val id = pending.create("query", options)
        options["a"] = "changed"
        options["b"] = "new"
        pending.consume(id, "b") shouldBe null
        pending.consume(id, "a") shouldBe ("query" to "first")
        pending.consume(id, "a") shouldBe null
    }

    "replacement and clear invalidate old ids" {
        val pending = PendingChoice<String>()
        val old = pending.create("first", mapOf("a" to "A"))
        val current = pending.create("second", mapOf("a" to "A"))
        old shouldNotBe current
        pending.consume(old, "a") shouldBe null
        pending.clear()
        pending.consume(current, "a") shouldBe null
        val next = pending.create("third", mapOf("a" to "A"))
        current shouldNotBe next
        pending.consume(next, "a") shouldBe ("third" to "A")
    }

    "empty options cannot create choice" {
        shouldThrow<IllegalArgumentException> { PendingChoice<String>().create("query", emptyMap()) }
    }
})
