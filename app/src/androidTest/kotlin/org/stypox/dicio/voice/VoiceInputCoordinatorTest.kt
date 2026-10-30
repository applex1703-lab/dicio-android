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
        var onRelease: (() -> Unit)? = null
        override fun tryLoad(thenStartListeningEventListener: ((InputEvent) -> Unit)?): Boolean {
            if (!available) return false
            callback = thenStartListeningEventListener
            starts++
            synchronousEvent?.let { thenStartListeningEventListener?.invoke(it) }
            return returnAfterEvent
        }
        override fun stopListening() { stops++ }
        override fun onClick(eventListener: (InputEvent) -> Unit) = Unit
        override fun reinitializeToReleaseResources() { releases++; onRelease?.invoke() }
    }

    private class Evaluator : SkillEvaluator {
        override val currentOutput = MutableStateFlow<org.dicio.skill.skill.SkillOutput?>(null)
        override val state = MutableStateFlow(InteractionLog(emptyList(), null))
        override val voicePresentation = MutableStateFlow(VoicePresentation())
        override var permissionRequester: suspend (List<Permission>) -> Boolean = { false }
        val events = mutableListOf<InputEvent>()
        var cancellations = 0
        override fun processInputEvent(event: InputEvent) { events.add(event) }
        override fun cancelPendingInput() { cancellations++ }
        override fun resetVoicePresentation() = Unit
    }

    private fun onMain(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

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
        coordinator.release(owner)
        assertEquals(1, evaluator.cancellations)
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
}
