package org.stypox.dicio.io.input

import android.content.Context
import android.app.AppOpsManager
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
        var onStart: ((RecognitionListener) -> Unit)? = null
        override fun setRecognitionListener(listener: RecognitionListener) { callback = listener; onSetListener?.invoke(listener) }
        override fun startListening(intent: Intent) { starts++; onStart?.invoke(callback) }
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
        var mode: Int? = null
        var clock = 0L
        val diagnostics = mutableListOf<Triple<String, Long, Int?>>()
        override fun diagnostic(event: String, elapsedMs: Long, chars: Int?) {
            diagnostics.add(Triple(event, elapsedMs, chars))
        }
        var checks = 0
        var checkFailure: Exception? = null
        var onCheck: (() -> Unit)? = null
        var token: Any? = null
        override fun prebindToken() = token
        override fun prebindCurrent(token: Any?) = token != null && token === this.token
        override fun elapsedRealtime() = clock
        override fun requiresAudioOpWait() = mode != null || checkFailure != null
        override fun audioOpMode(): Int? {
            checks++
            checkFailure?.let { throw it }
            onCheck?.invoke()
            return mode
        }
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

    private fun poll(device: GoogleSpeechInputDevice): Runnable {
        val field = GoogleSpeechInputDevice::class.java.getDeclaredField("appOpsPoll")
        field.isAccessible = true
        return field.get(device) as Runnable
    }

    @Test fun diagnosticsContainOnlyRelativeTimingAndLengths() {
        val platform = FakePlatform().apply { clock = 100 }
        val device = device(platform)
        onMain { device.onClick { }; platform.connections.single().onServiceConnected(null, Binder()) }
        drain()
        onMain {
            val callback = platform.recognizers.single().callback
            platform.clock = 110; callback.onReadyForSpeech(Bundle())
            platform.clock = 120; callback.onBeginningOfSpeech()
            platform.clock = 130; callback.onPartialResults(Bundle().apply {
                putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("secret"))
            })
            platform.clock = 140; callback.onEndOfSpeech()
            platform.clock = 150; device.stopListening()
            platform.clock = 160; callback.onResults(Bundle().apply {
                putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("private"))
            })
            callback.onReadyForSpeech(Bundle()) // stale callbacks are ignored
            assertEquals(listOf(
                Triple("ready", 10L, null), Triple("begin", 20L, null),
                Triple("partial", 30L, 6), Triple("end", 40L, null),
                Triple("stop", 50L, null), Triple("final", 60L, 7),
            ), platform.diagnostics)
            runBlocking { device.destroy() }
        }
    }

    @Test fun synchronousCallbacksAreLoggedOnceAtArrival() {
        val platform = FakePlatform().apply {
            clock = 100
            onCreate = { recognizer -> recognizer.onStart = { callback ->
                clock = 105; callback.onReadyForSpeech(Bundle())
                clock = 108; callback.onResults(Bundle())
            } }
        }
        val device = device(platform)
        onMain { device.onClick { }; platform.connections.single().onServiceConnected(null, Binder()) }
        drain()
        onMain {
            assertEquals(listOf(Triple("ready", 5L, null), Triple("final", 8L, 0)), platform.diagnostics)
            assertEquals(1, platform.recognizers.single().starts)
            runBlocking { device.destroy() }
        }
    }

    @Test fun synchronousCallbacksAfterTerminalAreNotLogged() {
        for (errorTerminal in listOf(false, true)) {
            val platform = FakePlatform().apply {
                clock = 100
                onCreate = { recognizer -> recognizer.onStart = { callback ->
                    clock = 105
                    if (errorTerminal) callback.onError(SpeechRecognizer.ERROR_NO_MATCH)
                    else callback.onResults(Bundle())
                    clock = 110
                    callback.onBeginningOfSpeech()
                    callback.onReadyForSpeech(Bundle())
                    callback.onEndOfSpeech()
                    callback.onPartialResults(Bundle().apply {
                        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("discarded"))
                    })
                    callback.onResults(Bundle().apply {
                        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("discarded"))
                    })
                } }
            }
            val device = device(platform)
            val events = mutableListOf<InputEvent>()
            onMain { device.onClick { events.add(it) }; platform.connections.single().onServiceConnected(null, Binder()) }
            drain()
            onMain {
                assertEquals(if (errorTerminal) emptyList<Triple<String, Long, Int?>>()
                    else listOf(Triple("final", 5L, 0)), platform.diagnostics)
                assertEquals(1, events.count { it is InputEvent.Error })
                assertEquals(1, platform.recognizers.single().starts)
                runBlocking { device.destroy() }
            }
        }
    }

    @Test fun connectedPrebindFastPathRequiresActionAndAllowed() {
        val platform = FakePlatform().apply { token = Any(); mode = AppOpsManager.MODE_ALLOWED }
        val device = device(platform)
        onMain {
            assertTrue(platform.recognizers.isEmpty())
            assertTrue(device.tryLoad(null))
            assertTrue(platform.recognizers.isEmpty())
            device.onClick { }
            assertEquals(0, platform.recognizers.single().starts)
            platform.connections.single().onServiceConnected(null, Binder())
        }
        drain()
        onMain {
            assertEquals(1, platform.recognizers.single().starts)
            runBlocking { device.destroy() }
        }
    }

    @Test fun fastPathFallsBackWhenGenerationChangesOrArrivesLate() {
        for (late in listOf(false, true)) {
            val platform = FakePlatform().apply { token = if (late) null else Any(); mode = AppOpsManager.MODE_ALLOWED }
            val device = device(platform)
            onMain {
                device.onClick { }
                platform.token = Any()
                platform.connections.single().onServiceConnected(null, Binder())
            }
            drain()
            onMain {
                assertEquals(0, platform.recognizers.single().starts)
                platform.clock = 6000
                poll(device).run()
                assertEquals(1, platform.recognizers.single().starts)
                runBlocking { device.destroy() }
            }
        }
    }

    @Test fun prebindInvalidatedDuringAppOpsCheckFallsBack() {
        val platform = FakePlatform().apply {
            token = Any(); mode = AppOpsManager.MODE_ALLOWED
            onCheck = { token = null }
        }
        val device = device(platform)
        onMain { device.onClick { }; platform.connections.single().onServiceConnected(null, Binder()) }
        drain()
        onMain {
            assertEquals(0, platform.recognizers.single().starts)
            platform.clock = 6000
            poll(device).run()
            assertEquals(1, platform.recognizers.single().starts)
            runBlocking { device.destroy() }
        }
    }

    @Test fun fastPathStillWaitsForAllowedAndDoesNotRetryErrorNine() {
        val platform = FakePlatform().apply { token = Any(); mode = AppOpsManager.MODE_IGNORED }
        val device = device(platform)
        val events = mutableListOf<InputEvent>()
        onMain { device.onClick { events.add(it) }; platform.connections.single().onServiceConnected(null, Binder()) }
        drain()
        onMain {
            assertEquals(0, platform.recognizers.single().starts)
            platform.mode = AppOpsManager.MODE_ALLOWED
            poll(device).run()
            assertEquals(1, platform.recognizers.single().starts)
            platform.recognizers.single().callback.onError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            assertTrue(events.last() is InputEvent.Error)
            val message = (events.last() as InputEvent.Error).throwable.message.orEmpty()
            assertTrue(message.contains("ошибка 9"))
            assertTrue(message.contains("автоматического повтора нет"))
            assertEquals(1, platform.recognizers.single().starts)
            assertEquals(1, platform.unbound.size)
            runBlocking { device.destroy() }
        }
    }

    @Test fun appOpsDelayedAllowedStartsAtMostOnce() {
        val platform = FakePlatform().apply { mode = AppOpsManager.MODE_IGNORED }
        val device = device(platform)
        val events = mutableListOf<InputEvent>()
        onMain { device.onClick { events.add(it) }; platform.connections.single().onServiceConnected(null, Binder()) }
        drain()
        onMain {
            val runnable = poll(device)
            assertEquals(0, platform.recognizers.single().starts)
            platform.clock = 6000
            platform.mode = AppOpsManager.MODE_ALLOWED
            runnable.run()
            runnable.run()
            platform.connections.single().onServiceConnected(null, Binder())
            assertEquals(1, platform.recognizers.single().starts)
            device.stopListening()
            runBlocking { device.destroy() }
        }
        drain()
        assertEquals(1, platform.recognizers.single().starts)
        assertEquals(1, platform.unbound.size)
    }

    @Test fun appOpsTimeoutAndLateCallbacksNeverStart() {
        val platform = FakePlatform().apply { mode = AppOpsManager.MODE_IGNORED }
        val device = device(platform)
        val events = mutableListOf<InputEvent>()
        onMain { device.onClick { events.add(it) }; platform.connections.single().onServiceConnected(null, Binder()) }
        drain()
        onMain {
            val runnable = poll(device)
            platform.clock = 8000
            platform.mode = AppOpsManager.MODE_ALLOWED
            runnable.run()
            runnable.run()
            platform.connections.single().onServiceConnected(null, Binder())
            platform.recognizers.single().callback.onError(SpeechRecognizer.ERROR_CLIENT)
            assertEquals(2, events.size)
            assertTrue(events.last() is InputEvent.Error)
            assertEquals(0, platform.recognizers.single().starts)
            assertEquals(1, platform.recognizers.single().destroys)
            assertEquals(1, platform.unbound.size)
            runBlocking { device.destroy() }
        }
        drain()
        assertEquals(0, platform.recognizers.single().starts)
    }

    @Test fun appOpsCancelAndDestroyRejectQueuedPolls() {
        for (destroy in listOf(false, true)) {
            val platform = FakePlatform().apply { mode = AppOpsManager.MODE_IGNORED }
            val device = device(platform)
            val events = mutableListOf<InputEvent>()
            onMain { device.onClick { events.add(it) }; platform.connections.single().onServiceConnected(null, Binder()) }
            drain()
            onMain {
                val runnable = poll(device)
                if (destroy) runBlocking { device.destroy() } else device.stopListening()
                platform.mode = AppOpsManager.MODE_ALLOWED
                runnable.run()
                assertEquals(0, platform.recognizers.single().starts)
                assertEquals(1, platform.recognizers.single().destroys)
                assertEquals(1, platform.unbound.size)
                assertEquals(if (destroy) listOf(InputEvent.Started) else listOf(InputEvent.Started, InputEvent.None), events)
                if (!destroy) runBlocking { device.destroy() }
            }
        }
    }

    @Test fun appOpsErrorsFailClosed() {
        for (failure in listOf(false, true)) {
            val platform = FakePlatform().apply {
                mode = AppOpsManager.MODE_ERRORED
                if (failure) checkFailure = SecurityException("uid or appops")
            }
            val device = device(platform)
            val events = mutableListOf<InputEvent>()
            onMain { device.onClick { events.add(it) }; platform.connections.single().onServiceConnected(null, Binder()) }
            drain()
            onMain {
                platform.clock = 6000
                poll(device).run()
                assertTrue(events.last() is InputEvent.Error)
                assertEquals(0, platform.recognizers.single().starts)
                assertEquals(1, platform.recognizers.single().destroys)
                assertEquals(1, platform.unbound.size)
                runBlocking { device.destroy() }
            }
        }
    }

    @Test fun appOpsEarlyAllowedWaitsAndReentrantCancellationDoesNotStart() {
        for (cancel in listOf(false, true)) {
            val platform = FakePlatform().apply { mode = AppOpsManager.MODE_ALLOWED }
            val device = device(platform)
            if (cancel) platform.onCheck = { device.stopListening() }
            onMain { device.onClick { }; platform.connections.single().onServiceConnected(null, Binder()) }
            drain()
            onMain {
                assertEquals(0, platform.recognizers.single().starts)
                assertEquals(0, platform.checks)
                val runnable = poll(device)
                platform.clock = 5999
                runnable.run()
                assertEquals(0, platform.checks)
                assertEquals(0, platform.recognizers.single().starts)
                platform.clock = 6000
                runnable.run()
                runnable.run()
                assertEquals(if (cancel) 0 else 1, platform.recognizers.single().starts)
                runBlocking { device.destroy() }
            }
        }
    }

    @Test fun appOpsLongCheckCannotStartAfterDeadline() {
        val platform = FakePlatform().apply {
            mode = AppOpsManager.MODE_ALLOWED
            onCheck = { clock = 8000 }
        }
        val device = device(platform)
        val events = mutableListOf<InputEvent>()
        onMain { device.onClick { events.add(it) }; platform.connections.single().onServiceConnected(null, Binder()) }
        drain()
        onMain {
            val runnable = poll(device)
            platform.clock = 5999
            runnable.run()
            assertEquals(0, platform.checks)
            platform.clock = 6000
            runnable.run()
            assertTrue(platform.checks >= 1)
            assertEquals(0, platform.recognizers.single().starts)
            assertTrue(events.last() is InputEvent.Error)
            assertEquals(1, platform.unbound.size)
            runBlocking { device.destroy() }
        }
    }

    @Test fun callbacksBeforeStartCannotProduceEventsOrChangeState() {
        val platform = FakePlatform().apply { mode = AppOpsManager.MODE_ALLOWED }
        val device = device(platform)
        val events = mutableListOf<InputEvent>()
        onMain {
            device.onClick { events.add(it) }
            val callback = platform.recognizers.single().callback
            callback.onReadyForSpeech(Bundle())
            callback.onPartialResults(Bundle().apply {
                putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("early"))
            })
            callback.onResults(Bundle().apply {
                putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("early"))
            })
            callback.onError(SpeechRecognizer.ERROR_CLIENT)
            assertEquals(listOf(InputEvent.Started), events)
            assertEquals(SttState.WaitingForResult, device.uiState.value)
            platform.connections.single().onServiceConnected(null, Binder())
        }
        drain()
        onMain {
            val callback = platform.recognizers.single().callback
            callback.onReadyForSpeech(Bundle())
            callback.onResults(Bundle().apply {
                putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("during wait"))
            })
            assertEquals(listOf(InputEvent.Started), events)
            assertEquals(SttState.WaitingForResult, device.uiState.value)
            assertEquals(0, platform.recognizers.single().starts)
            device.stopListening()
            runBlocking { device.destroy() }
        }
    }

    @Test fun synchronousCallbacksFromStartAreAcceptedExactlyOnce() {
        lateinit var device: GoogleSpeechInputDevice
        var ready = false
        val events = mutableListOf<InputEvent>()
        val platform = FakePlatform().apply {
            onCreate = { recognizer -> recognizer.onStart = { callback ->
                callback.onReadyForSpeech(Bundle())
                callback.onPartialResults(Bundle().apply {
                    putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("partial"))
                })
                callback.onResults(Bundle().apply {
                    putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("valid"))
                })
                assertEquals(listOf(InputEvent.Started), events)
                callback.onError(SpeechRecognizer.ERROR_CLIENT)
            } }
        }
        device = this.device(platform)
        onMain {
            device.onClick {
                if (it is InputEvent.Partial) ready = device.uiState.value == SttState.Listening
                events.add(it)
            }
            platform.connections.single().onServiceConnected(null, Binder())
        }
        drain()
        onMain {
            assertEquals(1, platform.recognizers.single().starts)
            assertTrue(ready)
            assertEquals(3, events.size)
            assertTrue(events.last() is InputEvent.Final)
            assertEquals(1, platform.unbound.size)
            runBlocking { device.destroy() }
        }
    }

    @Test fun synchronousFinalThenStartThrowsNeverDeliversFinalAndQuarantines() {
        val events = mutableListOf<InputEvent>()
        val platform = FakePlatform().apply {
            onCreate = { it.onStart = { callback ->
                callback.onResults(Bundle().apply {
                    putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("must not execute"))
                })
                assertEquals(listOf(InputEvent.Started), events)
                throw SecurityException("uncertain start")
            } }
        }
        val device = device(platform)
        onMain { device.onClick { events.add(it) }; platform.connections.single().onServiceConnected(null, Binder()) }
        drain()
        onMain {
            assertFalse(events.any { it is InputEvent.Final })
            assertTrue(events.last() is InputEvent.Error)
            assertEquals(1, platform.recognizers.single().starts)
            assertEquals(1, platform.recognizers.single().destroys)
            assertEquals(1, platform.unbound.size)
            assertFalse(device.tryLoad(null))
            try { runBlocking { device.destroy() }; fail("uncertain start was hidden") }
            catch (_: java.io.IOException) { }
        }
    }

    @Test fun offMainDestroySuppressesInFlightTerminalBeforeMainCleanup() {
        lateinit var device: GoogleSpeechInputDevice
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val events = mutableListOf<InputEvent>()
        val platform = FakePlatform().apply {
            onCreate = { it.onStart = { callback ->
                Thread {
                    entered.countDown()
                    runBlocking { device.destroy() }
                    finished.countDown()
                }.start()
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val closedField = GoogleSpeechInputDevice::class.java.getDeclaredField("closed")
                closedField.isAccessible = true
                val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!closedField.getBoolean(device) && System.nanoTime() < limit) Thread.yield()
                assertTrue(closedField.getBoolean(device))
                assertFalse(finished.await(100, TimeUnit.MILLISECONDS))
                callback.onResults(Bundle().apply {
                    putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("ordered"))
                })
                assertEquals(listOf(InputEvent.Started), events)
            } }
        }
        device = this.device(platform)
        onMain { device.onClick { events.add(it) }; platform.connections.single().onServiceConnected(null, Binder()) }
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        drain()
        assertTrue(events.none { it is InputEvent.Final })
        assertEquals(1, platform.recognizers.single().starts)
        assertEquals(1, platform.recognizers.single().destroys)
        assertEquals(1, platform.unbound.size)
    }

    @Test fun reentrantDestroyDuringStartFailsAndLateEffectCannotDeliverFinal() {
        lateinit var device: GoogleSpeechInputDevice
        var failure: Throwable? = null
        var lateEffect = false
        val events = mutableListOf<InputEvent>()
        val platform = FakePlatform().apply {
            onCreate = { it.onStart = { callback ->
                try { runBlocking { device.destroy() } } catch (error: Throwable) { failure = error }
                lateEffect = true // The old platform call can still act after attempted cleanup.
                callback.onResults(Bundle().apply {
                    putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("late"))
                })
                throw SecurityException("late start failure")
            } }
        }
        device = this.device(platform)
        onMain { device.onClick { events.add(it) }; platform.connections.single().onServiceConnected(null, Binder()) }
        drain()
        onMain {
            assertTrue(lateEffect)
            assertTrue(failure is java.io.IOException)
            assertEquals(listOf(InputEvent.Started), events)
            assertEquals(1, platform.recognizers.single().starts)
            assertEquals(1, platform.recognizers.single().destroys)
            assertEquals(1, platform.unbound.size)
            assertFalse(device.tryLoad(null))
            try { runBlocking { device.destroy() }; fail("uncertain cleanup was hidden") }
            catch (_: java.io.IOException) { }
        }
    }

    @Test fun offMainDestroyBeforeQueuedConnectionPreventsStart() {
        val platform = FakePlatform()
        val device = device(platform)
        val finished = CountDownLatch(1)
        val events = mutableListOf<InputEvent>()
        onMain {
            device.onClick { events.add(it) }
            platform.connections.single().onServiceConnected(null, Binder())
            Thread { runBlocking { device.destroy() }; finished.countDown() }.start()
            val closedField = GoogleSpeechInputDevice::class.java.getDeclaredField("closed")
            closedField.isAccessible = true
            val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!closedField.getBoolean(device) && System.nanoTime() < limit) Thread.yield()
            assertTrue(closedField.getBoolean(device))
            assertFalse(finished.await(100, TimeUnit.MILLISECONDS))
        }
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        drain()
        assertEquals(0, platform.recognizers.single().starts)
        assertEquals(listOf(InputEvent.Started), events)
        assertEquals(1, platform.unbound.size)
        assertEquals(1, platform.recognizers.single().destroys)
    }

    @Test fun synchronousErrorFromStartIsDeliveredAndCleansUp() {
        val platform = FakePlatform().apply {
            onCreate = { it.onStart = { callback -> callback.onError(SpeechRecognizer.ERROR_CLIENT) } }
        }
        val device = device(platform)
        val events = mutableListOf<InputEvent>()
        onMain { device.onClick { events.add(it) }; platform.connections.single().onServiceConnected(null, Binder()) }
        drain()
        onMain {
            assertEquals(1, platform.recognizers.single().starts)
            assertEquals(2, events.size)
            assertTrue(events.last() is InputEvent.Error)
            assertEquals(1, platform.recognizers.single().destroys)
            assertEquals(1, platform.unbound.size)
            runBlocking { device.destroy() }
        }
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
            platform.connections.single().onServiceConnected(null, Binder())
        }
        drain()
        onMain {
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

    @Test fun synchronousListenerTerminalBeforeStartIsIgnored() {
        val platform = FakePlatform().apply {
            onCreate = { recognizer -> recognizer.onSetListener = { it.onError(SpeechRecognizer.ERROR_CLIENT) } }
        }
        val device = device(platform)
        val events = mutableListOf<InputEvent>()
        onMain {
            device.onClick { events.add(it) }
            assertEquals(listOf(InputEvent.Started), events)
            assertEquals(1, platform.connections.size)
            device.stopListening()
            assertEquals(1, platform.recognizers.single().destroys)
            runBlocking { device.destroy() }
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

    @Test fun cancellationDuringBindCannotScheduleOrRunOldTimeoutAgainstNewSession() {
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
                    device.stopListening()
                }
            }
            device.onClick { event ->
                firstEvents.add(event)
                if (event == InputEvent.None) device.onClick { secondEvents.add(it) }
            }
            assertEquals(InputEvent.None, firstEvents.last())
            assertEquals(listOf(InputEvent.Started), secondEvents)
            assertEquals(2, platform.connections.size)
            val field = GoogleSpeechInputDevice::class.java.getDeclaredField("main")
            field.isAccessible = true
            val handler = field.get(device) as Handler
            if (android.os.Build.VERSION.SDK_INT >= 29) assertFalse(handler.hasCallbacks(oldDeadline))
            oldDeadline.run() // A stale runnable must also be harmless if already dispatched.
            assertEquals(listOf(InputEvent.Started), secondEvents)
            assertEquals(1, platform.recognizers.first().destroys)
            platform.connections.last().onServiceConnected(null, Binder())
        }
        drain()
        onMain {
            val recognizer = platform.recognizers.last()
            assertEquals(1, recognizer.starts)
            recognizer.callback.onResults(Bundle().apply {
                putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("second"))
            })
            assertTrue(secondEvents.last() is InputEvent.Final)
            runBlocking { device.destroy() }
        }
    }

    @Test fun staleBindExceptionQuarantinesReentrantSessionWithoutMisattributingIt() {
        val platform = FakePlatform()
        val device = device(platform)
        val firstEvents = mutableListOf<InputEvent>()
        val secondEvents = mutableListOf<InputEvent>()
        onMain {
            platform.onBind = {
                if (platform.connections.size == 1) {
                    device.stopListening()
                    throw SecurityException("old bind failed after callback")
                }
            }
            device.onClick { event ->
                firstEvents.add(event)
                if (event == InputEvent.None) device.onClick { secondEvents.add(it) }
            }
            assertEquals(InputEvent.None, firstEvents.last())
            assertEquals(2, platform.connections.size)
            assertEquals(InputEvent.Started, secondEvents.first())
            assertTrue(secondEvents.last() is InputEvent.Error)
            val failure = (secondEvents.last() as InputEvent.Error).throwable
            assertTrue(failure.cause?.message?.contains("Stale Google bind") == true)
            assertEquals(2, platform.unbound.size)
            assertEquals(1, platform.recognizers.first().destroys)
            assertEquals(1, platform.recognizers.last().destroys)
            assertFalse(device.tryLoad(null))
            platform.recognizers.last().callback.onResults(Bundle().apply {
                putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("late"))
            })
            assertEquals(2, secondEvents.size)
            repeat(2) {
                try { runBlocking { device.destroy() }; fail("stale bind failure was hidden") }
                catch (_: java.io.IOException) { }
            }
            assertEquals(1, platform.recognizers.last().destroys)
        }
    }

    @Test fun synchronousBindTerminalCannotReachEvaluatorBeforeBindReturns() {
        val platform = FakePlatform().apply {
            onBind = {
                recognizers.single().callback.onResults(Bundle().apply {
                    putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("must not execute"))
                })
                throw SecurityException("bind failed after terminal")
            }
        }
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
            assertTrue(evaluator.events.none { it is InputEvent.Final })
            assertTrue(evaluator.events.any { it is InputEvent.Error })
            assertTrue(input.isCleanupSafe())
            assertTrue(builds >= 1)
            assertEquals(1, platform.unbound.size)
            assertEquals(1, platform.recognizers.single().destroys)
        }
    }

    @Test fun noMatchIsOneTerminalErrorWithCleanupAndOnlyExplicitRetry() {
        val platform = FakePlatform()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val locale = LocaleManager.newForPreviews(context)
        val input = SttInputDeviceWrapperImpl(context, UserSettingsModule.newDataStoreForPreviews(), locale) {
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
            assertEquals(7, SpeechRecognizer.ERROR_NO_MATCH)
            assertEquals(1, recognizer.starts)
            recognizer.callback.onError(SpeechRecognizer.ERROR_NO_MATCH)
            recognizer.callback.onError(SpeechRecognizer.ERROR_NO_MATCH)
            recognizer.callback.onResults(Bundle().apply {
                putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("late"))
            })
            val terminal = evaluator.events.filter { it is InputEvent.Error || it is InputEvent.Final || it is InputEvent.None }
            assertEquals(1, terminal.size)
            assertTrue(terminal.single() is InputEvent.Error)
            assertTrue((terminal.single() as InputEvent.Error).throwable.message.orEmpty().contains("Речь не распознана"))
            assertEquals(1, recognizer.destroys)
            assertEquals(listOf(platform.connections.single()), platform.unbound)
            assertTrue(input.isCleanupSafe())
        }
        drain()
        onMain {
            assertEquals(1, platform.recognizers.size)
            assertEquals(1, platform.recognizers.single().starts)
            assertTrue(coordinator.start(owner)) // A subsequent user request is allowed.
            assertEquals(2, platform.recognizers.size)
            platform.connections.last().onServiceConnected(null, Binder())
        }
        drain()
        onMain {
            assertEquals(1, platform.recognizers.last().starts)
            assertEquals(1, evaluator.events.filterIsInstance<InputEvent.Error>().size)
            coordinator.cancel(owner)
            assertEquals(1, platform.recognizers.last().destroys)
            assertEquals(2, platform.unbound.size)
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
