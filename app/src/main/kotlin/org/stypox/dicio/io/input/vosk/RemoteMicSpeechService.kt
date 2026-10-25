package org.stypox.dicio.io.input.vosk

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Build
import android.util.Log
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import java.io.IOException

/** Narrow compatibility adapter for Vosk 0.3.70, which cannot accept a caller-owned recorder. */
class RemoteMicSpeechService(
    recognizer: Recognizer,
    sampleRate: Float,
    context: Context,
) : SpeechService(recognizer, sampleRate) {
    init {
        try {
            configureRemote(context)
        } catch (error: Exception) {
            shutdown()
            throw IOException("Cannot select TV remote microphone", error)
        }
    }

    @SuppressLint("NewApi") // Product-name matching also supports vendor BLE inputs on Android 11.
    private fun configureRemote(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val remote = manager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
            it.productName.toString().contains("TV Remote Mic", ignoreCase = true)
        } ?: return

        // This field belongs to our pinned, bundled Vosk dependency, not a hidden Android API.
        // Fail explicitly if the dependency changes instead of silently using the television mic.
        val field = SpeechService::class.java.getDeclaredField("recorder")
        field.isAccessible = true
        val recorder = field.get(this) as AudioRecord
        if (!recorder.setPreferredDevice(remote)) {
            throw IOException("TV remote microphone preference rejected")
        }
        Log.i(TAG, "Requested input id=${remote.id}, name=${remote.productName}, type=${remote.type}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            recorder.addOnRoutingChangedListener({ routing ->
                val actual: AudioDeviceInfo? = routing.routedDevice
                Log.i(TAG, "Actual input id=${actual?.id}, name=${actual?.productName}, type=${actual?.type}")
            }, android.os.Handler(android.os.Looper.getMainLooper()))
        }
    }

    companion object {
        private const val TAG = "DicioRemoteMic"
    }
}
