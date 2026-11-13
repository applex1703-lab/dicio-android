package org.stypox.dicio.voice

import android.content.Context
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.view.Gravity
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.memory.MemoryCache
import coil.request.Disposable
import coil.request.ImageRequest
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.stypox.dicio.R
import org.stypox.dicio.eval.VideoCard
import org.stypox.dicio.eval.VideoRow
import org.stypox.dicio.eval.VideoSource

internal fun allowedVideoThumbnail(raw: String, source: VideoSource): String? {
    val uri = try { java.net.URI(raw) } catch (_: java.net.URISyntaxException) { return null }
    val authority = uri.rawAuthority ?: return null
    if ('@' in authority) return null
    val url = raw.toHttpUrlOrNull() ?: return null
    val hosts = when (source) {
        VideoSource.RUTUBE -> setOf("pic.rtbcdn.ru", "pic.rutube.ru")
        VideoSource.YOUTUBE -> setOf("i.ytimg.com")
    }
    return url.takeIf { it.isHttps && it.port == 443 && it.host in hosts }?.toString()
}

internal fun videoImageLoader(context: Context): ImageLoader = ImageLoader.Builder(context.applicationContext)
    .memoryCache { MemoryCache.Builder(context.applicationContext).maxSizeBytes(16 * 1024 * 1024).build() }
    .diskCache(null)
    .bitmapFactoryMaxParallelism(2)
    .okHttpClient {
        OkHttpClient.Builder().dispatcher(Dispatcher().apply {
            maxRequests = 3
            maxRequestsPerHost = 3
        }).followRedirects(false).followSslRedirects(false).build()
    }.build()

/** Horizontal navigation stays in this row; vertical navigation remains Android's default. */
internal class VideoRecyclerView(context: Context) : RecyclerView(context) {
    private var pendingPosition: Int? = null
    private var pendingDirection = 0

    fun cancelPendingNavigation() {
        pendingPosition = null
        pendingDirection = 0
        stopScroll()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_UP || event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) {
            cancelPendingNavigation()
        }
        if (event.action == KeyEvent.ACTION_DOWN && !event.isCanceled &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            val focused = findFocus()
            val holder = focused?.let { findContainingViewHolder(it) }
            val position = holder?.bindingAdapterPosition ?: NO_POSITION
            if (position != NO_POSITION) {
                val direction = if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                if (pendingPosition != null) {
                    if (direction == pendingDirection) return true
                    cancelPendingNavigation()
                }
                val target = position + direction
                if (target !in 0 until (adapter?.itemCount ?: 0)) return true
                val next = findViewHolderForAdapterPosition(target)?.itemView
                if (next != null && next.requestFocus()) return true
                pendingPosition = target
                pendingDirection = direction
                smoothScrollToPosition(target)
                postOnAnimation { completePendingNavigation() }
                return true
            }
            // During asynchronous list updates a focused holder can temporarily lose its
            // adapter position. Never hand a horizontal key to a parent while that holder
            // still owns focus: the parent's geometric search could leave this row.
            if (focused != null && holder != null) return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun completePendingNavigation() {
        val target = pendingPosition ?: return
        if (findFocus() == null || target !in 0 until (adapter?.itemCount ?: 0)) {
            cancelPendingNavigation()
            return
        }
        val next = findViewHolderForAdapterPosition(target)?.itemView ?: return
        if (next.requestFocus()) {
            pendingPosition = null
            pendingDirection = 0
        }
    }

    override fun onChildAttachedToWindow(child: View) {
        super.onChildAttachedToWindow(child)
        if (pendingPosition != null) postOnAnimation { completePendingNavigation() }
    }

    override fun onScrollStateChanged(state: Int) {
        super.onScrollStateChanged(state)
        if (state == SCROLL_STATE_IDLE && pendingPosition != null) {
            completePendingNavigation()
            // If layout could not produce the target, allow the next key press to retry.
            pendingPosition = null
            pendingDirection = 0
        }
    }

    internal fun constrainFocus(focused: View, direction: Int, candidate: View?): View? {
        val horizontal = direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT
        if (horizontal && findContainingItemView(focused) != null &&
            (candidate == null || findContainingItemView(candidate) == null)) return focused
        return candidate
    }

    override fun focusSearch(focused: View, direction: Int): View? =
        constrainFocus(focused, direction, super.focusSearch(focused, direction))
}

/** An injected loader is owned by the containing UI; otherwise this rail owns it. */
internal class VideoRail(
    context: Context,
    private val onVideo: (Long, String) -> Unit,
    private val onNearEnd: (Long) -> Unit,
    private val onRetry: (Long) -> Unit,
    sharedImageLoader: ImageLoader? = null,
    private val claimInitialFocus: (View) -> Boolean = { it.requestFocus() },
) : LinearLayout(context) {
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
    private val cardTypeface by lazy {
        try { Typeface.createFromAsset(context.assets, "fonts/karminabold.otf") }
        catch (_: RuntimeException) { Typeface.create("sans-serif", Typeface.NORMAL) }
    }
    private val headingTypeface by lazy {
        try { Typeface.createFromAsset(context.assets, "fonts/Gnocchi-Regular.ttf") }
        catch (_: RuntimeException) { Typeface.create("sans-serif", Typeface.NORMAL) }
    }

    private val ownsImageLoader = sharedImageLoader == null
    private val imageLoader = sharedImageLoader ?: videoImageLoader(context)
    private val layout = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
    private val cards = CardAdapter()
    private val list = VideoRecyclerView(context).apply {
        layoutManager = layout
        adapter = cards
        itemAnimator = null
        isHorizontalScrollBarEnabled = false
        clipChildren = false
        clipToPadding = false
        setPadding(dp(14), dp(12), dp(14), dp(12))
        setItemViewCacheSize(2)
        addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = scheduleNearEnd()
        })
    }
    private val retry = Button(context).apply {
        text = "Повторить загрузку видео"
        typeface = cardTypeface
        visibility = View.GONE
        setOnClickListener { row?.takeIf { it.loadError && !it.loading }?.let { onRetry(it.id) } }
    }
    private val loading = TextView(context).apply {
        text = "Загрузка…"
        textSize = 14f
        typeface = cardTypeface
        setTextColor(Color.LTGRAY)
        visibility = View.GONE
    }
    private val sourceIcon = object : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        private val rutubeIcon = context.getDrawable(R.drawable.video_rutube_icon)
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val youtube = row?.source == VideoSource.YOUTUBE
            if (!youtube) {
                val icon = rutubeIcon ?: return
                val size = minOf(width, height)
                val left = (width - size) / 2
                val top = (height - size) / 2
                val save = canvas.save()
                path.reset()
                val radius = size * .2f
                path.addRoundRect(left.toFloat(), top.toFloat(), (left + size).toFloat(), (top + size).toFloat(), radius, radius, Path.Direction.CW)
                canvas.clipPath(path)
                icon.setBounds(left, top, left + size, top + size)
                icon.draw(canvas)
                canvas.restoreToCount(save)
                return
            }
            paint.style = Paint.Style.FILL
            paint.color = 0xffff0000.toInt()
            canvas.drawRoundRect(w * .04f, h * .16f, w * .96f, h * .84f, h * .17f, h * .17f, paint)
            path.reset()
            paint.color = Color.WHITE
            path.moveTo(w * .42f, h * .33f); path.lineTo(w * .42f, h * .67f); path.lineTo(w * .67f, h * .5f)
            path.close()
            canvas.drawPath(path, paint)
        }
        init { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    }
    private val heading = TextView(context).apply {
        textSize = 18f
        typeface = headingTypeface
        setTextColor(0xfff3f5fa.toInt())
        includeFontPadding = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private fun arrow(direction: Int): View = object : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            paint.style = Paint.Style.FILL
            paint.color = 0x4d506b9a
            canvas.drawCircle(cx, cy, minOf(width, height) / 2f, paint)
            paint.color = 0xffc4d4ed.toInt()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = resources.displayMetrics.density * 1.5f
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            val dx = resources.displayMetrics.density * 5f * direction
            val dy = resources.displayMetrics.density * 3.5f
            path.reset()
            path.moveTo(cx - dx, cy)
            path.lineTo(cx + dx, cy)
            path.moveTo(cx + dx - direction * dy, cy - dy)
            path.lineTo(cx + dx, cy)
            path.lineTo(cx + dx - direction * dy, cy + dy)
            canvas.drawPath(path, paint)
        }
        init { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    }
    private var row: VideoRow? = null
    private var released = false
    private var generation = 0L
    private var initialFocusRow: Long? = null
    private var nearEndPosted = false
    private val boundHolders = mutableSetOf<CardHolder>()
    private var glowBitmap: Bitmap? = null

    // Generate the soft outside-only falloff once per rail/size, not during animation.
    private fun glowForSize(width: Int, height: Int): Bitmap {
        glowBitmap?.let { if (it.width == width && it.height == height) return it }
        val pixels = IntArray(width * height)
        val density = resources.displayMetrics.density / 4f
        val insetX = dp(9) / 4f
        val insetY = dp(8) / 4f
        val radius = dp(10) / 4f
        val halfWidth = width / 2f - insetX
        val halfHeight = height / 2f - insetY
        for (y in 0 until height) for (x in 0 until width) {
            val qx = kotlin.math.abs(x + .5f - width / 2f) - (halfWidth - radius)
            val qy = kotlin.math.abs(y + .5f - height / 2f) - (halfHeight - radius)
            val distance = kotlin.math.sqrt(maxOf(qx, 0f) * maxOf(qx, 0f) + maxOf(qy, 0f) * maxOf(qy, 0f)) + minOf(maxOf(qx, qy), 0f) - radius
            if (distance >= 0f) {
                val edge = minOf(x, y, width - 1 - x, height - 1 - y)
                val fade = (edge / 2f).coerceIn(0f, 1f)
                val alpha = (100f * kotlin.math.exp(-distance / (2.5f * density)) * fade).toInt().coerceIn(0, 255)
                // Match the border's horizontal cyan -> blue -> purple gradient.
                val t = (x / maxOf(1f, width - 1f)).coerceIn(0f, 1f) * 2f
                val start = if (t < 1f) 0xff36e7f2.toInt() else 0xff477fff.toInt()
                val end = if (t < 1f) 0xff477fff.toInt() else 0xffb65cff.toInt()
                val mix = if (t < 1f) t else t - 1f
                val red = (Color.red(start) + (Color.red(end) - Color.red(start)) * mix).toInt()
                val green = (Color.green(start) + (Color.green(end) - Color.green(start)) * mix).toInt()
                val blue = (Color.blue(start) + (Color.blue(end) - Color.blue(start)) * mix).toInt()
                pixels[y * width + x] = Color.argb(alpha, red, green, blue)
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).also { glowBitmap = it }
    }

    init {
        orientation = VERTICAL
        visibility = View.GONE
        clipChildren = false
        clipToPadding = false
        setPadding(0, 0, 0, dp(12))
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, 0, dp(7))
            addView(sourceIcon, LayoutParams(dp(24), dp(21)).apply { marginEnd = dp(7) })
            addView(heading, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { weight = 0f })
            addView(View(context).apply {
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0x6688a4d0, 0x11758dbe))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutParams(0, maxOf(1, dp(1)), 1f).apply { marginStart = dp(12); marginEnd = dp(12) })
            addView(arrow(-1), LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(8) })
            addView(arrow(1), LayoutParams(dp(24), dp(24)))
        }
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(loading, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(retry, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun render(row: VideoRow?) {
        if (released) return
        val previous = this.row
        if (previous == row) return
        val newRow = previous?.id != row?.id || previous?.source != row?.source
        this.row = row
        heading.text = row?.source?.label.orEmpty()
        sourceIcon.invalidate()
        val version = ++generation
        if (newRow) {
            list.cancelPendingNavigation()
            boundHolders.toList().forEach { it.clear() }
            // A different row must rebind even if it contains the same video IDs.
            cards.submitList(null)
            initialFocusRow = row?.id
            list.scrollToPosition(0)
        }
        visibility = if (row == null) View.GONE else View.VISIBLE
        retry.visibility = if (row?.loadError == true && !row.loading) View.VISIBLE else View.GONE
        // Loading a subsequent page must not change the rail height or move another row.
        loading.visibility = View.GONE
        if (!newRow && previous?.cards == row?.cards) {
            list.post { if (!released) { focusFirstIfNeeded(); scheduleNearEnd() } }
            return
        }
        cards.submitList(row?.cards?.toList() ?: emptyList()) {
            if (released || version != generation || row == null || this.row?.id != row.id) return@submitList
            list.post {
                if (released || version != generation || this.row?.id != row.id) return@post
                focusFirstIfNeeded()
                scheduleNearEnd()
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        list.post { if (!released) focusFirstIfNeeded() }
    }

    private fun focusFirstIfNeeded() {
        if (initialFocusRow != null && initialFocusRow == row?.id && cards.itemCount > 0 && isShown) {
            val first = list.findViewHolderForAdapterPosition(0)?.itemView ?: return
            // Do not steal focus from another video rail that appeared earlier.
            val focused = rootView.findFocus()
            if (focused != null && isVideoCard(focused)) {
                initialFocusRow = null
            } else if (claimInitialFocus(first)) {
                initialFocusRow = null
            }
        }
    }

    private fun isVideoCard(view: View): Boolean {
        var parent = view.parent
        while (parent is View) {
            if (parent is VideoRail) return true
            parent = parent.parent
        }
        return false
    }

    private fun scheduleNearEnd() {
        if (nearEndPosted || released) return
        nearEndPosted = true
        list.post {
            nearEndPosted = false
            val current = row ?: return@post
            // A short visible row must not automatically chain page requests. Load as
            // the user moves focus towards its end, independently for each source.
            val focused = list.findFocus()
            val position = focused?.let { list.findContainingViewHolder(it)?.bindingAdapterPosition }
            val nearEnd = position != null && position != RecyclerView.NO_POSITION && position >= cards.itemCount - 3
            if (!released && current.hasNext && !current.loading && !current.loadError &&
                cards.itemCount == current.cards.size && cards.itemCount > 0 &&
                nearEnd) {
                onNearEnd(current.id)
            }
        }
    }

    fun release() {
        if (released) return
        released = true
        generation++
        list.cancelPendingNavigation()
        row = null
        initialFocusRow = null
        boundHolders.toList().forEach { it.clear() }
        cards.submitList(emptyList())
        list.adapter = null
        list.recycledViewPool.clear()
        if (ownsImageLoader) imageLoader.shutdown()
        visibility = View.GONE
    }

    private inner class CardAdapter : ListAdapter<VideoCard, CardHolder>(object : DiffUtil.ItemCallback<VideoCard>() {
        override fun areItemsTheSame(oldItem: VideoCard, newItem: VideoCard) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: VideoCard, newItem: VideoCard) = oldItem == newItem
    }) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = CardHolder()
        override fun onBindViewHolder(holder: CardHolder, position: Int) = holder.bind(getItem(position))
        override fun onViewAttachedToWindow(holder: CardHolder) {
            super.onViewAttachedToWindow(holder)
            list.post { if (!released) focusFirstIfNeeded() }
        }
        override fun onViewRecycled(holder: CardHolder) { holder.clear(); super.onViewRecycled(holder) }
    }

    private inner class CardHolder : RecyclerView.ViewHolder(LinearLayout(context)) {
        private val item = itemView as LinearLayout
        private val cardWidth = dp(((resources.displayMetrics.widthPixels / resources.displayMetrics.density - 112) / 4.65f).toInt().coerceIn(150, 200))
        private val cardHeight = (cardWidth * 9f / 16f).toInt() - 3
        private val outer = FrameLayout(context)
        private val halo = object : View(context) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            private val destination = Rect()
            private var bitmap: Bitmap? = null
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                destination.set(0, 0, w, h)
                bitmap = if (w > 0 && h > 0) glowForSize(maxOf(1, (w + 3) / 4), maxOf(1, (h + 3) / 4)) else null
            }
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                bitmap?.let { canvas.drawBitmap(it, null, destination, paint) }
            }
        }
        private val poster = FrameLayout(context)
        private val image = ImageView(context)
        private val border = object : View(context) {
            private val stroke = resources.displayMetrics.density * 1.5f
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = stroke
            }

            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                paint.shader = if (w > 0) LinearGradient(
                    0f, 0f, w.toFloat(), 0f,
                    intArrayOf(0xff36e7f2.toInt(), 0xff477fff.toInt(), 0xffb65cff.toInt()),
                    null, Shader.TileMode.CLAMP
                ) else null
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val inset = stroke / 2f
                val radius = dp(10).toFloat() - inset
                canvas.drawRoundRect(inset, inset, width - inset, height - inset, radius, radius, paint)
            }
        }
        private val title = TextView(context)
        private val author = TextView(context)
        private var request: Disposable? = null
        private var cardId: String? = null
        private var boundRowId: Long? = null

        init {
            item.orientation = VERTICAL
            item.layoutParams = RecyclerView.LayoutParams(cardWidth, LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(14) }
            item.isFocusable = true
            item.isClickable = true
            item.clipChildren = false
            outer.clipChildren = false
            halo.alpha = 0f
            halo.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            outer.addView(halo, FrameLayout.LayoutParams(-1, -1).apply { setMargins(-dp(9), -dp(8), -dp(9), -dp(8)) })
            poster.background = GradientDrawable().apply { setColor(0xff293446.toInt()); cornerRadius = dp(10).toFloat() }
            poster.clipToOutline = true
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            image.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            poster.addView(image, FrameLayout.LayoutParams(-1, -1))
            border.alpha = 0f
            border.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            poster.addView(border, FrameLayout.LayoutParams(-1, -1))
            outer.addView(poster, FrameLayout.LayoutParams(-1, -1))
            item.addView(outer, LayoutParams(cardWidth, cardHeight))
            title.textSize = 13f
            title.setTextColor(0xfff3f5fa.toInt())
            title.typeface = cardTypeface
            title.includeFontPadding = false
            title.maxLines = 2
            title.ellipsize = TextUtils.TruncateAt.END
            title.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            item.addView(title, LayoutParams(-1, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            author.textSize = 11f
            author.setTextColor(0xffa0aaba.toInt())
            author.typeface = cardTypeface
            author.includeFontPadding = false
            author.maxLines = 1
            author.ellipsize = TextUtils.TruncateAt.END
            author.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            item.addView(author, LayoutParams(-1, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
            updateMinimumHeight()
            item.setOnFocusChangeListener { _, focused ->
                border.animate().cancel()
                outer.animate().cancel()
                halo.animate().cancel()
                outer.animate().scaleX(if (focused) 1.035f else 1f).scaleY(if (focused) 1.035f else 1f)
                    .setDuration(180).setInterpolator(DecelerateInterpolator()).start()
                border.animate().alpha(if (focused) 1f else 0f).setDuration(180).start()
                halo.animate().alpha(if (focused) 1f else 0f).setDuration(180).start()
                if (focused) {
                    scheduleNearEnd()
                }
            }
            item.setOnClickListener {
                val id = cardId
                val current = row
                if (!released && id != null && current != null && boundRowId == current.id && bindingAdapterPosition != RecyclerView.NO_POSITION &&
                    current.cards.any { it.id == id }) onVideo(current.id, id)
            }
        }

        fun bind(card: VideoCard) {
            clear()
            updateMinimumHeight()
            cardId = card.id
            boundRowId = row?.id
            boundHolders.add(this)
            title.text = card.title
            author.text = card.author
            item.contentDescription = listOf(card.title, card.author).filter { it.isNotBlank() }.joinToString(", ")
            border.alpha = if (item.hasFocus()) 1f else 0f
            halo.alpha = if (item.hasFocus()) 1f else 0f
            outer.scaleX = if (item.hasFocus()) 1.035f else 1f
            outer.scaleY = if (item.hasFocus()) 1.035f else 1f
            // The parser validates these URLs; repeat the boundary check before handing one to Coil.
            val url = card.thumbnail?.let { thumbnail -> row?.source?.let { allowedVideoThumbnail(thumbnail, it) } }
            if (url != null) request = imageLoader.enqueue(ImageRequest.Builder(context)
                .data(url.toString()).size(cardWidth, cardHeight).target(image).build())
        }

        private fun updateMinimumHeight() {
            // Reserve the unused title line below the author, not between the texts.
            // TextView line heights reflect the configured typeface and font scale.
            item.minimumHeight = cardHeight + dp(6) + title.lineHeight * 2 + dp(2) + author.lineHeight
        }

        fun clear() {
            request?.dispose()
            request = null
            image.setImageDrawable(null)
            cardId = null
            boundRowId = null
            boundHolders.remove(this)
            border.animate().cancel()
            halo.animate().cancel()
            border.alpha = 0f
            halo.alpha = 0f
            outer.animate().cancel()
            outer.scaleX = 1f
            outer.scaleY = 1f
        }
    }
}
