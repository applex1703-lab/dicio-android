package org.stypox.dicio.io.input

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.stypox.dicio.di.LocaleManager
import java.io.IOException

/** One-shot Google recognition, returning text to Dicio rather than executing Google actions. */
class GoogleSpeechInputDevice(
    private val context: Context,
    private val localeManager: LocaleManager,
) : SttInputDevice {
    private val main = Handler(Looper.getMainLooper())
    private val state = MutableStateFlow<SttState>(SttState.Loaded)
    override val uiState: StateFlow<SttState> = state
    private var recognizer: SpeechRecognizer? = null
    private var listener: ((InputEvent) -> Unit)? = null
    private var closed = false
    private var generation = 0
    private val timeout = Runnable { complete(InputEvent.Error(IOException("Google speech input timed out"))) }

    override fun tryLoad(thenStartListeningEventListener: ((InputEvent) -> Unit)?): Boolean {
        if (closed) return false
        thenStartListeningEventListener?.let { start(it) }
        return true
    }

    override fun onClick(eventListener: (InputEvent) -> Unit) { start(eventListener) }

    private fun start(eventListener: (InputEvent) -> Unit) {
        main.post {
            if (closed || listener != null) return@post
            listener = eventListener
            val session = ++generation
            state.value = SttState.WaitingForResult
            try {
                val service = SpeechRecognizer.createSpeechRecognizer(context, ComponentName(
                    "com.google.android.katniss",
                    "com.google.android.apps.tvsearch.voice.recognition.KatnissRecognitionService",
                ))
                recognizer = service
                service.setRecognitionListener(object : RecognitionListener {
                    private fun current() = !closed && session == generation && listener != null
                    override fun onReadyForSpeech(params: Bundle?) {
                        if (current()) {
                            state.value = SttState.Listening
                            Log.i(TAG, "Google ready for speech")
                        }
                    }
                    override fun onBeginningOfSpeech() { }
                    override fun onRmsChanged(rmsdB: Float) { }
                    override fun onBufferReceived(buffer: ByteArray?) { }
                    override fun onEndOfSpeech() {
                        if (current()) state.value = SttState.WaitingForResult
                    }
                    override fun onError(error: Int) {
                        if (!current()) return
                        Log.w(TAG, "Google recognition error=$error")
                        complete(if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                            error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) InputEvent.None
                        else InputEvent.Error(IOException("Google recognition error $error")))
                    }
                    override fun onResults(results: Bundle?) {
                        if (!current()) return
                        val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val scores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                        val hypotheses = texts.orEmpty().mapIndexedNotNull { index, text ->
                            text.takeIf { it.isNotBlank() }?.let {
                                it to (scores?.getOrNull(index)?.takeIf { score -> score >= 0f } ?: 1f)
                            }
                        }
                        Log.i(TAG, "Google returned ${hypotheses.size} hypotheses to Dicio")
                        complete(if (hypotheses.isEmpty()) InputEvent.None else InputEvent.Final(hypotheses))
                    }
                    override fun onPartialResults(partialResults: Bundle?) { }
                    override fun onEvent(eventType: Int, params: Bundle?) { }
                })
                main.postDelayed(timeout, 30000)
                val locale = localeManager.locale.value
                val language = if (locale.language == "ru") "ru-RU" else locale.toLanguageTag()
                service.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                })
                Log.i(TAG, "Google recognition requested")
            } catch (error: Exception) { complete(InputEvent.Error(error)) }
        }
    }

    private fun complete(event: InputEvent) {
        val target = listener ?: return
        listener = null
        generation++
        main.removeCallbacks(timeout)
        val previous = recognizer
        recognizer = null
        previous?.destroy()
        state.value = SttState.Loaded
        if (!closed) target(event)
    }

    override fun stopListening() {
        main.post { if (!closed) recognizer?.stopListening() }
    }

    override suspend fun destroy() {
        main.post {
            closed = true
            complete(InputEvent.None)
        }
    }

    companion object { private const val TAG = "DicioGoogleSpeech" }
}
