package org.stypox.dicio.di

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
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

    fun reinitializeToReleaseResources()
}

class SttInputDeviceWrapperImpl(
    @param:ApplicationContext private val appContext: Context,
    dataStore: DataStore<UserSettings>,
    private val localeManager: LocaleManager,
) : SttInputDeviceWrapper {
    private val scope = CoroutineScope(Dispatchers.Default)
    private val deviceLock = Any()

    private var inputDeviceSetting: InputDevice
    private var sttPlaySoundSetting: SttPlaySound
    private var sttInputDevice: SttInputDevice?
    private var activeListener: ((InputEvent) -> Unit)? = null

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
        sttInputDevice = buildInputDevice(inputDeviceSetting)
        synchronized(deviceLock) { restartUiStateJob() }

        scope.launch(Dispatchers.Main.immediate) {
            nextSettingsFlow.collect { (inputDevice, sttPlaySound) ->
                var interrupted: ((InputEvent) -> Unit)? = null
                var previous: SttInputDevice? = null
                synchronized(deviceLock) {
                    sttPlaySoundSetting = sttPlaySound
                    if (inputDeviceSetting != inputDevice) {
                        interrupted = activeListener
                        activeListener = null
                        previous = changeInputDeviceTo(inputDevice)
                    }
                }
                destroyDevice(previous)
                interrupted?.invoke(InputEvent.Error(IOException("Настройка голосового ввода изменена. Начните новый запрос.")))
            }
        }
    }

    // Called with deviceLock held. Invalidate the old device before another caller can use it.
    private fun changeInputDeviceTo(setting: InputDevice): SttInputDevice? {
        val prevSttInputDevice = sttInputDevice
        inputDeviceSetting = setting
        sttInputDevice = buildInputDevice(setting)
        restartUiStateJob()
        return prevSttInputDevice
    }

    private fun destroyDevice(device: SttInputDevice?) {
        if (device != null) scope.launch(start = CoroutineStart.UNDISPATCHED) { device.destroy() }
    }

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper())

    private fun buildInputDevice(setting: InputDevice): SttInputDevice? {
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

    override fun tryLoad(thenStartListeningEventListener: ((InputEvent) -> Unit)?): Boolean {
        checkMain()
        val device = synchronized(deviceLock) { sttInputDevice } ?: return false
        if (thenStartListeningEventListener == null) return device.tryLoad(null)
        val callback = wrapEventListener(thenStartListeningEventListener)
        synchronized(deviceLock) { activeListener = callback }
        val loaded = device.tryLoad { event ->
            val deliver = synchronized(deviceLock) {
                if (activeListener !== callback) false else {
                    if (event is InputEvent.Final || event is InputEvent.Error || event is InputEvent.None) {
                        activeListener = null
                    }
                    true
                }
            }
            if (deliver) callback(event)
        }
        if (!loaded) synchronized(deviceLock) {
            if (activeListener === callback) activeListener = null
        }
        return loaded
    }

    override fun stopListening() {
        checkMain()
        val device = synchronized(deviceLock) { sttInputDevice }
        device?.stopListening()
    }

    override fun onClick(eventListener: (InputEvent) -> Unit) {
        checkMain()
        val device = synchronized(deviceLock) { sttInputDevice }
        device?.onClick(wrapEventListener(eventListener))
    }

    override fun reinitializeToReleaseResources() {
        checkMain()
        val previous = synchronized(deviceLock) {
            activeListener = null
            changeInputDeviceTo(inputDeviceSetting)
        }
        destroyDevice(previous)
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
