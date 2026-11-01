package org.stypox.dicio.io.input

import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.stypox.dicio.di.LocaleManager

class GoogleSpeechInputDeviceTest {
    private class FakeRecognizer : GoogleRecognizer {
        lateinit var callback: RecognitionListener
        var starts = 0
        var stops = 0
        var destroys = 0
        override fun setRecognitionListener(listener: RecognitionListener) { callback = listener }
        override fun startListening(intent: Intent) { starts++ }
        override fun stopListening() { stops++ }
        override fun destroy() { destroys++ }
    }

    private class FakePlatform : GoogleSpeechPlatform {
        val recognizers = mutableListOf<FakeRecognizer>()
        val connections = mutableListOf<ServiceConnection>()
        val unbound = mutableListOf<ServiceConnection>()
        var bindResult = true
        override fun createRecognizer(): GoogleRecognizer = FakeRecognizer().also { recognizers.add(it) }
        override fun bind(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            connections.add(connection)
            return bindResult
        }
        override fun unbind(connection: ServiceConnection) { unbound.add(connection) }
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
}
