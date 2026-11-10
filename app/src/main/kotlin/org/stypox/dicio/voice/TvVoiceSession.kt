package org.stypox.dicio.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.service.voice.VoiceInteractionSession
import android.text.TextUtils
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.annotation.RequiresApi
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.stypox.dicio.di.SttInputDeviceWrapper
import org.stypox.dicio.eval.SkillEvaluator
import org.stypox.dicio.eval.ChoicePresentation
import org.stypox.dicio.io.input.SttState

@EntryPoint
@InstallIn(SingletonComponent::class)
interface VoiceDependencies {
    fun evaluator(): SkillEvaluator
    fun input(): SttInputDeviceWrapper
    fun voiceInputCoordinator(): VoiceInputCoordinator
}

internal class VoiceSessionTeardown(private val coordinator: VoiceInputCoordinator, private val owner: Any) {
    fun close() = coordinator.release(owner)
}

internal fun voiceStatus(presentation: org.stypox.dicio.eval.VoicePresentation, stt: SttState?): String = when {
    presentation.answer.isNotEmpty() || presentation.status != "Слушаю…" && presentation.request.isNotEmpty() -> presentation.status
    stt == SttState.WaitingForResult && presentation.status == "Слушаю…" -> "Распознаю…"
    stt == SttState.Listening && presentation.request.isEmpty() && presentation.answer.isEmpty() -> "Распознаватель готов…"
    else -> presentation.status
}

/** Keeps a no-op start on an existing input from replacing its presentation subscription. */
internal class TvVoiceRequestAction(
    private val isVisible: () -> Boolean,
    private val start: (Boolean, () -> Unit) -> Boolean,
    private val revoked: () -> Unit,
    private val ensureUpdates: () -> Unit,
    private val failed: () -> Unit,
    private val hasPermission: () -> Boolean = { true },
    private val permissionDenied: () -> Unit = {},
) {
    fun request(initial: Boolean = false) {
        if (!isVisible()) return
        if (!hasPermission()) { permissionDenied(); return }
        val started = start(initial, revoked)
        if (!isVisible()) return
        if (started) ensureUpdates() else failed()
    }
}

/** A system voice window, not an Activity and not a SYSTEM_ALERT_WINDOW overlay. */
@RequiresApi(23)
class TvVoiceSession(private val app: Context) : VoiceInteractionSession(app) {
    private val dependencies = EntryPointAccessors.fromApplication(app.applicationContext, VoiceDependencies::class.java)
    private val evaluator = dependencies.evaluator()
    private val input = dependencies.input()
    private val coordinator = dependencies.voiceInputCoordinator()
    private val teardown = VoiceSessionTeardown(coordinator, this)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var updates: Job? = null
    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var answer: TextView
    private lateinit var choiceScroll: ScrollView
    private lateinit var choiceButtons: LinearLayout
    private var renderedChoice: ChoicePresentation? = null
    private var visible = false
    private val assistGesture = ExplicitAssistGesture()
    private var focusObserver: ViewTreeObserver? = null
    private val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
        if (!focused) assistGesture.discard()
    }
    private val requestAction = TvVoiceRequestAction(
        isVisible = { visible },
        start = { initial, revoked ->
            if (initial) coordinator.startTv(this, revoked)
            else coordinator.startTvIfAvailable(this, revoked)
        },
        revoked = {
            updates?.cancel()
            updates = null
            renderChoice(null)
            if (visible) answer.text = "Голосовой запрос прерван."
        },
        ensureUpdates = { ensureVoiceUpdates() },
        failed = {
            answer.text = "Распознавание речи недоступно. Проверьте настройки голосового ввода."
        },
        hasPermission = {
            ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        },
        permissionDenied = {
            status.text = "Нет разрешения микрофона"
            answer.text = "Разрешите доступ к микрофону в настройках приложения."
        },
    )

    private fun dp(value: Int) = (app.resources.displayMetrics.density * value).toInt()

    override fun onCreateContentView(): View {
        // Full-screen gradient keeps the TV visible but provides contrast behind the text.
        window?.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.35f }
        }
        return LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setPadding(dp(56), dp(72), dp(56), dp(32))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
                Color.argb(255, 0, 0, 0),
                Color.argb(242, 0, 0, 0),
                Color.argb(195, 0, 0, 0),
            ))
            status = line(22, Color.rgb(230, 232, 237))
            transcript = line(26, Color.rgb(242, 244, 248))
            answer = line(20, Color.rgb(230, 232, 237)).apply {
                maxLines = 8
                setPadding(0, dp(12), 0, dp(4))
                setLineSpacing(dp(3).toFloat(), 1f)
            }
            listOf(status, transcript, answer).forEach { view ->
                view.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                view.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        transcript.visibility = if (transcript.text.isNullOrEmpty()) View.GONE else View.VISIBLE
                        answer.visibility = if (answer.text.isNullOrEmpty()) View.GONE else View.VISIBLE
                        status.visibility = if (transcript.text.isNullOrEmpty() && answer.text.isNullOrEmpty() &&
                            !status.text.isNullOrEmpty()) View.VISIBLE else View.GONE
                    }
                    override fun afterTextChanged(s: Editable?) = Unit
                })
            }
            transcript.visibility = View.GONE
            answer.visibility = View.GONE
            status.visibility = View.GONE
            choiceScroll = object : ScrollView(app) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    val limit = dp(240)
                    val size = View.MeasureSpec.getSize(heightMeasureSpec)
                    val mode = View.MeasureSpec.getMode(heightMeasureSpec)
                    val height = if (mode == View.MeasureSpec.UNSPECIFIED) limit else minOf(size, limit)
                    super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST))
                }
            }.apply {
                visibility = View.GONE
                isFillViewport = false
                choiceButtons = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
                addView(choiceButtons)
            }
            addView(choiceScroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun renderChoice(choice: ChoicePresentation?) {
        if (renderedChoice == choice) return
        renderedChoice = choice
        transcript.maxLines = if (choice == null) Int.MAX_VALUE else 2
        transcript.ellipsize = if (choice == null) null else TextUtils.TruncateAt.END
        answer.maxLines = if (choice == null) 8 else 2
        choiceButtons.removeAllViews()
        choiceScroll.scrollTo(0, 0)
        choiceScroll.visibility = if (choice == null || choice.options.isEmpty()) View.GONE else View.VISIBLE
        choice?.options?.forEach { option ->
            val button = Button(app).apply {
                text = option.label
                isAllCaps = false
                isFocusable = true
                setTextColor(Color.WHITE)
                fun updateBackground(focused: Boolean) {
                    background = GradientDrawable().apply {
                        setColor(if (focused) Color.rgb(45, 80, 135) else Color.rgb(48, 51, 58))
                        cornerRadius = dp(8).toFloat()
                        setStroke(dp(if (focused) 3 else 1), if (focused) Color.WHITE else Color.LTGRAY)
                    }
                }
                updateBackground(false)
                setOnFocusChangeListener { _, focused -> updateBackground(focused) }
                setOnClickListener { coordinator.selectChoice(this@TvVoiceSession, choice.id, option.id) }
            }
            choiceButtons.addView(button, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            })
        }
        if (choice != null && choice.options.isNotEmpty()) choiceButtons.getChildAt(0).requestFocus()
    }

    private fun LinearLayout.line(size: Int, colour: Int): TextView = TextView(app).also {
        it.textSize = size.toFloat()
        it.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        it.gravity = Gravity.CENTER_HORIZONTAL
        it.setTextColor(colour)
        it.setPadding(0, dp(4), 0, dp(4))
        val textWidth = minOf((app.resources.displayMetrics.widthPixels * 0.7f).toInt(), dp(720))
        addView(it, LinearLayout.LayoutParams(textWidth, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        Log.i("DicioVoiceRoute", "onShow session=${System.identityHashCode(this)} uptime=${SystemClock.uptimeMillis()} visible=$visible flags=$showFlags argsPresent=${args != null}")
        super.onShow(args, showFlags)
        val observer = window?.window?.decorView?.viewTreeObserver
        if (observer != null && observer.isAlive && focusObserver !== observer) {
            focusObserver?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(focusListener)
            observer.addOnWindowFocusChangeListener(focusListener)
            focusObserver = observer
        }
        window?.window?.apply {
            setGravity(Gravity.TOP)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        }
        if (visible) return // duplicate show must not create a second recording/request
        visible = true
        assistGesture.shown(SystemClock.uptimeMillis())
        status.text = "Подготовка микрофона…"
        transcript.text = ""
        answer.text = ""
        requestVoiceInput(initial = true)
    }

    private fun requestVoiceInput(initial: Boolean = false) {
        if (!visible) return
        // A displaced session must not preempt another owner's active input.
        requestAction.request(initial)
    }

    private fun ensureVoiceUpdates() {
        if (updates != null) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            evaluator.voicePresentation.combine(input.uiState) { presentation, stt -> presentation to stt }
                .collect { (presentation, stt) ->
                    // Started resets this ephemeral state before the new recording results.
                    status.text = voiceStatus(presentation, stt)
                    transcript.text = presentation.request
                    answer.text = presentation.answer
                    renderChoice(presentation.choice)
                    if (presentation.dismiss) hide()
                }
        }
        updates = job
        job.start()
    }

    // The coordinator ignores release from a session that no longer owns the input.
    private fun releaseVoiceResources() {
        visible = false
        assistGesture.hidden()
        updates?.cancel()
        updates = null
        if (::choiceButtons.isInitialized) renderChoice(null)
        teardown.close()
    }

    override fun onHide() {
        Log.i("DicioVoiceRoute", "onHide session=${System.identityHashCode(this)} uptime=${SystemClock.uptimeMillis()} visible=$visible")
        releaseVoiceResources()
        super.onHide()
    }

    override fun onBackPressed() {
        Log.i("DicioVoiceRoute", "onBackPressed visible=$visible")
        // Closing a completed clarification is not the same as cancelling an active request.
        teardown.close()
        super.onBackPressed()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ASSIST || keyCode == KeyEvent.KEYCODE_VOICE_ASSIST) {
            Log.i("DicioVoiceRoute", "keyDown session=${System.identityHashCode(this)} uptime=${SystemClock.uptimeMillis()} code=$keyCode visible=$visible device=${event?.deviceId} downTime=${event?.downTime} eventTime=${event?.eventTime} repeat=${event?.repeatCount}")
            if (event != null) {
                if (!isRecentAssistEvent(event.downTime, event.eventTime, SystemClock.uptimeMillis())) {
                    assistGesture.discard(keyCode, event.deviceId, event.downTime)
                } else if (assistGesture.down(keyCode, event.deviceId, event.downTime,
                        event.repeatCount, event.isCanceled)) return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ASSIST || keyCode == KeyEvent.KEYCODE_VOICE_ASSIST) {
            Log.i("DicioVoiceRoute", "keyUp session=${System.identityHashCode(this)} uptime=${SystemClock.uptimeMillis()} code=$keyCode visible=$visible device=${event?.deviceId} downTime=${event?.downTime} eventTime=${event?.eventTime} cancelled=${event?.isCanceled}")
            if (event != null && (!isRecentAssistEvent(event.downTime, event.eventTime, SystemClock.uptimeMillis()) ||
                    window?.window?.decorView?.hasWindowFocus() != true)) {
                assistGesture.discard(keyCode, event.deviceId, event.downTime)
                return super.onKeyUp(keyCode, event)
            }
            if (event != null && assistGesture.up(keyCode, event.deviceId, event.downTime, event.isCanceled)) {
                if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    val started = coordinator.restartTvExplicit(this) {
                        updates?.cancel()
                        updates = null
                        renderChoice(null)
                        if (visible) answer.text = "Голосовой запрос прерван."
                    }
                    if (started && visible) ensureVoiceUpdates()
                    else if (visible) answer.text = "Распознавание речи недоступно."
                } else if (visible) {
                    status.text = "Нет разрешения микрофона"
                    answer.text = "Разрешите доступ к микрофону в настройках приложения."
                }
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onDestroy() {
        focusObserver?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(focusListener)
        focusObserver = null
        updates?.cancel()
        scope.cancel()
        releaseVoiceResources()
        super.onDestroy()
    }

}
