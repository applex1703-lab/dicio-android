package org.stypox.dicio.voice

import android.os.Build
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.util.Log

/** System assistant endpoint. Dicio does not start recognition while idle. */
class TvVoiceService : VoiceInteractionService() {
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
        if (supportsBackgroundPreparation(Build.VERSION.SDK_INT, Build.MANUFACTURER)) prebind.setEnabled(true)
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
        prebind.close()
    }

    companion object {
        internal val prebindReservation = PrebindReservation()
    }
}

internal fun supportsBackgroundPreparation(sdk: Int, manufacturer: String?): Boolean =
    sdk == 30 && manufacturer.equals("TCL", ignoreCase = true)

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
    private var uncertain = false
    private val waiters = mutableSetOf<Runnable>()
    fun acquire(token: Any): Boolean {
        if (owner != null) return false
        owner = token
        return true
    }
    fun release(token: Any) {
        check(owner === token)
        invalidate(token)
        owner = null
        val ready = waiters.toList()
        waiters.clear()
        ready.forEach { it.run() }
    }
    fun awaitRelease(waiter: Runnable) { if (!uncertain) waiters.add(waiter) }
    fun cancelWait(waiter: Runnable) { waiters.remove(waiter) }
    fun markUncertain(token: Any) {
        check(owner === token)
        uncertain = true
        waiters.clear()
    }
    fun markConnected(token: Any) { if (owner === token && connected == null) connected = Any() }
    fun invalidate(token: Any) { if (owner === token) connected = null }
    fun snapshot(): Any? = connected
    fun isCurrent(token: Any?): Boolean = token != null && connected === token && owner != null
}

/** Main-thread-only; recording is never started by this background binding. */
internal class KatnissPrebind(
    private val bind: (Intent, ServiceConnection, Int) -> Boolean,
    private val unbind: (ServiceConnection) -> Unit,
    private val reservation: PrebindReservation,
    private val timer: PrebindTimer = PrebindTimer(),
    private val schedule: (Runnable) -> Unit = timer::schedule,
    private val cancel: (Runnable) -> Unit = timer::cancel,
    private val now: () -> Long = SystemClock::elapsedRealtime,
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
    private var recoveryUsed = false
    private var recoveryPending = false
    private var recovery: Runnable? = null
    private var connectedAt: Long? = null
    private var reservationWait: Runnable? = null

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper())

    fun setEnabled(value: Boolean) {
        checkMain()
        if (closed || enabled == value) return
        enabled = value
        if (value) {
            if (binding || finishing || connection != null) enablePending = true else start()
        } else {
            cancelReservationWait()
            cancelRecovery()
            recoveryPending = false
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
                    if (!callbackConnected) connectedAt = now()
                    callbackConnected = true
                    if (!binding && enabled && !closed && !finishPending) reservation.markConnected(this)
                    Log.i(TAG, "prebind_connected")
                }
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                checkMain()
                if (connection !== this) return
                // Android retains this binding and may reconnect after the process restarts.
                reservation.invalidate(this)
                callbackConnected = false
                connectedAt = null
                Log.i(TAG, "prebind_disconnected_waiting_for_system")
            }
            override fun onBindingDied(name: ComponentName?) {
                checkMain()
                if (connection !== this) return
                Log.i(TAG, "prebind_binding_died")
                val since = connectedAt
                // A successful but short-lived reconnect must not create an endless retry loop.
                if (recoveryUsed && since != null && now() - since >= RECOVERY_STABLE_MILLIS) recoveryUsed = false
                if (enabled && !closed && !recoveryUsed) {
                    recoveryUsed = true
                    recoveryPending = true
                }
                finish()
            }
            override fun onNullBinding(name: ComponentName?) { checkMain(); if (connection === this) { Log.i(TAG, "prebind_refused null_binding"); finish() } }
        }
        if (!reservation.acquire(conn)) {
            Log.i(TAG, "prebind_skipped reservation_busy")
            if (enabled && !closed) {
                lateinit var waiter: Runnable
                waiter = Runnable {
                    if (reservationWait === waiter) {
                        reservationWait = null
                        if (enabled && !closed) {
                            attempted = false
                            start()
                        }
                    }
                }
                reservationWait = waiter
                reservation.awaitRelease(waiter)
            }
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
        recoveryPending = false
        cancelReservationWait()
        cancelRecovery()
        finish()
    }

    private fun finish() {
        val conn = connection ?: return
        reservation.invalidate(conn)
        callbackConnected = false
        connectedAt = null
        clearTimeout()
        if (binding) {
            finishPending = true
            return
        }
        connection = null
        finishPending = false
        finishing = true
        val released = try { unbind(conn); true } catch (_: Exception) { false }
        if (released) reservation.release(conn) else reservation.markUncertain(conn)
        finishing = false
        // On uncertain cleanup retain the process reservation and never retry.
        Log.i(TAG, if (released) "prebind_unbound" else "prebind_unbind_uncertain; further prebinds blocked until process exit")
        val startRequested = enablePending
        enablePending = false
        if (released && startRequested && enabled && !closed) start()
        else if (released && recoveryPending && enabled && !closed) {
            recoveryPending = false
            lateinit var task: Runnable
            task = Runnable {
                if (recovery !== task) return@Runnable
                recovery = null
                if (!closed && enabled && connection == null) {
                    attempted = false
                    start()
                }
            }
            recovery = task
            schedule(task)
        } else recoveryPending = false
    }

    private fun cancelRecovery() {
        recovery?.let(cancel)
        recovery = null
    }

    private fun cancelReservationWait() {
        reservationWait?.let(reservation::cancelWait)
        reservationWait = null
    }

    private fun clearTimeout() {
        timeout?.let(cancel)
        timeout = null
    }

    companion object {
        private const val TAG = "DicioPrebind"
        private const val RECOVERY_STABLE_MILLIS = 60_000L
        private val COMPONENT = ComponentName("com.google.android.katniss", "com.google.android.apps.tvsearch.voice.recognition.KatnissRecognitionService")
    }
}
