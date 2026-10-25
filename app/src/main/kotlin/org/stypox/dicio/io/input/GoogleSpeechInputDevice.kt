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
    private val timeout = Runnable {
        releaseRecognizer()
        complete(InputEvent.Error(IOException("Google speech input timed out")))
    }

    override fun tryLoad(thenStartListeningEventListener: ((InputEvent) -> Unit)?): Boolean {
        if (closed) return false
        main.post {
            if (!closed) {
                try {
                    prepareRecognizer()
                } catch (error: Exception) {
                    state.value = SttState.ErrorLoading(error)
                }
            }
        }
        thenStartListeningEventListener?.let { start(it) }
        return true
    }

    override fun onClick(eventListener: (InputEvent) -> Unit) { start(eventListener) }

    // Creating a client does not start recording. Keep the connection between explicit requests.
    private fun prepareRecognizer(): SpeechRecognizer {
        return recognizer ?: SpeechRecognizer.createSpeechRecognizer(context, ComponentName(
            "com.google.android.katniss",
            "com.google.android.apps.tvsearch.voice.recognition.KatnissRecognitionService",
        )).also {
            recognizer = it
            Log.i(TAG, "Google recognizer created (not listening)")
        }
    }

    private fun releaseRecognizer() {
        val previous = recognizer
        recognizer = null
        previous?.destroy()
    }

    private fun start(eventListener: (InputEvent) -> Unit) {
        main.post {
            if (closed || listener != null) return@post
            listener = eventListener
            eventListener(InputEvent.Started)
            val session = ++generation
            state.value = SttState.WaitingForResult
            try {
                val service = prepareRecognizer()
                service.setRecognitionListener(object : RecognitionListener {
                    private fun current() = !closed && session == generation && listener != null
                    override fun onReadyForSpeech(params: Bundle?) {
                        if (current()) {
                            state.value = SttState.Listening
                            Log.i(TAG, "Google ready for speech")
                        }
                    }
                    override fun onBeginningOfSpeech() {
                        if (current()) Log.i(TAG, "Google speech beginning session=$session")
                    }
                    override fun onRmsChanged(rmsdB: Float) { }
                    override fun onBufferReceived(buffer: ByteArray?) { }
                    override fun onEndOfSpeech() {
                        if (current()) state.value = SttState.WaitingForResult
                    }
                    override fun onError(error: Int) {
                        if (!current()) return
                        Log.w(TAG, "Google recognition error=$error")
                        val message = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH ->
                                "Речь не распознана. Повторите запрос после начала прослушивания."
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                                "Речь не поступила. Для микрофона пульта запустите запись кнопкой OK."
                            else -> "Google recognition error $error"
                        }
                        complete(InputEvent.Error(IOException(message)))
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
                        complete(if (hypotheses.isEmpty()) {
                            InputEvent.Error(IOException("Google returned no recognized text"))
                        } else InputEvent.Final(hypotheses))
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        if (!current()) return
                        val text = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull { it.isNotBlank() } ?: return
                        // Only final results execute skills; partial results update the UI.
                        listener?.invoke(InputEvent.Partial(text))
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) { }
                })
                main.postDelayed(timeout, 30000)
                val locale = localeManager.locale.value
                val language = if (locale.language == "ru") "ru-RU" else locale.toLanguageTag()
                service.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                })
                Log.i(TAG, "Google recognition requested")
            } catch (error: Exception) {
                releaseRecognizer()
                complete(InputEvent.Error(error))
            }
        }
    }

    private fun complete(event: InputEvent) {
        val target = listener ?: return
        listener = null
        generation++
        main.removeCallbacks(timeout)
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
            main.removeCallbacks(timeout)
            releaseRecognizer()
        }
    }

    companion object { private const val TAG = "DicioGoogleSpeech" }
}
