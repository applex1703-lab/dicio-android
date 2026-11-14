package org.stypox.dicio.voice

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.platform.app.InstrumentationRegistry
import coil.ImageLoader
import coil.decode.DataSource
import coil.intercept.Interceptor
import coil.request.ImageResult
import coil.request.SuccessResult
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.stypox.dicio.eval.VideoCard
import org.stypox.dicio.eval.VideoRow
import org.stypox.dicio.eval.VideoSource

/** Local, gated image delivery into a real window; this is not a performance benchmark. */
class VideoRailBitmapArrivalTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private data class Bounds(val x: Int, val y: Int, val width: Int, val height: Int)
    private data class Geometry(val rail: Bounds, val card: Bounds, val image: Bounds)

    private class GatedBitmap(private val bitmap: Bitmap, private val resources: android.content.res.Resources) : Interceptor {
        val gate = CompletableDeferred<Unit>()
        @Volatile var started = false
        @Volatile var returned = false

        override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
            check(chain.request.data == "https://i.ytimg.com/local-bitmap")
            started = true
            gate.await()
            returned = true
            return SuccessResult(BitmapDrawable(resources, bitmap), chain.request, DataSource.MEMORY_CACHE)
        }
    }

    @Test fun bitmapArrivalPreservesFixedCardGeometryAndFocus() {
        // Opposite aspect ratios and intrinsic dimensions, neither matching the card.
        for ((width, height) in listOf(37 to 211, 419 to 29)) exerciseBitmap(width, height)
    }

    private fun exerciseBitmap(width: Int, height: Int) {
        val host = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            BindDiagnosticActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(23, 127, 211))
            setPixel(width - 1, height - 1, Color.MAGENTA)
            density = Bitmap.DENSITY_NONE
        }
        val delayed = GatedBitmap(bitmap, host.resources)
        var loader: ImageLoader? = null
        var rail: VideoRail? = null
        var watching = false
        val focusEvents = mutableListOf<String>()
        val layouts = mutableListOf<Geometry>()
        lateinit var list: RecyclerView
        lateinit var card: View
        lateinit var image: ImageView
        var opened = 0
        try {
            instrumentation.runOnMainSync {
                loader = ImageLoader.Builder(host).components { add(delayed) }.build()
                val currentRail = VideoRail(host, { _, _ -> opened++ }, {}, {}, loader, { false })
                rail = currentRail
                host.setContentView(currentRail)
                list = currentRail.getChildAt(1) as RecyclerView
                host.window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { old, new ->
                    if (watching) focusEvents.add("$old -> $new")
                }
                host.window.decorView.viewTreeObserver.addOnWindowFocusChangeListener { focused ->
                    if (watching && !focused) focusEvents.add("window lost focus")
                }
                host.window.decorView.viewTreeObserver.addOnGlobalLayoutListener {
                    if (watching) layouts.add(geometry(currentRail, card, image))
                }
                currentRail.render(VideoRow(1, listOf(VideoCard("one", "Title", "Author",
                    "https://i.ytimg.com/local-bitmap")), source = VideoSource.YOUTUBE))
            }
            await { host.hasWindowFocus() && delayed.started && list.findViewHolderForAdapterPosition(0) != null }
            instrumentation.runOnMainSync {
                card = list.findViewHolderForAdapterPosition(0)!!.itemView
                image = findImage(card)!!
                assertTrue(card.requestFocus())
            }
            // Let the card's focus-scale animation settle before recording screen coordinates.
            SystemClock.sleep(300)
            instrumentation.waitForIdleSync()
            val before = onMain {
                assertTrue("No bitmap before releasing the gate", image.drawable == null && !delayed.returned)
                assertTrue("Card must be laid out", card.width > 0 && image.width > 0 && image.height > 0)
                watching = true
                geometry(rail!!, card, image)
            }
            delayed.gate.complete(Unit)
            await { (image.drawable as? BitmapDrawable)?.bitmap === bitmap }
            // Explicitly request a traversal so the global-layout observer is exercised even
            // when setImageDrawable itself does not request layout on this Android version.
            instrumentation.runOnMainSync { image.requestLayout() }
            await { layouts.isNotEmpty() }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val after = geometry(rail!!, card, image)
                val installed = (image.drawable as BitmapDrawable).bitmap
                assertEquals(width, installed.width)
                assertEquals(height, installed.height)
                assertEquals(Color.rgb(23, 127, 211), installed.getPixel(0, 0))
                assertEquals(Color.MAGENTA, installed.getPixel(width - 1, height - 1))
                assertEquals("Fixed rail, card and image bounds changed", before, after)
                assertTrue("Geometry changed during a global layout: $layouts", layouts.all { it == before })
                assertTrue("Focus changed during image delivery: $focusEvents", focusEvents.isEmpty())
                assertTrue("Card or window lost focus", card.hasFocus() && host.hasWindowFocus())
                assertEquals(0, opened)
                Log.i("VideoRailBitmapArrival", "bitmap=${width}x$height before=$before after=$after globalLayouts=${layouts.size} focusEvents=$focusEvents")
            }
        } finally {
            delayed.gate.complete(Unit)
            instrumentation.runOnMainSync {
                watching = false
                rail?.release()
                loader?.shutdown()
                host.finish()
            }
        }
    }

    private fun geometry(rail: View, card: View, image: View) = Geometry(bounds(rail), bounds(card), bounds(image))
    private fun bounds(view: View): Bounds {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return Bounds(location[0], location[1], view.width, view.height)
    }

    private fun findImage(view: View): ImageView? {
        if (view is ImageView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findImage(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Any? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (onMain(condition)) return
            SystemClock.sleep(20)
        }
        assertTrue("Timed out waiting for test state", onMain(condition))
    }
}
