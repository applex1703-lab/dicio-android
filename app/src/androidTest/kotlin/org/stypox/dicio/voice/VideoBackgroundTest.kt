package org.stypox.dicio.voice

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoBackgroundTest {
    @Test fun wideBoundsCropTopAndBottomWithoutStretching() {
        val source = coordinateBitmap()
        val target = Bitmap.createBitmap(16, 10, Bitmap.Config.ARGB_8888)
        val background = VideoBackground(source)
        background.setBounds(2, 2, 14, 8)
        background.draw(Canvas(target))

        for (y in 0 until 6) for (x in 0 until 12) {
            assertEquals("pixel ($x, $y)", source.getPixel(x, y + 3), target.getPixel(x + 2, y + 2))
        }
        assertEquals(Color.TRANSPARENT, target.getPixel(1, 2))
        assertEquals(Color.TRANSPARENT, target.getPixel(14, 7))
    }

    @Test fun narrowBoundsCropLeftAndRightWithoutStretching() {
        val source = coordinateBitmap()
        val target = Bitmap.createBitmap(10, 16, Bitmap.Config.ARGB_8888)
        val background = VideoBackground(source)
        background.setBounds(2, 2, 8, 14)
        background.draw(Canvas(target))

        for (y in 0 until 12) for (x in 0 until 6) {
            assertEquals("pixel ($x, $y)", source.getPixel(x + 3, y), target.getPixel(x + 2, y + 2))
        }
        assertEquals(Color.TRANSPARENT, target.getPixel(2, 1))
        assertEquals(Color.TRANSPARENT, target.getPixel(7, 14))
    }

    @Test fun emptyBoundsLeaveCanvasUnchanged() {
        val target = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888)
        target.eraseColor(Color.MAGENTA)
        val background = VideoBackground(coordinateBitmap())
        val canvas = Canvas(target)
        background.draw(canvas) // Default bounds are empty.
        background.setBounds(4, 2, 4, 10)
        background.draw(canvas)
        background.setBounds(2, 4, 10, 4)
        background.draw(canvas)

        for (y in 0 until 12) for (x in 0 until 12) {
            assertEquals(Color.MAGENTA, target.getPixel(x, y))
        }
    }

    @Test fun alphaIsAppliedWhenDrawing() {
        val source = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.RED)
        val target = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val background = VideoBackground(source)
        background.setBounds(0, 0, 4, 4)
        background.alpha = 128
        background.draw(Canvas(target))

        assertEquals(128, Color.alpha(target.getPixel(2, 2)))
        assertEquals(255, Color.red(target.getPixel(2, 2)))
    }

    private fun coordinateBitmap(): Bitmap =
        Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until height) for (x in 0 until width) {
                setPixel(x, y, Color.rgb(x * 20, y * 20, 40))
            }
        }
}
