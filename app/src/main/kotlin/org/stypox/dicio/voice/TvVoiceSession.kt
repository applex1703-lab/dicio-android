package org.stypox.dicio.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
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

    private fun dp(value: Int) = (app.resources.displayMetrics.density * value).toInt()

    override fun onCreateContentView(): View {
        // The window itself is transparent; only the compact top panel and dim layer are drawn.
        window?.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.35f }
        }
        return LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(dp(48), dp(24), dp(48), dp(24))
            setBackgroundColor(Color.rgb(22, 24, 29))
            status = line(18, Color.rgb(138, 180, 248))
            transcript = line(28, Color.WHITE)
            answer = line(22, Color.rgb(230, 232, 237)).apply { maxLines = 8 }
            choiceScroll = ScrollView(app).apply {
                visibility = View.GONE
                isFillViewport = false
                choiceButtons = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
                addView(choiceButtons)
            }
            addView(choiceScroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(240)))
            line(14, Color.LTGRAY).text = "Назад — закрыть · Новый запрос — кнопка Ассистента"
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
        it.setTextColor(colour)
        it.setPadding(0, dp(4), 0, dp(4))
        addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        Log.i("DicioVoiceRoute", "onShow visible=$visible flags=$showFlags")
        super.onShow(args, showFlags)
        window?.window?.apply {
            setGravity(Gravity.TOP)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        }
        if (visible) return // duplicate show must not create a second recording/request
        visible = true
        status.text = "Подготовка микрофона…"
        transcript.text = ""
        answer.text = ""
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status.text = "Нет разрешения микрофона"
            answer.text = "Откройте Dicio из списка приложений и разрешите запись звука."
            return
        }
        if (!coordinator.startTv(this) {
                updates?.cancel()
                updates = null
                renderChoice(null)
                if (visible) status.text = "Голосовой запрос прерван"
            }) {
            status.text = "Распознавание речи недоступно"
            answer.text = "Откройте настройки Dicio и проверьте голосовой ввод."
            return
        }
        updates?.cancel()
        updates = scope.launch {
            evaluator.voicePresentation.combine(input.uiState) { presentation, stt -> presentation to stt }
                .collect { (presentation, stt) ->
                    // Started resets this ephemeral state before the new recording results.
                    status.text = if (presentation.request.isEmpty() && presentation.answer.isEmpty() &&
                        stt == SttState.Listening) "Распознаватель готов…" else presentation.status
                    transcript.text = presentation.request
                    answer.text = presentation.answer
                    renderChoice(presentation.choice)
                    if (presentation.dismiss) hide()
                }
        }
    }

    // The coordinator ignores release from a session that no longer owns the input.
    private fun releaseVoiceResources() {
        visible = false
        updates?.cancel()
        updates = null
        if (::choiceButtons.isInitialized) renderChoice(null)
        teardown.close()
    }

    override fun onHide() {
        Log.i("DicioVoiceRoute", "onHide visible=$visible")
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
            Log.i("DicioVoiceRoute", "keyDown code=$keyCode visible=$visible")
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ASSIST || keyCode == KeyEvent.KEYCODE_VOICE_ASSIST) {
            Log.i("DicioVoiceRoute", "keyUp code=$keyCode visible=$visible")
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onDestroy() {
        updates?.cancel()
        scope.cancel()
        releaseVoiceResources()
        super.onDestroy()
    }

}
