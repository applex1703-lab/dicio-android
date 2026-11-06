package org.stypox.dicio.eval

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import javax.inject.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.dicio.skill.context.SpeechOutputDevice
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.stypox.dicio.di.SkillContextImpl
import org.stypox.dicio.di.SkillContextInternal
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.llm.MediaActions
import org.stypox.dicio.llm.PolzaClient
import org.stypox.dicio.llm.PolzaSettings
import org.stypox.dicio.llm.TvActions
import org.stypox.dicio.screenshot.FakeSttInputDeviceWrapper

@RunWith(AndroidJUnit4::class)
class EvaluatorTeardownTest {
    private class Speech : SpeechOutputDevice {
        val calls = mutableListOf<String>()
        var stopError: Exception? = null
        var cleanupError: Exception? = null
        var onStop: () -> Unit = {}
        override val isSpeaking = false
        override fun speak(speechOutput: String) { calls += "speak" }
        override fun stopSpeaking() { calls += "stop"; onStop(); stopError?.let { throw it } }
        override fun cleanup() { calls += "cleanup"; cleanupError?.let { throw it } }
        override fun runWhenFinishedSpeaking(runnable: Runnable) = runnable.run()
    }

    private fun evaluator(speech: Speech, clearVpn: suspend () -> Unit): SkillEvaluatorImpl {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val base = SkillContextImpl.newForPreviews(app)
        val context = object : SkillContextInternal by base {
            override val speechOutputDevice: SpeechOutputDevice = speech
        }
        return SkillEvaluatorImpl(context, Provider { error("unexpected legacy call") },
            FakeSttInputDeviceWrapper(), PolzaSettings(app), PolzaClient(OkHttpClient()),
            TvActions(app, MediaActions(app)), clearVpn)
    }

    @Test fun delayedClearDoesNotDelayTeardownOrStopNewSessionLater() = runBlocking {
        val lock = Mutex(locked = true)
        var clears = 0
        val speech = Speech()
        val evaluator = evaluator(speech) { lock.withLock { clears++ } }
        withContext(Dispatchers.Main) {
            evaluator.finishInputSession()
            assertEquals(listOf("stop", "cleanup"), speech.calls)
            evaluator.processInputEvent(InputEvent.Started)
            assertEquals(listOf("stop", "cleanup"), speech.calls)
            lock.unlock()
        }
        withContext(Dispatchers.Main) { }
        assertEquals(1, clears)
        assertEquals(listOf("stop", "cleanup", "stop"), speech.calls)
    }

    @Test fun failuresPropagateAfterBothAttemptsAndVpnClear() = runBlocking {
        val lock = Mutex(locked = true)
        var clears = 0
        val speech = Speech()
        val stop = IllegalStateException("stop")
        val cleanup = IllegalArgumentException("cleanup")
        speech.stopError = stop
        speech.cleanupError = cleanup
        val evaluator = evaluator(speech) { lock.withLock { clears++ } }
        withContext(Dispatchers.Main) {
            val thrown = try { evaluator.cancelPendingInput(); null } catch (error: Exception) { error }
            assertSame(stop, thrown)
            assertEquals(listOf(cleanup), thrown!!.suppressed.toList())
            assertEquals(listOf("stop", "cleanup"), speech.calls)
            lock.unlock()
        }
        withContext(Dispatchers.Main) { }
        assertEquals(1, clears)
    }

    @Test fun synchronousEntryRejectsBackgroundThread() = runBlocking {
        val evaluator = evaluator(Speech()) { }
        val operations: List<() -> Unit> = listOf(
            { evaluator.cancelPendingInput() }, { evaluator.finishInputSession() },
            { evaluator.resetVoicePresentation() }, { evaluator.hasPendingClarification() },
            { evaluator.processInputEvent(InputEvent.Started) }, { evaluator.selectChoice(1, "x") },
        )
        withContext(Dispatchers.IO) {
            operations.forEach { operation ->
                val error = try { operation(); null } catch (e: IllegalStateException) { e }
                assertNotNull(error)
                assertTrue(error!!.message!!.contains("main thread"))
            }
        }
    }

    @Test fun reentrantEvaluatorStartFailsBeforeOldCleanup() = runBlocking {
        val speech = Speech()
        var clears = 0
        val evaluator = evaluator(speech) { clears++ }
        speech.onStop = { evaluator.processInputEvent(InputEvent.Started) }
        withContext(Dispatchers.Main) {
            val error = try { evaluator.cancelPendingInput(); null } catch (e: IllegalStateException) { e }
            assertTrue(error!!.message!!.contains("teardown"))
            assertEquals(listOf("stop", "cleanup"), speech.calls)
            assertEquals(1, clears)
        }
    }
}
