package org.stypox.dicio.voice

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable

/** A static center-cropped image, without distorting its proportions. */
internal class VideoBackground(private val bitmap: Bitmap) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val source = Rect()

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty || bitmap.width <= 0 || bitmap.height <= 0) return
        val targetRatio = bounds.width().toFloat() / bounds.height()
        val imageRatio = bitmap.width.toFloat() / bitmap.height
        if (imageRatio > targetRatio) {
            val width = (bitmap.height * targetRatio).toInt().coerceIn(1, bitmap.width)
            val left = (bitmap.width - width) / 2
            source.set(left, 0, left + width, bitmap.height)
        } else {
            val height = (bitmap.width / targetRatio).toInt().coerceIn(1, bitmap.height)
            val top = (bitmap.height - height) / 2
            source.set(0, top, bitmap.width, top + height)
        }
        canvas.drawBitmap(bitmap, source, bounds, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Android") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
