package org.stypox.dicio.eval

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class CurrentVoicePresentationTest : StringSpec({
    "finished video search clears thinking even when both providers have no cards" {
        val current = CurrentVoicePresentation()
        current.thinking("query")
        current.videoSourceMessage(VideoSource.RUTUBE, "Rutube empty")
        current.videoSourceMessage(VideoSource.YOUTUBE, "YouTube unavailable")
        current.finishVideoSearch()
        current.state.value.status shouldBe ""
        current.state.value.videoSourceMessages.size shouldBe 2
    }
    "source messages do not overwrite results or other messages" {
        val current = CurrentVoicePresentation()
        val card = VideoCard("a", "A", "", null)
        current.addVideos(1, listOf(card), false, VideoSource.RUTUBE) shouldBe true
        current.videoSourceMessage(VideoSource.YOUTUBE, "Нет результатов")
        current.videoSourceMessage(VideoSource.RUTUBE, "Предупреждение")
        current.state.value.videoRows.single().cards shouldBe listOf(card)
        current.clearVideoSourceMessage(VideoSource.RUTUBE)
        current.state.value.videoSourceMessages shouldBe mapOf(VideoSource.YOUTUBE to "Нет результатов")
        current.addVideos(2, listOf(card), false, VideoSource.YOUTUBE) shouldBe true
        current.state.value.videoSourceMessages shouldBe emptyMap()
        current.videoSourceMessage(VideoSource.YOUTUBE, "Ошибка")
        current.videoSourceMessage(VideoSource.YOUTUBE, "")
        current.state.value.videoSourceMessages shouldBe emptyMap()
        current.videoSourceMessage(VideoSource.YOUTUBE, "Ошибка")
        current.openingVideo()
        current.state.value.videoSourceMessages shouldBe emptyMap()
        current.videoSourceMessage(VideoSource.YOUTUBE, "Ошибка")
        current.listening("new")
        current.state.value.videoSourceMessages shouldBe emptyMap()
    }
    "two sources stay ordered and paginate independently" {
        val current = CurrentVoicePresentation()
        val rutube = VideoCard("same", "Rutube", "", null)
        val youtube = VideoCard("same", "YouTube", "", null)
        val next = VideoCard("next", "Next", "", null)
        current.upsertVideos(2, listOf(youtube), true, VideoSource.YOUTUBE) shouldBe true
        current.upsertVideos(1, listOf(rutube), true, VideoSource.RUTUBE) shouldBe true
        current.state.value.videoRows.map { it.id } shouldBe listOf(1L, 2L)
        current.state.value.videoRow?.id shouldBe 1L
        current.upsertVideos(1, emptyList(), source = VideoSource.YOUTUBE) shouldBe false
        current.videoPageLoading(1) shouldBe true
        current.videoPageLoading(2) shouldBe true
        current.videoPageFailed(1)
        current.videoPageLoaded(2, listOf(youtube, next), false)
        current.state.value.videoRows[0].cards shouldBe listOf(rutube)
        current.state.value.videoRows[0].loadError shouldBe true
        current.state.value.videoRows[1].cards shouldBe listOf(youtube, next)
        current.state.value.videoRows[1].loadError shouldBe false
        current.videoPageLoading(1) shouldBe true
        current.upsertVideos(3, listOf(rutube), true, VideoSource.RUTUBE) shouldBe true
        current.videoPageLoaded(1, listOf(next), false)
        current.state.value.videoRows.map { it.id } shouldBe listOf(3L, 2L)
        current.state.value.videoRows[0].cards shouldBe listOf(rutube)
        current.state.value.videoRows[1].cards shouldBe listOf(youtube, next)
    }
    "opening and new input discard both rows and late pages" {
        val current = CurrentVoicePresentation()
        current.upsertVideos(1, emptyList(), true, VideoSource.RUTUBE)
        current.upsertVideos(2, emptyList(), true, VideoSource.YOUTUBE)
        current.videoPageLoading(1)
        current.videoPageLoading(2)
        current.openingVideo()
        current.videoPageLoaded(1, listOf(VideoCard("late", "Late", "", null)), true)
        current.videoPageFailed(2)
        current.state.value.videoRows shouldBe emptyList()
        current.upsertVideos(3, emptyList(), true, VideoSource.RUTUBE)
        current.upsertVideos(4, emptyList(), true, VideoSource.YOUTUBE)
        current.videoPageLoading(3)
        current.thinking("new")
        current.videoPageLoaded(3, emptyList(), true)
        current.state.value.videoRows shouldBe emptyList()
        current.state.value.request shouldBe "new"
    }
    "video source defaults to Rutube and survives page updates" {
        val current = CurrentVoicePresentation()
        current.videos(1, emptyList())
        current.state.value.videoRow?.source shouldBe VideoSource.RUTUBE
        current.videos(2, emptyList(), true, VideoSource.YOUTUBE)
        current.videoPageLoading(2) shouldBe true
        current.videoPageLoaded(2, emptyList(), false)
        current.state.value.videoRow?.source shouldBe VideoSource.YOUTUBE
        VideoSource.YOUTUBE.label shouldBe "YouTube"
    }
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
