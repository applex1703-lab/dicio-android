package org.stypox.dicio.voice

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.stypox.dicio.eval.VideoCard
import org.stypox.dicio.eval.VideoRow
import org.stypox.dicio.eval.VideoSource

/** A plain test-APK Activity: no Compose, voice, thumbnails, or video intents. */
class VideoRailTestActivity : Activity() {
    internal lateinit var upper: VideoRail
    internal lateinit var youtube: VideoRail
    internal lateinit var scroll: ScrollView
    internal val escapes = mutableListOf<String>()
    internal var watching = false
    internal var requests = 0
    internal var opened = 0
    private var row = VideoRow(2, cards("yt", 0, 8), hasNext = true, source = VideoSource.YOUTUBE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        upper = VideoRail(this, { _, _ -> opened++ }, {}, {}, claimInitialFocus = { false })
        youtube = VideoRail(this, { _, _ -> opened++ }, {
            if (it == row.id && !row.loading && requests == 0) {
                requests++
                row = row.copy(loading = true)
                youtube.render(row)
            }
        }, {}, claimInitialFocus = { false })
        rows.addView(upper, LinearLayout.LayoutParams(-1, -2))
        rows.addView(youtube, LinearLayout.LayoutParams(-1, -2))
        scroll = ScrollView(this).apply { addView(rows) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(scroll, LinearLayout.LayoutParams(-1, (230 * resources.displayMetrics.density).toInt()))
        }
        setContentView(root)
        root.viewTreeObserver.addOnGlobalFocusChangeListener { old, next ->
            if (watching && (next == null || !inside(youtube, next))) {
                escapes.add("view focus $old -> $next, scrollY=${scroll.scrollY}")
            }
        }
        upper.render(VideoRow(1, cards("rt", 0, 8), source = VideoSource.RUTUBE))
        youtube.render(row)
    }

    // The test decides when the simulated response arrives; ListAdapter still diffs asynchronously.
    internal fun appendPage() {
        check(requests == 1 && row.loading)
        row = row.copy(cards = row.cards + cards("yt", 8, 24), loading = false, hasNext = false)
        youtube.render(row)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (watching && !hasFocus) escapes.add("Activity window lost focus")
    }

    override fun onDestroy() {
        watching = false
        upper.release()
        youtube.release()
        super.onDestroy()
    }

    companion object {
        private fun cards(prefix: String, start: Int, end: Int) = (start until end).map {
            VideoCard("$prefix-$it", "Video $it", "Channel", null)
        }
        internal fun inside(root: View, view: View): Boolean {
            var current: View? = view
            while (current != null) {
                if (current === root) return true
                current = current.parent as? View
            }
            return false
        }
    }
}

class VideoRailActivityNavigationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun rapidRightAcrossAsyncAppendStaysInYoutubeAndUpReachesOtherRail() {
        val intent = Intent().setClassName(instrumentation.context, VideoRailTestActivity::class.java.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent) as VideoRailTestActivity
        try {
            val list = activity.youtube.getChildAt(1) as RecyclerView
            await { activity.hasWindowFocus() && list.findViewHolderForAdapterPosition(0) != null }
            instrumentation.runOnMainSync {
                assertTrue(list.findViewHolderForAdapterPosition(0)!!.itemView.requestFocus())
                activity.scroll.scrollTo(0, activity.scroll.getChildAt(0).height)
                activity.watching = true
            }
            // Advance until the actual near-end callback asks for a page.
            repeat(80) {
                if (onMain { activity.requests == 0 }) {
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
                    SystemClock.sleep(20)
                }
            }
            assertTrue("Navigation must request the next page", onMain { activity.requests == 1 })
            instrumentation.runOnMainSync { activity.appendPage() }
            val downTime = SystemClock.uptimeMillis()
            repeat(160) { count ->
                instrumentation.sendKeySync(KeyEvent(downTime, SystemClock.uptimeMillis(),
                    KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, count))
                SystemClock.sleep(8)
            }
            instrumentation.sendKeySync(KeyEvent(downTime, SystemClock.uptimeMillis(),
                KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT, 0))
            await { list.adapter?.itemCount == 24 }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val focus = activity.window.decorView.findFocus()
                val position = focus?.let { list.findContainingViewHolder(it)?.bindingAdapterPosition } ?: -1
                assertTrue("Focus left YouTube: ${activity.escapes}", activity.escapes.isEmpty())
                assertTrue("Focus must remain in YouTube", focus != null && VideoRailTestActivity.inside(activity.youtube, focus))
                assertTrue("Navigation must advance into appended cards, position=$position", position >= 8)
                assertTrue("No video should be opened", activity.opened == 0)
                activity.watching = false
            }
            // Start vertical search from aligned first cards, not from a parked RecyclerView.
            instrumentation.runOnMainSync { list.scrollToPosition(0) }
            await { list.findViewHolderForAdapterPosition(0) != null }
            instrumentation.runOnMainSync {
                assertTrue(list.findViewHolderForAdapterPosition(0)!!.itemView.requestFocus())
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
            await { activity.window.decorView.findFocus()?.let { VideoRailTestActivity.inside(activity.upper, it) } == true }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun onMain(condition: () -> Boolean): Boolean {
        var result = false
        instrumentation.runOnMainSync { result = condition() }
        return result
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (onMain(condition)) return
            SystemClock.sleep(20)
        }
        assertTrue("Timed out waiting for Activity state", onMain(condition))
    }
}
