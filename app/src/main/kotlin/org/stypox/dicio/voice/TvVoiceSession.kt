package org.stypox.dicio.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.os.Build
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
import android.widget.FrameLayout
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
import org.stypox.dicio.eval.VideoRow
import org.stypox.dicio.eval.VideoSource
import coil.ImageLoader
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

internal fun showVoiceStatus(status: CharSequence?, answer: CharSequence?): Boolean =
    !status.isNullOrEmpty() && answer.isNullOrEmpty()

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
    private val voiceTypeface by lazy { Typeface.createFromAsset(app.assets, "fonts/sofia_sans_condensed.ttf") }
    private var updates: Job? = null
    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var answer: TextView
    private lateinit var choiceScroll: ScrollView
    private lateinit var choiceButtons: LinearLayout
    private val videoRails = mutableMapOf<VideoSource, VideoRail>()
    private val videoMessages = mutableMapOf<VideoSource, TextView>()
    private var videoImages: ImageLoader? = null
    private var videoFocusClaimed = false
    private lateinit var contentRoot: LinearLayout
    private lateinit var normalBackground: Drawable
    private lateinit var videoHeader: LinearLayout
    private lateinit var videoQuery: TextView
    private lateinit var voiceDivider: View
    private var renderedVideos: List<VideoRow> = emptyList()
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
            renderVideos(emptyList())
            videoMessages.values.forEach { it.text = ""; it.visibility = View.GONE }
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
        videoRails.values.forEach { it.release() }
        videoRails.clear()
        videoMessages.clear()
        videoImages?.shutdown()
        videoImages = null
        renderedVideos = emptyList()
        videoFocusClaimed = false
        // Full-screen gradient keeps the TV visible but provides contrast behind the text.
        window?.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.35f }
        }
        return LinearLayout(app).apply {
            contentRoot = this
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setPadding(dp(56), dp(72), dp(56), dp(32))
            background = LayerDrawable(arrayOf(
                GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
                    Color.argb(252, 11, 17, 31), Color.argb(245, 12, 17, 27),
                    Color.argb(230, 13, 19, 30))),
                GradientDrawable().apply {
                    gradientType = GradientDrawable.RADIAL_GRADIENT
                    setGradientCenter(0.5f, 0f)
                    gradientRadius = app.resources.displayMetrics.widthPixels * 0.55f
                    colors = intArrayOf(Color.argb(120, 69, 91, 148), Color.TRANSPARENT)
                },
            ))
            normalBackground = background
            transcript = line(26, Color.rgb(196, 211, 231))
            if (Build.VERSION.SDK_INT >= 28) transcript.typeface = Typeface.create(voiceTypeface, 500, false)
            val divider = View(app).apply {
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(
                    Color.TRANSPARENT, Color.rgb(148, 183, 255), Color.rgb(212, 200, 255),
                    Color.rgb(148, 183, 255), Color.TRANSPARENT))
                visibility = View.GONE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            voiceDivider = divider
            addView(divider, LinearLayout.LayoutParams(dp(48), maxOf(1, dp(2))).apply {
                topMargin = dp(12)
            })
            fun updateDividerWidth() {
                val layout = transcript.layout ?: return
                val width = (0 until layout.lineCount).maxOfOrNull { layout.getLineWidth(it) } ?: return
                val params = divider.layoutParams
                val desired = width.toInt().coerceIn(1, transcript.width.coerceAtLeast(1))
                if (params.width != desired) { params.width = desired; divider.layoutParams = params }
            }
            transcript.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateDividerWidth() }
            videoHeader = LinearLayout(app).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(44)
                setPadding(dp(13), dp(6), dp(18), dp(6))
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(
                    0x995a538f.toInt(), 0x77443a77, 0x002f396d)).apply { cornerRadius = dp(28).toFloat() }
                visibility = View.GONE
                addView(object : View(app) {
                    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.rgb(237, 243, 255)
                        style = Paint.Style.STROKE
                        strokeCap = Paint.Cap.ROUND
                    }
                    override fun onDraw(canvas: Canvas) {
                        super.onDraw(canvas)
                        val unit = minOf(width, height).toFloat()
                        paint.strokeWidth = unit * .065f
                        canvas.drawCircle(width * .43f, height * .41f, unit * .25f, paint)
                        canvas.drawLine(width * .61f, height * .60f, width * .79f, height * .78f, paint)
                    }
                }.apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(25), dp(25)))
                addView(View(app).apply { setBackgroundColor(0x668da4ce) }, LinearLayout.LayoutParams(maxOf(1, dp(1)), dp(24)).apply {
                    marginStart = dp(14); marginEnd = dp(14)
                })
                addView(TextView(app).apply {
                    text = "Результаты поиска"
                    textSize = 16f
                    setTextColor(0xffc3ccdf.toInt())
                    typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                    includeFontPadding = false
                })
                addView(TextView(app).apply {
                    text = "·"; textSize = 17f; setTextColor(0xffa9b9d0.toInt())
                    setPadding(dp(9), 0, dp(9), 0)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                })
                videoQuery = TextView(app).apply {
                    textSize = 18f
                    setTextColor(Color.rgb(243, 245, 250))
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    includeFontPadding = false
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                }
                addView(videoQuery, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            addView(videoHeader, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(23)
            })
            fun TextView.responseStyle() {
                val maxTextWidth = minOf((app.resources.displayMetrics.widthPixels * 0.7f).toInt(), dp(720))
                maxWidth = maxTextWidth
                layoutParams = (layoutParams as LinearLayout.LayoutParams).apply {
                    width = LinearLayout.LayoutParams.WRAP_CONTENT
                    topMargin = dp(16)
                }
                setPadding(dp(20), dp(12), dp(20), dp(12))
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(
                    Color.argb(64, 84, 123, 177), Color.argb(41, 112, 90, 158))).apply {
                    cornerRadius = dp(14).toFloat()
                    setStroke(maxOf(1, dp(1)), Color.argb(43, 163, 190, 230))
                }
            }
            status = line(22, Color.rgb(184, 199, 220)).apply { responseStyle() }
            answer = line(22, Color.rgb(236, 241, 249)).apply {
                maxLines = 8
                responseStyle()
                setLineSpacing(dp(3).toFloat(), 1f)
            }
            listOf(status, transcript, answer).forEach { view ->
                view.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                view.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        updateVideoHeader()
                        answer.visibility = if (answer.text.isNullOrEmpty()) View.GONE else View.VISIBLE
                        status.visibility = if (showVoiceStatus(status.text, answer.text)) View.VISIBLE else View.GONE
                        divider.visibility = if (renderedVideos.isEmpty() && !transcript.text.isNullOrEmpty() &&
                            (!answer.text.isNullOrEmpty() || !status.text.isNullOrEmpty())) View.VISIBLE else View.GONE
                    }
                    override fun afterTextChanged(s: Editable?) = Unit
                })
            }
            transcript.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    transcript.post { updateDividerWidth() }
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
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
            val images = videoImageLoader(app).also { videoImages = it }
            val rails = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
            listOf(VideoSource.RUTUBE, VideoSource.YOUTUBE).forEach { source ->
                val rail = VideoRail(app,
                    onVideo = { rowId, videoId -> coordinator.selectVideo(this@TvVoiceSession, rowId, videoId) },
                    onNearEnd = { rowId -> coordinator.loadNextVideoPage(this@TvVoiceSession, rowId) },
                    onRetry = { rowId -> coordinator.loadNextVideoPage(this@TvVoiceSession, rowId, retry = true) },
                    sharedImageLoader = images,
                    claimInitialFocus = { view ->
                        if (videoFocusClaimed) false else view.requestFocus().also { if (it) videoFocusClaimed = true }
                    })
                videoRails[source] = rail
                rails.addView(rail, LinearLayout.LayoutParams(-1, -2))
                videoMessages[source] = TextView(app).apply {
                    setTextColor(Color.LTGRAY)
                    textSize = 16f
                    visibility = View.GONE
                }.also { rails.addView(it, LinearLayout.LayoutParams(-1, -2)) }
            }
            val railsScroll = ScrollView(app).apply {
                isVerticalScrollBarEnabled = false
                addView(rails)
            }
            val scrollHint = TextView(app).apply {
                text = "⌄"
                textSize = 30f
                gravity = Gravity.CENTER
                setTextColor(0xffd4e7ff.toInt())
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                isClickable = false
                visibility = View.GONE
            }
            fun updateScrollHint() {
                scrollHint.visibility = if (renderedVideos.isNotEmpty() && railsScroll.canScrollVertically(1)) View.VISIBLE else View.GONE
            }
            railsScroll.setOnScrollChangeListener { _, _, _, _, _ -> updateScrollHint() }
            railsScroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateScrollHint() }
            rails.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateScrollHint() }
            addView(FrameLayout(app).apply {
                addView(railsScroll, FrameLayout.LayoutParams(-1, -1))
                addView(scrollHint, FrameLayout.LayoutParams(dp(52), dp(31), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
            }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
    }

    private fun renderVideos(rows: List<VideoRow>) {
        if (videoRails.isEmpty() || renderedVideos == rows) return
        val wasVideo = renderedVideos.isNotEmpty()
        val isVideo = rows.isNotEmpty()
        renderedVideos = rows
        if (rows.isEmpty()) videoFocusClaimed = false
        if (wasVideo != isVideo) {
            contentRoot.setPadding(dp(if (isVideo) 48 else 56), dp(if (isVideo) 27 else 72), dp(if (isVideo) 48 else 56), dp(if (isVideo) 0 else 32))
            contentRoot.background = if (isVideo) LayerDrawable(arrayOf(
                GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xff132d59.toInt(), 0xff071934.toInt(), 0xff07152a.toInt())),
                GradientDrawable().apply {
                    gradientType = GradientDrawable.RADIAL_GRADIENT
                    setGradientCenter(.82f, .03f)
                    gradientRadius = app.resources.displayMetrics.widthPixels * .65f
                    colors = intArrayOf(0x8b594078.toInt(), 0x292e468b, Color.TRANSPARENT)
                },
            )) else normalBackground
        }
        updateVideoHeader()
        val compact = rows.isNotEmpty() || renderedChoice != null
        transcript.maxLines = if (compact) 2 else Int.MAX_VALUE
        transcript.ellipsize = if (compact) TextUtils.TruncateAt.END else null
        videoRails.forEach { (source, rail) -> rail.render(rows.firstOrNull { it.source == source }) }
    }

    private fun updateVideoHeader() {
        val hasQuery = !transcript.text.isNullOrEmpty()
        val video = renderedVideos.isNotEmpty()
        transcript.visibility = if (hasQuery && !video) View.VISIBLE else View.GONE
        videoHeader.visibility = if (hasQuery && video) View.VISIBLE else View.GONE
        videoQuery.text = transcript.text
        voiceDivider.visibility = if (!video && hasQuery && (!answer.text.isNullOrEmpty() || !status.text.isNullOrEmpty())) View.VISIBLE else View.GONE
    }

    private fun renderChoice(choice: ChoicePresentation?) {
        if (renderedChoice == choice) return
        renderedChoice = choice
        val compact = choice != null || renderedVideos.isNotEmpty()
        transcript.maxLines = if (compact) 2 else Int.MAX_VALUE
        transcript.ellipsize = if (compact) TextUtils.TruncateAt.END else null
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
        it.typeface = voiceTypeface
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
                    renderVideos(presentation.videoRows)
                    videoMessages.forEach { (source, view) ->
                        val message = presentation.videoSourceMessages[source]
                        view.text = if (message.isNullOrBlank()) "" else "${source.label}: $message"
                        view.visibility = if (message.isNullOrBlank()) View.GONE else View.VISIBLE
                    }
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
        renderVideos(emptyList())
        videoMessages.values.forEach { it.text = ""; it.visibility = View.GONE }
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
                        renderVideos(emptyList())
                        videoMessages.values.forEach { it.text = ""; it.visibility = View.GONE }
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
        videoRails.values.forEach { it.release() }
        videoRails.clear()
        videoMessages.clear()
        videoImages?.shutdown()
        videoImages = null
        super.onDestroy()
    }

}
