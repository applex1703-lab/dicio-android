package org.stypox.dicio.di

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.dicio.skill.context.SpeechOutputDevice
import org.stypox.dicio.io.speech.AndroidTtsSpeechDevice
import org.stypox.dicio.io.speech.NothingSpeechDevice
import org.stypox.dicio.io.speech.SnackbarSpeechDevice
import org.stypox.dicio.io.speech.ToastSpeechDevice
import org.stypox.dicio.settings.datastore.SpeechOutputDevice.SPEECH_OUTPUT_DEVICE_ANDROID_TTS
import org.stypox.dicio.settings.datastore.SpeechOutputDevice.SPEECH_OUTPUT_DEVICE_NOTHING
import org.stypox.dicio.settings.datastore.SpeechOutputDevice.SPEECH_OUTPUT_DEVICE_SNACKBAR
import org.stypox.dicio.settings.datastore.SpeechOutputDevice.SPEECH_OUTPUT_DEVICE_TOAST
import org.stypox.dicio.settings.datastore.SpeechOutputDevice.SPEECH_OUTPUT_DEVICE_UNSET
import org.stypox.dicio.settings.datastore.SpeechOutputDevice.UNRECOGNIZED
import org.stypox.dicio.settings.datastore.UserSettings
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpeechOutputDeviceWrapper @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dataStore: DataStore<UserSettings>,
    private val localeManager: LocaleManager,
    // this is always instantiated, but will do nothing if
    // it is not the speech device chosen by the user
    private val snackbarSpeechDevice: SnackbarSpeechDevice,
) : SpeechOutputDevice {

    // instantiate SpeechOutputDevices on the main thread
    private val scope = CoroutineScope(Dispatchers.Main)
    private var wrappedSpeechDevice: SpeechOutputDevice = NothingSpeechDevice()
    private var currentSetting = SPEECH_OUTPUT_DEVICE_UNSET
    private var currentLocale = localeManager.locale.value
    private var sessionActive = false

    /** Called only by an explicit input session, never by a boot/service bind. */
    fun beginSession() {
        if (sessionActive) return
        sessionActive = true
        createDevice()
    }

    private fun createDevice() {
        val previous = wrappedSpeechDevice
        wrappedSpeechDevice = when (currentSetting) {
            UNRECOGNIZED, SPEECH_OUTPUT_DEVICE_UNSET, SPEECH_OUTPUT_DEVICE_ANDROID_TTS ->
                AndroidTtsSpeechDevice(context, currentLocale, prepareSilently = true)
            SPEECH_OUTPUT_DEVICE_NOTHING -> NothingSpeechDevice()
            SPEECH_OUTPUT_DEVICE_TOAST -> ToastSpeechDevice(context)
            SPEECH_OUTPUT_DEVICE_SNACKBAR -> snackbarSpeechDevice
        }
        previous.cleanup()
    }

    init {
        scope.launch {
            dataStore.data
                .combine(localeManager.locale) { userSettings, locale ->
                    Pair(userSettings.speechOutputDevice, locale)
                }
                .distinctUntilChanged()
                .collect { (setting, locale) ->
                    // TODO avoid using locale here, but delegate listening to locale changes to
                    //  AndroidTtsSpeechDevice, or in alternative make it so that
                    //  SttInputDeviceWrapper works the same way
                    val changed = currentSetting != setting || currentLocale != locale
                    currentSetting = setting
                    currentLocale = locale
                    if (sessionActive && changed) createDevice()
                }
        }
    }


    override fun speak(speechOutput: String) {
        beginSession()
        wrappedSpeechDevice.speak(speechOutput)
    }

    override fun stopSpeaking() {
        wrappedSpeechDevice.stopSpeaking()
    }

    override val isSpeaking: Boolean
        get() = wrappedSpeechDevice.isSpeaking

    override fun runWhenFinishedSpeaking(runnable: Runnable) {
        wrappedSpeechDevice.runWhenFinishedSpeaking(runnable)
    }

    override fun cleanup() {
        wrappedSpeechDevice.cleanup()
        wrappedSpeechDevice = NothingSpeechDevice()
        sessionActive = false
        Log.i(TAG, "Speech session released")
    }

    companion object {
        val TAG: String = SpeechOutputDeviceWrapper::class.simpleName!!
    }
}
