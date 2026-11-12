package org.stypox.dicio.voice

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.stypox.dicio.eval.VideoCard
import org.stypox.dicio.eval.VideoRow
import org.stypox.dicio.eval.VideoSource

/** Exercises only the isolated View; no microphone, network or external application. */
class VideoRailTest {
    @Test fun independentRailsKeepTheirOwnRowsAndRetryAfterPeerRelease() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val loader = videoImageLoader(context)
            val retried = mutableListOf<Long>()
            val first = VideoRail(context, { _, _ -> }, {}, { retried.add(it) }, loader)
            val second = VideoRail(context, { _, _ -> }, {}, { retried.add(it) }, loader)
            first.render(VideoRow(1, emptyList(), hasNext = true, loadError = true, source = VideoSource.RUTUBE))
            second.render(VideoRow(2, emptyList(), hasNext = true, loadError = true, source = VideoSource.YOUTUBE))
            assertEquals("Rutube", (first.getChildAt(0) as TextView).text.toString())
            assertEquals("YouTube", (second.getChildAt(0) as TextView).text.toString())
            first.release()
            (first.getChildAt(4) as Button).performClick()
            (second.getChildAt(4) as Button).performClick()
            assertEquals(listOf(2L), retried)
            second.release()
            loader.shutdown()
        }
    }

    @Test fun sourceReplacementUpdatesHeadingAndRetryUsesOnlyCurrentRow() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val retried = mutableListOf<Long>()
            val context = ApplicationProvider.getApplicationContext<Context>()
            val rail = VideoRail(context, { _, _ -> }, {}, { retried.add(it) })
            val heading = rail.getChildAt(0) as TextView
            val retry = rail.getChildAt(4) as Button
            val cards = listOf(VideoCard("video", "Title", "Author", null))
            rail.render(VideoRow(11, cards, hasNext = true, loadError = true, source = VideoSource.YOUTUBE))
            assertEquals("YouTube", heading.text.toString())
            retry.performClick()
            rail.render(VideoRow(12, cards, hasNext = true, loading = true, source = VideoSource.RUTUBE))
            assertEquals("Rutube", heading.text.toString())
            retry.performClick()
            rail.render(VideoRow(12, cards, hasNext = true, loadError = true, source = VideoSource.RUTUBE))
            retry.performClick()
            rail.release()
            retry.performClick()
            assertEquals(listOf(11L, 12L), retried)
        }
    }

    @Test fun bindingDoesNotSelectAndReleaseRejectsLateClick() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val selected = mutableListOf<Pair<Long, String>>()
            val context = ApplicationProvider.getApplicationContext<Context>()
            val rail = VideoRail(context, { row, card -> selected.add(row to card) }, {}, {})
            rail.render(VideoRow(11, listOf(VideoCard("video", "Title", "Author", null))))
            assertEquals(View.VISIBLE, rail.visibility)
            val list = rail.getChildAt(1) as RecyclerView
            @Suppress("UNCHECKED_CAST")
            val adapter = list.adapter as RecyclerView.Adapter<RecyclerView.ViewHolder>
            assertEquals(1, adapter.itemCount)
            val holder = adapter.createViewHolder(list, 0)
            adapter.bindViewHolder(holder, 0)
            assertEquals("Title, Author", holder.itemView.contentDescription.toString())
            assertEquals(emptyList<Pair<Long, String>>(), selected)
            rail.release()
            holder.itemView.performClick()
            assertEquals(emptyList<Pair<Long, String>>(), selected)
        }
    }
}
