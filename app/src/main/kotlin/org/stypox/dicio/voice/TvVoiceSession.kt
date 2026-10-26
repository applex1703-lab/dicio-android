package org.stypox.dicio.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
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
import org.stypox.dicio.io.input.SttState

@EntryPoint
@InstallIn(SingletonComponent::class)
interface VoiceDependencies {
    fun evaluator(): SkillEvaluator
    fun input(): SttInputDeviceWrapper
}

/** A system voice window, not an Activity and not a SYSTEM_ALERT_WINDOW overlay. */
@RequiresApi(23)
class TvVoiceSession(private val app: Context) : VoiceInteractionSession(app) {
    private val dependencies = EntryPointAccessors.fromApplication(app.applicationContext, VoiceDependencies::class.java)
    private val evaluator = dependencies.evaluator()
    private val input = dependencies.input()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var updates: Job? = null
    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var answer: TextView
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
            line(14, Color.LTGRAY).text = "Назад — закрыть · Новый запрос — кнопка Ассистента"
        }
    }

    private fun LinearLayout.line(size: Int, colour: Int): TextView = TextView(app).also {
        it.textSize = size.toFloat()
        it.setTextColor(colour)
        it.setPadding(0, dp(4), 0, dp(4))
        addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
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
        evaluator.resetVoicePresentation()
        input.tryLoad(evaluator::processInputEvent)
        updates?.cancel()
        updates = scope.launch {
            evaluator.voicePresentation.combine(input.uiState) { presentation, stt -> presentation to stt }
                .collect { (presentation, stt) ->
                    // Started resets this ephemeral state before the new recording results.
                    status.text = if (presentation.request.isEmpty() && presentation.answer.isEmpty() &&
                        stt == SttState.Listening) "Распознаватель готов…" else presentation.status
                    transcript.text = presentation.request
                    answer.text = presentation.answer
                    if (presentation.dismiss) hide()
                }
        }
    }

    override fun onHide() {
        visible = false
        updates?.cancel()
        updates = null
        input.reinitializeToReleaseResources()
        evaluator.cancelPendingInput()
        super.onHide()
    }

    override fun onDestroy() {
        updates?.cancel()
        scope.cancel()
        if (visible) {
            input.reinitializeToReleaseResources()
            evaluator.cancelPendingInput()
        }
        super.onDestroy()
    }
}
