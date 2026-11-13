package org.stypox.dicio.voice

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.os.SystemClock
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.stypox.dicio.eval.VideoCard
import org.stypox.dicio.eval.VideoRow
import org.stypox.dicio.eval.VideoSource

/** Real attached RecyclerViews on the emulator: no voice input, network, or video launch. */
class VideoRailNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rapidRightDoesNotMoveFocusToOtherRow() {
        lateinit var upper: VideoRail
        lateinit var lower: VideoRail
        lateinit var root: LinearLayout
        var watch = false
        var escaped = false
        compose.setContent {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                upper = VideoRail(context, { _, _ -> }, {}, {}, claimInitialFocus = { false })
                lower = VideoRail(context, { _, _ -> }, {}, {}, claimInitialFocus = { false })
                root.addView(upper)
                root.addView(lower)
                fun row(id: Long, source: VideoSource) = VideoRow(id,
                    (0 until 100).map { VideoCard("$source-$it", "Video $it", "Channel", null) }, source = source)
                upper.render(row(1, VideoSource.RUTUBE))
                lower.render(row(2, VideoSource.YOUTUBE))
                root.viewTreeObserver.addOnGlobalFocusChangeListener { _, next ->
                    if (watch && next != null && isDescendant(upper, next)) escaped = true
                }
                root
            })
        }
        compose.waitUntil(5_000) {
            val list = lower.getChildAt(1) as RecyclerView
            list.findViewHolderForAdapterPosition(0) != null
        }
        compose.runOnIdle {
            val list = lower.getChildAt(1) as RecyclerView
            assertTrue(list.findViewHolderForAdapterPosition(0)!!.itemView.requestFocus())
            watch = true
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        repeat(60) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT) }
        val downTime = SystemClock.uptimeMillis()
        repeat(60) { repeat ->
            instrumentation.sendKeySync(KeyEvent(downTime, SystemClock.uptimeMillis(),
                KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, repeat))
        }
        instrumentation.sendKeySync(KeyEvent(downTime, SystemClock.uptimeMillis(),
            KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT, 0))
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse("Horizontal input moved focus to the upper row", escaped)
            val focused = root.findFocus()
            assertTrue("Focus should remain in the YouTube row", focused != null && isDescendant(lower, focused))
            val list = lower.getChildAt(1) as RecyclerView
            assertTrue("Navigation should advance rather than remain stuck", (list.findContainingViewHolder(focused!!)?.bindingAdapterPosition ?: -1) > 0)
            upper.release()
            lower.release()
        }
    }

    @Test fun rapidRightDuringPaginationKeepsFocusInScrolledLowerRail() {
        lateinit var upper: VideoRail
        lateinit var lower: VideoRail
        lateinit var scroll: ScrollView
        var watch = false
        val escapes = mutableListOf<String>()
        var pageRequests = 0
        var youtube = VideoRow(2, (0 until 8).map {
            VideoCard("yt-$it", "YouTube video $it", "Channel", null)
        }, hasNext = true, source = VideoSource.YOUTUBE)
        compose.setContent {
            AndroidView(modifier = Modifier.fillMaxWidth().height(230.dp), factory = { context ->
                val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                upper = VideoRail(context, { _, _ -> }, {}, {}, claimInitialFocus = { false })
                lower = VideoRail(context, { _, _ -> }, { id ->
                    if (id == youtube.id && !youtube.loading) {
                        pageRequests++
                        youtube = youtube.copy(loading = true)
                        lower.render(youtube)
                        lower.postDelayed({
                            val start = youtube.cards.size
                            youtube = youtube.copy(cards = youtube.cards + (start until start + 8).map {
                                VideoCard("yt-$it", "YouTube video $it", "Channel", null)
                            }, loading = false, hasNext = pageRequests < 3)
                            lower.render(youtube)
                        }, 30)
                    }
                }, {}, claimInitialFocus = { false })
                rows.addView(upper, LinearLayout.LayoutParams(-1, -2))
                rows.addView(lower, LinearLayout.LayoutParams(-1, -2))
                scroll = ScrollView(context).apply {
                    isVerticalScrollBarEnabled = false
                    addView(rows)
                }
                upper.render(VideoRow(1, (0 until 8).map {
                    VideoCard("rt-$it", "Rutube video $it", "Channel", null)
                }, source = VideoSource.RUTUBE))
                lower.render(youtube)
                scroll.viewTreeObserver.addOnGlobalFocusChangeListener { _, next ->
                    if (watch && (next == null || !isDescendant(lower, next))) {
                        escapes.add("focus=${next?.javaClass?.simpleName}, scrollY=${scroll.scrollY}")
                    }
                }
                scroll
            })
        }
        compose.waitUntil(5_000) {
            (lower.getChildAt(1) as RecyclerView).findViewHolderForAdapterPosition(0) != null
        }
        compose.runOnIdle {
            val list = lower.getChildAt(1) as RecyclerView
            assertTrue(list.findViewHolderForAdapterPosition(0)!!.itemView.requestFocus())
            scroll.scrollTo(0, scroll.getChildAt(0).height)
            watch = true
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val downTime = SystemClock.uptimeMillis()
        repeat(160) { count ->
            instrumentation.sendKeySync(KeyEvent(downTime, SystemClock.uptimeMillis(),
                KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, count))
            SystemClock.sleep(8)
        }
        instrumentation.sendKeySync(KeyEvent(downTime, SystemClock.uptimeMillis(),
            KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT, 0))
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("Expected a pagination request during navigation", pageRequests > 0)
            assertTrue("Focus left YouTube during right input: $escapes", escapes.isEmpty())
            val focused = scroll.findFocus()
            assertTrue("Focus must remain in YouTube", focused != null && isDescendant(lower, focused))
            upper.release()
            lower.release()
        }
    }

    @Test fun rightIsConsumedWhileFocusedHolderHasNoAdapterPosition() {
        lateinit var list: VideoRecyclerView
        lateinit var adapter: RecyclerView.Adapter<RecyclerView.ViewHolder>
        compose.setContent {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                list = VideoRecyclerView(context)
                adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                    override fun getItemCount() = 4
                    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                        object : RecyclerView.ViewHolder(TextView(parent.context).apply {
                            layoutParams = RecyclerView.LayoutParams(200, 100)
                            isFocusable = true
                        }) {}
                    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                        (holder.itemView as TextView).text = "Card $position"
                    }
                }
                list.layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
                list.adapter = adapter
                list
            })
        }
        compose.waitUntil(5_000) { list.findViewHolderForAdapterPosition(0) != null }
        compose.runOnIdle {
            val holder = list.findViewHolderForAdapterPosition(0)!!
            assertTrue("RecyclerView must be attached", list.isAttachedToWindow)
            assertTrue("The attached card must receive focus", holder.itemView.requestFocus())
            // notifyDataSetChanged invalidates positions immediately; dispatch before the next layout.
            adapter.notifyDataSetChanged()
            assertTrue("The invalidated holder must still own focus", holder.itemView.hasFocus())
            assertTrue("Test must actually enter the NO_POSITION branch",
                holder.bindingAdapterPosition == RecyclerView.NO_POSITION)
            val now = SystemClock.uptimeMillis()
            val consumed = list.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_DPAD_RIGHT, 0))
            assertTrue("RIGHT must be consumed even while the focused holder has NO_POSITION", consumed)
            assertTrue("Focus must remain on the invalidated card until layout", holder.itemView.hasFocus())
        }
    }

    private fun isDescendant(root: View, view: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === root) return true
            current = current.parent as? View
        }
        return false
    }
}
