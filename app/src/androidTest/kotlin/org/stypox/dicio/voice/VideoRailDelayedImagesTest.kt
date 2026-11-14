package org.stypox.dicio.voice

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.platform.app.InstrumentationRegistry
import coil.ImageLoader
import coil.decode.DataSource
import coil.intercept.Interceptor
import coil.request.ImageResult
import coil.request.SuccessResult
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.stypox.dicio.eval.VideoCard
import org.stypox.dicio.eval.VideoRow
import org.stypox.dicio.eval.VideoSource
import java.util.Collections
import java.util.IdentityHashMap

/** Real rails and window, but every thumbnail is supplied locally, after an explicit gate. */
class VideoRailDelayedImagesTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private class DelayedImages : Interceptor {
        val gate = CompletableDeferred<Unit>()
        val started = Collections.synchronizedList(mutableListOf<String>())
        val completed = Collections.synchronizedList(mutableListOf<String>())
        val cancelled = Collections.synchronizedList(mutableListOf<String>())

        override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
            val url = chain.request.data.toString()
            check(url.startsWith("https://i.ytimg.com/") || url.startsWith("https://pic.rtbcdn.ru/"))
            started.add(url)
            try {
                gate.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled.add(url)
                throw e
            }
            completed.add(url)
            return SuccessResult(ColorDrawable(color(url)), chain.request, DataSource.MEMORY_CACHE)
        }

        companion object {
            fun color(url: String): Int = Color.rgb((url.hashCode() ushr 16) and 255,
                (url.hashCode() ushr 8) and 255, url.hashCode() and 255)
        }
    }

    @Test fun delayedImagesDuringHorizontalNavigationDoNotStealFocusOrOpenVideo() {
        val host = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            BindDiagnosticActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val delayed = DelayedImages()
        lateinit var loader: ImageLoader
        lateinit var upper: VideoRail
        lateinit var lower: VideoRail
        lateinit var scroll: ScrollView
        lateinit var list: RecyclerView
        var opened = 0
        var watching = false
        val focusEvents = mutableListOf<String>()
        val escapes = mutableListOf<String>()
        val layouts = mutableListOf<String>()
        val seen = IdentityHashMap<View, MutableSet<Int>>()
        try {
            instrumentation.runOnMainSync {
                loader = ImageLoader.Builder(host).components { add(delayed) }.build()
                upper = VideoRail(host, { _, _ -> opened++ }, {}, {}, loader, { false })
                lower = VideoRail(host, { _, _ -> opened++ }, {}, {}, loader, { false })
                val rows = LinearLayout(host).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(upper, LinearLayout.LayoutParams(-1, -2))
                    addView(lower, LinearLayout.LayoutParams(-1, -2))
                }
                scroll = ScrollView(host).apply {
                    addView(rows)
                    layoutParams = LinearLayout.LayoutParams(-1, (230 * resources.displayMetrics.density).toInt())
                }
                host.setContentView(LinearLayout(host).apply { addView(scroll) })
                list = lower.getChildAt(1) as RecyclerView
                host.window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { old, new ->
                    if (watching) {
                        focusEvents.add("$old -> $new")
                        if (new == null || !inside(lower, new)) escapes.add("$old -> $new")
                    }
                }
                host.window.decorView.viewTreeObserver.addOnWindowFocusChangeListener {
                    if (watching && !it) escapes.add("window lost focus")
                }
                host.window.decorView.viewTreeObserver.addOnGlobalLayoutListener {
                    if (watching) layouts.add("upper=${upper.height}, lower=${lower.height}, top=${lower.top}, scrollY=${scroll.scrollY}")
                }
                fun cards(prefix: String, hostName: String) = (0 until 40).map {
                    VideoCard("$prefix-$it", "Video $it", "Channel", "https://$hostName/$prefix-$it")
                }
                upper.render(VideoRow(1, cards("rt", "pic.rtbcdn.ru"), source = VideoSource.RUTUBE))
                lower.render(VideoRow(2, cards("yt", "i.ytimg.com"), source = VideoSource.YOUTUBE))
            }
            await { host.hasWindowFocus() && list.findViewHolderForAdapterPosition(0) != null && delayed.started.size >= 2 }
            instrumentation.runOnMainSync {
                scroll.scrollTo(0, scroll.getChildAt(0).height)
                assertTrue(list.findViewHolderForAdapterPosition(0)!!.itemView.requestFocus())
                watching = true
            }
            fun remember() = instrumentation.runOnMainSync {
                for (i in 0 until list.childCount) {
                    val child = list.getChildAt(i)
                    val position = list.getChildAdapterPosition(child)
                    if (position != RecyclerView.NO_POSITION) seen.getOrPut(child) { mutableSetOf() }.add(position)
                }
            }
            remember()
            // Bursts exercise in-flight navigation; wait on state, not a fixed animation duration.
            for (burst in 0 until 30) {
                repeat(5) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT) }
                await { focusedPosition(list) != RecyclerView.NO_POSITION }
                remember()
                if (onMain { focusedPosition(list) >= 12 }) break
            }
            await { focusedPosition(list) >= 12 }
            remember()
            assertTrue("Expected requests while navigation is in progress", delayed.started.size > 2)
            assertTrue("No image may complete before the gate opens", delayed.completed.isEmpty())
            val before = onMainValue { "upper=${upper.height}, lower=${lower.height}, top=${lower.top}, scrollY=${scroll.scrollY}" }
            val countBefore = onMainValue { layouts.size }
            delayed.gate.complete(Unit)
            await { delayed.completed.isNotEmpty() }
            // Verify actual image delivery on a currently bound holder, not merely interceptor return.
            await { visibleCorrectImages(list) > 0 }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val after = "upper=${upper.height}, lower=${lower.height}, top=${lower.top}, scrollY=${scroll.scrollY}"
                Log.i("VideoRailDelayedImages", "before=$before after=$after layoutCallbacks=${layouts.size - countBefore} events=$layouts recycled=${seen.values.count { it.size > 1 }} cancelled=${delayed.cancelled.size} completed=${delayed.completed.size}")
                assertTrue("Expected observed focus transitions", focusEvents.isNotEmpty())
                assertTrue("Focus escaped lower rail: $escapes", escapes.isEmpty())
                assertTrue("Final focus must be on an advanced card", focusedPosition(list) >= 12)
                assertEquals("Navigation and image completion must not open video", 0, opened)
                assertTrue("Every delivered visible image must match its current binding", visibleImagesMatch(list))
            }
        } finally {
            delayed.gate.complete(Unit)
            instrumentation.runOnMainSync {
                watching = false
                runCatching { upper.release() }
                runCatching { lower.release() }
                runCatching { loader.shutdown() }
                host.finish()
            }
        }
    }

    private fun focusedPosition(list: RecyclerView): Int = list.findFocus()?.let {
        list.findContainingViewHolder(it)?.bindingAdapterPosition
    } ?: RecyclerView.NO_POSITION

    private fun visibleCorrectImages(list: RecyclerView): Int = (0 until list.childCount).count { index ->
        val item = list.getChildAt(index)
        val pos = list.getChildAdapterPosition(item)
        val image = imageIn(item)
        pos != RecyclerView.NO_POSITION && (image?.drawable as? ColorDrawable)?.color ==
            DelayedImages.color("https://i.ytimg.com/yt-$pos")
    }

    private fun visibleImagesMatch(list: RecyclerView): Boolean = (0 until list.childCount).all { index ->
        val item = list.getChildAt(index)
        val pos = list.getChildAdapterPosition(item)
        val drawable = imageIn(item)?.drawable
        drawable == null || (pos != RecyclerView.NO_POSITION && drawable is ColorDrawable &&
            drawable.color == DelayedImages.color("https://i.ytimg.com/yt-$pos"))
    }

    private fun imageIn(view: View): ImageView? {
        if (view is ImageView) return view
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) {
            imageIn(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun inside(root: View, view: View): Boolean {
        var current: View? = view
        while (current != null) { if (current === root) return true; current = current.parent as? View }
        return false
    }

    private fun <T> onMainValue(block: () -> T): T {
        var value: Any? = null
        instrumentation.runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }
    private fun onMain(block: () -> Boolean) = onMainValue(block)
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (onMain(condition)) return
            SystemClock.sleep(20)
        }
        assertTrue("Timed out waiting for test state", onMain(condition))
    }
}
