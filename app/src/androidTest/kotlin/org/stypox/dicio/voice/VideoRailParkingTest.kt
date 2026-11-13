package org.stypox.dicio.voice

import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Attached views only: no speech, image loading, network, or playback. */
class VideoRailParkingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun upWhileParkedMovesAboveAndDoesNotReturn() = verticalWhileParked(KeyEvent.KEYCODE_DPAD_UP)

    @Test fun downWhileParkedMovesBelowAndDoesNotReturn() = verticalWhileParked(KeyEvent.KEYCODE_DPAD_DOWN)

    @Test fun reverseWhileParkedCancelsPendingAndKeepsOrigin() {
        val fixture = setUp()
        lateinit var origin: View
        var targetPosition = -1
        compose.runOnIdle {
            val start = startPending(fixture)
            origin = start.first
            targetPosition = start.second
            assertTrue(key(fixture.list, KeyEvent.KEYCODE_DPAD_LEFT))
            assertSame("Reverse must restore the still-attached origin", origin, fixture.root.findFocus())
        }
        // Let scroll/layout and the previously posted completion callbacks run.
        SystemClock.sleep(800)
        compose.waitForIdle()
        compose.runOnIdle {
            assertSame("A stale pending completion must not focus target $targetPosition", origin, fixture.root.findFocus())
        }
    }

    private fun verticalWhileParked(code: Int) {
        val fixture = setUp()
        var leftRow = false
        val returned = mutableListOf<View>()
        val destination = if (code == KeyEvent.KEYCODE_DPAD_UP) fixture.above else fixture.below
        compose.runOnIdle {
            startPending(fixture)
            fixture.root.viewTreeObserver.addOnGlobalFocusChangeListener { _, next ->
                if (next === destination) leftRow = true
                if (leftRow && next != null && isDescendant(fixture.list, next)) returned.add(next)
            }
            assertTrue("Vertical key must be consumed while parked", key(fixture.list, code))
            assertSame("Vertical navigation must reach the intended outside view", destination, fixture.root.findFocus())
        }
        SystemClock.sleep(800)
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("Focus must not return to the row after explicit navigation: $returned", returned.isEmpty())
            assertSame("Delayed completion must not steal focus back", destination, fixture.root.findFocus())
        }
    }

    private data class Fixture(val root: LinearLayout, val list: VideoRecyclerView, val above: View, val below: View)

    private fun setUp(): Fixture {
        lateinit var fixture: Fixture
        compose.setContent {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                val density = context.resources.displayMetrics.density
                fun dp(value: Int) = (value * density).toInt()
                val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                val above = TextView(context).apply { text = "Above"; isFocusable = true }
                val below = TextView(context).apply { text = "Below"; isFocusable = true }
                val list = VideoRecyclerView(context).apply {
                    layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
                    itemAnimator = null
                    adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                        override fun getItemCount() = 100
                        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                            object : RecyclerView.ViewHolder(TextView(parent.context).apply {
                                layoutParams = RecyclerView.LayoutParams(dp(180), dp(60))
                                isFocusable = true
                            }) {}
                        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                            (holder.itemView as TextView).text = "Card $position"
                        }
                    }
                }
                root.addView(above, LinearLayout.LayoutParams(dp(180), dp(60)))
                root.addView(list, LinearLayout.LayoutParams(dp(180), dp(60)))
                root.addView(below, LinearLayout.LayoutParams(dp(180), dp(60)))
                fixture = Fixture(root, list, above, below)
                root
            })
        }
        compose.waitUntil(5_000) { fixture.list.findViewHolderForAdapterPosition(0) != null }
        return fixture
    }

    private fun startPending(fixture: Fixture): Pair<View, Int> {
        val list = fixture.list
        assertTrue("RecyclerView must be attached", list.isAttachedToWindow)
        // Pick the last attached holder, making the next adapter position genuinely unattached.
        val last = (0 until list.childCount).mapNotNull {
            list.getChildViewHolder(list.getChildAt(it)).takeIf { holder ->
                holder.bindingAdapterPosition != RecyclerView.NO_POSITION
            }
        }.maxByOrNull { it.bindingAdapterPosition }
        assertNotNull("An attached origin is required", last)
        val holder = last!!
        val target = holder.bindingAdapterPosition + 1
        assertTrue("Target must exist in adapter", target < list.adapter!!.itemCount)
        assertNull("Target holder must be unattached before RIGHT", list.findViewHolderForAdapterPosition(target))
        assertTrue("Origin must take focus", holder.itemView.requestFocus())
        assertTrue("RIGHT must be consumed", key(list, KeyEvent.KEYCODE_DPAD_RIGHT))
        assertSame("Focus must be parked before the next layout", list, fixture.root.findFocus())
        assertNull("Target must still be unattached while pending", list.findViewHolderForAdapterPosition(target))
        return holder.itemView to target
    }

    private fun key(list: VideoRecyclerView, code: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        return list.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
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
