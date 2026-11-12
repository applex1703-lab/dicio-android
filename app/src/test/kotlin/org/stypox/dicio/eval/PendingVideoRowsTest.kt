package org.stypox.dicio.eval

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class PendingVideoRowsTest : StringSpec({
    "rows are independent even with the same video id and selection clears all" {
        val pending = PendingVideoRows<String>()
        val first = pending.create("first", mapOf("video" to "first video"))
        val second = pending.create("second", mapOf("video" to "second video"))
        pending.consume(first, "missing") shouldBe null
        pending.extend(first, mapOf("extra" to "extra video")) shouldBe true
        pending.consume(second, "video") shouldBe ("second" to "second video")
        pending.consume(first, "video") shouldBe null
        pending.consume(second, "video") shouldBe null
        pending.extend(first, mapOf("new" to "new video")) shouldBe false
        pending.extend(second, mapOf("new" to "new video")) shouldBe false
    }

    "creation and extension snapshot their option maps" {
        val pending = PendingVideoRows<String>()
        val initial = mutableMapOf("a" to "original")
        val id = pending.create("query", initial)
        initial["a"] = "changed"
        initial["b"] = "added"
        val additional = mutableMapOf("c" to "extended")
        pending.extend(id, additional) shouldBe true
        additional["c"] = "changed"
        additional["d"] = "added"
        pending.consume(id, "b") shouldBe null
        pending.consume(id, "d") shouldBe null
        pending.consume(id, "c") shouldBe ("query" to "extended")
    }

    "clear invalidates rows without reusing ids and stale operations do nothing" {
        val pending = PendingVideoRows<String>()
        val first = pending.create("first", mapOf("x" to "one"))
        val second = pending.create("second", mapOf("x" to "two"))
        pending.clear()
        val third = pending.create("third", mapOf("x" to "three"))
        (first < second && second < third) shouldBe true
        pending.extend(first, mapOf("x" to "stale")) shouldBe false
        pending.consume(second, "x") shouldBe null
        pending.consume(third, "x") shouldBe ("third" to "three")
        val fourth = pending.create("fourth", mapOf("x" to "four"))
        (third < fourth) shouldBe true
    }

    "nullable values are valid options" {
        val pending = PendingVideoRows<String?>()
        val id = pending.create("query", mapOf("null" to null))
        pending.consume(id, "null") shouldBe ("query" to null)
        pending.consume(id, "null") shouldBe null
    }
})
