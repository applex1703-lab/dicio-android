package org.stypox.dicio.io.input

import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.dicio.skill.skill.Permission
import org.junit.Assert.*
import org.junit.Test
import org.stypox.dicio.di.LocaleManager
import org.stypox.dicio.di.SttInputDeviceWrapperImpl
import org.stypox.dicio.eval.SkillEvaluator
import org.stypox.dicio.eval.VoicePresentation
import org.stypox.dicio.settings.datastore.UserSettingsModule
import org.stypox.dicio.ui.home.InteractionLog
import org.stypox.dicio.voice.VoiceInputCoordinator

class GoogleSpeechInputDeviceTest {
    private class FakeRecognizer : GoogleRecognizer {
        lateinit var callback: RecognitionListener
        var starts = 0
        var stops = 0
        var destroys = 0
        var failDestroy = false
        var onSetListener: ((RecognitionListener) -> Unit)? = null
        override fun setRecognitionListener(listener: RecognitionListener) { callback = listener; onSetListener?.invoke(listener) }
        override fun startListening(intent: Intent) { starts++ }
        override fun stopListening() { stops++ }
        override fun destroy() { destroys++; if (failDestroy) throw IllegalStateException("destroy") }
    }

    private class FakePlatform : GoogleSpeechPlatform {
        val recognizers = mutableListOf<FakeRecognizer>()
        val connections = mutableListOf<ServiceConnection>()
        val unbound = mutableListOf<ServiceConnection>()
        var bindResult = true
        var failUnbind = false
        var failBind = false
        var onCreate: ((FakeRecognizer) -> Unit)? = null
        var onBind: ((ServiceConnection) -> Unit)? = null
        override fun createRecognizer(): GoogleRecognizer = FakeRecognizer().also { recognizers.add(it); onCreate?.invoke(it) }
        override fun bind(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            connections.add(connection)
            if (failBind) throw SecurityException("bind")
            onBind?.invoke(connection)
            return bindResult
        }
        override fun unbind(connection: ServiceConnection) { unbound.add(connection); if (failUnbind) throw IllegalStateException("unbind") }
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun drain() = instrumentation.waitForIdleSync()
    private fun device(platform: FakePlatform): GoogleSpeechInputDevice {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return GoogleSpeechInputDevice(context, LocaleManager.newForPreviews(context), platform)
    }

    @Test fun rejectedBindFailsWithoutStartingRecognition() {
        val platform = FakePlatform().apply { bindResult = false }
        val device = device(platform)
        val events = mutableListOf<InputEvent>()
        onMain {
            device.onClick { events.add(it) }
            assertEquals(2, events.size)
            assertEquals(InputEvent.Started, events.first())
            assertTrue(events.last() is InputEvent.Error)
            assertEquals(0, platform.recognizers.single().starts)
            assertEquals(1, platform.recognizers.single().destroys)
            assertEquals(SttState.Loaded, device.uiState.value)
            runBlocking { device.destroy() }
        }
    }

    @Test fun stopBeforeConnectionRejectsQueuedAndLateCallbacks() {
        val platform = FakePlatform()
        val device = device(platform)
        val events = mutableListOf<InputEvent>()
        onMain {
            device.onClick { events.add(it) }
            val connection = platform.connections.single()
            // The connection callback posts its work, so cancel before that work runs.
            connection.onServiceConnected(null, Binder())
            device.stopListening()
            assertEquals(listOf(InputEvent.Started, InputEvent.None), events)
            assertEquals(SttState.Loaded, device.uiState.value)
            assertEquals(listOf(connection), platform.unbound)
            assertEquals(1, platform.recognizers.single().destroys)
            assertEquals(0, platform.recognizers.single().stops)
        }
        drain()
        onMain {
            val oldConnection = platform.connections.single()
            val oldRecognizer = platform.recognizers.single()
            oldConnection.onServiceConnected(null, Binder())
            oldRecognizer.callback.onReadyForSpeech(Bundle())
            oldRecognizer.callback.onError(SpeechRecognizer.ERROR_CLIENT)
            device.onClick { events.add(it) }
        }
        drain()
        onMain {
            assertEquals(0, platform.recognizers[0].starts)
            assertEquals(listOf(InputEvent.Started, InputEvent.None, InputEvent.Started), events)
            assertEquals(SttState.WaitingForResult, device.uiState.value)
            device.stopListening()
            assertEquals(2, platform.unbound.size)
            assertEquals(1, platform.recognizers[1].destroys)
            runBlocking { device.destroy() }
        }
    }

    @Test fun stopAfterConnectionWaitsForResultAndCleansUp() {
        val platform = FakePlatform()
        val device = device(platform)
        val events = mutableListOf<InputEvent>()
        onMain {
            device.onClick { events.add(it) }
            platform.connections.single().onServiceConnected(null, Binder())
        }
        drain()
        onMain {
            val recognizer = platform.recognizers.single()
            assertEquals(1, recognizer.starts)
            device.stopListening()
            assertEquals(1, recognizer.stops)
            assertEquals(0, recognizer.destroys)
            assertTrue(platform.unbound.isEmpty())
            assertEquals(listOf(InputEvent.Started), events)
            recognizer.callback.onResults(Bundle().apply {
                putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("hello"))
            })
            assertEquals(2, events.size)
            assertTrue(events.last() is InputEvent.Final)
            assertEquals(SttState.Loaded, device.uiState.value)
            assertEquals(1, platform.unbound.size)
            recognizer.callback.onError(SpeechRecognizer.ERROR_CLIENT)
            assertEquals(2, events.size)
            runBlocking { device.destroy() }
            assertEquals(1, recognizer.destroys)
            assertEquals(1, platform.unbound.size)
        }
    }

    @Test fun destroyBeforeConnectionCleansUpWithoutTerminalEvent() {
        val platform = FakePlatform()
        val device = device(platform)
        val events = mutableListOf<InputEvent>()
        onMain {
            device.onClick { events.add(it) }
            platform.connections.single().onServiceConnected(null, Binder())
            runBlocking { device.destroy() }
        }
        drain()
        onMain {
            platform.connections.single().onServiceConnected(null, Binder())
            platform.recognizers.single().callback.onReadyForSpeech(Bundle())
        }
        drain()
        onMain {
            assertEquals(listOf(InputEvent.Started), events)
            assertEquals(0, platform.recognizers.single().starts)
            assertEquals(1, platform.recognizers.single().destroys)
            assertEquals(1, platform.unbound.size)
            assertEquals(SttState.Loaded, device.uiState.value)
        }
    }
    @Test fun unbindFailureSuppressesFinalAndAttemptsDestroyOnce() {
        val platform = FakePlatform().apply { failUnbind = true }
        val device = device(platform)
        val events = mutableListOf<InputEvent>()
        onMain {
            device.onClick { events.add(it) }
            val recognizer = platform.recognizers.single()
            recognizer.failDestroy = true
            recognizer.callback.onResults(Bundle().apply {
                putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("danger"))
            })
            assertTrue(events.last() is InputEvent.Error)
            assertEquals(1, recognizer.destroys)
            assertEquals(1, platform.unbound.size)
            assertFalse(device.tryLoad(null))
            device.onClick { events.add(it) }
            assertTrue(events.last() is InputEvent.Error)
            assertEquals(1, platform.recognizers.size)
            try { runBlocking { device.destroy() }; fail("cleanup failure was hidden") }
            catch (_: java.io.IOException) { }
            try { runBlocking { device.destroy() }; fail("repeated destroy hid failure") }
            catch (_: java.io.IOException) { }
            assertEquals(1, recognizer.destroys)
            assertEquals(1, platform.unbound.size)
        }
    }

    @Test fun destroyFailureStillUnbindsAndCannotRestart() {
        val platform = FakePlatform()
        val device = device(platform)
        onMain {
            device.onClick { }
            platform.recognizers.single().failDestroy = true
            device.stopListening()
            assertEquals(1, platform.unbound.size)
            assertEquals(1, platform.recognizers.single().destroys)
            assertFalse(device.tryLoad(null))
            try { runBlocking { device.destroy() }; fail("cleanup failure was hidden") }
            catch (_: java.io.IOException) { }
        }
    }

    @Test fun offMainDestroyWaitsAndPropagatesFailure() {
        val platform = FakePlatform().apply { failUnbind = true }
        val device = device(platform)
        onMain { device.onClick { } }
        try { runBlocking { device.destroy() }; fail("cleanup failure was hidden") }
        catch (_: java.io.IOException) { }
        assertEquals(1, platform.unbound.size)
        assertEquals(1, platform.recognizers.single().destroys)
    }

    private class Evaluator : SkillEvaluator {
        override val currentOutput = MutableStateFlow<org.dicio.skill.skill.SkillOutput?>(null)
        override val state = MutableStateFlow(InteractionLog(emptyList(), null))
        override val voicePresentation = MutableStateFlow(VoicePresentation())
        override var permissionRequester: suspend (List<Permission>) -> Boolean = { false }
        val events = mutableListOf<InputEvent>()
        override fun processInputEvent(event: InputEvent) { events.add(event) }
        override fun cancelPendingInput() = Unit
        override fun resetVoicePresentation() = Unit
        override fun selectChoice(choiceId: Long, optionId: String) = Unit
    }

    @Test fun alreadyCancelledCallerStillWaitsForCleanupAndSeesFailure() {
        val platform = FakePlatform().apply { failUnbind = true }
        val device = device(platform)
        onMain { device.onClick { } }
        var failure: Throwable? = null
        try {
            runBlocking {
                coroutineContext[Job]!!.cancel()
                try { device.destroy() } catch (error: Throwable) { failure = error }
            }
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertTrue(failure is java.io.IOException)
        assertEquals(1, platform.unbound.size)
        assertEquals(1, platform.recognizers.single().destroys)
    }

    @Test fun cancellationWhileMainIsBlockedDoesNotFinishBeforeCleanup() {
        val platform = FakePlatform()
        val device = device(platform)
        onMain { device.onClick { } }
        val mainBlocked = CountDownLatch(1)
        val unblockMain = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            mainBlocked.countDown()
            unblockMain.await(5, TimeUnit.SECONDS)
        }
        assertTrue(mainBlocked.await(5, TimeUnit.SECONDS))
        try {
            runBlocking {
                val entered = CompletableDeferred<Unit>()
                val finished = CompletableDeferred<Unit>()
                val job = launch(Dispatchers.Default) {
                    try {
                        entered.complete(Unit)
                        device.destroy()
                    } finally { finished.complete(Unit) }
                }
                entered.await()
                job.cancel()
                assertFalse(finished.isCompleted)
                unblockMain.countDown()
                job.join()
                assertTrue(finished.isCompleted)
            }
        } finally { unblockMain.countDown() }
        assertEquals(1, platform.unbound.size)
        assertEquals(1, platform.recognizers.single().destroys)
    }

    @Test fun bindExceptionCleansUpAndRejectsLateCallbacks() {
        val platform = FakePlatform().apply { failBind = true }
        val device = device(platform)
        val events = mutableListOf<InputEvent>()
        onMain {
            device.onClick { events.add(it) }
            assertEquals(2, events.size)
            assertTrue(events.last() is InputEvent.Error)
            assertEquals(1, platform.recognizers.single().destroys)
            assertEquals(1, platform.unbound.size)
            platform.connections.single().onServiceConnected(null, Binder())
            platform.recognizers.single().callback.onResults(Bundle().apply {
                putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("late"))
            })
        }
        drain()
        onMain {
            assertEquals(2, events.size)
            assertEquals(0, platform.recognizers.single().starts)
            runBlocking { device.destroy() }
        }
    }

    @Test fun synchronousListenerTerminalDoesNotBindAfterDestroy() {
        val platform = FakePlatform().apply {
            onCreate = { recognizer -> recognizer.onSetListener = { it.onError(SpeechRecognizer.ERROR_CLIENT) } }
        }
        val device = device(platform)
        val events = mutableListOf<InputEvent>()
        onMain {
            device.onClick {
                events.add(it)
                if (it is InputEvent.Error) runBlocking { device.destroy() }
            }
            assertEquals(2, events.size)
            assertTrue(events.last() is InputEvent.Error)
            assertTrue(platform.connections.isEmpty())
            assertEquals(1, platform.recognizers.single().destroys)
        }
    }

    @Test fun realCoordinatorAndWrapperSuppressFinalWhenDestroyFailsAfterUnbind() {
        val platform = FakePlatform()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val locale = LocaleManager.newForPreviews(context)
        var builds = 0
        val input = SttInputDeviceWrapperImpl(context, UserSettingsModule.newDataStoreForPreviews(), locale) {
            builds++
            GoogleSpeechInputDevice(context, locale, platform)
        }
        val evaluator = Evaluator()
        val coordinator = VoiceInputCoordinator(input, evaluator)
        onMain {
            assertTrue(coordinator.start(Any()))
            platform.connections.single().onServiceConnected(null, Binder())
        }
        drain()
        onMain {
            val recognizer = platform.recognizers.single()
            assertEquals(1, recognizer.starts)
            recognizer.failDestroy = true
            recognizer.callback.onResults(Bundle().apply {
                putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("must not execute"))
            })
            assertEquals(1, platform.unbound.size)
            assertEquals(1, recognizer.destroys)
            assertFalse(evaluator.events.any { it is InputEvent.Final })
            assertTrue(evaluator.events.any { it is InputEvent.Error })
            assertFalse(input.tryLoad(null))
            assertFalse(coordinator.start(Any()))
            recognizer.callback.onResults(Bundle().apply {
                putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("late"))
            })
            assertFalse(evaluator.events.any { it is InputEvent.Final })
            assertEquals(1, recognizer.destroys)
            assertEquals(1, builds)
        }
    }

    @Test fun terminalDuringBindCannotScheduleOrRunOldTimeoutAgainstNewSession() {
        val platform = FakePlatform()
        val device = device(platform)
        val firstEvents = mutableListOf<InputEvent>()
        val secondEvents = mutableListOf<InputEvent>()
        lateinit var oldDeadline: Runnable
        onMain {
            platform.onBind = {
                if (platform.connections.size == 1) {
                    val field = GoogleSpeechInputDevice::class.java.getDeclaredField("bindTimeout")
                    field.isAccessible = true
                    oldDeadline = field.get(device) as Runnable
                    platform.recognizers.single().callback.onResults(Bundle().apply {
                        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("first"))
                    })
                }
            }
            device.onClick { event ->
                firstEvents.add(event)
                if (event is InputEvent.Final) device.onClick { secondEvents.add(it) }
            }
            assertTrue(firstEvents.last() is InputEvent.Final)
            assertEquals(listOf(InputEvent.Started), secondEvents)
            assertEquals(2, platform.connections.size)
            val field = GoogleSpeechInputDevice::class.java.getDeclaredField("main")
            field.isAccessible = true
            val handler = field.get(device) as Handler
            if (android.os.Build.VERSION.SDK_INT >= 29) assertFalse(handler.hasCallbacks(oldDeadline))
            oldDeadline.run() // A stale runnable must also be harmless if already dispatched.
            assertEquals(listOf(InputEvent.Started), secondEvents)
            assertEquals(0, platform.recognizers.single().destroys)
            platform.connections.last().onServiceConnected(null, Binder())
        }
        drain()
        onMain {
            val recognizer = platform.recognizers.single()
            assertEquals(1, recognizer.starts)
            recognizer.callback.onResults(Bundle().apply {
                putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("second"))
            })
            assertTrue(secondEvents.last() is InputEvent.Final)
            runBlocking { device.destroy() }
        }
    }

    @Test fun realCoordinatorAndWrapperDeliverFinalAfterSuccessfulCleanup() {
        val platform = FakePlatform()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val locale = LocaleManager.newForPreviews(context)
        var builds = 0
        val input = SttInputDeviceWrapperImpl(context, UserSettingsModule.newDataStoreForPreviews(), locale) {
            builds++
            GoogleSpeechInputDevice(context, locale, platform)
        }
        val evaluator = Evaluator()
        val coordinator = VoiceInputCoordinator(input, evaluator)
        val owner = Any()
        onMain {
            assertTrue(coordinator.start(owner))
            platform.connections.single().onServiceConnected(null, Binder())
        }
        drain()
        onMain {
            val recognizer = platform.recognizers.single()
            assertEquals(1, recognizer.starts)
            recognizer.callback.onResults(Bundle().apply {
                putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("safe"))
            })
            assertEquals(listOf(InputEvent.Final(listOf("safe" to 1f))), evaluator.events.filterIsInstance<InputEvent.Final>())
            assertEquals(1, platform.unbound.size)
            assertEquals(1, recognizer.destroys)
            assertEquals(2, builds)
            recognizer.callback.onError(SpeechRecognizer.ERROR_CLIENT)
            assertEquals(1, evaluator.events.filterIsInstance<InputEvent.Final>().size)
            coordinator.release(owner)
        }
    }
}
