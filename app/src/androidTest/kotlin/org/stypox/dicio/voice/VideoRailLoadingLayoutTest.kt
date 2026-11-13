package org.stypox.dicio.voice

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.stypox.dicio.eval.VideoCard
import org.stypox.dicio.eval.VideoRow
import org.stypox.dicio.eval.VideoSource

/** Isolated views with no activity, microphone, thumbnail requests, or video playback. */
class VideoRailLoadingLayoutTest {
    @Test fun loadingTransitionsDoNotMoveSecondRail() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val first = VideoRail(context, { _, _ -> }, {}, {}, claimInitialFocus = { false })
            val second = VideoRail(context, { _, _ -> }, {}, {}, claimInitialFocus = { false })
            try {
                root.addView(first, LinearLayout.LayoutParams(-1, -2))
                root.addView(second, LinearLayout.LayoutParams(-1, -2))
                val firstRow = VideoRow(1, listOf(VideoCard("one", "First", "Author", null)),
                    hasNext = true, source = VideoSource.RUTUBE)
                val secondRow = VideoRow(2, listOf(VideoCard("two", "Second", "Author", null)),
                    source = VideoSource.YOUTUBE)
                first.render(firstRow)
                second.render(secondRow)
                layout(root)
                val top = second.top
                val bottom = second.bottom
                assertTrue("The second rail must be laid out below the first", top > 0 && bottom > top)

                first.render(firstRow.copy(loading = true))
                layout(root)
                assertEquals("Loading must not move the second rail's top", top, second.top)
                assertEquals("Loading must not move the second rail's bottom", bottom, second.bottom)

                first.render(firstRow.copy(loading = false))
                layout(root)
                assertEquals("Finishing loading must not move the second rail's top", top, second.top)
                assertEquals("Finishing loading must not move the second rail's bottom", bottom, second.bottom)
            } finally {
                first.release()
                second.release()
            }
        }
    }

    @Test fun loadErrorStillOffersWorkingRetry() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val retried = mutableListOf<Long>()
            val rail = VideoRail(context, { _, _ -> }, {}, { retried.add(it) },
                claimInitialFocus = { false })
            try {
                val row = VideoRow(1, listOf(VideoCard("one", "First", "Author", null)),
                    hasNext = true, source = VideoSource.RUTUBE)
                rail.render(row.copy(loadError = true))
                val retry = (0 until rail.childCount).map { rail.getChildAt(it) }
                    .filterIsInstance<Button>().single()
                assertEquals(View.VISIBLE, retry.visibility)
                assertTrue("Retry must be enabled", retry.isEnabled)
                assertTrue("Retry must handle clicks", retry.performClick())
                assertEquals(listOf(1L), retried)
            } finally {
                rail.release()
            }
        }
    }

    private fun layout(root: View) {
        val width = View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY)
        val height = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        root.measure(width, height)
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
    }
}
