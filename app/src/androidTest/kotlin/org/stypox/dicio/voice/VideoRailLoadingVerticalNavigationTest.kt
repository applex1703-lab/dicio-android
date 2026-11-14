package org.stypox.dicio.voice

import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Vertical departure while a locally simulated YouTube page is still outstanding. */
class VideoRailLoadingVerticalNavigationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun delayedPageDoesNotReclaimFocusAfterMovingToRutube() {
        val activity = VideoRailTestActivity.launch()
        val upper = activity.upper.getChildAt(1) as RecyclerView
        val lower = activity.youtube.getChildAt(1) as RecyclerView
        val events = mutableListOf<String>()
        val violations = mutableListOf<String>()
        var focusListener: ViewTreeObserver.OnGlobalFocusChangeListener? = null
        var windowListener: ViewTreeObserver.OnWindowFocusChangeListener? = null
        try {
            instrumentation.runOnMainSync {
                activity.scroll.layoutParams = (activity.scroll.layoutParams as LinearLayout.LayoutParams).apply {
                    height = LinearLayout.LayoutParams.MATCH_PARENT
                }
            }
            await("window and initial cards") {
                activity.hasWindowFocus() && upper.findViewHolderForAdapterPosition(0) != null &&
                    lower.findViewHolderForAdapterPosition(0) != null
            }
            instrumentation.runOnMainSync {
                assertTrue(lower.findViewHolderForAdapterPosition(0)!!.itemView.requestFocus())
            }
            for (position in 1..7) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
                await("YouTube card $position") { focusedPosition(activity, lower) == position }
            }
            await("outstanding page request") {
                activity.requests == 1 && lower.scrollState == RecyclerView.SCROLL_STATE_IDLE
            }

            // Return to aligned attached cards while the requested page is still withheld.
            instrumentation.runOnMainSync {
                upper.scrollToPosition(0)
                lower.scrollToPosition(0)
            }
            await("aligned first cards") {
                upper.findViewHolderForAdapterPosition(0) != null &&
                    lower.findViewHolderForAdapterPosition(0) != null &&
                    upper.scrollState == RecyclerView.SCROLL_STATE_IDLE &&
                    lower.scrollState == RecyclerView.SCROLL_STATE_IDLE
            }
            instrumentation.runOnMainSync {
                assertTrue(lower.findViewHolderForAdapterPosition(0)!!.itemView.requestFocus())
            }
            await("visible, vertically aligned cards and focused window") {
                val a = upper.findViewHolderForAdapterPosition(0)?.itemView
                val b = lower.findViewHolderForAdapterPosition(0)?.itemView
                val ar = Rect()
                val br = Rect()
                activity.hasWindowFocus() && focusedPosition(activity, lower) == 0 &&
                    a != null && b != null && a.getGlobalVisibleRect(ar) && b.getGlobalVisibleRect(br) &&
                    ar.bottom <= br.top && ar.left < br.right && br.left < ar.right
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
            await("DPAD_UP to Rutube") {
                activity.hasWindowFocus() && activity.window.decorView.findFocus()?.let {
                    VideoRailTestActivity.inside(activity.upper, it)
                } == true
            }
            instrumentation.runOnMainSync {
                focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { old, next ->
                    val event = "$old -> $next"
                    events.add(event)
                    if (next == null || !VideoRailTestActivity.inside(activity.upper, next)) violations.add(event)
                }.also { activity.window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener(it) }
                windowListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
                    if (!hasFocus) violations.add("Activity window lost focus")
                }.also { activity.window.decorView.viewTreeObserver.addOnWindowFocusChangeListener(it) }
                assertEquals("Page must still be outstanding", 8, lower.adapter?.itemCount)
                activity.appendPage()
            }
            await("delayed page commit") { lower.adapter?.itemCount == 24 }
            // Observe diff dispatch, subsequent layout and posted focus-recovery callbacks.
            SystemClock.sleep(1_000)
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val focus = activity.window.decorView.findFocus()
                assertTrue("Focus escaped Rutube: $violations; focus events: $events",
                    violations.isEmpty() && activity.hasWindowFocus() && focus != null &&
                        VideoRailTestActivity.inside(activity.upper, focus))
                focusListener?.let { activity.window.decorView.viewTreeObserver.removeOnGlobalFocusChangeListener(it) }
                focusListener = null
                windowListener?.let { activity.window.decorView.viewTreeObserver.removeOnWindowFocusChangeListener(it) }
                windowListener = null
            }
            await("visible geometry for DPAD_DOWN") {
                val focus = activity.window.decorView.findFocus()
                val target = lower.findViewHolderForAdapterPosition(0)?.itemView
                val fromRect = Rect()
                val targetRect = Rect()
                activity.hasWindowFocus() && focus != null && target != null &&
                    VideoRailTestActivity.inside(activity.upper, focus) &&
                    focus.getGlobalVisibleRect(fromRect) && target.getGlobalVisibleRect(targetRect) &&
                    fromRect.bottom <= targetRect.top && fromRect.left < targetRect.right &&
                    targetRect.left < fromRect.right
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            await("DPAD_DOWN back to YouTube") {
                activity.hasWindowFocus() && activity.window.decorView.findFocus()?.let {
                    VideoRailTestActivity.inside(activity.youtube, it)
                } == true
            }
            instrumentation.runOnMainSync {
                assertEquals("One simulated page request", 1, activity.requests)
                assertEquals("No video opened", 0, activity.opened)
            }
        } finally {
            instrumentation.runOnMainSync {
                focusListener?.let { activity.window.decorView.viewTreeObserver.removeOnGlobalFocusChangeListener(it) }
                windowListener?.let { activity.window.decorView.viewTreeObserver.removeOnWindowFocusChangeListener(it) }
                activity.finish()
            }
        }
    }

    private fun focusedPosition(activity: VideoRailTestActivity, list: RecyclerView): Int {
        val focus = activity.window.decorView.findFocus() ?: return RecyclerView.NO_POSITION
        return list.findContainingViewHolder(focus)?.bindingAdapterPosition ?: RecyclerView.NO_POSITION
    }

    private fun onMain(condition: () -> Boolean): Boolean {
        var result = false
        instrumentation.runOnMainSync { result = condition() }
        return result
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (onMain(condition)) return
            SystemClock.sleep(20)
        }
        assertTrue("Timed out waiting for $label", onMain(condition))
    }
}
