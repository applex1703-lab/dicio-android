package org.stypox.dicio.io.speech

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import org.dicio.skill.context.SpeechOutputDevice
import org.stypox.dicio.R
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean

class AndroidTtsSpeechDevice(private var context: Context, locale: Locale,
    private val prepareSilently: Boolean = false) : SpeechOutputDevice {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private fun <T> onMain(action: () -> T): T {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return action()
        val task = FutureTask { action() }
        check(main.post(task)) { "Main thread is unavailable" }
        return task.get()
    }
    private var closed = false
    private val pendingSpeech = linkedMapOf<String, String>()
    private val speechTexts = linkedMapOf<String, String>()
    private var preparationFile: java.io.File? = null
    private var preparationDeadline: Runnable? = null
    private val lifecycle = TtsSessionLifecycle { Log.e(TAG, "TTS completion callback failed", it) }
    private val preparationId = "prepare_${java.util.UUID.randomUUID()}"
    private fun clearPreparation() {
        preparationDeadline?.let(main::removeCallbacks)
        preparationDeadline = null
        lifecycle.endPreparation()
        try { preparationFile?.delete() } catch (error: RuntimeException) {
            Log.w(TAG, "Unable to remove TTS preparation file", error)
        }
        preparationFile = null
        timings.remove(preparationId)
    }
    private var textToSpeech: TextToSpeech? = null
    private var initializedCorrectly = false
    private var initializationFailed = false
    private var lastUtteranceId = 0
    private class Timing(val queuedAtMs: Long) {
        val firstAudio = AtomicBoolean(false)
        val firstRange = AtomicBoolean(false)
    }
    private val timings = ConcurrentHashMap<String, Timing>()
    private fun elapsed(id: String) = timings[id]?.let {
        android.os.SystemClock.elapsedRealtime() - it.queuedAtMs
    } ?: -1L
    private val initializationStartedAt = android.os.SystemClock.elapsedRealtime()

    private fun stopEngine() {
        try { textToSpeech?.stop() } catch (error: RuntimeException) {
            Log.w(TAG, "TTS stop failed", error)
        }
    }

    private fun shutdownEngine() {
        val engine = textToSpeech
        textToSpeech = null
        try { engine?.shutdown() } catch (error: RuntimeException) {
            Log.w(TAG, "TTS shutdown failed", error)
        }
    }

    private fun failedUtterance(id: String) {
        val text = speechTexts.remove(id)
        if (text != null) {
            try { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
            catch (error: RuntimeException) { Log.w(TAG, "TTS text fallback failed", error) }
        }
        lifecycle.finished(id)
    }

    init {
        textToSpeech = TextToSpeech(context) { status: Int -> main.post {
            if (closed) return@post
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.run {
                    val errorCode = try {
                        setLanguage(locale)
                    } catch (error: RuntimeException) {
                        Log.e(TAG, "TTS language initialization failed", error)
                        TextToSpeech.ERROR
                    }
                    if (errorCode >= 0) { // errors are -1 or -2
                        initializedCorrectly = true
                        Log.i(TAG, "TTS initialized language=${locale.toLanguageTag()} elapsedMs=${android.os.SystemClock.elapsedRealtime() - initializationStartedAt}")
                        val listener = object :
                            UtteranceProgressListener() {
                            override fun onStart(utteranceId: String) {
                                Log.i(TAG, "TTS started id=$utteranceId queueElapsedMs=${elapsed(utteranceId)}")
                            }
                            override fun onBeginSynthesis(utteranceId: String, sampleRateInHz: Int, audioFormat: Int, channelCount: Int) {
                                Log.i(TAG, "TTS synthesis id=$utteranceId queueElapsedMs=${elapsed(utteranceId)} sampleRate=$sampleRateInHz format=$audioFormat channels=$channelCount")
                            }
                            override fun onAudioAvailable(utteranceId: String, audio: ByteArray) {
                                // Neither retain nor inspect speech audio; log only first chunk timing/size.
                                if (audio.isNotEmpty() && timings[utteranceId]?.firstAudio?.compareAndSet(false, true) == true) {
                                    Log.i(TAG, "TTS firstAudio id=$utteranceId queueElapsedMs=${elapsed(utteranceId)} bytes=${audio.size}")
                                }
                            }
                            override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) {
                                if (timings[utteranceId]?.firstRange?.compareAndSet(false, true) == true) {
                                    Log.i(TAG, "TTS firstRange id=$utteranceId queueElapsedMs=${elapsed(utteranceId)} frame=$frame")
                                }
                            }
                            override fun onStop(utteranceId: String, interrupted: Boolean) {
                                Log.i(TAG, "TTS stopped id=$utteranceId interrupted=$interrupted")
                                timings.remove(utteranceId)
                                main.post {
                                    if (closed) return@post
                                    if (utteranceId == preparationId) {
                                        if (lifecycle.preparationPending) clearPreparation()
                                    } else {
                                        speechTexts.remove(utteranceId)
                                        lifecycle.finished(utteranceId)
                                    }
                                }
                            }
                            override fun onDone(utteranceId: String) {
                                Log.i(TAG, "TTS completed id=$utteranceId")
                                timings.remove(utteranceId)
                                main.post {
                                    if (closed) return@post
                                    if (utteranceId == preparationId) clearPreparation()
                                    speechTexts.remove(utteranceId)
                                    lifecycle.finished(utteranceId)
                                }
                            }

                            @Suppress("OVERRIDE_DEPRECATION")
                            @Deprecated("")
                            override fun onError(utteranceId: String) {
                                if (utteranceId == preparationId) main.post { if (!closed) clearPreparation() }
                                else main.post { if (!closed) failedUtterance(utteranceId) }
                                timings.remove(utteranceId)
                                Log.w(TAG, "TTS failed id=$utteranceId")
                            }
                            override fun onError(utteranceId: String, errorCode: Int) {
                                if (utteranceId == preparationId) main.post { if (!closed) clearPreparation() }
                                else main.post { if (!closed) failedUtterance(utteranceId) }
                                timings.remove(utteranceId)
                                Log.w(TAG, "TTS failed id=$utteranceId code=$errorCode")
                            }
                        }
                        val listenerResult = try {
                            setOnUtteranceProgressListener(listener)
                        } catch (error: RuntimeException) {
                            Log.e(TAG, "TTS progress listener registration failed", error)
                            TextToSpeech.ERROR
                        }
                        if (listenerResult != TextToSpeech.SUCCESS) {
                            Log.e(TAG, "TTS progress listener registration failed: $listenerResult")
                            handleInitializationError(R.string.android_tts_error)
                            return@post
                        }
                        if (prepareSilently && pendingSpeech.isEmpty() && lifecycle.beginPreparation()) {
                            // Fixed non-user text, file synthesis only: no AudioTrack or audible output.
                            try {
                                preparationFile = java.io.File.createTempFile("dicio-tts-prepare-", ".wav", context.cacheDir)
                                timings[preparationId] = Timing(android.os.SystemClock.elapsedRealtime())
                                val result = synthesizeToFile("а", Bundle(), preparationFile!!, preparationId)
                                Log.i(TAG, "TTS preparation submitted result=$result")
                                if (result != TextToSpeech.SUCCESS) clearPreparation()
                                else {
                                    val deadline = Runnable {
                                        if (!closed && lifecycle.preparationPending) {
                                            stopEngine()
                                            clearPreparation()
                                            Log.w(TAG, "TTS preparation deadline reached; no retry")
                                        }
                                    }
                                    preparationDeadline = deadline
                                    main.postDelayed(deadline, 10_000)
                                }
                            } catch (error: Exception) {
                                stopEngine()
                                clearPreparation()
                                Log.w(TAG, "TTS preparation unavailable; continue without it", error)
                            }
                        }
                        for (id in pendingSpeech.keys.toList()) {
                            val text = pendingSpeech.remove(id) ?: continue
                            submitSpeech(id, text)
                        }
                    } else {
                        Log.e(TAG, "Unsupported language: $errorCode")
                        handleInitializationError(R.string.android_tts_unsupported_language)
                    }
                }
            } else {
                Log.e(TAG, "TTS error: $status")
                handleInitializationError(R.string.android_tts_error)
            }
        } }
    }

    override fun speak(speechOutput: String) = onMain {
        if (closed) {
            if (initializationFailed) Toast.makeText(context, speechOutput, Toast.LENGTH_LONG).show()
            return@onMain
        }
        if (initializationFailed) {
            Toast.makeText(context, speechOutput, Toast.LENGTH_LONG).show()
            return@onMain
        }
        val id = "dicio_${++lastUtteranceId}"
        lifecycle.submitted(id)
        speechTexts[id] = speechOutput
        if (initializedCorrectly) {
            submitSpeech(id, speechOutput)
        } else {
            pendingSpeech[id] = speechOutput
        }
    }

    private fun submitSpeech(id: String, speechOutput: String) {
            // Never queue the real answer behind an unfinished preparation request.
            if (lifecycle.preparationPending) {
                stopEngine()
                clearPreparation()
            }
            timings[id] = Timing(android.os.SystemClock.elapsedRealtime())
            Log.i(TAG, "TTS queued id=$id chars=${speechOutput.length}")
            val result = try {
                textToSpeech?.speak(speechOutput, TextToSpeech.QUEUE_ADD, null, id)
            } catch (error: RuntimeException) {
                Log.e(TAG, "TTS submission failed id=$id", error)
                TextToSpeech.ERROR
            }
            Log.i(TAG, "TTS submitted id=$id result=$result")
            if (result != TextToSpeech.SUCCESS) {
                timings.remove(id)
                failedUtterance(id)
            }
    }

    override fun stopSpeaking() = onMain {
        val stopEngine = lifecycle.stopSpeech()
        pendingSpeech.clear()
        speechTexts.clear()
        if (stopEngine) stopEngine()
        timings.keys.removeAll { it != preparationId }
        Unit
    }

    override val isSpeaking: Boolean
        get() = onMain { lifecycle.hasSpeech }

    override fun runWhenFinishedSpeaking(runnable: Runnable) = onMain {
        if (lifecycle.hasSpeech) {
            lifecycle.whenFinished(runnable)
        } else {
            try { runnable.run() } catch (error: RuntimeException) {
                Log.e(TAG, "TTS completion callback failed", error)
            }
        }
    }

    override fun cleanup() = onMain {
        closed = true
        lifecycle.close()
        initializedCorrectly = false
        pendingSpeech.clear()
        speechTexts.clear()
        clearPreparation()
        timings.clear()
        shutdownEngine()
        Unit
    }

    private fun handleInitializationError(@StringRes errorString: Int) {
        initializationFailed = true
        val undelivered = pendingSpeech.toMap()
        try { Toast.makeText(context, errorString, Toast.LENGTH_SHORT).show() }
        catch (error: RuntimeException) { Log.w(TAG, "TTS error notification failed", error) }
        pendingSpeech.clear()
        initializedCorrectly = false
        shutdownEngine()
        for ((id, _) in undelivered) failedUtterance(id)
    }

    companion object {
        val TAG: String = AndroidTtsSpeechDevice::class.simpleName!!
    }
}

/** Main-thread session state; Android's progress callbacks are marshalled to that thread. */
internal class TtsSessionLifecycle(private val onCallbackError: (RuntimeException) -> Unit = {}) {
    var preparationPending = false
        private set
    private var preparationAttempted = false
    private var closed = false
    private val pendingIds = linkedSetOf<String>()
    private var generation = 0L
    private val callbacks = mutableListOf<Runnable>()
    val hasSpeech: Boolean get() = pendingIds.isNotEmpty()

    fun beginPreparation(): Boolean {
        if (closed || preparationAttempted) return false
        preparationAttempted = true
        preparationPending = true
        return true
    }

    fun endPreparation() {
        preparationPending = false
    }

    fun submitted(id: String) {
        if (!closed) {
            preparationAttempted = true
            pendingIds.add(id)
        }
    }

    fun whenFinished(callback: Runnable) {
        if (!closed) callbacks.add(callback)
    }

    fun finished(id: String) {
        if (closed || !pendingIds.remove(id) || pendingIds.isNotEmpty()) return
        val expectedGeneration = generation
        val ready = callbacks.toList()
        callbacks.clear()
        for ((index, callback) in ready.withIndex()) {
            if (closed || generation != expectedGeneration) break
            if (hasSpeech) {
                callbacks.addAll(0, ready.subList(index, ready.size))
                break
            }
            try { callback.run() } catch (error: RuntimeException) { onCallbackError(error) }
        }
    }

    fun stopSpeech(): Boolean {
        generation++
        pendingIds.clear()
        callbacks.clear()
        // TextToSpeech.stop() also cancels file synthesis, so leave it alone
        // while the one session preparation is running.
        return !preparationPending
    }

    fun close() {
        stopSpeech()
        endPreparation()
        closed = true
    }
}
