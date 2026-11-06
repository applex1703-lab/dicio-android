package org.stypox.dicio.voice

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ExplicitAssistGestureTest : StringSpec({
    "delayed, future and inconsistent event timestamps are rejected" {
        isRecentAssistEvent(1_000, 1_100, 1_200) shouldBe true
        isRecentAssistEvent(1_100, 1_000, 1_200) shouldBe false
        isRecentAssistEvent(1_000, 1_200, 1_100) shouldBe false
        isRecentAssistEvent(1_000, 1_100, 2_101) shouldBe false
        isRecentAssistEvent(1_000, 6_000, 6_001) shouldBe false
    }
    "only a new complete press after show is accepted once" {
        val gesture = ExplicitAssistGesture()
        gesture.shown(100)
        gesture.down(1, 2, 99, 0, false) shouldBe false
        gesture.down(1, 2, 100, 0, false) shouldBe false
        gesture.down(1, 2, 101, 0, false) shouldBe true
        gesture.down(1, 2, 101, 1, false) shouldBe true
        gesture.up(1, 2, 101, false) shouldBe true
        gesture.up(1, 2, 101, false) shouldBe false
        gesture.down(1, 2, 101, 0, false) shouldBe false
        gesture.down(1, 2, 102, 0, false) shouldBe true
        gesture.up(1, 2, 102, false) shouldBe true
        gesture.down(1, 2, 101, 0, false) shouldBe false
    }
    "cancelled mismatched and hidden gestures cannot start input" {
        val gesture = ExplicitAssistGesture()
        gesture.shown(10)
        gesture.down(1, 2, 11, 0, false) shouldBe true
        gesture.up(1, 3, 11, false) shouldBe false
        gesture.up(1, 2, 11, true) shouldBe false
        gesture.down(1, 2, 12, 0, false) shouldBe true
        gesture.hidden()
        gesture.up(1, 2, 12, false) shouldBe false
        gesture.shown(20)
        gesture.down(1, 2, 19, 0, false) shouldBe false
        gesture.down(1, 2, 21, 0, false) shouldBe true
        gesture.down(1, 2, 21, 1, true) shouldBe true
        gesture.up(1, 2, 21, false) shouldBe false
        gesture.down(1, 2, 22, 0, false) shouldBe true
        gesture.down(1, 2, 23, 0, false) shouldBe true
        gesture.up(1, 2, 22, false) shouldBe false
        gesture.up(1, 2, 23, false) shouldBe true
        gesture.down(1, 2, 24, 0, false) shouldBe true
        gesture.discard(1, 3, 24)
        gesture.up(1, 2, 24, false) shouldBe true
        gesture.down(1, 2, 25, 0, false) shouldBe true
        gesture.discard()
        gesture.up(1, 2, 25, false) shouldBe false
    }
})
