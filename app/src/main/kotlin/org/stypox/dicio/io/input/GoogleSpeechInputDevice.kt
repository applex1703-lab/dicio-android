package org.stypox.dicio.io.input

import android.content.ComponentName
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.stypox.dicio.di.LocaleManager
import java.io.IOException

internal interface GoogleRecognizer {
    fun setRecognitionListener(listener: RecognitionListener)
    fun startListening(intent: Intent)
    fun stopListening()
    fun destroy()
}

internal interface GoogleSpeechPlatform {
    fun createRecognizer(): GoogleRecognizer
    fun bind(intent: Intent, connection: ServiceConnection, flags: Int): Boolean
    fun unbind(connection: ServiceConnection)
    fun requiresAudioOpWait(): Boolean = false
    fun audioOpMode(): Int? = null
    fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()
}

/** One-shot Google recognition, returning text to Dicio rather than executing Google actions. */
class GoogleSpeechInputDevice(
    private val context: Context,
    private val localeManager: LocaleManager,
) : SttInputDevice {
    private val main = Handler(Looper.getMainLooper())
    private val state = MutableStateFlow<SttState>(SttState.Loaded)
    override val uiState: StateFlow<SttState> = state
    private var platform: GoogleSpeechPlatform = object : GoogleSpeechPlatform {
        override fun createRecognizer(): GoogleRecognizer {
            val delegate = SpeechRecognizer.createSpeechRecognizer(context, SERVICE_COMPONENT)
            return object : GoogleRecognizer {
                override fun setRecognitionListener(listener: RecognitionListener) = delegate.setRecognitionListener(listener)
                override fun startListening(intent: Intent) = delegate.startListening(intent)
                override fun stopListening() = delegate.stopListening()
                override fun destroy() = delegate.destroy()
            }
        }
        override fun bind(intent: Intent, connection: ServiceConnection, flags: Int) =
            context.bindService(intent, connection, flags)
        override fun unbind(connection: ServiceConnection) = context.unbindService(connection)
        override fun requiresAudioOpWait() = Build.VERSION.SDK_INT == 30
        override fun audioOpMode(): Int? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
            val uid = context.packageManager.getPackageUid(SERVICE_COMPONENT.packageName, 0)
            val appOps = context.getSystemService(AppOpsManager::class.java)
                ?: throw IOException("AppOps service unavailable")
            @Suppress("DEPRECATION")
            return appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_RECORD_AUDIO, uid, SERVICE_COMPONENT.packageName)
        }
    }
    internal constructor(context: Context, localeManager: LocaleManager, platform: GoogleSpeechPlatform) :
        this(context, localeManager) {
        this.platform = platform
    }
    private var recognizer: GoogleRecognizer? = null
    private var listener: ((InputEvent) -> Unit)? = null
    // Only this short gate crosses threads. Never hold it across a platform call or suspension.
    private val startGate = Any()
    @Volatile private var closed = false
    private var generation = 0
    private var binding: ServiceConnection? = null
    private var bindCallsInFlight = 0
    private var startCallsInFlight = 0 // main-thread owned
    private var recognitionStarted = false
    @Volatile private var cleanupFailure: Exception? = null
    private var recognizerDestroyAttempted = false
    private var unbindAttempted = false
    private var bindTimeout: Runnable? = null
    private var appOpsPoll: Runnable? = null
    private val timeout = Runnable {
        generation++
        releaseRecognizer()
        complete(InputEvent.Error(IOException("Google speech input timed out")))
    }

    override fun tryLoad(thenStartListeningEventListener: ((InputEvent) -> Unit)?): Boolean {
        if (closed || cleanupFailure != null) return false
        thenStartListeningEventListener?.let { start(it) }
        return true
    }

    override fun onClick(eventListener: (InputEvent) -> Unit) { start(eventListener) }

    // Create the recognizer only when a recording is explicitly requested.
    private fun prepareRecognizer(): GoogleRecognizer {
        return recognizer ?: platform.createRecognizer().also {
            recognizer = it
            recognizerDestroyAttempted = false
            Log.i(TAG, "Google recognizer created (not listening)")
        }
    }

    // A failed operation is never retried: its outcome may already have taken effect.
    private fun releaseRecognizer() {
        val previous = recognizer ?: return
        if (recognizerDestroyAttempted) return
        recognizerDestroyAttempted = true
        try {
            previous.destroy()
            recognizer = null
        } catch (error: Exception) {
            recordCleanupFailure(error)
        }
    }

    private fun recordCleanupFailure(error: Exception) {
        if (cleanupFailure == null) cleanupFailure = error
        else if (cleanupFailure !== error) cleanupFailure?.addSuppressed(error)
        Log.e(TAG, "Google speech cleanup failed; voice input disabled", error)
    }

    private fun start(eventListener: (InputEvent) -> Unit) {
        val begin = begin@{
            if (closed || cleanupFailure != null) {
                eventListener(InputEvent.Error(IOException("Google speech cleanup failed; voice input disabled", cleanupFailure)))
                return@begin
            }
            if (listener != null) {
                // Preserve the existing listener and its request; never emit a second terminal event.
                Log.w(TAG, "Rejecting start while recognition is active")
                eventListener(InputEvent.Error(IOException("Распознаватель Google занят. Повторите запрос позже.")))
                return@begin
            }
            listener = eventListener
            val session = ++generation
            eventListener(InputEvent.Started)
            if (closed || session != generation || listener == null) return@begin
            state.value = SttState.WaitingForResult
            var bindInFlight = false
            var startInFlight = false
            var pendingTerminal: (() -> Unit)? = null
            val pendingCallbacks = mutableListOf<() -> Unit>()
            try {
                val service = prepareRecognizer()
                service.setRecognitionListener(object : RecognitionListener {
                    private fun current() = !closed && session == generation && listener != null && recognitionStarted
                    override fun onReadyForSpeech(params: Bundle?) {
                        if (current()) {
                            if (startInFlight) {
                                if (pendingTerminal == null) pendingCallbacks.add { onReadyForSpeech(params) }
                                return
                            }
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
                        if (current()) {
                            if (startInFlight) {
                                if (pendingTerminal == null) pendingCallbacks.add { onEndOfSpeech() }
                                return
                            }
                            state.value = SttState.WaitingForResult
                        }
                    }
                    override fun onError(error: Int) {
                        if (!current()) return
                        if (startInFlight) {
                            if (pendingTerminal == null) pendingTerminal = { onError(error) }
                            return
                        }
                        Log.w(TAG, "Google recognition error=$error")
                        val message = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH ->
                                "Речь не распознана. Повторите запрос после начала прослушивания."
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                                "Речь не поступила. Повторите запрос кнопкой Ассистента."
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                                "Android запретил Google доступ к записи. Проверьте разрешение микрофона и выбор Google как системного распознавателя речи."
                            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                                "Ошибка сети Google при распознавании речи. Проверьте подключение и повторите запрос."
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                                "Распознаватель Google занят. Повторите запрос немного позже."
                            else -> "Google recognition error $error"
                        }
                        // A rejected connection must not be retained for the next explicit press.
                        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ||
                            error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_NETWORK ||
                            error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                            error == SpeechRecognizer.ERROR_SERVER) {
                            generation++
                            releaseRecognizer()
                        }
                        complete(InputEvent.Error(IOException(message)))
                    }
                    override fun onResults(results: Bundle?) {
                        if (!current()) return
                        if (startInFlight) {
                            if (pendingTerminal == null) pendingTerminal = { onResults(results) }
                            return
                        }
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
                        if (startInFlight) {
                            if (pendingTerminal == null) pendingCallbacks.add { onPartialResults(partialResults) }
                            return
                        }
                        val text = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull { it.isNotBlank() } ?: return
                        // Only final results execute skills; partial results update the UI.
                        listener?.invoke(InputEvent.Partial(text))
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) { }
                })
                // A platform may deliver a terminal callback synchronously while installing it.
                if (closed || session != generation || listener == null) return@begin
                val locale = localeManager.locale.value
                val language = if (locale.language == "ru") "ru-RU" else locale.toLanguageTag()
                val recognitionIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }
                var started = false
                val bindRequestedAt = SystemClock.elapsedRealtime()
                lateinit var bindDeadline: Runnable
                val connection = object : ServiceConnection {
                    // Posting also handles a callback delivered reentrantly during bindService.
                    private fun dispatch(action: () -> Unit) {
                        main.post {
                            if (!closed && session == generation && listener != null && binding === this) {
                                action()
                            }
                        }
                    }
                    private fun fail(reason: String) {
                        Log.w(TAG, "Google diagnostic bind $reason")
                        generation++
                        releaseRecognizer()
                        complete(InputEvent.Error(IOException("Google speech service bind $reason")))
                    }
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) = dispatch {
                        if (binder == null) {
                            fail("returned null binder")
                        } else if (!started) {
                            started = true
                            Log.i(TAG, "Google diagnostic bind connected after ${SystemClock.elapsedRealtime() - bindRequestedAt} ms")
                            main.removeCallbacks(bindDeadline)
                            if (bindTimeout === bindDeadline) bindTimeout = null
                            var waitStarted: Long? = null
                            lateinit var poll: Runnable
                            poll = Runnable {
                                if (closed || session != generation || listener == null || binding !== this || appOpsPoll !== poll) return@Runnable
                                try {
                                    val now = platform.elapsedRealtime()
                                    val began = waitStarted ?: now.also { waitStarted = it }
                                    val limit = began + APP_OPS_WAIT_MS
                                    // Do not accept a late ALLOWED after the deadline.
                                    if (now >= limit) {
                                        throw IOException("Google microphone AppOps wait timed out")
                                    }
                                    val requiresWait = platform.requiresAudioOpWait()
                                    if (requiresWait && now - began < APP_OPS_MIN_WAIT_MS) {
                                        main.postDelayed(poll, APP_OPS_POLL_MS)
                                        return@Runnable
                                    }
                                    val mode = if (requiresWait) platform.audioOpMode() else null
                                    if (closed || session != generation || listener == null || binding !== this || appOpsPoll !== poll) return@Runnable
                                    val checkedAt = platform.elapsedRealtime()
                                    if (checkedAt >= limit) throw IOException("Google microphone AppOps wait timed out")
                                    if (!requiresWait || mode == AppOpsManager.MODE_ALLOWED) {
                                        appOpsPoll = null
                                        main.postDelayed(timeout, 30000)
                                        // Linearize the start commitment against a destroy request.
                                        // A committed call may already be in progress when destroy arrives.
                                        val committed = synchronized(startGate) {
                                            if (closed) false else {
                                                recognitionStarted = true
                                                startCallsInFlight++
                                                true
                                            }
                                        }
                                        if (!committed) return@Runnable
                                        startInFlight = true
                                        try {
                                            try {
                                                service.startListening(recognitionIntent)
                                            } finally {
                                                startCallsInFlight--
                                            }
                                        } catch (error: Exception) {
                                            startInFlight = false
                                            pendingTerminal = null
                                            pendingCallbacks.clear()
                                            // A throwing call may have started recording. Do not reuse this
                                            // device even if destroy succeeds; its outcome is uncertain.
                                            recordCleanupFailure(IOException("Google startListening outcome is uncertain", error))
                                            generation++
                                            releaseRecognizer()
                                            if (listener != null) complete(InputEvent.Error(error)) else cleanupBinding()
                                            return@Runnable
                                        }
                                        startInFlight = false
                                        val terminal = pendingTerminal
                                        pendingTerminal = null
                                        val callbacks = pendingCallbacks.toList()
                                        pendingCallbacks.clear()
                                        callbacks.forEach { it() }
                                        terminal?.invoke()
                                        Log.i(TAG, "Google recognition requested after diagnostic bind (AppOps=$mode)")
                                    } else if (mode == AppOpsManager.MODE_IGNORED || mode == AppOpsManager.MODE_FOREGROUND) {
                                        main.postDelayed(poll, APP_OPS_POLL_MS)
                                    } else {
                                        throw IOException("Google microphone AppOps unavailable (mode=$mode)")
                                    }
                                } catch (error: Exception) {
                                    if (!closed && session == generation && listener != null && binding === this) {
                                        generation++
                                        releaseRecognizer()
                                        complete(InputEvent.Error(error))
                                    }
                                }
                            }
                            appOpsPoll = poll
                            poll.run()
                        }
                    }
                    override fun onServiceDisconnected(name: ComponentName?) = dispatch { fail("disconnected") }
                    override fun onBindingDied(name: ComponentName?) = dispatch { fail("died") }
                    override fun onNullBinding(name: ComponentName?) = dispatch { fail("returned null binding") }
                }
                val flags = Context.BIND_AUTO_CREATE or
                    (if (Build.VERSION.SDK_INT >= 29) Context.BIND_INCLUDE_CAPABILITIES else 0)
                bindDeadline = Runnable {
                    if (!closed && session == generation && listener != null && binding === connection) {
                        Log.w(TAG, "Google diagnostic bind timed out")
                        generation++
                        releaseRecognizer()
                        complete(InputEvent.Error(IOException("Google speech service bind timed out")))
                    }
                }
                bindTimeout = bindDeadline
                binding = connection
                unbindAttempted = false
                bindInFlight = true
                bindCallsInFlight++
                val bound = try {
                    platform.bind(Intent("android.speech.RecognitionService").apply {
                        component = SERVICE_COMPONENT
                    }, connection, flags)
                } finally {
                    bindCallsInFlight--
                }
                bindInFlight = false
                if (closed || session != generation || listener == null || binding !== connection) return@begin
                if (!bound) {
                    Log.w(TAG, "Google diagnostic bind rejected")
                    generation++
                    releaseRecognizer()
                    complete(InputEvent.Error(IOException("Google speech service bind rejected")))
                } else {
                    main.postDelayed(bindDeadline, 10000)
                    Log.i(TAG, "Google diagnostic bind requested")
                }
            } catch (error: Exception) {
                if (session != generation || closed || listener == null) {
                    if (bindInFlight) {
                        // The old bind threw after its terminal callback. Its effects after the
                        // earlier unbind are unknowable, even if another session has started.
                        recordCleanupFailure(IOException("Stale Google bind outcome is uncertain", error))
                        generation++
                        releaseRecognizer()
                        if (listener != null) complete(InputEvent.Error(error)) else cleanupBinding()
                    } else {
                        Log.w(TAG, "Ignoring exception from obsolete Google session", error)
                    }
                    return@begin
                }
                generation++
                releaseRecognizer()
                complete(InputEvent.Error(error))
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) begin() else main.post { begin() }
    }

    private fun complete(event: InputEvent) {
        val target = listener ?: return
        listener = null
        recognitionStarted = false
        generation++
        main.removeCallbacks(timeout)
        appOpsPoll?.let { main.removeCallbacks(it) }
        appOpsPoll = null
        bindTimeout?.let { main.removeCallbacks(it) }
        bindTimeout = null
        cleanupBinding()
        if (cleanupFailure != null) releaseRecognizer()
        state.value = SttState.Loaded
        val failure = cleanupFailure
        // Delivery and destroy have an explicit ordering without holding a lock in user code.
        val deliver = synchronized(startGate) { !closed }

        if (deliver) target(if (failure != null) InputEvent.Error(
            IOException("Google speech cleanup failed; voice input disabled", failure)
        ) else event)
    }

    private fun cleanupBinding() {
        val previous = binding ?: return
        if (unbindAttempted) return
        unbindAttempted = true
        try {
            platform.unbind(previous)
            binding = null
        } catch (error: Exception) {
            recordCleanupFailure(error)
        }
    }

    override fun stopListening() {
        val stop = {
            if (!closed && listener != null) {
                if (recognitionStarted) {
                    recognizer?.stopListening()
                } else {
                    generation++
                    releaseRecognizer()
                    complete(InputEvent.None)
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) stop() else main.post { stop() }
    }

    override suspend fun destroy() {
        // Prevent new commitments and terminal delivery immediately, even while main is busy.
        synchronized(startGate) { closed = true }
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            if (bindCallsInFlight != 0 && cleanupFailure == null) {
                recordCleanupFailure(IOException("Google bind is still in progress during cleanup"))
            }
            if (startCallsInFlight != 0) {
                recordCleanupFailure(IOException("Google startListening is still in progress during cleanup"))
            }
            complete(InputEvent.None)
            main.removeCallbacks(timeout)
            appOpsPoll?.let { main.removeCallbacks(it) }
            appOpsPoll = null
            bindTimeout?.let { main.removeCallbacks(it) }
            bindTimeout = null
            cleanupBinding()
            releaseRecognizer()
            cleanupFailure?.let { throw IOException("Google speech cleanup failed", it) }
        }
    }

    companion object {
        private const val TAG = "DicioGoogleSpeech"
        // Observed TCL API 30 committed around five seconds after bind; this is only a
        // device-specific settling heuristic, not evidence that microphone access is granted.
        private const val APP_OPS_MIN_WAIT_MS = 6000L
        private const val APP_OPS_WAIT_MS = 8000L
        private const val APP_OPS_POLL_MS = 250L
        private val SERVICE_COMPONENT = ComponentName(
            "com.google.android.katniss",
            "com.google.android.apps.tvsearch.voice.recognition.KatnissRecognitionService",
        )
    }
}
