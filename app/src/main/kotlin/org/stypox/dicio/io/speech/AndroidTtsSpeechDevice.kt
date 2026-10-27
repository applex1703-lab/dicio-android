package org.stypox.dicio.io.speech

import android.content.Context
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

class AndroidTtsSpeechDevice(private var context: Context, locale: Locale) : SpeechOutputDevice {
    private var textToSpeech: TextToSpeech? = null
    private var initializedCorrectly = false
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
                                if ("dicio_$lastUtteranceId" == utteranceId) {
                                    // run only when the last enqueued utterance is finished
                                    for (runnable in runnablesWhenFinished) {
                                        runnable.run()
                                    }
                                    runnablesWhenFinished.clear()
                                }
                            }

                            @Suppress("OVERRIDE_DEPRECATION")
                            @Deprecated("")
                            override fun onError(utteranceId: String) {
                                timings.remove(utteranceId)
                                Log.w(TAG, "TTS failed id=$utteranceId")
                            }
                            override fun onError(utteranceId: String, errorCode: Int) {
                                timings.remove(utteranceId)
                                Log.w(TAG, "TTS failed id=$utteranceId code=$errorCode")
                            }
                        })
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
        if (initializedCorrectly) {
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
            Toast.makeText(context, speechOutput, Toast.LENGTH_LONG).show()
        }
    }

    override fun stopSpeaking() {
        textToSpeech?.stop()
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
        timings.clear()
        textToSpeech?.apply {
            shutdown()
            textToSpeech = null
        }
    }

    private fun handleInitializationError(@StringRes errorString: Int) {
        Toast.makeText(context, errorString, Toast.LENGTH_SHORT).show()
        cleanup()
    }

    companion object {
        val TAG: String = AndroidTtsSpeechDevice::class.simpleName!!
    }
}
