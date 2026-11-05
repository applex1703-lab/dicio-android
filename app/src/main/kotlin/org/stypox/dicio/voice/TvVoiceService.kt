package org.stypox.dicio.voice

import android.os.Build
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.util.Log

/** System assistant endpoint. Dicio does not start recognition while idle. */
class TvVoiceService : VoiceInteractionService() {
    private val preferences by lazy { prebindPreferences(this) }
    private var observing = false
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == PREBIND_KEY || key == null) prebind.setEnabled(preferences.getBoolean(PREBIND_KEY, false))
    }
    private val prebind by lazy {
        KatnissPrebind({ intent, connection, flags -> bindService(intent, connection, flags) },
            { connection -> unbindService(connection) }, prebindReservation)
    }

    override fun onReady() {
        super.onReady()
        if (prebind.closed) return
        if (Build.VERSION.SDK_INT >= 23) {
            setDisabledShowContext(VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
        }
        if (!observing) {
            preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
            observing = true
        }
        prebind.setEnabled(preferences.getBoolean(PREBIND_KEY, false))
    }

    override fun onShutdown() {
        stopPrebind()
        super.onShutdown()
    }

    override fun onDestroy() {
        stopPrebind()
        super.onDestroy()
    }

    private fun stopPrebind() {
        if (observing) {
            preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
            observing = false
        }
        prebind.close()
    }

    companion object {
        internal val prebindReservation = PrebindReservation()
    }
}

internal const val PREBIND_KEY = "background_prebind_enabled"
internal fun prebindPreferences(context: Context): SharedPreferences =
    context.getSharedPreferences("tv_voice_prebind", Context.MODE_PRIVATE)

internal class PrebindTimer(
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val delayMillis: Long = 10_000,
) {
    fun schedule(task: Runnable) { handler.postDelayed(task, delayMillis) }
    fun cancel(task: Runnable) { handler.removeCallbacks(task) }
}

/** One outstanding or uncertain binding per process, including across service instances. */
internal class PrebindReservation {
    private var owner: Any? = null
    private var connected: Any? = null
    fun acquire(token: Any): Boolean {
        if (owner != null) return false
        owner = token
        return true
    }
    fun release(token: Any) {
        check(owner === token)
        invalidate(token)
        owner = null
    }
    fun markConnected(token: Any) { if (owner === token) connected = token }
    fun invalidate(token: Any) { if (connected === token) connected = null }
    fun snapshot(): Any? = connected
    fun isCurrent(token: Any?): Boolean = token != null && connected === token && owner === token
}

/** Main-thread-only; attempts follow initial readiness or explicit enable, never a timer retry. */
internal class KatnissPrebind(
    private val bind: (Intent, ServiceConnection, Int) -> Boolean,
    private val unbind: (ServiceConnection) -> Unit,
    private val reservation: PrebindReservation,
    private val timer: PrebindTimer = PrebindTimer(),
    private val schedule: (Runnable) -> Unit = timer::schedule,
    private val cancel: (Runnable) -> Unit = timer::cancel,
) {
    var closed = false
        private set
    private var attempted = false
    private var connection: ServiceConnection? = null
    private var binding = false
    private var finishPending = false
    private var finishing = false
    private var enablePending = false
    private var enabled = false
    private var timeout: Runnable? = null
    private var callbackConnected = false

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper())

    fun setEnabled(value: Boolean) {
        checkMain()
        if (closed || enabled == value) return
        enabled = value
        if (value) {
            if (binding || finishing || connection != null) enablePending = true else start()
        } else {
            enablePending = false
            attempted = false
            finish()
        }
    }

    fun start() {
        checkMain()
        if (closed || attempted) return
        attempted = true
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                checkMain()
                if (connection !== this) return
                clearTimeout()
                if (service == null) {
                    Log.i(TAG, "prebind_refused null_binder")
                    finish()
                } else {
                    callbackConnected = true
                    if (!binding && enabled && !closed && !finishPending) reservation.markConnected(this)
                    Log.i(TAG, "prebind_connected")
                }
            }
            override fun onServiceDisconnected(name: ComponentName?) { checkMain(); if (connection === this) { Log.i(TAG, "prebind_disconnected"); finish() } }
            override fun onBindingDied(name: ComponentName?) { checkMain(); if (connection === this) { Log.i(TAG, "prebind_binding_died"); finish() } }
            override fun onNullBinding(name: ComponentName?) { checkMain(); if (connection === this) { Log.i(TAG, "prebind_refused null_binding"); finish() } }
        }
        if (!reservation.acquire(conn)) {
            Log.i(TAG, "prebind_skipped reservation_busy")
            return
        }
        connection = conn
        lateinit var deadline: Runnable
        deadline = Runnable {
            checkMain()
            if (connection === conn && timeout === deadline) {
                Log.i(TAG, "prebind_timeout")
                finish()
            }
        }
        timeout = deadline
        schedule(deadline)
        binding = true
        val accepted = try {
            bind(Intent("android.speech.RecognitionService").setComponent(COMPONENT), conn, Context.BIND_AUTO_CREATE)
        } catch (_: Exception) {
            false
        } finally {
            binding = false
        }
        Log.i(TAG, if (accepted) "prebind_requested" else "prebind_refused bind_failed")
        if (!accepted || finishPending) finish()
        else if (callbackConnected && enabled && !closed && connection === conn) reservation.markConnected(conn)
    }

    fun close() {
        checkMain()
        closed = true
        enablePending = false
        finish()
    }

    private fun finish() {
        val conn = connection ?: return
        reservation.invalidate(conn)
        callbackConnected = false
        clearTimeout()
        if (binding) {
            finishPending = true
            return
        }
        connection = null
        finishPending = false
        finishing = true
        val released = try { unbind(conn); true } catch (_: Exception) { false }
        if (released) reservation.release(conn)
        finishing = false
        // On uncertain cleanup retain the process reservation and never retry.
        Log.i(TAG, if (released) "prebind_unbound" else "prebind_unbind_uncertain; further prebinds blocked until process exit")
        val startRequested = enablePending
        enablePending = false
        if (released && startRequested && enabled && !closed) start()
    }

    private fun clearTimeout() {
        timeout?.let(cancel)
        timeout = null
    }

    companion object {
        private const val TAG = "DicioPrebind"
        private val COMPONENT = ComponentName("com.google.android.katniss", "com.google.android.apps.tvsearch.voice.recognition.KatnissRecognitionService")
    }
}
