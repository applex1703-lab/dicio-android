package org.stypox.dicio.voice

import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.stypox.dicio.eval.VideoCard

/** A delayed page arrives only after RIGHT has been released on the last loaded card. */
class VideoRailPageArrivalTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun delayedAppendKeepsEveryFocusEventOnLastYoutubeCard() {
        val activity = VideoRailTestActivity.launch()
        val list = activity.youtube.getChildAt(1) as RecyclerView
        val events = mutableListOf<String>()
        val violations = mutableListOf<String>()
        var listener: ViewTreeObserver.OnGlobalFocusChangeListener? = null

        // Read the actual card ID from the adapter, not just the holder's position or View identity.
        fun describe(view: View?): String {
            val inYoutube = view != null && VideoRailTestActivity.inside(activity.youtube, view)
            val inRutube = view != null && VideoRailTestActivity.inside(activity.upper, view)
            val holder = view?.let { list.findContainingViewHolder(it) }
            val position = holder?.bindingAdapterPosition ?: RecyclerView.NO_POSITION
            val cards = (list.adapter as? ListAdapter<*, *>)?.currentList
            val id = if (position >= 0) (cards?.getOrNull(position) as? VideoCard)?.id else null
            return "row=${if (inYoutube) "youtube" else if (inRutube) "rutube" else "outside"}, id=$id, position=$position, view=$view"
        }

        fun checkFocus(view: View?, label: String) {
            val description = describe(view)
            events.add("$label: $description")
            val holder = view?.let { list.findContainingViewHolder(it) }
            val position = holder?.bindingAdapterPosition ?: RecyclerView.NO_POSITION
            val cards = (list.adapter as? ListAdapter<*, *>)?.currentList
            val id = if (position >= 0) (cards?.getOrNull(position) as? VideoCard)?.id else null
            if (view == null || !VideoRailTestActivity.inside(activity.youtube, view) || id != "yt-7") {
                violations.add("$label: $description")
            }
        }

        try {
            await { activity.hasWindowFocus() && list.findViewHolderForAdapterPosition(0) != null }
            instrumentation.runOnMainSync {
                assertTrue(list.findViewHolderForAdapterPosition(0)!!.itemView.requestFocus())
                activity.scroll.scrollTo(0, activity.scroll.getChildAt(0).height)
            }
            // One complete key press at a time, waiting for each navigation to settle.
            for (position in 1..7) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
                await {
                    val focused = activity.window.decorView.findFocus()
                    focused != null && list.findContainingViewHolder(focused)?.bindingAdapterPosition == position
                }
            }
            await { activity.requests == 1 && list.scrollState == RecyclerView.SCROLL_STATE_IDLE }
            instrumentation.runOnMainSync {
                checkFocus(activity.window.decorView.findFocus(), "before last RIGHT")
                listener = ViewTreeObserver.OnGlobalFocusChangeListener { old, next ->
                    checkFocus(next, "global focus from $old")
                }.also { activity.window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener(it) }
            }
            // Exercise the edge once more, including ACTION_UP, before simulating the response.
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            instrumentation.runOnMainSync { checkFocus(activity.window.decorView.findFocus(), "after RIGHT up") }
            SystemClock.sleep(500)
            instrumentation.runOnMainSync {
                checkFocus(activity.window.decorView.findFocus(), "before response")
                activity.appendPage()
            }
            await { list.adapter?.itemCount == 24 }
            // Observe asynchronous diff, layout, focus recovery and posted callbacks after commit.
            SystemClock.sleep(1_000)
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                checkFocus(activity.window.decorView.findFocus(), "after response and layout")
                assertEquals("One page request expected", 1, activity.requests)
                assertEquals("No video should be opened", 0, activity.opened)
                assertTrue("Focus changed card or row: $violations; all observations: $events", violations.isEmpty())
            }
        } finally {
            instrumentation.runOnMainSync {
                listener?.let { activity.window.decorView.viewTreeObserver.removeOnGlobalFocusChangeListener(it) }
                activity.finish()
            }
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
