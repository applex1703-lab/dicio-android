package org.stypox.dicio.voice

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Main-thread-only process-wide reservation. An uncertain connection is deliberately retained. */
internal class BindProbeReservation {
    private var owner: Activity? = null
    private var connection: ServiceConnection? = null
    private var uncertain = false

    fun reserve(activity: Activity, conn: ServiceConnection): Boolean {
        if (connection != null) return false
        owner = activity
        connection = conn
        return true
    }

    fun release(conn: ServiceConnection) {
        check(connection === conn)
        connection = null
        owner = null
        uncertain = false
    }

    fun isReserved() = connection != null
    fun isUncertain() = uncertain
    fun markUncertain(conn: ServiceConnection) {
        check(connection === conn)
        uncertain = true
    }
}

/** Dicio binds explicitly but does not create a recognizer or start recognition. */
class BindDiagnosticActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var button: Button
    private var connection: ServiceConnection? = null
    private var deadline: Runnable? = null
    private var sequence = 0
    private var bindInFlight = false
    private var deferredFinish: String? = null
    internal var reservation = processReservation
    internal var bindForProbe: (Intent, ServiceConnection, Int) -> Boolean = { intent, conn, flags ->
        bindService(intent, conn, flags)
    }
    internal var unbindForProbe: (ServiceConnection) -> Unit = { unbindService(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { text = "Dicio подключается к сервису Google и не запускает распознавание. Сторонний сервис может выполнять собственные действия. Запрошена передача доступных foreground-возможностей для диагностики. Завершение планируется через 20 секунд на главном потоке; это не жёсткий предел, при задержке оно может произойти позже."; textSize = 20f }
        button = Button(this).apply { text = "Начать проверку"; setOnClickListener { startProbe() } }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
            addView(status)
            addView(button)
        })
    }

    internal fun startProbe() {
        if (connection != null) return
        val id = ++sequence
        val startedAt = SystemClock.elapsedRealtime()
        val flags = Context.BIND_AUTO_CREATE or if (Build.VERSION.SDK_INT >= 29) Context.BIND_INCLUDE_CAPABILITIES else 0
        val conn = object : ServiceConnection {
            private fun dispatch(action: () -> Unit) = handler.post {
                if (connection === this) action()
            }
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) { dispatch {
                if (binder == null) finishProbe("null_binder") else {
                    Log.i(TAG, "bind_connected id=$id elapsed_ms=${SystemClock.elapsedRealtime() - startedAt} flags=$flags component=$COMPONENT")
                    status.text = "Соединение активно. Dicio не запускает распознавание; поведение сервиса Google не контролируется."
                }
            } }
            override fun onServiceDisconnected(name: ComponentName?) { dispatch { finishProbe("disconnected") } }
            override fun onBindingDied(name: ComponentName?) { dispatch { finishProbe("binding_died") } }
            override fun onNullBinding(name: ComponentName?) { dispatch { finishProbe("null_binding") } }
        }
        if (!reservation.reserve(this, conn)) {
            val uncertain = reservation.isUncertain()
            status.text = if (uncertain) "Освобождение предыдущего соединения не подтверждено. Новые проверки заблокированы до завершения процесса приложения."
                else "Другая проверка активна. Повторите после её завершения."
            // A live reservation may be released while this screen remains visible.
            button.isEnabled = !uncertain
            return
        }
        connection = conn
        button.isEnabled = false
        status.text = "Подключение к Google…"
        Log.i(TAG, "bind_requested id=$id flags=$flags component=$COMPONENT")
        val end = Runnable { if (connection === conn) finishProbe("timeout") }
        deadline = end
        handler.postDelayed(end, 20_000)
        bindInFlight = true
        val result = try {
            if (bindForProbe(Intent("android.speech.RecognitionService").setComponent(COMPONENT), conn, flags)) null else "rejected"
        } catch (_: Exception) {
            "bind_exception"
        } finally {
            bindInFlight = false
        }
        val finishReason = deferredFinish ?: result
        deferredFinish = null
        if (finishReason != null) finishProbe(finishReason)
    }

    internal fun finishProbe(reason: String) {
        val conn = connection ?: return
        if (bindInFlight) {
            if (deferredFinish == null) deferredFinish = reason
            return
        }
        connection = null
        deadline?.let(handler::removeCallbacks)
        deadline = null
        val cleaned = try { unbindForProbe(conn); true } catch (_: Exception) { false }
        if (cleaned) reservation.release(conn) else reservation.markUncertain(conn)
        Log.i(TAG, "diagnostic_end id=$sequence reason=$reason unbound=$cleaned")
        status.text = if (cleaned) "Проверка завершена: $reason. Dicio не запускал распознавание."
            else "Освобождение соединения не подтверждено. Новые проверки заблокированы до завершения процесса приложения."
        button.isEnabled = cleaned
    }

    override fun onStop() {
        finishProbe("activity_stopped")
        super.onStop()
    }

    companion object {
        private const val TAG = "DicioBindProbe"
        private val processReservation = BindProbeReservation()
        private val COMPONENT = ComponentName("com.google.android.katniss", "com.google.android.apps.tvsearch.voice.recognition.KatnissRecognitionService")
    }
}
