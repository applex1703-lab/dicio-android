package org.stypox.dicio.voice

import android.app.Dialog
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.stypox.dicio.eval.VideoCard
import org.stypox.dicio.eval.VideoRow
import org.stypox.dicio.eval.VideoSource

/** Separate Android window; no voice, network thumbnails, or video launch. */
class VideoRailDialogNavigationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun delayedPageKeepsFocusOnLastYoutubeCardInDialog() {
        val intent = Intent(instrumentation.targetContext, BindDiagnosticActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent)
        lateinit var dialog: Dialog
        lateinit var youtube: VideoRail
        lateinit var upper: VideoRail
        var requests = 0
        var opened = 0
        var row = VideoRow(2, cards("yt", 0, 8), hasNext = true, source = VideoSource.YOUTUBE)
        val escapes = mutableListOf<String>()
        var watching = false
        var setupComplete = false
        try {
            instrumentation.runOnMainSync {
                upper = VideoRail(activity, { _, _ -> opened++ }, {}, {}, claimInitialFocus = { false })
                youtube = VideoRail(activity, { _, _ -> opened++ }, { id ->
                    if (id == row.id && !row.loading) {
                        requests++
                        row = row.copy(loading = true)
                        youtube.render(row)
                    }
                }, {}, claimInitialFocus = { false })
                val rows = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(upper)
                    addView(youtube)
                }
                val scroll = ScrollView(activity).apply { addView(rows) }
                dialog = Dialog(activity).apply {
                    setContentView(scroll)
                    show()
                    window!!.setLayout(WindowManager.LayoutParams.MATCH_PARENT, (230 * activity.resources.displayMetrics.density).toInt())
                }
                upper.render(VideoRow(1, cards("rt", 0, 8), source = VideoSource.RUTUBE))
                youtube.render(row)
                dialog.window!!.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, next ->
                    if (watching && (next == null || !inside(youtube, next))) escapes.add("focus=$next")
                }
                setupComplete = true
            }
            val list = youtube.getChildAt(1) as RecyclerView
            await { dialog.window!!.decorView.hasWindowFocus() && list.findViewHolderForAdapterPosition(0) != null }
            instrumentation.runOnMainSync { assertTrue(list.findViewHolderForAdapterPosition(0)!!.itemView.requestFocus()) }
            for (position in 1..7) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
                await { list.findFocus()?.let { list.findContainingViewHolder(it)?.bindingAdapterPosition } == position }
            }
            await { requests == 1 && list.scrollState == RecyclerView.SCROLL_STATE_IDLE }
            instrumentation.runOnMainSync {
                watching = true
                row = row.copy(cards = row.cards + cards("yt", 8, 24), hasNext = false, loading = false)
                youtube.render(row)
            }
            await { list.adapter?.itemCount == 24 && list.scrollState == RecyclerView.SCROLL_STATE_IDLE }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val focused = list.findFocus()
                assertTrue("Focus left YouTube: $escapes", escapes.isEmpty())
                assertEquals("The last original card must retain focus", 7,
                    focused?.let { list.findContainingViewHolder(it)?.bindingAdapterPosition } ?: RecyclerView.NO_POSITION)
                val position = focused?.let { list.findContainingViewHolder(it)?.bindingAdapterPosition } ?: RecyclerView.NO_POSITION
                val card = (list.adapter as? ListAdapter<*, *>)?.currentList?.getOrNull(position) as? VideoCard
                assertEquals("The same card ID must retain focus", "yt-7", card?.id)
                assertEquals(1, requests)
                assertEquals(0, opened)
                watching = false
            }
        } finally {
            instrumentation.runOnMainSync {
                watching = false
                if (setupComplete) {
                    youtube.release()
                    upper.release()
                    dialog.dismiss()
                }
                activity.finish()
            }
        }
    }

    private fun cards(prefix: String, start: Int, end: Int) = (start until end).map {
        VideoCard("$prefix-$it", "Video $it", "Channel", null)
    }

    private fun inside(root: View, view: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === root) return true
            current = current.parent as? View
        }
        return false
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        var result = false
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync { result = condition() }
            if (result) return
            SystemClock.sleep(20)
        }
        assertTrue("Timed out waiting for dialog state", result)
    }
}
