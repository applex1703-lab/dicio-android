package org.stypox.dicio.io.input.stt_service

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import org.stypox.dicio.di.LocaleManager
import org.stypox.dicio.voice.VoiceInputCoordinator
import org.stypox.dicio.io.input.InputEvent
import java.util.Locale
import javax.inject.Inject


// TODO this class is really simple at the moment, but many more things could be implemented, e.g.:
//  - allowing an SttInputDevice to download/support multiple languages
//  - handling more EXTRAs, e.g. EXTRA_LANGUAGE, EXTRA_LANGUAGE_PREFERENCE,
//  EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, EXTRA_LANGUAGE_MODEL, LANGUAGE_MODEL_FREE_FORM,
//  LANGUAGE_MODEL_WEB_SEARCH, EXTRA_SEGMENTED_SESSION,
//  EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
//  EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
//  EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, EXTRA_AUDIO_SOURCE, EXTRA_AUDIO_SOURCE_CHANNEL_COUNT,
//  EXTRA_AUDIO_SOURCE_ENCODING, EXTRA_AUDIO_SOURCE_SAMPLING_RATE, EXTRA_BIASING_STRINGS,
//  EXTRA_ENABLE_BIASING_DEVICE_CONTEXT
@AndroidEntryPoint
class SttService : RecognitionService() {
    private var activeCallback: Callback? = null

    @Inject
    lateinit var coordinator: VoiceInputCoordinator

    @Inject
    lateinit var localeManager: LocaleManager

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        val wantedLanguageExtra = recognizerIntent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)
        // "und" is "Undetermined", see https://www.loc.gov/standards/iso639-2/php/code_list.php
        if (wantedLanguageExtra != null && wantedLanguageExtra != "und") {
            val appLanguage = localeManager.locale.value.language
            val wantedLanguage = Locale.forLanguageTag(wantedLanguageExtra).language
            if (appLanguage != wantedLanguage) {
                Log.e(TAG, "Unsupported language: app=$appLanguage wanted=$wantedLanguageExtra")
                // From the javadoc of ERROR_LANGUAGE_UNAVAILABLE: Requested language is supported,
                // but not available currently (e.g. not downloaded yet).
                logRemoteExceptions { listener.error(ERROR_LANGUAGE_UNAVAILABLE) }
                return
            }
        }

        var beginningOfSpeech = true
        var terminal = false
        val willStartListening = coordinator.startService(listener, { inputEvent ->
            if (terminal) return@startService
            if (inputEvent is InputEvent.Error || inputEvent is InputEvent.Final || inputEvent is InputEvent.None) {
                terminal = true
                if (activeCallback === listener) activeCallback = null
            }
            when (inputEvent) {
                InputEvent.Started -> Unit
                is InputEvent.Error -> {
                    logRemoteExceptions { listener.error(SpeechRecognizer.ERROR_SERVER) }
                }

                is InputEvent.Final -> {
                    if (beginningOfSpeech) {
                        logRemoteExceptions { listener.beginningOfSpeech() }
                        beginningOfSpeech = false
                    }

                    val results = Bundle()
                    results.putStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION,
                        ArrayList(inputEvent.utterances.map { it.first })
                    )
                    results.putFloatArray(
                        SpeechRecognizer.CONFIDENCE_SCORES,
                        inputEvent.utterances.map { it.second }.toFloatArray()
                    )

                    logRemoteExceptions { listener.endOfSpeech() }
                    logRemoteExceptions { listener.results(results) }
                }

                InputEvent.None -> {
                    logRemoteExceptions { listener.endOfSpeech() }
                    logRemoteExceptions { listener.error(SpeechRecognizer.ERROR_SPEECH_TIMEOUT) }
                }

                is InputEvent.Partial -> {
                    if (beginningOfSpeech) {
                        logRemoteExceptions { listener.beginningOfSpeech() }
                        beginningOfSpeech = false
                    }

                    val partResult = Bundle()
                    partResult.putStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION,
                        arrayListOf(inputEvent.utterance)
                    )

                    logRemoteExceptions { listener.partialResults(partResult) }
                }
            }
        }, {
            if (activeCallback === listener) activeCallback = null
            if (!terminal) {
                terminal = true
                logRemoteExceptions { listener.error(SpeechRecognizer.ERROR_CLIENT) }
            }
        })

        if (willStartListening == null) {
            logRemoteExceptions { listener.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY) }
        } else if (!willStartListening) {
            Log.w(TAG, "Could not start STT recognizer")
            logRemoteExceptions { listener.error(ERROR_LANGUAGE_UNAVAILABLE) }
        } else if (!terminal) {
            activeCallback = listener
        }
    }

    override fun onCancel(listener: Callback) {
        if (activeCallback === listener) activeCallback = null
        coordinator.cancel(listener)
    }

    override fun onStopListening(listener: Callback) {
        coordinator.stop(listener)
    }

    override fun onDestroy() {
        activeCallback?.let { coordinator.cancel(it) }
        activeCallback = null
        super.onDestroy()
    }

    companion object {
        val TAG = SttService::class.simpleName

        /**
         * From the javadoc of [SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE]: Requested language is
         * supported, but not available currently (e.g. not downloaded yet).
         */
        val ERROR_LANGUAGE_UNAVAILABLE = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
        } else {
            SpeechRecognizer.ERROR_SERVER
        }

        fun logRemoteExceptions(f: () -> Unit) {
            try {
                return f()
            } catch (e: RemoteException) {
                Log.e(TAG, "Remote exception", e)
            }
        }
    }
}
