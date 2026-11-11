package org.stypox.dicio.voice

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.stypox.dicio.eval.VideoCard
import org.stypox.dicio.eval.VideoRow

/** Exercises only the isolated View; no microphone, network or external application. */
class VideoRailTest {
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
