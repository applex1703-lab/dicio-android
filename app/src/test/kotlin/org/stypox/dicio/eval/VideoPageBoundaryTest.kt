package org.stypox.dicio.eval

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class VideoPageBoundaryTest : StringSpec({
    "a late page cannot alter a replacement row or extend its selectable videos" {
        val presentation = CurrentVoicePresentation()
        val pending = PendingChoice<String>()
        val old = pending.create("old query", mapOf("old" to "old"))
        presentation.videos(old, listOf(VideoCard("old", "Old", "", null)), true, VideoSource.YOUTUBE)
        presentation.videoPageLoading(old) shouldBe true

        val current = pending.create("new query", mapOf("new" to "new"))
        presentation.videos(current, listOf(VideoCard("new", "New", "", null)), true, VideoSource.RUTUBE)
        val before = presentation.state.value
        pending.extend(old, mapOf("late" to "late")) shouldBe false
        presentation.videoPageLoaded(old, listOf(VideoCard("late", "Late", "", null)), false)
        presentation.videoPageFailed(old)
        presentation.state.value shouldBe before
        pending.consume(current, "late") shouldBe null
        pending.consume(current, "new") shouldBe ("new query" to "new")
    }

    "duplicate page with a continuation requires retry and preserves the source" {
        val presentation = CurrentVoicePresentation()
        val card = VideoCard("same", "Original", "", null)
        presentation.videos(1, listOf(card), true, VideoSource.YOUTUBE)
        presentation.videoPageLoading(1) shouldBe true
        presentation.videoPageLoading(1) shouldBe false
        presentation.videoPageLoaded(1, listOf(card, card.copy(title = "Duplicate")), true)
        presentation.state.value.videoRow shouldBe VideoRow(1, listOf(card), hasNext = true,
            loadError = true, source = VideoSource.YOUTUBE)
        presentation.videoPageLoading(1) shouldBe true
        val next = VideoCard("next", "Next", "", null)
        presentation.videoPageLoaded(1, listOf(card, next, next), false)
        presentation.state.value.videoRow shouldBe VideoRow(1, listOf(card, next), source = VideoSource.YOUTUBE)
        presentation.videoPageLoading(1) shouldBe false
    }

    "a page arriving after selection cannot recreate the dismissed row" {
        val presentation = CurrentVoicePresentation()
        val pending = PendingChoice<String>()
        val id = pending.create("query", mapOf("video" to "video"))
        presentation.videos(id, listOf(VideoCard("video", "Video", "", null)), true, VideoSource.YOUTUBE)
        presentation.videoPageLoading(id) shouldBe true
        pending.consume(id, "video") shouldBe ("query" to "video")
        presentation.openingVideo()
        val before = presentation.state.value
        pending.extend(id, mapOf("late" to "late")) shouldBe false
        presentation.videoPageLoaded(id, listOf(VideoCard("late", "Late", "", null)), true)
        presentation.videoPageFailed(id)
        presentation.state.value shouldBe before
    }
})
