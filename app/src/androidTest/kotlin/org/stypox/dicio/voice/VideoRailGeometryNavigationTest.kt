package org.stypox.dicio.voice

import android.os.SystemClock
import android.view.KeyEvent
import androidx.recyclerview.widget.RecyclerView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.stypox.dicio.eval.VideoCard
import org.stypox.dicio.eval.VideoRow
import org.stypox.dicio.eval.VideoSource

/** Content coordinates and viewport scrolling are deliberately measured separately. */
class VideoRailGeometryNavigationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private data class Geometry(
        val upperTop: Int, val upperHeight: Int,
        val lowerTop: Int, val lowerHeight: Int, val scrollY: Int,
    )

    @Test fun horizontalNavigationDoesNotMoveOrResizeEitherRail() {
        val fixture = VideoRailTestActivity.launch()
        val upperList = fixture.upper.getChildAt(1) as RecyclerView
        val lowerList = fixture.youtube.getChildAt(1) as RecyclerView
        val observations = mutableListOf<String>()
        try {
            instrumentation.runOnMainSync {
                // Null thumbnails and hasNext=false keep this test entirely local.
                fixture.upper.render(VideoRow(101, cards("upper"), source = VideoSource.RUTUBE))
                fixture.youtube.render(VideoRow(102, cards("lower"), source = VideoSource.YOUTUBE))
            }
            await("initial layout") {
                fixture.hasWindowFocus() && upperList.adapter?.itemCount == 8 &&
                    lowerList.adapter?.itemCount == 8 &&
                    upperList.findViewHolderForAdapterPosition(0) != null &&
                    lowerList.findViewHolderForAdapterPosition(0) != null &&
                    fixture.upper.height > 0 && fixture.youtube.height > 0 &&
                    !fixture.upper.isLayoutRequested && !fixture.youtube.isLayoutRequested
            }

            fun geometry() = Geometry(fixture.upper.top, fixture.upper.height,
                fixture.youtube.top, fixture.youtube.height, fixture.scroll.scrollY)

            var baseline: Geometry? = null
            for ((name, list) in listOf("upper" to upperList, "lower" to lowerList)) {
                instrumentation.runOnMainSync {
                    assertTrue("$name first card must accept focus",
                        list.findViewHolderForAdapterPosition(0)!!.itemView.requestFocus())
                }
                await("$name first card and layout") {
                    focusedPosition(fixture, list) == 0 && list.scrollState == RecyclerView.SCROLL_STATE_IDLE &&
                        !fixture.upper.isLayoutRequested && !fixture.youtube.isLayoutRequested
                }
                if (baseline == null) baseline = onMain { geometry() }
                for (position in 0..5) {
                    if (position > 0) instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
                    await("$name position $position") {
                        focusedPosition(fixture, list) == position && list.scrollState == RecyclerView.SCROLL_STATE_IDLE &&
                            !fixture.upper.isLayoutRequested && !fixture.youtube.isLayoutRequested
                    }
                    val current = onMain { geometry() }
                    observations.add("$name[$position]=$current")
                    val expected = baseline!!
                    val detail = "after $name[$position]; observations=$observations"
                    assertEquals("upper top $detail", expected.upperTop, current.upperTop)
                    assertEquals("upper height $detail", expected.upperHeight, current.upperHeight)
                    assertEquals("lower top $detail", expected.lowerTop, current.lowerTop)
                    assertEquals("lower height $detail", expected.lowerHeight, current.lowerHeight)
                }
            }
            assertEquals("No simulated page request", 0, onMain { fixture.requests })
            assertEquals("No video opened", 0, onMain { fixture.opened })
        } finally {
            instrumentation.runOnMainSync { fixture.finish() }
        }
    }

    private fun cards(prefix: String) = (0 until 8).map { index ->
        val title = if (index % 2 == 0) "Кот" else
            "Очень длинное название мультфильма для детей которое занимает несколько строк и обрезается"
        val author = if (index % 2 == 0) "A" else "Очень длинное название канала для проверки обрезки текста"
        VideoCard("$prefix-$index", title, author, null)
    }

    private fun focusedPosition(fixture: VideoRailTestActivity, list: RecyclerView): Int {
        val focus = fixture.window.decorView.findFocus() ?: return RecyclerView.NO_POSITION
        return list.findContainingViewHolder(focus)?.bindingAdapterPosition ?: RecyclerView.NO_POSITION
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Any? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
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
