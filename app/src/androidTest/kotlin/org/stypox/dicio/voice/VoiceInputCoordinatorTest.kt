package org.stypox.dicio.voice

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ApplicationProvider
import android.app.Application
import kotlinx.coroutines.flow.MutableStateFlow
import org.dicio.skill.skill.Permission
import org.junit.Assert.*
import org.junit.Test
import org.stypox.dicio.di.SttInputDeviceWrapper
import org.stypox.dicio.eval.SkillEvaluator
import org.stypox.dicio.eval.VoicePresentation
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.io.input.SttState
import org.stypox.dicio.ui.home.InteractionLog
import org.stypox.dicio.io.input.stt_popup.SttPopupViewModel

class VoiceInputCoordinatorTest {
    private class Input : SttInputDeviceWrapper {
        override val uiState = MutableStateFlow<SttState?>(null)
        var callback: ((InputEvent) -> Unit)? = null
        var starts = 0
        var releases = 0
        var stops = 0
        var available = true
        var synchronousEvent: InputEvent? = null
        var returnAfterEvent = true
        var onLoad: (() -> Unit)? = null
        var onRelease: (() -> Unit)? = null
        var cleanupSucceeds = true
        var cleanupSafe = true
        override fun tryLoad(thenStartListeningEventListener: ((InputEvent) -> Unit)?): Boolean {
            if (!available) return false
            callback = thenStartListeningEventListener
            starts++
            synchronousEvent?.let { thenStartListeningEventListener?.invoke(it) }
            onLoad?.invoke()
            return returnAfterEvent
        }
        override fun stopListening() { stops++ }
        override fun onClick(eventListener: (InputEvent) -> Unit) = Unit
        override fun reinitializeToReleaseResources(): Boolean {
            releases++
            onRelease?.invoke()
            if (!cleanupSucceeds) cleanupSafe = false
            return cleanupSucceeds
        }
        override fun isCleanupSafe(): Boolean = cleanupSafe
    }

    private class Evaluator : SkillEvaluator {
        override val currentOutput = MutableStateFlow<org.dicio.skill.skill.SkillOutput?>(null)
        override val state = MutableStateFlow(InteractionLog(emptyList(), null))
        override val voicePresentation = MutableStateFlow(VoicePresentation())
        override var permissionRequester: suspend (List<Permission>) -> Boolean = { false }
        val events = mutableListOf<InputEvent>()
        var cancellations = 0
        var finishes = 0
        var pendingClarification = false
        var failCancel = false
        val choices = mutableListOf<Pair<Long, String>>()
        val videoPages = mutableListOf<Pair<Long, Boolean>>()
        override fun processInputEvent(event: InputEvent) { events.add(event) }
        override fun cancelPendingInput() { cancellations++; pendingClarification = false; if (failCancel) throw IllegalStateException("evaluator") }
        override fun finishInputSession() { finishes++; cancellations++; if (failCancel) throw IllegalStateException("evaluator") }
        override fun hasPendingClarification() = pendingClarification
        override fun resetVoicePresentation() = Unit
        override fun selectChoice(choiceId: Long, optionId: String) { choices.add(choiceId to optionId) }
        override fun loadNextVideoPage(rowId: Long, retry: Boolean) { videoPages.add(rowId to retry) }
    }

    private fun onMain(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    @Test fun initialTvStartRejectsReentrantHideAndLateCallback() = onMain {
        for (terminal in listOf(false, true)) {
            val input = Input().apply { if (terminal) synchronousEvent = InputEvent.None }
            val evaluator = Evaluator()
            val coordinator = VoiceInputCoordinator(input, evaluator)
            val owner = Any()
            input.onLoad = { coordinator.release(owner) }
            assertFalse(coordinator.startTv(owner))
            assertFalse(coordinator.ownsWork(owner))
            assertEquals(1, input.starts)
            assertEquals(1, input.releases)
            val events = evaluator.events.toList()
            input.callback!!(InputEvent.Final(listOf("late" to 1f)))
            assertEquals(events, evaluator.events)
        }
    }

    @Test fun initialTvStartReportsSynchronousTerminalCleanupFailure() = onMain {
        for (loaded in listOf(false, true)) {
            val input = Input().apply {
                synchronousEvent = InputEvent.Final(listOf("must not execute" to 1f))
                returnAfterEvent = loaded
                cleanupSucceeds = false
            }
            val evaluator = Evaluator()
            val coordinator = VoiceInputCoordinator(input, evaluator)
            assertFalse(coordinator.startTv(Any()))
            assertTrue(evaluator.events.single() is InputEvent.Error)
            assertFalse(coordinator.startTv(Any()))
            assertEquals(1, input.starts)
            assertEquals(1, input.releases)
        }
    }

    @Test fun initialTvStartAcceptsOneSynchronousTerminalAfterCleanup() = onMain {
        for (loaded in listOf(false, true)) {
            val input = Input().apply {
                synchronousEvent = InputEvent.Final(listOf("done" to 1f))
                returnAfterEvent = loaded
            }
            val evaluator = Evaluator()
            val coordinator = VoiceInputCoordinator(input, evaluator)
            val owner = Any()
            assertTrue(coordinator.startTv(owner))
            assertEquals(listOf(InputEvent.Final(listOf("done" to 1f))), evaluator.events)
            assertTrue(coordinator.ownsWork(owner))
            input.callback!!(InputEvent.Final(listOf("late" to 1f)))
            assertEquals(1, evaluator.events.size)
            assertEquals(1, input.releases)
        }
    }

    @Test fun rapidTvHideShowDoesNotLetOldStartCleanUpNewOwner() = onMain {
        val input = Input()
        val evaluator = Evaluator()
        val coordinator = VoiceInputCoordinator(input, evaluator)
        val oldOwner = Any()
        val newOwner = Any()
        var oldCallback: ((InputEvent) -> Unit)? = null
        input.onLoad = {
            oldCallback = input.callback
            input.onLoad = null
            coordinator.release(oldOwner)
            assertTrue(coordinator.startTvIfAvailable(newOwner))
        }
        assertFalse(coordinator.startTv(oldOwner))
        assertTrue(coordinator.ownsWork(newOwner))
        assertEquals(2, input.starts)
        assertEquals(1, input.releases)
        oldCallback!!(InputEvent.Final(listOf("old" to 1f)))
        coordinator.release(oldOwner)
        assertTrue(evaluator.events.isEmpty())
        assertTrue(coordinator.ownsWork(newOwner))
        input.callback!!(InputEvent.Final(listOf("new" to 1f)))
        assertEquals(listOf(InputEvent.Final(listOf("new" to 1f))), evaluator.events)
    }

    @Test fun explicitTvRestartRejectsReentrantCancelOrReleaseDuringLoad() = onMain {
        for (release in listOf(false, true)) {
            val input = Input()
            val evaluator = Evaluator()
            val coordinator = VoiceInputCoordinator(input, evaluator)
            val owner = Any()
            input.onLoad = {
                if (release) coordinator.release(owner) else coordinator.cancel(owner)
            }
            assertFalse(coordinator.restartTvExplicit(owner))
            assertFalse(coordinator.ownsWork(owner))
            assertEquals(1, input.starts)
            assertEquals(1, input.releases)
            input.callback!!(InputEvent.Final(listOf("late" to 1f)))
            assertTrue(evaluator.events.isEmpty())
        }
    }

    @Test fun explicitTvRestartAcceptsSynchronousTerminalOnlyWhileLeaseRemainsCurrent() = onMain {
        for (loaded in listOf(false, true)) {
            val input = Input().apply {
                synchronousEvent = InputEvent.Final(listOf("done" to 1f))
                returnAfterEvent = loaded
            }
            val evaluator = Evaluator()
            val coordinator = VoiceInputCoordinator(input, evaluator)
            val owner = Any()
            assertTrue(coordinator.restartTvExplicit(owner))
            assertEquals(1, evaluator.events.size)
            assertTrue(coordinator.ownsWork(owner))
            input.callback!!(InputEvent.Final(listOf("late" to 1f)))
            assertEquals(1, evaluator.events.size)
        }
        val input = Input().apply { synchronousEvent = InputEvent.None }
        val coordinator = VoiceInputCoordinator(input, Evaluator())
        val owner = Any()
        input.onLoad = { coordinator.cancel(owner) }
        assertFalse(coordinator.restartTvExplicit(owner))
        assertFalse(coordinator.ownsWork(owner))
    }

    @Test fun explicitTvRestartReportsSynchronousTerminalCleanupFailure() = onMain {
        for (loaded in listOf(false, true)) {
            val input = Input().apply {
                synchronousEvent = InputEvent.Final(listOf("must not execute" to 1f))
                returnAfterEvent = loaded
                cleanupSucceeds = false
            }
            val evaluator = Evaluator()
            val coordinator = VoiceInputCoordinator(input, evaluator)
            val owner = Any()
            assertFalse(coordinator.restartTvExplicit(owner))
            assertEquals(1, evaluator.events.size)
            assertTrue(evaluator.events.single() is InputEvent.Error)
            input.callback!!(InputEvent.Final(listOf("late" to 1f)))
            assertEquals(1, evaluator.events.size)
            assertFalse(coordinator.restartTvExplicit(owner))
            assertEquals(1, input.starts)
            assertEquals(1, input.releases)
        }
    }

    @Test fun explicitTvRestartCleansUpAfterReentrantForeignNoOp() = onMain {
        for (terminal in listOf(false, true)) {
            for (foreignStart in listOf(false, true)) {
                val input = Input().apply {
                    if (terminal) synchronousEvent = InputEvent.None
                }
                val evaluator = Evaluator()
                val coordinator = VoiceInputCoordinator(input, evaluator)
                val owner = Any()
                val foreign = Any()
                input.onLoad = {
                    if (foreignStart) assertFalse(coordinator.startTvIfAvailable(foreign))
                    else coordinator.release(foreign)
                }
                assertFalse(coordinator.restartTvExplicit(owner))
                assertFalse(coordinator.ownsWork(owner))
                assertFalse(coordinator.ownsWork(foreign))
                assertEquals(1, input.starts)
                assertEquals(1, input.releases)
                assertEquals(1, evaluator.cancellations)
                val events = evaluator.events.toList()
                input.callback!!(InputEvent.Final(listOf("late" to 1f)))
                assertEquals(events, evaluator.events)
            }
        }
    }

    @Test fun invalidatedExplicitTvRestartDoesNotCleanUpReplacementLease() = onMain {
        val input = Input()
        val evaluator = Evaluator()
        val coordinator = VoiceInputCoordinator(input, evaluator)
        val owner = Any()
        val replacement = Any()
        var old: ((InputEvent) -> Unit)? = null
        input.onLoad = {
            old = input.callback
            input.onLoad = null
            coordinator.cancel(owner)
            assertTrue(coordinator.startTv(replacement))
        }
        assertFalse(coordinator.restartTvExplicit(owner))
        assertTrue(coordinator.ownsWork(replacement))
        assertEquals(2, input.starts)
        assertEquals(1, input.releases)
        old!!(InputEvent.Final(listOf("late" to 1f)))
        assertTrue(evaluator.events.isEmpty())
        input.callback!!(InputEvent.Final(listOf("new" to 1f)))
        assertEquals(1, evaluator.events.size)
    }

    @Test fun explicitTvRestartReplacesOnlyOwnActiveInputAndRejectsLateEvents() = onMain {
        val input = Input()
        val evaluator = Evaluator()
        val coordinator = VoiceInputCoordinator(input, evaluator)
        val owner = Any()
        var revoked = 0
        assertTrue(coordinator.startTv(owner) { revoked++ })
        val old = input.callback!!
        evaluator.pendingClarification = true
        input.onRelease = { assertEquals(1, input.starts) }
        assertFalse(coordinator.restartTvExplicit(Any()))
        assertEquals(0, input.releases)
        assertTrue(coordinator.restartTvExplicit(owner))
        assertEquals(1, revoked)
        assertEquals(1, input.releases)
        assertEquals(2, input.starts)
        input.onRelease = null
        assertFalse(evaluator.pendingClarification)
        assertEquals(0, evaluator.finishes)
        old(InputEvent.Final(listOf("late" to 1f)))
        assertTrue(evaluator.events.isEmpty())
        input.callback!!(InputEvent.Final(listOf("new" to 1f)))
        assertEquals(listOf(InputEvent.Final(listOf("new" to 1f))), evaluator.events)
    }

    @Test fun explicitTvRestartPreservesOnlyCompletedClarification() = onMain {
        val input = Input()
        val evaluator = Evaluator()
        val coordinator = VoiceInputCoordinator(input, evaluator)
        val owner = Any()
        assertTrue(coordinator.startTv(owner))
        input.callback!!(InputEvent.Final(listOf("question" to 1f)))
        evaluator.pendingClarification = true
        assertFalse(coordinator.restartTvExplicit(Any())) // foreign evaluation, even without input
        assertTrue(coordinator.restartTvExplicit(owner))
        assertTrue(evaluator.pendingClarification)
        assertEquals(1, evaluator.finishes)
        assertEquals(2, input.starts)
    }

    @Test fun explicitTvRestartAbortsOnReentrantReleaseAndFailedCleanup() = onMain {
        val input = Input()
        val evaluator = Evaluator()
        val coordinator = VoiceInputCoordinator(input, evaluator)
        val owner = Any()
        assertTrue(coordinator.startTv(owner))
        input.onRelease = {
            assertFalse(coordinator.restartTvExplicit(owner))
            coordinator.release(owner)
        }
        assertFalse(coordinator.restartTvExplicit(owner))
        assertEquals(1, input.starts)
        assertEquals(1, input.releases)

        input.onRelease = null
        assertTrue(coordinator.startTv(owner))
        val old = input.callback!!
        input.cleanupSucceeds = false
        assertFalse(coordinator.restartTvExplicit(owner))
        old(InputEvent.Final(listOf("late" to 1f)))
        assertTrue(evaluator.events.isEmpty())
        assertEquals(2, input.starts)
        assertEquals(2, input.releases)
        assertFalse(coordinator.restartTvExplicit(owner))
        assertEquals(2, input.releases) // no cleanup retry
    }

    @Test fun choiceRequiresCurrentEvaluationOwner() = onMain {
        val input = Input()
        val evaluator = Evaluator()
        val coordinator = VoiceInputCoordinator(input, evaluator)
        val owner = Any()
        coordinator.selectChoice(owner, 1, "a")
        coordinator.submitText(owner, "search")
        coordinator.selectChoice(Any(), 1, "a")
        coordinator.selectChoice(owner, 1, "a")
        coordinator.release(owner)
        coordinator.selectChoice(owner, 1, "a")
        assertEquals(listOf(1L to "a"), evaluator.choices)
        assertEquals(0, input.starts)
    }

    @Test fun videoPageRequiresCurrentOwnerAndStopsAfterRelease() = onMain {
        val input = Input()
        val evaluator = Evaluator()
        val coordinator = VoiceInputCoordinator(input, evaluator)
        val owner = Any()
        coordinator.loadNextVideoPage(owner, 5)
        coordinator.submitText(owner, "test")
        coordinator.loadNextVideoPage(Any(), 5)
        coordinator.loadNextVideoPage(owner, 5)
        coordinator.loadNextVideoPage(owner, 5, retry = true)
        coordinator.release(owner)
        coordinator.loadNextVideoPage(owner, 5)
        assertEquals(listOf(5L to false, 5L to true), evaluator.videoPages)
        assertEquals(0, input.starts)
    }

    @Test fun choiceWaitsForConfirmedInputCleanup() = onMain {
        val input = Input().apply { cleanupSucceeds = false }
        val evaluator = Evaluator()
        val coordinator = VoiceInputCoordinator(input, evaluator)
        val owner = Any()
        assertTrue(coordinator.start(owner))
        input.callback!!(InputEvent.Final(listOf("ignored" to 1f)))
        assertTrue(evaluator.events.single() is InputEvent.Error)
        coordinator.selectChoice(owner, 1, "a")
        assertTrue(evaluator.choices.isEmpty())
        input.cleanupSafe = true
        coordinator.selectChoice(owner, 1, "a")
        assertEquals(listOf(1L to "a"), evaluator.choices)
        assertEquals(1, input.releases)
    }

    @Test fun cancelRejectsLateCallbacksAndForeignRelease() = onMain {
        val input = Input()
        val coordinator = VoiceInputCoordinator(input, Evaluator())
        val owner = Any()
        val received = mutableListOf<InputEvent>()
        assertEquals(true, coordinator.startService(owner, { received.add(it) }, {}))
        val oldCallback = input.callback!!
        coordinator.release(Any())
        assertEquals(0, input.releases)
        coordinator.cancel(owner)
        oldCallback(InputEvent.Final(listOf("late" to 1f)))
        assertTrue(received.isEmpty())
        assertEquals(1, input.releases)
    }

    @Test fun busyAndStopAreOwnerScoped() = onMain {
        val input = Input()
        val coordinator = VoiceInputCoordinator(input, Evaluator())
        val owner = Any()
        assertEquals(true, coordinator.startService(owner, {}, {}))
        assertNull(coordinator.startService(Any(), {}, {}))
        assertFalse(coordinator.startPopup(Any(), {}))
        coordinator.stop(Any())
        assertEquals(0, input.stops)
        coordinator.stop(owner)
        assertEquals(1, input.stops)
        coordinator.cancel(owner)
        assertEquals(true, coordinator.startService(Any(), {}, {}))
    }

    @Test fun duplicateStartAndTerminalCallbackAreOneShot() = onMain {
        val input = Input()
        val evaluator = Evaluator()
        val coordinator = VoiceInputCoordinator(input, evaluator)
        val owner = Any()
        assertTrue(coordinator.start(owner))
        assertTrue(coordinator.start(owner))
        assertEquals(1, input.starts)
        val callback = input.callback!!
        callback(InputEvent.Final(listOf("first" to 1f)))
        callback(InputEvent.Final(listOf("second" to 1f)))
        assertEquals(1, evaluator.events.size)
        evaluator.pendingClarification = true
        coordinator.release(owner)
        assertEquals(1, evaluator.finishes)
    }

    @Test fun completedReleaseAndSameOwnerRestartPreserveButCancelAndPreemptionDoNot() = onMain {
        val input = Input()
        val evaluator = Evaluator()
        val coordinator = VoiceInputCoordinator(input, evaluator)
        val owner = Any()
        assertTrue(coordinator.start(owner))
        input.callback!!(InputEvent.Final(listOf("first" to 1f)))
        evaluator.pendingClarification = true
        assertTrue(coordinator.start(owner))
        assertEquals(1, evaluator.finishes)
        coordinator.cancel(owner)
        assertEquals(2, evaluator.cancellations)
        assertTrue(coordinator.start(owner))
        input.callback!!(InputEvent.Final(listOf("question" to 1f)))
        evaluator.pendingClarification = true
        coordinator.release(owner)
        assertEquals(2, evaluator.finishes)
        assertTrue(coordinator.start(owner))
        assertTrue(coordinator.start(Any()))
        assertEquals(4, evaluator.cancellations)
    }

    @Test fun sessionCloseThenRestartOrExplicitCancelUsesSameOwner() = onMain {
        val input = Input()
        val evaluator = Evaluator()
        val coordinator = VoiceInputCoordinator(input, evaluator)
        val owner = Any()
        val session = VoiceSessionTeardown(coordinator, owner)
        assertTrue(coordinator.start(owner))
        val callback = input.callback!!
        callback(InputEvent.Final(listOf("first" to 1f)))
        evaluator.pendingClarification = true // question was published
        session.close() // Back, then onHide and onDestroy
        session.close()
        session.close()
        assertTrue(evaluator.pendingClarification)
        assertTrue(coordinator.start(owner))
        coordinator.cancel(owner)
        assertFalse(evaluator.pendingClarification)
        callback(InputEvent.Final(listOf("late" to 1f)))
        assertFalse(evaluator.pendingClarification)
        coordinator.submitText(owner, "new")
        evaluator.pendingClarification = true
        session.close()
        coordinator.cancel(owner)
        assertFalse(evaluator.pendingClarification)
    }

    @Test fun retainedContextTransferredToTextIsCancelledOnceOnPreemption() = onMain {
        val evaluator = Evaluator()
        val coordinator = VoiceInputCoordinator(Input(), evaluator)
        val owner = Any()
        coordinator.submitText(owner, "question")
        evaluator.pendingClarification = true
        coordinator.release(owner)
        assertEquals(1, evaluator.finishes)
        coordinator.submitText(owner, "answer")
        assertTrue(evaluator.pendingClarification)
        coordinator.submitText(Any(), "new topic")
        assertEquals(2, evaluator.cancellations) // one finish and one cancellation
        assertFalse(evaluator.pendingClarification)
    }

    @Test fun sessionCloseBeforeCompletionAndDifferentOwnerDiscardContext() = onMain {
        val input = Input()
        val evaluator = Evaluator()
        val coordinator = VoiceInputCoordinator(input, evaluator)
        val first = Any()
        val second = Any()
        val session = VoiceSessionTeardown(coordinator, first)
        assertTrue(coordinator.start(first))
        evaluator.pendingClarification = true
        val late = input.callback!!
        session.close() // active input: interruption, not a completed question
        assertFalse(evaluator.pendingClarification)
        late(InputEvent.Final(listOf("late" to 1f)))
        assertTrue(evaluator.events.isEmpty())
        coordinator.submitText(first, "question")
        evaluator.pendingClarification = true
        session.close()
        assertTrue(evaluator.pendingClarification)
        coordinator.submitText(second, "unrelated")
        assertFalse(evaluator.pendingClarification)
        coordinator.cancel(first)
        assertTrue(coordinator.ownsWork(second))
    }

    @Test fun tvScopeSurvivesRecreationAndLateOldCallbacksCannotCancelNewOwner() = onMain {
        val input = Input()
        val evaluator = Evaluator()
        val coordinator = VoiceInputCoordinator(input, evaluator)
        val old = Any()
        val next = Any()
        assertTrue(coordinator.startTv(old))
        input.callback!!(InputEvent.Final(listOf("question" to 1f)))
        evaluator.pendingClarification = true
        VoiceSessionTeardown(coordinator, old).close()
        assertTrue(coordinator.startTv(next))
        assertTrue(evaluator.pendingClarification)
        VoiceSessionTeardown(coordinator, old).close()
        coordinator.cancel(old)
        assertTrue(coordinator.ownsWork(next))
        assertTrue(evaluator.pendingClarification)
        input.callback!!(InputEvent.Final(listOf("answer" to 1f)))
        VoiceSessionTeardown(coordinator, next).close()
        coordinator.cancel(old)
        assertTrue(evaluator.pendingClarification)
        coordinator.cancel(next)
        assertFalse(evaluator.pendingClarification)
    }

    @Test fun tvOwnerHandoffPrecedesReentrantRevocation() = onMain {
        val input = Input()
        val evaluator = Evaluator()
        val coordinator = VoiceInputCoordinator(input, evaluator)
        val old = Any()
        val next = Any()
        assertTrue(coordinator.startTv(old) { coordinator.cancel(old) })
        input.callback!!(InputEvent.Final(listOf("question" to 1f)))
        evaluator.pendingClarification = true
        // The old owner's reentrant cancellation invalidates this start; only a new
        // explicit request may acquire input after the handoff.
        assertFalse(coordinator.startTv(next))
        assertFalse(coordinator.ownsWork(next))
        assertTrue(coordinator.startTv(next))
        assertTrue(coordinator.ownsWork(next))
        assertTrue(evaluator.pendingClarification)
        coordinator.cancel(next)
        assertFalse(evaluator.pendingClarification)
    }

    @Test fun tvScopeDoesNotTransferToHomeOrExternalInput() = onMain {
        val input = Input()
        val evaluator = Evaluator()
        val coordinator = VoiceInputCoordinator(input, evaluator)
        val tv = Any()
        val home = Any()
        assertTrue(coordinator.startTv(tv))
        input.callback!!(InputEvent.Final(listOf("question" to 1f)))
        evaluator.pendingClarification = true
        coordinator.release(tv)
        coordinator.submitText(home, "new topic")
        assertFalse(evaluator.pendingClarification)
        coordinator.release(home)
        assertTrue(coordinator.startTv(tv))
        input.callback!!(InputEvent.Final(listOf("question" to 1f)))
        evaluator.pendingClarification = true
        coordinator.release(tv)
        assertEquals(true, coordinator.startService(Any(), {}, {}))
        assertFalse(evaluator.pendingClarification)
    }

    @Test fun preemptionTearsDownBeforeRevocationAndNewStart() = onMain {
        val input = Input()
        val coordinator = VoiceInputCoordinator(input, Evaluator())
        val first = Any()
        var releasesWhenRevoked = -1
        assertEquals(true, coordinator.startService(first, {}, { releasesWhenRevoked = input.releases }))
        val oldCallback = input.callback!!
        assertTrue(coordinator.start(Any()))
        assertEquals(1, releasesWhenRevoked)
        oldCallback(InputEvent.None)
        assertEquals(2, input.starts)
        coordinator.release(first)
        assertEquals(1, input.releases)
    }

    @Test fun unavailableDeviceDoesNotKeepLease() = onMain {
        val input = Input().apply { available = false }
        val coordinator = VoiceInputCoordinator(input, Evaluator())
        assertEquals(false, coordinator.startService(Any(), {}, {}))
        input.available = true
        assertEquals(true, coordinator.startService(Any(), {}, {}))
    }

    @Test fun throwingStartReleasesLeaseAndBlocksFurtherInput() = onMain {
        val input = Input().apply { onLoad = { throw IllegalStateException("start") } }
        val evaluator = Evaluator()
        val coordinator = VoiceInputCoordinator(input, evaluator)
        val owner = Any()
        assertFalse(coordinator.start(owner))
        assertFalse(coordinator.ownsWork(owner))
        assertEquals(1, input.releases)
        assertEquals(1, evaluator.cancellations)
        input.callback!!(InputEvent.Final(listOf("late" to 1f)))
        assertTrue(evaluator.events.isEmpty())
        assertFalse(coordinator.start(Any()))
        assertEquals(1, input.starts)
    }

    @Test fun interruptedInputIsTerminalAndCanBeReplaced() = onMain {
        val input = Input()
        val coordinator = VoiceInputCoordinator(input, Evaluator())
        val owner = Any()
        val events = mutableListOf<InputEvent>()
        assertTrue(coordinator.startPopup(owner) { events.add(it) })
        val old = input.callback!!
        old(InputEvent.Error(java.io.IOException("setting changed")))
        old(InputEvent.Final(listOf("late" to 1f)))
        assertEquals(1, events.size)
        assertTrue(coordinator.startPopup(owner) { events.add(it) })
        assertEquals(2, input.starts)
    }

    @Test fun popupTerminalFreesInputForServiceAndOldOwnerCannotReleaseIt() = onMain {
        for (event in listOf(InputEvent.None, InputEvent.Error(java.io.IOException("error")),
            InputEvent.Final(listOf("text" to 1f)))) {
            val input = Input()
            val coordinator = VoiceInputCoordinator(input, Evaluator())
            val popup = Any()
            assertTrue(coordinator.startPopup(popup) {})
            val old = input.callback!!
            old(event)
            assertEquals(1, input.releases)
            assertEquals(true, coordinator.startService(Any(), {}, {}))
            coordinator.release(popup)
            old(InputEvent.None)
            assertEquals(1, input.releases)
        }
    }

    @Test fun synchronousErrorAndFalseAreOneTerminalOutcome() = onMain {
        val error = InputEvent.Error(java.io.IOException("specific"))
        val input = Input().apply { synchronousEvent = error; returnAfterEvent = false }
        val coordinator = VoiceInputCoordinator(input, Evaluator())
        val events = mutableListOf<InputEvent>()
        assertTrue(coordinator.startPopup(Any()) { events.add(it) })
        assertEquals(listOf(error), events)
        assertEquals(1, input.releases)
        input.synchronousEvent = null
        input.returnAfterEvent = true
        assertEquals(true, coordinator.startService(Any(), {}, {}))
    }

    @Test fun popupDoneUsesDisplayedPartialAndRestoresPreviousOnError() = onMain {
        val input = Input()
        val popup = SttPopupViewModel(ApplicationProvider.getApplicationContext<Application>(),
            input, VoiceInputCoordinator(input, Evaluator()))
        popup.setTextFieldValue("old")
        popup.onSttClick()
        input.callback!!(InputEvent.Partial("new partial"))
        assertEquals(listOf("new partial" to 1f), popup.getUtterancesWithConfidence())
        input.callback!!(InputEvent.Error(java.io.IOException("error")))
        assertEquals(listOf("old" to 1f), popup.getUtterancesWithConfidence())
        popup.onSttClick()
        input.callback!!(InputEvent.Final(listOf("final" to 0.8f, "alternative" to 0.4f)))
        assertEquals(listOf("final" to 0.8f, "alternative" to 0.4f), popup.getUtterancesWithConfidence())
    }

    @Test fun homeTerminalBlocksForeignInputUntilOwnerRelease() = onMain {
        val input = Input()
        val evaluator = Evaluator()
        val coordinator = VoiceInputCoordinator(input, evaluator)
        val home = Any()
        val service = Any()
        assertTrue(coordinator.startHome(home))
        val old = input.callback!!
        old(InputEvent.Final(listOf("home" to 1f)))
        assertNull(coordinator.startService(service, {}, {}))
        assertFalse(coordinator.startPopup(Any(), {}))
        coordinator.release(service)
        assertEquals(0, evaluator.cancellations)
        coordinator.release(home)
        assertEquals(1, evaluator.cancellations)
        assertEquals(1, input.releases)
        assertEquals(1, input.starts) // no automatic retry
        assertEquals(true, coordinator.startService(service, {}, {}))
        old(InputEvent.None)
        coordinator.stop(service)
        assertEquals(1, input.stops)
        coordinator.cancel(service)
        assertEquals(2, input.releases)
    }

    @Test fun textBlocksForeignPopupAndServiceUntilOwnerRelease() = onMain {
        val input = Input()
        val evaluator = Evaluator()
        val coordinator = VoiceInputCoordinator(input, evaluator)
        val home = Any()
        val popup = Any()
        val service = Any()
        coordinator.submitText(home, "text")
        assertFalse(coordinator.startPopup(popup, {}))
        coordinator.release(popup)
        assertEquals(0, evaluator.cancellations)
        assertNull(coordinator.startService(service, {}, {}))
        coordinator.cancel(service)
        assertEquals(0, evaluator.cancellations)
        coordinator.release(home)
        assertEquals(1, evaluator.cancellations)
        assertEquals(0, input.starts)
        assertTrue(coordinator.startPopup(popup, {}))
        coordinator.release(popup)
        assertEquals(true, coordinator.startService(service, {}, {}))
    }

    @Test fun homeNoneBlocksPopupUntilEvaluationOwnerReleases() = onMain {
        val input = Input()
        val evaluator = Evaluator()
        val coordinator = VoiceInputCoordinator(input, evaluator)
        val home = Any()
        assertTrue(coordinator.start(home))
        input.callback!!(InputEvent.None)
        val popup = Any()
        assertFalse(coordinator.startPopup(popup, {}))
        coordinator.release(popup)
        assertEquals(0, evaluator.cancellations)
        coordinator.release(home)
        assertEquals(1, evaluator.cancellations)
        assertTrue(coordinator.startPopup(popup, {}))
    }

    @Test fun reentrantHideDuringTerminalTeardownCancelsEvaluationBeforeDelivery() = onMain {
        val input = Input()
        val evaluator = Evaluator()
        val coordinator = VoiceInputCoordinator(input, evaluator)
        val home = Any()
        assertTrue(coordinator.start(home))
        var reentrantResult: Boolean? = true
        input.onRelease = {
            input.onRelease = null
            reentrantResult = coordinator.startService(Any(), {}, {})
            coordinator.release(home)
        }
        input.callback!!(InputEvent.Final(listOf("must not execute" to 1f)))
        assertNull(reentrantResult)
        assertEquals(1, evaluator.cancellations)
        assertTrue(evaluator.events.isEmpty())
        assertEquals(true, coordinator.startService(Any(), {}, {}))
    }

    @Test fun oldPopupCallbackCannotReleaseNewInputOrEvaluation() = onMain {
        val input = Input()
        val evaluator = Evaluator()
        val coordinator = VoiceInputCoordinator(input, evaluator)
        val home = Any()
        val popup = Any()
        val service = Any()
        assertTrue(coordinator.startPopup(popup, {}))
        val old = input.callback!!
        old(InputEvent.None)
        coordinator.submitText(home, "text")
        assertNull(coordinator.startService(service, {}, {}))
        old(InputEvent.Final(listOf("late" to 1f)))
        coordinator.release(popup)
        assertEquals(0, evaluator.cancellations)
        coordinator.release(home)
        assertEquals(1, evaluator.cancellations)
        assertEquals(true, coordinator.startService(service, {}, {}))
        old(InputEvent.Final(listOf("late" to 1f)))
        coordinator.release(popup)
        coordinator.stop(service)
        assertEquals(1, input.stops)
        coordinator.release(home)
        assertEquals(1, evaluator.cancellations)
        assertEquals(1, input.releases)
    }
    @Test fun failedTerminalCleanupNeverExecutesFinalOrRetries() = onMain {
        val input = Input().apply { cleanupSucceeds = false }
        val evaluator = Evaluator()
        val coordinator = VoiceInputCoordinator(input, evaluator)
        assertTrue(coordinator.start(Any()))
        input.callback!!(InputEvent.Final(listOf("danger" to 1f)))
        assertEquals(1, evaluator.events.size)
        assertTrue(evaluator.events.single() is InputEvent.Error)
        assertEquals(1, input.releases)
        assertEquals(1, input.starts)
    }

    @Test fun failedPreemptionDoesNotStartAnotherRecording() = onMain {
        val input = Input().apply { cleanupSucceeds = false }
        val coordinator = VoiceInputCoordinator(input, Evaluator())
        assertEquals(true, coordinator.startService(Any(), {}, {}))
        assertFalse(coordinator.start(Any()))
        assertEquals(1, input.starts)
        assertEquals(1, input.releases)
    }

    @Test fun failedVoiceTeardownBlocksCurrentTextButNotLaterText() = onMain {
        val input = Input().apply { cleanupSucceeds = false }
        val evaluator = Evaluator()
        val coordinator = VoiceInputCoordinator(input, evaluator)
        assertTrue(coordinator.start(Any()))
        val late = input.callback!!
        coordinator.submitText(Any(), "must not execute")
        assertTrue(evaluator.events.none { it is InputEvent.Final })
        assertEquals(1, evaluator.cancellations)
        late(InputEvent.Final(listOf("late" to 1f)))
        assertTrue(evaluator.events.none { it is InputEvent.Final })
        coordinator.submitText(Any(), "still unsafe")
        assertTrue(evaluator.events.none { it is InputEvent.Final })
        input.cleanupSafe = true // Explicitly completed; no implicit retry or execution.
        coordinator.submitText(Any(), "explicit later text")
        assertEquals(listOf(InputEvent.Final(listOf("explicit later text" to 1f))), evaluator.events)
        assertEquals(1, input.releases)
    }

    @Test fun throwingInputTeardownStillCancelsEvaluatorAndNotifiesOwners() = onMain {
        val input = Input()
        val evaluator = Evaluator()
        val coordinator = VoiceInputCoordinator(input, evaluator)
        var revoked = 0
        assertTrue(coordinator.start(Any()) { revoked++ })
        input.onRelease = { throw IllegalStateException("injected") }
        assertFalse(coordinator.start(Any()))
        assertEquals(1, evaluator.cancellations)
        assertEquals(1, revoked)
        assertEquals(1, input.starts)
        input.onRelease = null
        assertFalse(coordinator.start(Any()))
        assertFalse(coordinator.startPopup(Any()) { })
        assertEquals(false, coordinator.startService(Any(), {}, {}))
        coordinator.submitText(Any(), "must remain blocked")
        assertTrue(evaluator.events.isEmpty())
        assertEquals(1, input.starts)
    }

    @Test fun evaluatorTeardownFailureBlocksNewExecutionAndStillNotifies() = onMain {
        val input = Input()
        val evaluator = Evaluator().apply { failCancel = true }
        val coordinator = VoiceInputCoordinator(input, evaluator)
        var revoked = 0
        assertTrue(coordinator.start(Any()) { revoked++ })
        coordinator.submitText(Any(), "blocked")
        assertEquals(1, input.releases)
        assertEquals(1, evaluator.cancellations)
        assertEquals(1, revoked)
        assertTrue(evaluator.events.none { it is InputEvent.Final })
        coordinator.submitText(Any(), "still blocked")
        assertFalse(coordinator.start(Any()))
        assertEquals(1, input.starts)
        assertTrue(evaluator.events.none { it is InputEvent.Final })
    }

    @Test fun pendingCleanupBlocksReentrantAndRepeatedTextUntilExplicitSafeRetry() = onMain {
        val input = Input().apply { cleanupSucceeds = false }
        val evaluator = Evaluator()
        val coordinator = VoiceInputCoordinator(input, evaluator)
        assertTrue(coordinator.start(Any()))
        input.onRelease = { coordinator.submitText(Any(), "reentrant") }
        coordinator.submitText(Any(), "transition")
        coordinator.submitText(Any(), "pending")
        assertTrue(evaluator.events.none { it is InputEvent.Final })
        assertEquals(1, input.releases)
        input.cleanupSafe = true
        coordinator.submitText(Any(), "explicit retry")
        assertEquals(listOf(InputEvent.Final(listOf("explicit retry" to 1f))), evaluator.events)
        assertEquals(1, input.releases)
    }
}
