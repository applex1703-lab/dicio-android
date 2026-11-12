package org.stypox.dicio.eval

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class YoutubePageTokenTest : StringSpec({
    "repeated current token A to A stops pagination" {
        nextYoutubePageToken(next = "A", current = "A", previous = emptySet()) shouldBe null
    }

    "cycle A to B to A stops pagination" {
        nextYoutubePageToken(next = "A", current = "B", previous = setOf("A")) shouldBe null
    }

    "new token continues pagination" {
        nextYoutubePageToken(next = "C", current = "B", previous = setOf("A")) shouldBe "C"
    }

    "missing next token ends pagination" {
        nextYoutubePageToken(next = null, current = "B", previous = setOf("A")) shouldBe null
    }
})
