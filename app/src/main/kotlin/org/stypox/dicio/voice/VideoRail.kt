package org.stypox.dicio.voice

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
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
        setPadding(dp(4), dp(5), dp(8), dp(4))
        setItemViewCacheSize(2)
        addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = scheduleNearEnd()
        })
    }
    private val retry = Button(context).apply {
        text = "Повторить загрузку видео"
        visibility = View.GONE
        setOnClickListener { row?.takeIf { it.loadError && !it.loading }?.let { onRetry(it.id) } }
    }
    private val loading = TextView(context).apply {
        text = "Загрузка…"
        textSize = 14f
        setTextColor(Color.LTGRAY)
        visibility = View.GONE
    }
    private val heading = TextView(context).apply {
        textSize = 17f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(Color.rgb(226, 233, 244))
        setPadding(dp(4), dp(6), 0, dp(4))
    }
    private var row: VideoRow? = null
    private var released = false
    private var generation = 0L
    private var initialFocusRow: Long? = null
    private var nearEndPosted = false
    private val boundHolders = mutableSetOf<CardHolder>()

    init {
        orientation = VERTICAL
        visibility = View.GONE
        clipChildren = false
        clipToPadding = false
        addView(heading)
        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(loading, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(retry, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun render(row: VideoRow?) {
        if (released) return
        val previous = this.row
        val newRow = previous?.id != row?.id || previous?.source != row?.source
        this.row = row
        heading.text = row?.source?.label.orEmpty()
        val version = ++generation
        if (newRow) {
            boundHolders.toList().forEach { it.clear() }
            // A different row must rebind even if it contains the same video IDs.
            cards.submitList(null)
            initialFocusRow = row?.id
            list.scrollToPosition(0)
        }
        visibility = if (row == null) View.GONE else View.VISIBLE
        retry.visibility = if (row?.loadError == true && !row.loading) View.VISIBLE else View.GONE
        loading.visibility = if (row?.loading == true) View.VISIBLE else View.GONE
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
        private val poster = FrameLayout(context)
        private val image = ImageView(context)
        private val border = View(context)
        private val title = TextView(context)
        private val author = TextView(context)
        private var request: Disposable? = null
        private var cardId: String? = null
        private var boundRowId: Long? = null

        init {
            item.orientation = VERTICAL
            item.layoutParams = RecyclerView.LayoutParams(dp(150), LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(12) }
            item.isFocusable = true
            item.isClickable = true
            item.clipChildren = false
            poster.background = GradientDrawable().apply { setColor(Color.rgb(38, 48, 64)); cornerRadius = dp(9).toFloat() }
            poster.clipToOutline = true
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            image.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            poster.addView(image, FrameLayout.LayoutParams(-1, -1))
            border.background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT); cornerRadius = dp(9).toFloat(); setStroke(maxOf(1, dp(2)), Color.WHITE)
            }
            border.alpha = 0f
            border.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            poster.addView(border, FrameLayout.LayoutParams(-1, -1))
            item.addView(poster, LayoutParams(dp(150), dp(84)))
            title.textSize = 15f
            title.setTextColor(Color.rgb(242, 246, 252))
            title.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            title.minLines = 2
            title.maxLines = 2
            title.ellipsize = TextUtils.TruncateAt.END
            title.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            item.addView(title, LayoutParams(-1, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) })
            author.textSize = 12f
            author.setTextColor(Color.rgb(174, 188, 207))
            author.maxLines = 1
            author.ellipsize = TextUtils.TruncateAt.END
            author.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            item.addView(author, LayoutParams(-1, LayoutParams.WRAP_CONTENT))
            item.setOnFocusChangeListener { _, focused ->
                border.animate().cancel()
                poster.animate().cancel()
                poster.animate().scaleX(if (focused) 1.025f else 1f).scaleY(if (focused) 1.025f else 1f)
                    .translationY(if (focused) -dp(3).toFloat() else 0f)
                    .setDuration(220).setInterpolator(DecelerateInterpolator()).start()
                border.animate().alpha(if (focused) 1f else 0f).setDuration(220).start()
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
            cardId = card.id
            boundRowId = row?.id
            boundHolders.add(this)
            title.text = card.title
            author.text = card.author
            item.contentDescription = listOf(card.title, card.author).filter { it.isNotBlank() }.joinToString(", ")
            border.alpha = if (item.hasFocus()) 1f else 0f
            // The parser validates these URLs; repeat the boundary check before handing one to Coil.
            val url = card.thumbnail?.let { thumbnail -> row?.source?.let { allowedVideoThumbnail(thumbnail, it) } }
            if (url != null) request = imageLoader.enqueue(ImageRequest.Builder(context)
                .data(url.toString()).size(dp(150), dp(84)).target(image).build())
        }

        fun clear() {
            request?.dispose()
            request = null
            image.setImageDrawable(null)
            cardId = null
            boundRowId = null
            boundHolders.remove(this)
            border.animate().cancel()
            poster.animate().cancel()
            poster.scaleX = 1f
            poster.scaleY = 1f
            poster.translationY = 0f
        }
    }
}
