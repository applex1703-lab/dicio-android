package org.stypox.dicio.voice

import android.view.KeyEvent
import android.view.View
import android.os.SystemClock
import android.widget.LinearLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.recyclerview.widget.RecyclerView
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

    private fun isDescendant(root: View, view: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === root) return true
            current = current.parent as? View
        }
        return false
    }
}
