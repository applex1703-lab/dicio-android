package org.stypox.dicio.voice

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import org.dicio.skill.skill.Permission
import org.dicio.skill.skill.SkillOutput
import org.junit.Assert.*
import org.junit.Test
import org.stypox.dicio.di.SttInputDeviceWrapper
import org.stypox.dicio.eval.SkillEvaluator
import org.stypox.dicio.eval.VoicePresentation
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.io.input.SttState
import org.stypox.dicio.ui.home.InteractionLog

class TvVoiceRequestActionTest {
    private class Input : SttInputDeviceWrapper {
        override val uiState = MutableStateFlow<SttState?>(null)
        var starts = 0
        var releases = 0
        var available = true
        var onCheck: (() -> Unit)? = null
        var callback: ((InputEvent) -> Unit)? = null
        override fun tryLoad(thenStartListeningEventListener: ((InputEvent) -> Unit)?): Boolean {
            starts++
            callback = thenStartListeningEventListener
            return available
        }
        override fun stopListening() = Unit
        override fun onClick(eventListener: (InputEvent) -> Unit) = Unit
        override fun reinitializeToReleaseResources(): Boolean { releases++; return true }
        override fun isCleanupSafe(): Boolean { onCheck?.invoke(); return true }
    }

    private class Evaluator : SkillEvaluator {
        override val currentOutput = MutableStateFlow<SkillOutput?>(null)
        override val state = MutableStateFlow(InteractionLog(emptyList(), null))
        override val voicePresentation = MutableStateFlow(VoicePresentation())
        override var permissionRequester: suspend (List<Permission>) -> Boolean = { false }
        var pending = false
        var finishes = 0
        var onReset: (() -> Unit)? = null
        var onHasPending: (() -> Unit)? = null
        override fun processInputEvent(event: InputEvent) = Unit
        override fun cancelPendingInput() { pending = false }
        override fun finishInputSession() { finishes++ }
        override fun hasPendingClarification(): Boolean { onHasPending?.invoke(); return pending }
        override fun resetVoicePresentation() { onReset?.invoke() }
        override fun selectChoice(choiceId: Long, optionId: String) = Unit
    }

    @Test fun buttonUsesExistingInputThenRestartsWithClarificationAfterTerminalInput() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val input = Input()
            val evaluator = Evaluator()
            val coordinator = VoiceInputCoordinator(input, evaluator)
            val owner = Any()
            var updates = false
            var subscriptions = 0
            var rejected = 0
            val action = TvVoiceRequestAction(
                { true }, { initial, revoked ->
                    if (initial) coordinator.startTv(owner, revoked)
                    else coordinator.startTvIfAvailable(owner, revoked)
                },
                { updates = false },
                { if (!updates) { updates = true; subscriptions++ } },
                { rejected++ },
            )
            action.request(initial = true)
            action.request()
            assertEquals(1, input.starts)
            assertEquals(0, input.releases)
            assertEquals(1, subscriptions)
            input.callback!!(InputEvent.Final(listOf("question" to 1f)))
            evaluator.pending = true
            action.request()
            assertEquals(2, input.starts)
            assertEquals(1, input.releases)
            assertEquals(1, evaluator.finishes)
            assertTrue(evaluator.pending)
            assertEquals(2, subscriptions)
            coordinator.release(owner)
            val other = Any()
            assertTrue(coordinator.start(other))
            val releases = input.releases
            action.request()
            assertEquals(1, rejected)
            assertEquals(3, input.starts)
            assertEquals(releases, input.releases)
            assertTrue(coordinator.ownsWork(other))
            coordinator.release(other)
        }
    }

    @Test fun failedFirstStartAndIdleRetryAreAllowedWithoutPreemptingOthers() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val input = Input()
            val coordinator = VoiceInputCoordinator(input, Evaluator())
            val owner = Any()
            input.available = false
            assertFalse(coordinator.startTv(owner))
            assertFalse(coordinator.ownsWork(owner))
            input.available = true
            assertTrue(coordinator.startTvIfAvailable(owner))
            coordinator.release(owner)
            // The no-permission path never acquired a lease; its later retry is identical.
            assertTrue(coordinator.startTvIfAvailable(owner))
            coordinator.release(owner)
            val other = Any()
            assertEquals(true, coordinator.startService(other, {} , {}))
            val releases = input.releases
            assertFalse(coordinator.startTvIfAvailable(owner))
            assertEquals(releases, input.releases)
            assertTrue(coordinator.ownsWork(other))
            coordinator.release(other)
            coordinator.submitText(other, "other evaluation")
            assertFalse(coordinator.startTvIfAvailable(owner))
            assertTrue(coordinator.ownsWork(other))
            coordinator.release(other)
        }
    }

    @Test fun retryCannotDisplaceOwnerAcquiredReentrantlyDuringCheckOrReset() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val input = Input()
            val evaluator = Evaluator()
            val coordinator = VoiceInputCoordinator(input, evaluator)
            val owner = Any()
            val other = Any()
            input.onCheck = {
                input.onCheck = null
                assertTrue(coordinator.start(other))
            }
            assertFalse(coordinator.startTvIfAvailable(owner))
            assertTrue(coordinator.ownsWork(other))
            coordinator.release(other)
            evaluator.onReset = {
                evaluator.onReset = null
                assertTrue(coordinator.start(other))
            }
            assertFalse(coordinator.startTvIfAvailable(owner))
            assertTrue(coordinator.ownsWork(other))
            coordinator.release(other)
        }
    }

    @Test fun retryDoesNotClearAReplacementLeaseDuringClarificationCheck() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val input = Input()
            val evaluator = Evaluator()
            val coordinator = VoiceInputCoordinator(input, evaluator)
            val owner = Any()
            val other = Any()
            assertTrue(coordinator.startTv(owner))
            input.callback!!(InputEvent.Final(listOf("question" to 1f)))
            evaluator.pending = true
            evaluator.onHasPending = {
                evaluator.onHasPending = null
                assertTrue(coordinator.start(other))
            }
            assertFalse(coordinator.startTvIfAvailable(owner))
            assertTrue(coordinator.ownsWork(other))
            coordinator.release(other)
        }
    }
}
