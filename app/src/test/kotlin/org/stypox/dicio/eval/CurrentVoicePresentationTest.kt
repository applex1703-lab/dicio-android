package org.stypox.dicio.eval

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class CurrentVoicePresentationTest : StringSpec({
    "pages preserve row identity, reject stale updates and expose explicit retry" {
        val current = CurrentVoicePresentation()
        val a = VideoCard("a", "A", "", null)
        val b = VideoCard("b", "B", "", null)
        current.videos(7, listOf(a), hasNext = true)
        current.videoPageLoading(8) shouldBe false
        current.videoPageLoading(7) shouldBe true
        current.videoPageLoading(7) shouldBe false
        current.videoPageLoaded(8, listOf(b), false)
        current.state.value.videoRow?.cards shouldBe listOf(a)
        current.videoPageLoaded(7, listOf(a, b), true)
        current.state.value.videoRow?.cards shouldBe listOf(a, b)
        current.state.value.videoRow?.id shouldBe 7L
        current.videoPageLoading(7) shouldBe true
        current.videoPageFailed(7)
        current.state.value.videoRow?.loadError shouldBe true
        current.videoPageLoading(7) shouldBe true
        current.videoPageLoaded(7, emptyList(), false)
        current.state.value.videoRow?.hasNext shouldBe false
        current.videoPageLoading(7) shouldBe false
    }
    "a page of duplicates cannot trigger endless automatic prefetch" {
        val current = CurrentVoicePresentation()
        val a = VideoCard("a", "A", "", null)
        current.videos(9, listOf(a), hasNext = true)
        current.videoPageLoading(9) shouldBe true
        current.videoPageLoaded(9, listOf(a), true)
        current.state.value.videoRow?.cards shouldBe listOf(a)
        current.state.value.videoRow?.hasNext shouldBe true
        current.state.value.videoRow?.loadError shouldBe true
        current.state.value.videoRow?.loading shouldBe false
    }
    "a late page cannot restore a row after video selection or new input" {
        val current = CurrentVoicePresentation()
        val a = VideoCard("a", "A", "", null)
        current.videos(9, listOf(a), hasNext = true)
        current.videoPageLoading(9) shouldBe true
        current.openingVideo()
        current.videoPageLoaded(9, listOf(a), true)
        current.videoPageFailed(9)
        current.state.value.videoRow shouldBe null
        current.thinking("new request")
        current.videoPageLoaded(9, listOf(a), true)
        current.state.value.videoRow shouldBe null
        current.state.value.request shouldBe "new request"
    }
    "video rows and choices do not share state and are cleared on new input" {
        val current = CurrentVoicePresentation()
        current.thinking("test")
        current.videos(1, listOf(VideoCard("id", "title", "author", null)))
        current.state.value.videoRow?.cards?.size shouldBe 1
        current.state.value.choice shouldBe null
        current.choice(2, listOf(ChoiceOption("vpn", "VPN")), "choose")
        current.state.value.videoRow shouldBe null
        current.state.value.choice?.id shouldBe 2L
        current.thinking("next")
        current.state.value.videoRow shouldBe null
        current.state.value.choice shouldBe null
    }
    "repeated results retain only the current request and answer" {
        val current = CurrentVoicePresentation()
        repeat(1000) { index ->
            current.thinking("request $index")
            current.answer("answer $index")
        }
        current.state.value shouldBe VoicePresentation(request = "request 999", status = "Ответ", answer = "answer 999")
    }
    "partial, error, silent success and clear replace transient state" {
        val current = CurrentVoicePresentation()
        current.listening("partial")
        current.state.value.request shouldBe "partial"
        current.error("permission denied")
        current.state.value.answer shouldBe "permission denied"
        current.state.value.dismiss shouldBe false
        current.thinking("next")
        current.answer("not spoken or shown", silent = true)
        current.state.value.answer shouldBe ""
        current.state.value.dismiss shouldBe true
        current.noInput()
        current.state.value.request shouldBe ""
        current.state.value.answer shouldBe "Речь не распознана."
        current.state.value.dismiss shouldBe false
        current.clear()
        current.state.value shouldBe VoicePresentation()
    }
})
