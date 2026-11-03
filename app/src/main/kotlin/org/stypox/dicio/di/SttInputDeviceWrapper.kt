package org.stypox.dicio.di

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import androidx.datastore.core.DataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.stypox.dicio.R
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.io.input.SttInputDevice
import org.stypox.dicio.io.input.SttState
import org.stypox.dicio.io.input.GoogleSpeechInputDevice
import org.stypox.dicio.settings.datastore.InputDevice
import org.stypox.dicio.settings.datastore.InputDevice.INPUT_DEVICE_EXTERNAL_POPUP
import org.stypox.dicio.settings.datastore.InputDevice.INPUT_DEVICE_NOTHING
import org.stypox.dicio.settings.datastore.InputDevice.INPUT_DEVICE_UNSET
import org.stypox.dicio.settings.datastore.InputDevice.INPUT_DEVICE_VOSK
import org.stypox.dicio.settings.datastore.InputDevice.UNRECOGNIZED
import org.stypox.dicio.settings.datastore.SttPlaySound
import org.stypox.dicio.settings.datastore.UserSettings
import org.stypox.dicio.util.distinctUntilChangedBlockingFirst
import java.io.IOException


interface SttInputDeviceWrapper {
    val uiState: StateFlow<SttState?>

    fun tryLoad(thenStartListeningEventListener: ((InputEvent) -> Unit)?): Boolean

    fun stopListening()

    fun onClick(eventListener: (InputEvent) -> Unit)

    fun reinitializeToReleaseResources(): Boolean

    /** Whether no STT cleanup is pending or known to have failed. */
    fun isCleanupSafe(): Boolean
}

class SttInputDeviceWrapperImpl(
    @param:ApplicationContext private val appContext: Context,
    dataStore: DataStore<UserSettings>,
    private val localeManager: LocaleManager,
    private val deviceFactory: ((InputDevice) -> SttInputDevice?)? = null,
) : SttInputDeviceWrapper {
    private val scope = CoroutineScope(Dispatchers.Default)
    private val deviceLock = Any()
    private val main = Handler(Looper.getMainLooper())

    private var inputDeviceSetting: InputDevice
    private var sttPlaySoundSetting: SttPlaySound
    private var sttInputDevice: SttInputDevice?
    private var cleanupPending = false
    private var cleanupFailed = false
    private var activeListener: ((InputEvent) -> Unit)? = null
    private var deviceGeneration = 0L

    // null means that the user has not enabled any STT input device
    private val _uiState: MutableStateFlow<SttState?> = MutableStateFlow(null)
    override val uiState: StateFlow<SttState?> = _uiState
    private var uiStateJob: Job? = null


    init {
        // Run blocking, because the data store is always available right away since LocaleManager
        // also initializes in a blocking way from the same data store.
        val (firstSettings, nextSettingsFlow) = dataStore.data
            .map { Pair(it.inputDevice, it.sttPlaySound) }
            .distinctUntilChangedBlockingFirst()

        inputDeviceSetting = firstSettings.first
        sttPlaySoundSetting = firstSettings.second
        sttInputDevice = try { buildInputDevice(inputDeviceSetting) } catch (error: Exception) {
            cleanupFailed = true
            android.util.Log.e("SttInputDeviceWrapper", "STT construction failed", error)
            null
        }
        synchronized(deviceLock) { restartUiStateJob() }

        scope.launch(Dispatchers.Main.immediate) {
            nextSettingsFlow.collect { (inputDevice, sttPlaySound) ->
                val interrupted: ((InputEvent) -> Unit)?
                synchronized(deviceLock) {
                    sttPlaySoundSetting = sttPlaySound
                    if (inputDeviceSetting == inputDevice) return@collect
                    inputDeviceSetting = inputDevice
                    interrupted = activeListener
                    activeListener = null
                }
                replaceDevice()
                interrupted?.let { notifySafely(it, InputEvent.Error(IOException("Voice input settings changed. Start a new request."))) }
            }
        }
    }

    // Main-thread callers see no replacement until destroy has actually completed.
    // An uncertain cleanup permanently disables voice input for this wrapper instance.
    private fun replaceDevice(): Boolean {
        checkMain()
        val previous = synchronized(deviceLock) {
            if (cleanupFailed || cleanupPending) return false
            cleanupPending = true
            val old = sttInputDevice
            sttInputDevice = null
            deviceGeneration++
            restartUiStateJob()
            old
        }
        var completed = false
        scope.launch(Dispatchers.Main.immediate, start = CoroutineStart.UNDISPATCHED) {
            var success = false
            try {
                previous?.destroy()
                success = true
            } catch (error: Exception) {
                android.util.Log.e("SttInputDeviceWrapper", "STT cleanup failed; voice input disabled", error)
            }
            synchronized(deviceLock) {
                if (!success) cleanupFailed = true
                else {
                    try {
                        sttInputDevice = buildInputDevice(inputDeviceSetting)
                        restartUiStateJob()
                        completed = true
                    } catch (error: Exception) {
                        cleanupFailed = true
                        android.util.Log.e("SttInputDeviceWrapper", "STT construction failed", error)
                    }
                }
                cleanupPending = false
            }
        }
        return completed
    }

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper())

    private fun buildInputDevice(setting: InputDevice): SttInputDevice? {
        deviceFactory?.let { return it(setting) }
        return when (setting) {
            UNRECOGNIZED,
            INPUT_DEVICE_UNSET,
            INPUT_DEVICE_VOSK,
            INPUT_DEVICE_EXTERNAL_POPUP ->
                GoogleSpeechInputDevice(appContext, localeManager)
            INPUT_DEVICE_NOTHING -> null
        }
    }

    private fun restartUiStateJob() {
        uiStateJob?.cancel()
        val newSttInputDevice = sttInputDevice
        if (newSttInputDevice == null) {
            uiStateJob = null
            _uiState.value = null
        } else {
            uiStateJob = scope.launch(Dispatchers.Main) {
                newSttInputDevice.uiState.collect {
                    if (synchronized(deviceLock) { sttInputDevice === newSttInputDevice }) {
                        _uiState.value = it
                        if (it == SttState.Listening) playSound(R.raw.listening_sound)
                    }
                }
            }
        }
    }

    private fun playSound(resid: Int) {
        val attributes = AudioAttributes.Builder()
            .setUsage(
                when (sttPlaySoundSetting) {
                    SttPlaySound.UNRECOGNIZED,
                    SttPlaySound.STT_PLAY_SOUND_UNSET,
                    SttPlaySound.STT_PLAY_SOUND_NOTIFICATION -> AudioAttributes.USAGE_NOTIFICATION
                    SttPlaySound.STT_PLAY_SOUND_ALARM -> AudioAttributes.USAGE_ALARM
                    SttPlaySound.STT_PLAY_SOUND_MEDIA -> AudioAttributes.USAGE_MEDIA
                    SttPlaySound.STT_PLAY_SOUND_NONE -> return // do not play any sound
                }
            )
            .build()
        val mediaPlayer = MediaPlayer.create(appContext, resid, attributes, 0) ?: return
        try {
            mediaPlayer.setOnCompletionListener { it.release() }
            mediaPlayer.setOnErrorListener { player, _, _ -> player.release(); true }
            mediaPlayer.setVolume(0.75f, 0.75f)
            mediaPlayer.start()
        } catch (error: RuntimeException) {
            mediaPlayer.release()
            android.util.Log.w("SttInputDeviceWrapper", "Could not play listening signal", error)
        }
    }

    private fun wrapEventListener(eventListener: (InputEvent) -> Unit): (InputEvent) -> Unit = {
        if (it is InputEvent.None) {
            scope.launch {
                playSound(R.raw.listening_no_input_sound)
            }
        }
        eventListener(it)
    }

    private fun notifySafely(callback: (InputEvent) -> Unit, event: InputEvent): Boolean = try {
        callback(event)
        true
    } catch (error: Exception) {
        android.util.Log.e("SttInputDeviceWrapper", "STT callback failed", error)
        false
    }

    // Serialize validation and delivery with settings changes and reinitialization on main.
    private fun listenerFor(device: SttInputDevice, generation: Long, callback: (InputEvent) -> Unit): (InputEvent) -> Unit = { event ->
        val deliver = {
            val current = synchronized(deviceLock) {
                if (activeListener !== callback || sttInputDevice !== device || deviceGeneration != generation) false else {
                    if (event is InputEvent.Final || event is InputEvent.Error || event is InputEvent.None) activeListener = null
                    true
                }
            }
            if (current && !notifySafely(callback, event)) {
                val shouldRelease = synchronized(deviceLock) {
                    if (sttInputDevice !== device || deviceGeneration != generation ||
                        (activeListener != null && activeListener !== callback)) false else {
                        if (activeListener === callback) activeListener = null
                        true
                    }
                }
                if (shouldRelease) replaceDevice()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) deliver() else main.post { deliver() }
    }

    private fun failedDeviceCall(device: SttInputDevice, error: Exception) {
        android.util.Log.e("SttInputDeviceWrapper", "STT device call failed", error)
        var interrupted: ((InputEvent) -> Unit)? = null
        val shouldRelease = synchronized(deviceLock) {
            if (sttInputDevice !== device) false else {
                interrupted = activeListener
                activeListener = null
                true
            }
        }
        if (shouldRelease) replaceDevice()
        // This may be a reentrant new owner of the same now-uncertain device.
        interrupted?.let { notifySafely(it, InputEvent.Error(IOException("Voice input failed", error))) }
    }

    override fun tryLoad(thenStartListeningEventListener: ((InputEvent) -> Unit)?): Boolean {
        checkMain()
        val device = synchronized(deviceLock) { if (cleanupFailed || cleanupPending) null else sttInputDevice } ?: return false
        val generation = deviceGeneration
        if (thenStartListeningEventListener == null) return try { device.tryLoad(null) } catch (error: Exception) {
            failedDeviceCall(device, error)
            false
        }
        if (synchronized(deviceLock) { activeListener != null }) {
            notifySafely(thenStartListeningEventListener, InputEvent.Error(IOException("Voice input is busy")))
            return false
        }
        val callback = wrapEventListener(thenStartListeningEventListener)
        synchronized(deviceLock) { activeListener = callback }
        val loaded = try { device.tryLoad(listenerFor(device, generation, callback)) } catch (error: Exception) {
            failedDeviceCall(device, error)
            false
        }
        if (!loaded) synchronized(deviceLock) {
            if (activeListener === callback) activeListener = null
        }
        return loaded
    }

    override fun stopListening() {
        checkMain()
        val device = synchronized(deviceLock) { sttInputDevice }
        if (device != null) try { device.stopListening() } catch (error: Exception) {
            failedDeviceCall(device, error)
        }
    }

    override fun onClick(eventListener: (InputEvent) -> Unit) {
        checkMain()
        val device = synchronized(deviceLock) { if (cleanupFailed || cleanupPending) null else sttInputDevice }
        if (device == null) {
            notifySafely(eventListener, InputEvent.Error(IOException("Voice input unavailable: cleanup did not complete.")))
            return
        }
        if (synchronized(deviceLock) { activeListener != null }) {
            notifySafely(eventListener, InputEvent.Error(IOException("Voice input is busy")))
            return
        }
        val callback = wrapEventListener(eventListener)
        val generation = deviceGeneration
        synchronized(deviceLock) { activeListener = callback }
        try { device.onClick(listenerFor(device, generation, callback)) } catch (error: Exception) {
            failedDeviceCall(device, error)
        }
    }

    override fun reinitializeToReleaseResources(): Boolean {
        checkMain()
        synchronized(deviceLock) { activeListener = null }
        return replaceDevice()
    }

    override fun isCleanupSafe(): Boolean {
        checkMain()
        return synchronized(deviceLock) { !cleanupFailed && !cleanupPending }
    }

}

@Module
@InstallIn(SingletonComponent::class)
class SttInputDeviceWrapperModule {
    @Provides
    @Singleton
    fun provideInputDeviceWrapper(
        @ApplicationContext appContext: Context,
        dataStore: DataStore<UserSettings>,
        localeManager: LocaleManager,
    ): SttInputDeviceWrapper {
        return SttInputDeviceWrapperImpl(
            appContext, dataStore, localeManager
        )
    }
}
