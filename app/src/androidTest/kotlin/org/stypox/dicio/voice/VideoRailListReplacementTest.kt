package org.stypox.dicio.voice

import android.os.SystemClock
import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.stypox.dicio.eval.VideoCard
import org.stypox.dicio.eval.VideoRow
import org.stypox.dicio.eval.VideoSource

/** Attached rail with local cards only: no thumbnail requests, microphone or playback. */
class VideoRailListReplacementTest {
    @get:Rule val compose = createComposeRule()

    @Test fun returningToAWhileBIsPendingKeepsA() {
        val fixture = setUp()
        try {
            val a = row(1, "a")
            val b = row(1, "b")
            compose.runOnIdle {
                fixture.rail.render(a)
                assertEquals(a.cards, fixture.adapter.currentList)
                fixture.rail.render(b)
                // The diff result cannot be committed while this main-thread block runs.
                assertEquals(a.cards, fixture.adapter.currentList)
                fixture.rail.render(a)
            }
            settle()
            compose.runOnIdle {
                assertEquals(a.cards, fixture.adapter.currentList)
                val holder = fixture.list.findViewHolderForAdapterPosition(0)
                assertNotNull(holder)
                assertEquals("a title, author", holder!!.itemView.contentDescription.toString())
                holder.itemView.performClick()
                assertEquals(listOf(1L to "a"), fixture.opened)
            }
        } finally {
            compose.runOnIdle { fixture.rail.release() }
        }
    }

    @Test fun replacingRowWhileDiffPendingRejectsOldHolderAndShowsNewRow() {
        val fixture = setUp()
        try {
            val a = row(1, "a")
            val b = row(1, "b")
            // Reuse the old video ID to exercise row identity as well as card identity.
            val next = row(2, "a", VideoSource.RUTUBE)
            compose.runOnIdle { fixture.rail.render(a) }
            compose.waitUntil(5_000) { fixture.list.findViewHolderForAdapterPosition(0) != null }
            compose.runOnIdle {
                val oldHolder = fixture.list.findViewHolderForAdapterPosition(0)!!
                fixture.rail.render(b)
                assertEquals(a.cards, fixture.adapter.currentList)
                fixture.rail.render(next)
                oldHolder.itemView.performClick()
                assertTrue("Old holder must not open a card in the new row", fixture.opened.isEmpty())
            }
            settle()
            compose.runOnIdle {
                assertEquals(next.cards, fixture.adapter.currentList)
                val holder = fixture.list.findViewHolderForAdapterPosition(0)
                assertNotNull(holder)
                holder!!.itemView.performClick()
                assertEquals(listOf(2L to "a"), fixture.opened)
                assertTrue(fixture.nearEnd.isEmpty())
            }
        } finally {
            compose.runOnIdle { fixture.rail.release() }
        }
    }

    @Test fun releaseWhileDiffPendingDetachesAdapterAndRejectsLateWork() {
        val fixture = setUp()
        val a = row(1, "a")
        val b = row(1, "b")
        compose.runOnIdle { fixture.rail.render(a) }
        compose.waitUntil(5_000) { fixture.list.findViewHolderForAdapterPosition(0) != null }
        compose.runOnIdle {
            val oldHolder = fixture.list.findViewHolderForAdapterPosition(0)!!
            fixture.rail.render(b)
            assertEquals(a.cards, fixture.adapter.currentList)
            fixture.rail.release()
            fixture.rail.render(b)
            oldHolder.itemView.performClick()
            assertNull(fixture.list.adapter)
            assertEquals(View.GONE, fixture.rail.visibility)
            assertTrue(fixture.opened.isEmpty())
        }
        settle()
        compose.runOnIdle {
            assertNull(fixture.list.adapter)
            assertEquals(View.GONE, fixture.rail.visibility)
            assertTrue(fixture.opened.isEmpty())
            assertTrue(fixture.nearEnd.isEmpty())
        }
    }

    private data class Fixture(
        val rail: VideoRail,
        val list: RecyclerView,
        val adapter: ListAdapter<*, *>,
        val opened: MutableList<Pair<Long, String>>,
        val nearEnd: MutableList<Long>,
    )

    private fun setUp(): Fixture {
        lateinit var fixture: Fixture
        compose.setContent {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                val opened = mutableListOf<Pair<Long, String>>()
                val nearEnd = mutableListOf<Long>()
                val rail = VideoRail(context, { id, card -> opened.add(id to card) }, { nearEnd.add(it) }, {})
                val list = rail.getChildAt(1) as RecyclerView
                fixture = Fixture(rail, list, list.adapter as ListAdapter<*, *>, opened, nearEnd)
                rail
            })
        }
        compose.waitForIdle()
        return fixture
    }

    private fun row(id: Long, card: String, source: VideoSource = VideoSource.YOUTUBE) =
        VideoRow(id, listOf(VideoCard(card, "$card title", "author", null)), source = source)

    private fun settle() {
        // AsyncListDiffer's background executor is not tracked by the Compose idling resource.
        SystemClock.sleep(1_000)
        compose.waitForIdle()
    }
}
