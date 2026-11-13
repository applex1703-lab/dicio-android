package org.stypox.dicio.voice

import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.stypox.dicio.eval.VideoCard
import org.stypox.dicio.eval.VideoRow

/** Measures the real card holder without attaching a microphone or requesting images. */
class VideoCardTypographyTest {
    @Test fun authorFollowsTitleAndCardHeightRemainsStable() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val app = ApplicationProvider.getApplicationContext<Context>()
            for (scale in listOf(1f, 1.3f)) {
                val context = app.createConfigurationContext(Configuration(app.resources.configuration).apply {
                    fontScale = scale
                })
                val rail = VideoRail(context, { _, _ -> }, {}, {})
                try {
                    val shortTitle = "Кот"
                    val longTitle = "Очень длинное название мультфильма для детей которое должно занимать не больше двух строк и аккуратно обрезаться"
                    val author = "A complete channel name for accessibility"
                    rail.render(VideoRow(1, listOf(
                        VideoCard("short", shortTitle, author, null),
                        VideoCard("long", longTitle, author, null),
                    )))
                    val list = (0 until rail.childCount).map { rail.getChildAt(it) }
                        .filterIsInstance<RecyclerView>().single()
                    @Suppress("UNCHECKED_CAST")
                    val adapter = list.adapter as RecyclerView.Adapter<RecyclerView.ViewHolder>
                    val holder = adapter.createViewHolder(list, 0)
                    val width = holder.itemView.layoutParams.width
                    assertTrue("Card width must be positive", width > 0)

                    fun bindAndMeasure(position: Int, titleText: String): Pair<Int, Int> {
                        adapter.bindViewHolder(holder, position)
                        val item = holder.itemView
                        item.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                        item.layout(0, 0, item.measuredWidth, item.measuredHeight)
                        val texts = descendants(item).filterIsInstance<TextView>()
                        val title = texts.single { it.text.toString() == titleText }
                        val authorView = texts.single { it.text.toString() == author }
                        assertEquals("Title must allow at most two lines", 2, title.maxLines)
                        assertTrue("Rendered title must have at most two lines", title.lineCount <= 2)
                        val expectedLines = if (position == 0) 1 else 2
                        assertEquals("Unexpected title wrapping at fontScale=$scale", expectedLines, title.lineCount)
                        assertTrue("Title and author must share a parent", title.parent === authorView.parent)
                        val gap = authorView.top - title.bottom
                        val expectedGap = (2 * context.resources.displayMetrics.density).toInt()
                        assertEquals("Author should immediately follow the title", expectedGap, gap)
                        assertEquals("Full text must be accessible", "$titleText, $author", item.contentDescription.toString())
                        return authorView.top to item.measuredHeight
                    }

                    val short = bindAndMeasure(0, shortTitle)
                    val long = bindAndMeasure(1, longTitle)
                    assertTrue("Short-title author must appear above long-title author", short.first < long.first)
                    assertEquals("Card height should not depend on title length at fontScale=$scale", short.second, long.second)
                } finally {
                    rail.release()
                }
            }
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
