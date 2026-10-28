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
import java.util.concurrent.atomic.AtomicBoolean

class AndroidTtsSpeechDevice(private var context: Context, locale: Locale,
    private val prepareSilently: Boolean = false) : SpeechOutputDevice {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var closed = false
    private var pendingSpeech: String? = null
    private var preparationFile: java.io.File? = null
    private var preparationPending = false
    private val preparationId = "prepare_${java.util.UUID.randomUUID()}"
    private fun clearPreparation() {
        preparationPending = false
        preparationFile?.delete()
        preparationFile = null
        timings.remove(preparationId)
    }
    private var textToSpeech: TextToSpeech? = null
    private var initializedCorrectly = false
    private var initializationFailed = false
    private val runnablesWhenFinished: MutableList<Runnable> = ArrayList()
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

    init {
        textToSpeech = TextToSpeech(context) { status: Int ->
            if (closed) return@TextToSpeech
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.run {
                    val errorCode = setLanguage(locale)
                    if (errorCode >= 0) { // errors are -1 or -2
                        initializedCorrectly = true
                        Log.i(TAG, "TTS initialized language=${locale.toLanguageTag()} engine=${defaultEngine} elapsedMs=${android.os.SystemClock.elapsedRealtime() - initializationStartedAt}")
                        setOnUtteranceProgressListener(object :
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
                            }
                            override fun onDone(utteranceId: String) {
                                Log.i(TAG, "TTS completed id=$utteranceId")
                                timings.remove(utteranceId)
                                main.post {
                                    if (closed) return@post
                                    if (utteranceId == preparationId) clearPreparation()
                                    if ("dicio_$lastUtteranceId" == utteranceId) {
                                        val callbacks = runnablesWhenFinished.toList()
                                        runnablesWhenFinished.clear()
                                        callbacks.forEach { it.run() }
                                    }
                                }
                            }

                            @Suppress("OVERRIDE_DEPRECATION")
                            @Deprecated("")
                            override fun onError(utteranceId: String) {
                                if (utteranceId == preparationId) main.post { clearPreparation() }
                                timings.remove(utteranceId)
                                Log.w(TAG, "TTS failed id=$utteranceId")
                            }
                            override fun onError(utteranceId: String, errorCode: Int) {
                                if (utteranceId == preparationId) main.post { clearPreparation() }
                                timings.remove(utteranceId)
                                Log.w(TAG, "TTS failed id=$utteranceId code=$errorCode")
                            }
                        })
                        if (prepareSilently && pendingSpeech == null) {
                            // Fixed non-user text, file synthesis only: no AudioTrack or audible output.
                            try {
                                preparationFile = java.io.File.createTempFile("dicio-tts-prepare-", ".wav", context.cacheDir)
                                preparationPending = true
                                timings[preparationId] = Timing(android.os.SystemClock.elapsedRealtime())
                                val result = synthesizeToFile("а", Bundle(), preparationFile!!, preparationId)
                                Log.i(TAG, "TTS preparation submitted result=$result")
                                if (result != TextToSpeech.SUCCESS) clearPreparation()
                                else main.postDelayed({
                                    if (!closed && preparationPending) {
                                        textToSpeech?.stop()
                                        clearPreparation()
                                        Log.w(TAG, "TTS preparation deadline reached; no retry")
                                    }
                                }, 10_000)
                            } catch (error: java.io.IOException) {
                                clearPreparation()
                                Log.w(TAG, "TTS preparation unavailable; continue without it", error)
                            }
                        }
                        pendingSpeech?.let { text -> pendingSpeech = null; speak(text) }
                    } else {
                        Log.e(TAG, "Unsupported language: $errorCode")
                        handleInitializationError(R.string.android_tts_unsupported_language)
                    }
                }
            } else {
                Log.e(TAG, "TTS error: $status")
                handleInitializationError(R.string.android_tts_error)
            }
        }
    }

    override fun speak(speechOutput: String) {
        if (closed) {
            if (initializationFailed) Toast.makeText(context, speechOutput, Toast.LENGTH_LONG).show()
            return
        }
        if (initializedCorrectly) {
            // Never queue the real answer behind an unfinished preparation request.
            if (preparationPending) {
                textToSpeech?.stop()
                clearPreparation()
            }
            lastUtteranceId += 1
            val id = "dicio_$lastUtteranceId"
            timings[id] = Timing(android.os.SystemClock.elapsedRealtime())
            Log.i(TAG, "TTS queued id=dicio_$lastUtteranceId chars=${speechOutput.length}")
            val result = textToSpeech?.speak(
                speechOutput, TextToSpeech.QUEUE_ADD, null,
                "dicio_$lastUtteranceId"
            )
            Log.i(TAG, "TTS submitted id=$id result=$result voice=${textToSpeech?.voice?.name} network=${textToSpeech?.voice?.isNetworkConnectionRequired}")
            if (result != TextToSpeech.SUCCESS) timings.remove(id)
        } else {
            if (initializationFailed) Toast.makeText(context, speechOutput, Toast.LENGTH_LONG).show()
            else pendingSpeech = speechOutput
        }
    }

    override fun stopSpeaking() {
        pendingSpeech = null
        textToSpeech?.stop()
        clearPreparation()
        runnablesWhenFinished.clear()
    }

    override val isSpeaking: Boolean
        get() = textToSpeech?.isSpeaking == true

    override fun runWhenFinishedSpeaking(runnable: Runnable) {
        if (isSpeaking) {
            runnablesWhenFinished.add(runnable)
        } else {
            runnable.run()
        }
    }

    override fun cleanup() {
        closed = true
        main.removeCallbacksAndMessages(null)
        initializedCorrectly = false
        pendingSpeech = null
        clearPreparation()
        runnablesWhenFinished.clear()
        timings.clear()
        textToSpeech?.apply {
            shutdown()
            textToSpeech = null
        }
    }

    private fun handleInitializationError(@StringRes errorString: Int) {
        initializationFailed = true
        val undelivered = pendingSpeech
        Toast.makeText(context, errorString, Toast.LENGTH_SHORT).show()
        cleanup()
        if (!undelivered.isNullOrBlank()) Toast.makeText(context, undelivered, Toast.LENGTH_LONG).show()
    }

    companion object {
        val TAG: String = AndroidTtsSpeechDevice::class.simpleName!!
    }
}
