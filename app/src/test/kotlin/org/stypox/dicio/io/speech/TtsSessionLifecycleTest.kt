package org.stypox.dicio.io.speech

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class TtsSessionLifecycleTest : StringSpec({
    "stop ignores a late completion even after new speech" {
        val state = TtsSessionLifecycle()
        var calls = 0
        state.submitted("old")
        state.whenFinished(Runnable { calls++ })
        state.stopSpeech()
        state.submitted("new")
        state.whenFinished(Runnable { calls++ })
        state.finished("old")
        calls shouldBe 0
        state.finished("new")
        state.finished("new")
        calls shouldBe 1
    }
    "only the last queued utterance finishes the callbacks" {
        val state = TtsSessionLifecycle()
        var calls = 0
        state.submitted("first")
        state.whenFinished(Runnable { calls++ })
        state.submitted("second")
        state.finished("first")
        calls shouldBe 0
        state.finished("second")
        calls shouldBe 1
    }
    "terminal events out of order wait for every accepted utterance" {
        val state = TtsSessionLifecycle()
        var calls = 0
        state.submitted("first")
        state.submitted("second")
        state.whenFinished(Runnable { calls++ })
        // The second submission can fail synchronously while the first is still playing.
        state.finished("second")
        state.hasSpeech shouldBe true
        calls shouldBe 0
        state.finished("first")
        state.hasSpeech shouldBe false
        calls shouldBe 1
    }
    "queued failures and late duplicate events terminate only once" {
        val state = TtsSessionLifecycle()
        var calls = 0
        state.submitted("queued-1")
        state.submitted("queued-2")
        state.whenFinished(Runnable { calls++ })
        state.finished("queued-1")
        state.finished("queued-1")
        calls shouldBe 0
        state.finished("queued-2")
        state.finished("queued-2")
        calls shouldBe 1
    }
    "multiple utterances queued during initialization remain distinct" {
        val state = TtsSessionLifecycle()
        var calls = 0
        state.submitted("pending-1")
        state.whenFinished(Runnable { calls++ })
        state.submitted("pending-2")
        state.finished("pending-1")
        calls shouldBe 0
        state.finished("pending-2")
        calls shouldBe 1
    }
    "callback failure does not discard other callbacks" {
        var errors = 0
        var calls = 0
        val state = TtsSessionLifecycle { errors++ }
        state.submitted("one")
        state.whenFinished(Runnable { throw IllegalStateException("callback") })
        state.whenFinished(Runnable { calls++ })
        state.finished("one")
        errors shouldBe 1
        calls shouldBe 1
    }
    "reentrant speech defers remaining callbacks until the new queue ends" {
        val state = TtsSessionLifecycle()
        var calls = 0
        state.submitted("one")
        state.whenFinished(Runnable { state.submitted("two") })
        state.whenFinished(Runnable { calls++ })
        state.finished("one")
        calls shouldBe 0
        state.finished("two")
        calls shouldBe 1
    }
    "initialization failure and engine stop terminate waiting exactly once" {
        val state = TtsSessionLifecycle()
        var calls = 0
        state.submitted("pending")
        state.whenFinished(Runnable { calls++ })
        state.finished("pending")
        state.finished("pending")
        calls shouldBe 1
        state.submitted("stopped-by-engine")
        state.whenFinished(Runnable { calls++ })
        state.finished("stopped-by-engine")
        state.finished("stopped-by-engine")
        calls shouldBe 2
    }
    "cleanup and reentrant stop suppress remaining callbacks" {
        val state = TtsSessionLifecycle()
        var calls = 0
        state.submitted("one")
        state.whenFinished(Runnable { state.stopSpeech() })
        state.whenFinished(Runnable { calls++ })
        state.finished("one")
        state.submitted("two")
        state.whenFinished(Runnable { calls++ })
        state.close()
        state.finished("two")
        state.submitted("three")
        state.hasSpeech shouldBe false
        calls shouldBe 0
    }
    "session start and listening stops preserve one preparation until answer or cleanup" {
        val state = TtsSessionLifecycle()
        // Started stops the previous speech before beginSession creates the device.
        state.stopSpeech() shouldBe true
        state.beginPreparation() shouldBe true
        // Listening must not call the engine's global stop on the file synthesis.
        state.stopSpeech() shouldBe false
        state.preparationPending shouldBe true
        state.beginPreparation() shouldBe false
        // A real answer interrupts synthesis and is then submitted normally.
        state.endPreparation()
        state.submitted("answer")
        state.stopSpeech() shouldBe true
        state.beginPreparation() shouldBe false
        state.close()
        state.beginPreparation() shouldBe false
    }
    "stop before initialization does not prevent session preparation but cleanup does" {
        val state = TtsSessionLifecycle()
        state.stopSpeech() shouldBe true
        state.beginPreparation() shouldBe true
        state.close()
        state.preparationPending shouldBe false
        state.beginPreparation() shouldBe false
    }
})
